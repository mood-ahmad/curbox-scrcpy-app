package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden guardian that monitors the remote-control engine.
 *
 * The Guardian attempts recovery only when Remote Control is enabled
 * and no start, stop, restart, or previous repair is in progress.
 *
 * Two independent health axes are monitored:
 *
 *  - Core health (Signaling + Agent). Failures here trigger the Full
 *    Engine Guardian Repair path that calls RemoteControlManager.restart(
 *    reason = GUARDIAN_REPAIR).
 *
 *  - Cloudflare health (cloudflared). Failures here — while the core
 *    remains healthy — trigger the Cloudflare-only repair path that
 *    calls RemoteControlManager.repairPublicTunnel(context). That path
 *    never stops Signaling, Agent, Local URL, or the Runtime.
 *
 * Shizuku readiness is checked before every health check and at every
 * decision point inside the repair paths. Shizuku failures are never
 * treated as engine failures, never consume repair attempt budget,
 * and never trigger alerts.
 *
 * No notifications and no UI.
 */
object RemoteControlGuardian {

    private const val CHECK_INTERVAL_MS =
        30_000L

    private const val INITIAL_SETTLE_MS =
        30_000L

    private const val REPAIR_SETTLE_MS =
        30_000L

    private const val HEALTH_CHECK_TIMEOUT_MS =
        12_000L

    private const val FIRST_REPAIR_BACKOFF_MS =
        5_000L

    private const val SECOND_REPAIR_BACKOFF_MS =
        15_000L

    private const val THIRD_REPAIR_BACKOFF_MS =
        30_000L

    private const val MAX_REPAIR_ATTEMPTS =
        3

    private enum class ShizukuReadiness {
        READY,
        BINDER_UNAVAILABLE,
        PERMISSION_DENIED
    }

    private enum class HealthResult {
        HEALTHY,
        UNHEALTHY,
        SHIZUKU_UNAVAILABLE,
        SHIZUKU_PERMISSION_DENIED,
        TIMEOUT
    }

    private val scope =
        CoroutineScope(
            Dispatchers.IO
        )

    private val guardianLock =
        Any()

    /*
     * Serializes Guardian cycle transitions (start/stop) with the final
     * dispatch of RemoteControlManager.restart() and
     * RemoteControlManager.repairPublicTunnel().
     *
     * Lock order (mandatory everywhere):
     *   guardianDispatchLock -> guardianLock
     *
     * Never the other way around.
     */
    private val guardianDispatchLock =
        Any()

    private var job:
        Job? = null

    private var guardianGeneration =
        0L

    private var consecutiveFailures =
        0

    private var alertSent =
        false

    /*
     * Once the maximum number of repair attempts is reached,
     * the Guardian keeps checking health but stops launching
     * additional automatic repair attempts.
     *
     * The suspension is cleared when the engine recovers,
     * Remote Control is disabled, or the Guardian is restarted.
     */
    private var automaticRepairSuspended =
        false

    private val repairInProgress =
        AtomicBoolean(false)

    /*
     * Cloudflare-only recovery state.
     *
     * Kept fully independent from the Full Engine repair state above
     * so that a healthy Core with a dead Cloudflare tunnel does not
     * inherit the Core repair attempt budget (and vice versa).
     */
    private var cloudflareConsecutiveFailures =
        0

    private var cloudflareAlertSent =
        false

    private var cloudflareAutomaticRepairSuspended =
        false

    private val cloudflareRepairInProgress =
        AtomicBoolean(false)

    /**
     * Starts the Guardian loop.
     *
     * Safe to call multiple times inside the main application process.
     */
    fun start(
        context: Context
    ) {
        val appContext =
            context.applicationContext

        synchronized(guardianDispatchLock) {
            synchronized(guardianLock) {
                if (job?.isActive == true) {
                    EventLogger.logStage(
                        stage = "GUARDIAN",
                        status = "START_SKIPPED",
                        details = "reason=already_active"
                    )

                    EventLogger.log(
                        "GUARDIAN",
                        "Start skipped: Guardian is already active"
                    )

                    return
                }

                guardianGeneration++

                val startGeneration =
                    guardianGeneration

                /*
                 * Ensure atomics from any previous cycle are cleared
                 * before the new loop begins. This prevents a stale
                 * cancellation from leaving the new cycle locked out.
                 */
                repairInProgress.set(
                    false
                )

                cloudflareRepairInProgress.set(
                    false
                )

                resetFailureStateLocked()
                resetCloudflareFailureStateLocked()

                job =
                    scope.launch {
                        EventLogger.logStage(
                            stage = "GUARDIAN",
                            status = "STARTED",
                            details = "generation=$startGeneration"
                        )

                        EventLogger.log(
                            "GUARDIAN",
                            "Guardian started"
                        )

                        /*
                         * Allow the normal application AutoStart enough
                         * time to finish before the first health check.
                         */
                        delay(
                            INITIAL_SETTLE_MS
                        )

                        while (isActive) {
                            try {
                                tick(
                                    appContext,
                                    startGeneration
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (t: Throwable) {
                                EventLogger.logStage(
                                    stage = "GUARDIAN_CHECK",
                                    status = "EXCEPTION",
                                    details =
                                        "reason=" +
                                        safeLogSummary(
                                            t.message
                                                ?: t.javaClass.simpleName
                                        )
                                )

                                EventLogger.log(
                                    "GUARDIAN",
                                    "Tick exception: " +
                                        (
                                            t.message
                                                ?: t.javaClass.simpleName
                                        )
                                )
                            }

                            delay(
                                CHECK_INTERVAL_MS
                            )
                        }
                    }
            }
        }
    }

    /**
     * Stops the Guardian loop and prevents the current repair from
     * launching another recovery attempt.
     */
    fun stop() {
        synchronized(guardianDispatchLock) {
            synchronized(guardianLock) {
                guardianGeneration++

                job?.cancel()
                job = null

                repairInProgress.set(
                    false
                )

                cloudflareRepairInProgress.set(
                    false
                )

                resetFailureStateLocked()
                resetCloudflareFailureStateLocked()
            }
        }

        EventLogger.logStage(
            stage = "GUARDIAN",
            status = "STOPPED"
        )

        EventLogger.log(
            "GUARDIAN",
            "Guardian stopped"
        )
    }

    // -------------------------------------------------------------------------

    private fun isGuardianGenerationCurrent(
        generation: Long
    ): Boolean {
        return synchronized(guardianLock) {
            guardianGeneration == generation
        }
    }

    /**
     * Probes Shizuku binder and permission without ever requesting
     * permission from the Guardian.
     *
     * Any Throwable during the probe is treated as the binder being
     * unavailable. checkSelfPermission is only called after a
     * successful pingBinder().
     */
    private fun checkShizukuReadiness(): ShizukuReadiness {
        val result: ShizukuReadiness =
            try {
                if (!Shizuku.pingBinder()) {
                    ShizukuReadiness.BINDER_UNAVAILABLE
                } else {
                    val permission =
                        Shizuku.checkSelfPermission()

                    if (
                        permission ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        ShizukuReadiness.READY
                    } else {
                        ShizukuReadiness.PERMISSION_DENIED
                    }
                }
            } catch (t: Throwable) {
                ShizukuReadiness.BINDER_UNAVAILABLE
            }

        EventLogger.logStage(
            stage = "GUARDIAN_SHIZUKU_CHECK",
            status = result.name
        )

        return result
    }

    // -------------------------------------------------------------------------

    private suspend fun tick(
        context: Context,
        generation: Long
    ) {
        if (!isGuardianGenerationCurrent(generation)) {
            return
        }

        if (
            !RemoteControlManager.isEnabled(
                context
            )
        ) {
            resetFailureState()
            resetCloudflareFailureState()

            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "SKIPPED",
                details = "reason=feature_disabled"
            )

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: remote control disabled"
            )

            return
        }

        /*
         * Manager owns the complete Start/Stop/Restart lifecycle.
         *
         * Guardian must never perform a health check or request a repair
         * while another lifecycle operation is active.
         */
        if (
            RemoteControlManager.isLifecycleBusy()
        ) {
            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "SKIPPED",
                details = "reason=lifecycle_busy"
            )

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: engine lifecycle operation is active"
            )

            return
        }

        if (
            repairInProgress.get()
        ) {
            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "SKIPPED",
                details = "reason=repair_in_progress"
            )

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: repair already in progress"
            )

            return
        }

        if (
            cloudflareRepairInProgress.get()
        ) {
            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "SKIPPED",
                details = "reason=cloudflare_repair_in_progress"
            )

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: cloudflare repair already in progress"
            )

            return
        }

        when (
            RemoteControlManager.state.value
        ) {
            is RemoteControlState.Starting -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CHECK",
                    status = "SKIPPED",
                    details = "reason=engine_starting"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Check skipped: engine is starting"
                )

                return
            }

            is RemoteControlState.Stopping -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CHECK",
                    status = "SKIPPED",
                    details = "reason=engine_stopping"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Check skipped: engine is stopping"
                )

                return
            }

            else -> Unit
        }

        /*
         * Shizuku readiness gate.
         *
         * If Shizuku is not ready, health checks against
         * RemoteControlManager cannot produce reliable answers.
         * We must not mark the engine or the tunnel as unhealthy,
         * must not consume repair budget, and must not fire alerts.
         * We also must not reset existing failure counters, because
         * the previous failure state (if any) still stands.
         */
        val readiness =
            checkShizukuReadiness()

        if (readiness == ShizukuReadiness.BINDER_UNAVAILABLE) {
            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "SKIPPED",
                details = "reason=shizuku_service_unavailable"
            )

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: Shizuku binder is unavailable"
            )

            return
        }

        if (readiness == ShizukuReadiness.PERMISSION_DENIED) {
            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "SKIPPED",
                details = "reason=shizuku_permission_not_granted"
            )

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: Shizuku permission is not granted"
            )

            return
        }

        EventLogger.logStage(
            stage = "GUARDIAN_CHECK",
            status = "STARTED"
        )

        /*
         * Core health must be evaluated first. A dead core always takes
         * priority over the Cloudflare tunnel because Cloudflare repair
         * assumes Signaling, Agent, and Local are healthy.
         */
        val coreResult =
            checkEngineHealth()

        when (coreResult) {
            HealthResult.HEALTHY -> Unit

            HealthResult.UNHEALTHY -> {
                handleCoreRepair(
                    context = context,
                    generation = generation,
                    reason = "health_check_failed"
                )

                return
            }

            HealthResult.TIMEOUT -> {
                handleCoreRepair(
                    context = context,
                    generation = generation,
                    reason = "health_check_timeout"
                )

                return
            }

            HealthResult.SHIZUKU_UNAVAILABLE -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CHECK",
                    status = "SKIPPED",
                    details = "reason=shizuku_service_unavailable"
                )

                return
            }

            HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CHECK",
                    status = "SKIPPED",
                    details = "reason=shizuku_permission_not_granted"
                )

                return
            }
        }

        if (!isGuardianGenerationCurrent(generation)) {
            return
        }

        /*
         * Core is healthy. Capture the previous Core failure count
         * before resetting, so the recovery log reflects reality.
         */
        val previousCoreFailures =
            synchronized(
                guardianLock
            ) {
                consecutiveFailures
            }

        resetFailureState()

        if (previousCoreFailures > 0) {
            EventLogger.log(
                "GUARDIAN",
                "Engine recovered after " +
                    "$previousCoreFailures failure(s)"
            )
        }

        /*
         * Core is healthy. Now check whether the public tunnel is
         * still alive. A healthy core with a dead Cloudflare tunnel
         * must never trigger a Full Engine repair.
         */
        val cloudflareResult =
            checkCloudflareHealth()

        when (cloudflareResult) {
            HealthResult.HEALTHY -> {
                val previousCloudflareFailures =
                    synchronized(
                        guardianLock
                    ) {
                        cloudflareConsecutiveFailures
                    }

                resetCloudflareFailureState()

                if (previousCloudflareFailures > 0) {
                    EventLogger.log(
                        "GUARDIAN",
                        "Cloudflare tunnel recovered after " +
                            "$previousCloudflareFailures failure(s)"
                    )
                }

                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_CHECK",
                    status = "HEALTHY"
                )

                EventLogger.logStage(
                    stage = "GUARDIAN_CHECK",
                    status = "HEALTHY",
                    details =
                        "core=true; cloudflare=true; " +
                        "previousCoreFailures=$previousCoreFailures"
                )
            }

            HealthResult.UNHEALTHY -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_CHECK",
                    status = "UNHEALTHY"
                )

                handleCloudflareRepair(
                    context = context,
                    generation = generation
                )
            }

            HealthResult.TIMEOUT -> {
                /*
                 * TIMEOUT is only returned when Shizuku is READY.
                 * Treat as a Cloudflare failure.
                 */
                handleCloudflareRepair(
                    context = context,
                    generation = generation
                )
            }

            HealthResult.SHIZUKU_UNAVAILABLE -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_CHECK",
                    status = "SKIPPED",
                    details = "reason=shizuku_service_unavailable"
                )
            }

            HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_CHECK",
                    status = "SKIPPED",
                    details = "reason=shizuku_permission_not_granted"
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Core repair (existing Full Engine Guardian Repair path)
    // -------------------------------------------------------------------------

    private suspend fun handleCoreRepair(
        context: Context,
        generation: Long,
        reason: String
    ) {
        if (!isGuardianGenerationCurrent(generation)) {
            return
        }

        EventLogger.logStage(
            stage = "GUARDIAN_CHECK",
            status = "UNHEALTHY",
            details = "reason=$reason"
        )

        /*
         * After the maximum failed attempts, continue observing engine
         * health without creating an unlimited restart loop.
         */
        val repairsSuspended =
            synchronized(
                guardianLock
            ) {
                automaticRepairSuspended
            }

        if (repairsSuspended) {
            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "SKIPPED",
                details =
                    "reason=automatic_repair_suspended; " +
                    "maxAttempts=$MAX_REPAIR_ATTEMPTS"
            )

            EventLogger.log(
                "GUARDIAN",
                "Engine is unhealthy; automatic repair remains " +
                    "suspended after $MAX_REPAIR_ATTEMPTS failed attempts"
            )

            return
        }

        if (
            !repairInProgress.compareAndSet(
                false,
                true
            )
        ) {
            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "SKIPPED",
                details = "reason=repair_lock_unavailable"
            )

            EventLogger.log(
                "GUARDIAN",
                "Repair skipped: another repair acquired the lock"
            )

            return
        }

        var repairAttempt: Int? = null
        var repairDispatched = false

        try {
            // Shizuku re-check #1: immediately after acquiring the lock.
            val readiness1 =
                checkShizukuReadiness()

            if (readiness1 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness1
                        ) +
                        "_after_lock"
                )

                return
            }

            if (!isGuardianGenerationCurrent(generation)) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation"
                )

                return
            }

            /*
             * Re-check Manager ownership after acquiring the repair lock.
             * The state may have changed following the health check.
             */
            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=lifecycle_became_active"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair cancelled: lifecycle operation became active"
                )

                return
            }

            when (
                RemoteControlManager.state.value
            ) {
                is RemoteControlState.Starting -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_REPAIR",
                        status = "CANCELLED",
                        details = "reason=engine_started_normally"
                    )

                    EventLogger.log(
                        "GUARDIAN",
                        "Repair cancelled: engine started normally"
                    )

                    return
                }

                is RemoteControlState.Stopping -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_REPAIR",
                        status = "CANCELLED",
                        details = "reason=engine_stopping"
                    )

                    EventLogger.log(
                        "GUARDIAN",
                        "Repair cancelled: engine is stopping"
                    )

                    return
                }

                else -> Unit
            }

            val attempt =
                synchronized(
                    guardianLock
                ) {
                    if (
                        consecutiveFailures >=
                            MAX_REPAIR_ATTEMPTS
                    ) {
                        null
                    } else {
                        consecutiveFailures++

                        consecutiveFailures
                    }
                }

            if (attempt == null) {
                /*
                 * Ceiling reached. Perform a fresh verification with
                 * Shizuku READY before deciding whether to suspend
                 * and alert. Shizuku failure must not set
                 * automaticRepairSuspended=true, and must not fire an
                 * alert, and must not reset failure state.
                 */
                val verify =
                    checkEngineHealth()

                when (verify) {
                    HealthResult.HEALTHY -> {
                        resetFailureState()

                        EventLogger.logStage(
                            stage = "GUARDIAN_REPAIR",
                            status = "OK",
                            details = "reason=recovered_at_max_attempts"
                        )

                        EventLogger.log(
                            "GUARDIAN",
                            "Engine recovered before suspension"
                        )

                        return
                    }

                    HealthResult.SHIZUKU_UNAVAILABLE -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_REPAIR",
                            status = "SKIPPED",
                            details = "reason=shizuku_service_unavailable_at_max"
                        )

                        return
                    }

                    HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_REPAIR",
                            status = "SKIPPED",
                            details = "reason=shizuku_permission_not_granted_at_max"
                        )

                        return
                    }

                    HealthResult.UNHEALTHY,
                    HealthResult.TIMEOUT -> Unit
                }

                /*
                 * Verification confirmed a real engine failure
                 * while Shizuku was READY. Only now may we suspend
                 * automatic repair and fire the alert.
                 */
                synchronized(
                    guardianLock
                ) {
                    automaticRepairSuspended =
                        true
                }

                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=maximum_attempts_reached"
                )

                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "SUSPENDED",
                    details =
                        "attempts=$MAX_REPAIR_ATTEMPTS"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair cancelled: maximum attempts reached"
                )

                sendAlertOnce()

                return
            }

            repairAttempt =
                attempt

            val backoffMs =
                repairBackoffFor(
                    attempt
                )

            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "SCHEDULED",
                details =
                    "attempt=$attempt; " +
                    "backoffMs=$backoffMs"
            )

            EventLogger.log(
                "GUARDIAN",
                "Engine unhealthy; repair attempt " +
                    "#$attempt scheduled after " +
                    "${backoffMs}ms"
            )

            delay(
                backoffMs
            )

            if (!isGuardianGenerationCurrent(generation)) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation_after_backoff"
                )

                return
            }

            // Shizuku re-check #2: after backoff.
            val readiness2 =
                checkShizukuReadiness()

            if (readiness2 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness2
                        ) +
                        "_after_backoff"
                )

                return
            }

            /*
             * Never start a repair if another lifecycle operation began
             * during the backoff delay.
             */
            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=lifecycle_active_after_backoff"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair cancelled after backoff: " +
                        "lifecycle operation is active"
                )

                return
            }

            if (
                !RemoteControlManager.isEnabled(
                    context
                )
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=feature_disabled_after_backoff"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair cancelled after backoff: " +
                        "remote control disabled"
                )

                resetFailureState()

                return
            }

            // Shizuku re-check #3: immediately before dispatch.
            val readiness3 =
                checkShizukuReadiness()

            if (readiness3 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness3
                        ) +
                        "_before_dispatch"
                )

                return
            }

            /*
             * Atomic final dispatch under guardianDispatchLock.
             *
             * This closes the race where stop() bumps guardianGeneration
             * after our last generation check but before the Manager
             * call actually runs. We verify generation, lifecycle and
             * feature flag under guardianDispatchLock, and only then
             * invoke RemoteControlManager.restart(). stop() is likewise
             * serialized through the same lock, so either the dispatch
             * happens before stop() (the repair goes out), or it is
             * rejected because the generation is stale.
             *
             * Lock order inside this block:
             *   guardianDispatchLock (held) -> guardianLock (brief)
             * which is the single allowed order.
             */
            var dispatchAccepted =
                false

            synchronized(guardianDispatchLock) {
                val stillCurrent =
                    synchronized(guardianLock) {
                        guardianGeneration ==
                            generation
                    }

                if (
                    stillCurrent &&
                    !RemoteControlManager.isLifecycleBusy() &&
                    RemoteControlManager.isEnabled(
                        context
                    )
                ) {
                    repairDispatched =
                        true

                    dispatchAccepted =
                        true

                    RemoteControlManager.restart(
                        context = context,
                        reason =
                            RemoteControlManager
                                .StartReason
                                .GUARDIAN_REPAIR
                    )
                }
            }

            if (!dispatchAccepted) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details =
                        "attempt=$attempt; " +
                        "reason=stale_or_busy_before_dispatch"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair dispatch rejected: " +
                        "stale generation, lifecycle busy, " +
                        "or feature disabled"
                )

                return
            }

            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "STARTED",
                details = "attempt=$attempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Starting Guardian repair attempt " +
                    "#$attempt"
            )

            /*
             * Give cleanup, file preparation, Signaling, and Agent time
             * to complete their controlled startup.
             */
            delay(
                REPAIR_SETTLE_MS
            )

            if (!isGuardianGenerationCurrent(generation)) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation_after_settle"
                )

                return
            }

            /*
             * If Manager still owns a lifecycle operation, do not perform
             * a competing health check. The next Guardian tick can verify
             * the final state.
             */
            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "PENDING",
                    details =
                        "attempt=$attempt; " +
                        "reason=lifecycle_still_active"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair lifecycle still active; " +
                        "waiting for next health check"
                )

                return
            }

            // Shizuku re-check #4: before the post-settle verification.
            val readiness4 =
                checkShizukuReadiness()

            if (readiness4 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "PENDING",
                    details =
                        "attempt=$attempt; " +
                        "reason=" +
                        shizukuReasonFor(
                            readiness4
                        ) +
                        "_during_verification"
                )

                return
            }

            val verifyResult =
                checkEngineHealth()

            when (verifyResult) {
                HealthResult.HEALTHY -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_REPAIR",
                        status = "OK",
                        details = "attempt=$attempt"
                    )

                    EventLogger.log(
                        "GUARDIAN",
                        "Repair successful on attempt " +
                            "#$attempt"
                    )

                    resetFailureState()

                    return
                }

                HealthResult.SHIZUKU_UNAVAILABLE -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_REPAIR",
                        status = "PENDING",
                        details =
                            "attempt=$attempt; " +
                            "reason=shizuku_service_unavailable_after_settle"
                    )

                    return
                }

                HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_REPAIR",
                        status = "PENDING",
                        details =
                            "attempt=$attempt; " +
                            "reason=shizuku_permission_not_granted_after_settle"
                    )

                    return
                }

                HealthResult.UNHEALTHY,
                HealthResult.TIMEOUT -> Unit
            }

            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "FAILED",
                details = "attempt=$attempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Repair failed on attempt " +
                    "#$attempt"
            )

            if (
                attempt >=
                    MAX_REPAIR_ATTEMPTS
            ) {
                synchronized(
                    guardianLock
                ) {
                    automaticRepairSuspended =
                        true
                }

                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "SUSPENDED",
                    details =
                        "attempts=$MAX_REPAIR_ATTEMPTS"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Automatic repair suspended after " +
                        "$MAX_REPAIR_ATTEMPTS failed attempts"
                )

                sendAlertOnce()
            }
        } finally {
            /*
             * Only mutate per-cycle state when this coroutine still
             * belongs to the current Guardian generation. A stale
             * finally must not clear locks for a new cycle.
             */
            if (isGuardianGenerationCurrent(generation)) {
                repairInProgress.set(
                    false
                )

                val countedAttempt =
                    repairAttempt

                if (!repairDispatched && countedAttempt != null) {
                    revertCoreAttemptIfUndispatched(
                        countedAttempt
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cloudflare-only repair path
    // -------------------------------------------------------------------------

    private suspend fun handleCloudflareRepair(
        context: Context,
        generation: Long
    ) {
        if (!isGuardianGenerationCurrent(generation)) {
            return
        }

        val repairsSuspended =
            synchronized(
                guardianLock
            ) {
                cloudflareAutomaticRepairSuspended
            }

        if (repairsSuspended) {
            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "SKIPPED",
                details =
                    "reason=automatic_repair_suspended; " +
                    "maxAttempts=$MAX_REPAIR_ATTEMPTS"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare tunnel is unhealthy; automatic repair " +
                    "remains suspended after $MAX_REPAIR_ATTEMPTS " +
                    "failed attempts"
            )

            return
        }

        if (
            !cloudflareRepairInProgress.compareAndSet(
                false,
                true
            )
        ) {
            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "SKIPPED",
                details = "reason=repair_lock_unavailable"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare repair skipped: " +
                    "another repair acquired the lock"
            )

            return
        }

        var cloudflareRepairAttempt: Int? = null
        var repairDispatched = false

        try {
            // Shizuku re-check #1: immediately after acquiring the lock.
            val readiness1 =
                checkShizukuReadiness()

            if (readiness1 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness1
                        ) +
                        "_after_lock"
                )

                return
            }

            if (!isGuardianGenerationCurrent(generation)) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation"
                )

                return
            }

            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=lifecycle_became_active"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled: " +
                        "lifecycle operation became active"
                )

                return
            }

            if (
                !RemoteControlManager.isEnabled(
                    context
                )
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=feature_disabled"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled: " +
                        "remote control disabled"
                )

                resetCloudflareFailureState()

                return
            }

            // Shizuku re-check #2: before the pre-repair core check.
            val readiness2 =
                checkShizukuReadiness()

            if (readiness2 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness2
                        ) +
                        "_before_core_check"
                )

                return
            }

            val coreCheckBeforeRepair =
                checkEngineHealth()

            if (coreCheckBeforeRepair != HealthResult.HEALTHY) {
                val reason =
                    when (coreCheckBeforeRepair) {
                        HealthResult.SHIZUKU_UNAVAILABLE ->
                            "shizuku_service_unavailable_before_repair"

                        HealthResult.SHIZUKU_PERMISSION_DENIED ->
                            "shizuku_permission_not_granted_before_repair"

                        else ->
                            "core_became_unhealthy"
                    }

                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=$reason"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled: $reason"
                )

                return
            }

            val attempt =
                synchronized(
                    guardianLock
                ) {
                    if (
                        cloudflareConsecutiveFailures >=
                            MAX_REPAIR_ATTEMPTS
                    ) {
                        null
                    } else {
                        cloudflareConsecutiveFailures++

                        cloudflareConsecutiveFailures
                    }
                }

            if (attempt == null) {
                /*
                 * Ceiling reached. Perform a fresh verification with
                 * Shizuku READY before deciding whether to suspend
                 * and alert. Shizuku failure must not set
                 * cloudflareAutomaticRepairSuspended=true, and must
                 * not fire an alert, and must not reset failure state.
                 */
                val verify =
                    checkCloudflareHealth()

                when (verify) {
                    HealthResult.HEALTHY -> {
                        resetCloudflareFailureState()

                        EventLogger.logStage(
                            stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                            status = "OK",
                            details = "reason=recovered_at_max_attempts"
                        )

                        EventLogger.log(
                            "GUARDIAN",
                            "Cloudflare tunnel recovered before suspension"
                        )

                        return
                    }

                    HealthResult.SHIZUKU_UNAVAILABLE -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                            status = "SKIPPED",
                            details = "reason=shizuku_service_unavailable_at_max"
                        )

                        return
                    }

                    HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                            status = "SKIPPED",
                            details = "reason=shizuku_permission_not_granted_at_max"
                        )

                        return
                    }

                    HealthResult.UNHEALTHY,
                    HealthResult.TIMEOUT -> Unit
                }

                /*
                 * Verification confirmed a real tunnel failure
                 * while Shizuku was READY. Only now may we suspend
                 * automatic repair and fire the alert.
                 */
                synchronized(
                    guardianLock
                ) {
                    cloudflareAutomaticRepairSuspended =
                        true
                }

                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=maximum_attempts_reached"
                )

                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "SUSPENDED",
                    details =
                        "attempts=$MAX_REPAIR_ATTEMPTS"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled: " +
                        "maximum attempts reached"
                )

                sendCloudflareAlertOnce()

                return
            }

            cloudflareRepairAttempt =
                attempt

            val backoffMs =
                repairBackoffFor(
                    attempt
                )

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "SCHEDULED",
                details =
                    "attempt=$attempt; " +
                    "backoffMs=$backoffMs"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare tunnel unhealthy; repair attempt " +
                    "#$attempt scheduled after " +
                    "${backoffMs}ms"
            )

            delay(
                backoffMs
            )

            if (!isGuardianGenerationCurrent(generation)) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation_after_backoff"
                )

                return
            }

            // Shizuku re-check #3: after backoff.
            val readiness3 =
                checkShizukuReadiness()

            if (readiness3 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness3
                        ) +
                        "_after_backoff"
                )

                return
            }

            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=lifecycle_active_after_backoff"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled after backoff: " +
                        "lifecycle operation is active"
                )

                return
            }

            if (
                !RemoteControlManager.isEnabled(
                    context
                )
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=feature_disabled_after_backoff"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled after backoff: " +
                        "remote control disabled"
                )

                resetCloudflareFailureState()

                return
            }

            // Shizuku re-check #4: before the post-backoff core check.
            val readiness4 =
                checkShizukuReadiness()

            if (readiness4 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness4
                        ) +
                        "_before_core_check_after_backoff"
                )

                return
            }

            val coreCheckAfterBackoff =
                checkEngineHealth()

            if (coreCheckAfterBackoff != HealthResult.HEALTHY) {
                val reason =
                    when (coreCheckAfterBackoff) {
                        HealthResult.SHIZUKU_UNAVAILABLE ->
                            "shizuku_service_unavailable_after_backoff"

                        HealthResult.SHIZUKU_PERMISSION_DENIED ->
                            "shizuku_permission_not_granted_after_backoff"

                        else ->
                            "core_unhealthy_after_backoff"
                    }

                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=$reason"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled after backoff: $reason"
                )

                return
            }

            /*
             * If the tunnel recovered by itself during the backoff,
             * do not fire an unnecessary repair.
             */
            val cloudflareRecheck =
                checkCloudflareHealth()

            when (cloudflareRecheck) {
                HealthResult.HEALTHY -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                        status = "CANCELLED",
                        details = "reason=cloudflare_recovered_before_repair"
                    )

                    EventLogger.log(
                        "GUARDIAN",
                        "Cloudflare repair cancelled: tunnel recovered " +
                            "before repair was launched"
                    )

                    resetCloudflareFailureState()

                    return
                }

                HealthResult.SHIZUKU_UNAVAILABLE -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                        status = "CANCELLED",
                        details = "reason=shizuku_service_unavailable_before_dispatch"
                    )

                    return
                }

                HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                        status = "CANCELLED",
                        details = "reason=shizuku_permission_not_granted_before_dispatch"
                    )

                    return
                }

                HealthResult.UNHEALTHY,
                HealthResult.TIMEOUT -> Unit
            }

            // Shizuku re-check #5: immediately before dispatch.
            val readiness5 =
                checkShizukuReadiness()

            if (readiness5 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details =
                        "reason=" +
                        shizukuReasonFor(
                            readiness5
                        ) +
                        "_before_dispatch"
                )

                return
            }

            /*
             * Atomic final dispatch under guardianDispatchLock.
             *
             * Same rationale as the Core repair dispatch above:
             * serialize with stop() so that a stop() that bumps the
             * generation cannot be bypassed by a stale dispatch.
             *
             * Lock order inside this block:
             *   guardianDispatchLock (held) -> guardianLock (brief)
             */
            var dispatchAccepted =
                false

            synchronized(guardianDispatchLock) {
                val stillCurrent =
                    synchronized(guardianLock) {
                        guardianGeneration ==
                            generation
                    }

                if (
                    stillCurrent &&
                    !RemoteControlManager.isLifecycleBusy() &&
                    RemoteControlManager.isEnabled(
                        context
                    )
                ) {
                    repairDispatched =
                        true

                    dispatchAccepted =
                        true

                    RemoteControlManager.repairPublicTunnel(
                        context
                    )
                }
            }

            if (!dispatchAccepted) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details =
                        "attempt=$attempt; " +
                        "reason=stale_or_busy_before_dispatch"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair dispatch rejected: " +
                        "stale generation, lifecycle busy, " +
                        "or feature disabled"
                )

                return
            }

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "STARTED",
                details = "attempt=$attempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Starting Cloudflare-only repair attempt " +
                    "#$attempt"
            )

            delay(
                REPAIR_SETTLE_MS
            )

            if (!isGuardianGenerationCurrent(generation)) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation_after_settle"
                )

                return
            }

            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "PENDING",
                    details =
                        "attempt=$attempt; " +
                        "reason=lifecycle_still_active"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair lifecycle still active; " +
                        "waiting for next health check"
                )

                return
            }

            // Shizuku re-check #6: before post-settle verification.
            val readiness6 =
                checkShizukuReadiness()

            if (readiness6 != ShizukuReadiness.READY) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "PENDING",
                    details =
                        "attempt=$attempt; " +
                        "reason=" +
                        shizukuReasonFor(
                            readiness6
                        ) +
                        "_during_verification"
                )

                return
            }

            val coreVerify =
                checkEngineHealth()

            if (coreVerify != HealthResult.HEALTHY) {
                when (coreVerify) {
                    HealthResult.SHIZUKU_UNAVAILABLE -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                            status = "PENDING",
                            details =
                                "attempt=$attempt; " +
                                "reason=shizuku_service_unavailable_during_core_verify"
                        )

                        return
                    }

                    HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                            status = "PENDING",
                            details =
                                "attempt=$attempt; " +
                                "reason=shizuku_permission_not_granted_during_core_verify"
                        )

                        return
                    }

                    else -> {
                        EventLogger.logStage(
                            stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                            status = "FAILED",
                            details =
                                "attempt=$attempt; " +
                                "reason=core_unhealthy_after_repair"
                        )

                        EventLogger.log(
                            "GUARDIAN",
                            "Cloudflare repair attempt " +
                                "#$attempt failed: " +
                                "core became unhealthy after repair"
                        )

                        return
                    }
                }
            }

            val cloudflareVerify =
                checkCloudflareHealth()

            when (cloudflareVerify) {
                HealthResult.HEALTHY -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                        status = "OK",
                        details = "attempt=$attempt"
                    )

                    EventLogger.log(
                        "GUARDIAN",
                        "Cloudflare repair successful on attempt " +
                            "#$attempt"
                    )

                    resetCloudflareFailureState()

                    return
                }

                HealthResult.SHIZUKU_UNAVAILABLE -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                        status = "PENDING",
                        details =
                            "attempt=$attempt; " +
                            "reason=shizuku_service_unavailable_after_settle"
                    )

                    return
                }

                HealthResult.SHIZUKU_PERMISSION_DENIED -> {
                    EventLogger.logStage(
                        stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                        status = "PENDING",
                        details =
                            "attempt=$attempt; " +
                            "reason=shizuku_permission_not_granted_after_settle"
                    )

                    return
                }

                HealthResult.UNHEALTHY,
                HealthResult.TIMEOUT -> Unit
            }

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "FAILED",
                details = "attempt=$attempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare repair failed on attempt " +
                    "#$attempt"
            )

            if (
                attempt >=
                    MAX_REPAIR_ATTEMPTS
            ) {
                synchronized(
                    guardianLock
                ) {
                    cloudflareAutomaticRepairSuspended =
                        true
                }

                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "SUSPENDED",
                    details =
                        "attempts=$MAX_REPAIR_ATTEMPTS"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare automatic repair suspended after " +
                        "$MAX_REPAIR_ATTEMPTS failed attempts"
                )

                sendCloudflareAlertOnce()
            }
        } finally {
            if (isGuardianGenerationCurrent(generation)) {
                cloudflareRepairInProgress.set(
                    false
                )

                val countedAttempt =
                    cloudflareRepairAttempt

                if (!repairDispatched && countedAttempt != null) {
                    revertCloudflareAttemptIfUndispatched(
                        countedAttempt
                    )
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Health checks
    // -------------------------------------------------------------------------

    /**
     * Runs the process health check with a timeout so a missing Shizuku
     * callback cannot block the Guardian loop indefinitely.
     *
     * Any false or timeout result is re-validated against Shizuku
     * before being classified as a real engine failure.
     */
    private suspend fun checkEngineHealth(): HealthResult {
        val preReadiness =
            checkShizukuReadiness()

        if (preReadiness == ShizukuReadiness.BINDER_UNAVAILABLE) {
            return HealthResult.SHIZUKU_UNAVAILABLE
        }

        if (preReadiness == ShizukuReadiness.PERMISSION_DENIED) {
            return HealthResult.SHIZUKU_PERMISSION_DENIED
        }

        val result =
            withTimeoutOrNull(
                HEALTH_CHECK_TIMEOUT_MS
            ) {
                RemoteControlManager
                    .isAgentRunningBlocking()
            }

        if (result == null) {
            val recheck =
                checkShizukuReadiness()

            if (recheck == ShizukuReadiness.BINDER_UNAVAILABLE) {
                return HealthResult.SHIZUKU_UNAVAILABLE
            }

            if (recheck == ShizukuReadiness.PERMISSION_DENIED) {
                return HealthResult.SHIZUKU_PERMISSION_DENIED
            }

            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "TIMEOUT",
                details =
                    "timeoutMs=$HEALTH_CHECK_TIMEOUT_MS"
            )

            EventLogger.log(
                "GUARDIAN",
                "Engine health check timed out"
            )

            return HealthResult.TIMEOUT
        }

        if (result) {
            return HealthResult.HEALTHY
        }

        val recheck =
            checkShizukuReadiness()

        if (recheck == ShizukuReadiness.BINDER_UNAVAILABLE) {
            return HealthResult.SHIZUKU_UNAVAILABLE
        }

        if (recheck == ShizukuReadiness.PERMISSION_DENIED) {
            return HealthResult.SHIZUKU_PERMISSION_DENIED
        }

        return HealthResult.UNHEALTHY
    }

    /**
     * Runs the cloudflared health check with a timeout so a missing
     * Shizuku callback cannot block the Guardian loop indefinitely.
     *
     * Any false or timeout result is re-validated against Shizuku
     * before being classified as a real tunnel failure.
     */
    private suspend fun checkCloudflareHealth(): HealthResult {
        val preReadiness =
            checkShizukuReadiness()

        if (preReadiness == ShizukuReadiness.BINDER_UNAVAILABLE) {
            return HealthResult.SHIZUKU_UNAVAILABLE
        }

        if (preReadiness == ShizukuReadiness.PERMISSION_DENIED) {
            return HealthResult.SHIZUKU_PERMISSION_DENIED
        }

        val result =
            withTimeoutOrNull(
                HEALTH_CHECK_TIMEOUT_MS
            ) {
                RemoteControlManager
                    .isCloudflaredRunningBlocking()
            }

        if (result == null) {
            val recheck =
                checkShizukuReadiness()

            if (recheck == ShizukuReadiness.BINDER_UNAVAILABLE) {
                return HealthResult.SHIZUKU_UNAVAILABLE
            }

            if (recheck == ShizukuReadiness.PERMISSION_DENIED) {
                return HealthResult.SHIZUKU_PERMISSION_DENIED
            }

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_CHECK",
                status = "TIMEOUT",
                details =
                    "timeoutMs=$HEALTH_CHECK_TIMEOUT_MS"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare health check timed out"
            )

            return HealthResult.TIMEOUT
        }

        if (result) {
            return HealthResult.HEALTHY
        }

        val recheck =
            checkShizukuReadiness()

        if (recheck == ShizukuReadiness.BINDER_UNAVAILABLE) {
            return HealthResult.SHIZUKU_UNAVAILABLE
        }

        if (recheck == ShizukuReadiness.PERMISSION_DENIED) {
            return HealthResult.SHIZUKU_PERMISSION_DENIED
        }

        return HealthResult.UNHEALTHY
    }

    // -------------------------------------------------------------------------
    // Attempt accounting helpers
    // -------------------------------------------------------------------------

    /**
     * Reverts a Core repair attempt reservation iff the same attempt
     * value is still the current counter. This never drives the counter
     * below zero and never touches a dispatched attempt.
     */
    private fun revertCoreAttemptIfUndispatched(
        attempt: Int
    ) {
        synchronized(
            guardianLock
        ) {
            if (
                consecutiveFailures ==
                attempt &&
                consecutiveFailures > 0
            ) {
                consecutiveFailures--
            }
        }
    }

    private fun revertCloudflareAttemptIfUndispatched(
        attempt: Int
    ) {
        synchronized(
            guardianLock
        ) {
            if (
                cloudflareConsecutiveFailures ==
                attempt &&
                cloudflareConsecutiveFailures > 0
            ) {
                cloudflareConsecutiveFailures--
            }
        }
    }

    private fun repairBackoffFor(
        attempt: Int
    ): Long {
        return when (attempt) {
            1 ->
                FIRST_REPAIR_BACKOFF_MS

            2 ->
                SECOND_REPAIR_BACKOFF_MS

            else ->
                THIRD_REPAIR_BACKOFF_MS
        }
    }

    private fun shizukuReasonFor(
        readiness: ShizukuReadiness
    ): String {
        return when (readiness) {
            ShizukuReadiness.BINDER_UNAVAILABLE ->
                "shizuku_service_unavailable"

            ShizukuReadiness.PERMISSION_DENIED ->
                "shizuku_permission_not_granted"

            ShizukuReadiness.READY ->
                "shizuku_ready"
        }
    }

    // -------------------------------------------------------------------------
    // Alerts
    // -------------------------------------------------------------------------

    private suspend fun sendAlertOnce() {
        val shouldSend =
            synchronized(
                guardianLock
            ) {
                if (alertSent) {
                    false
                } else {
                    alertSent =
                        true

                    true
                }
            }

        if (!shouldSend) {
            EventLogger.logStage(
                stage = "GUARDIAN_ALERT",
                status = "SKIPPED",
                details = "reason=already_sent"
            )

            return
        }

        EventLogger.logStage(
            stage = "GUARDIAN_ALERT",
            status = "REQUESTED",
            details =
                "failedAttempts=$MAX_REPAIR_ATTEMPTS"
        )

        EventLogger.log(
            "GUARDIAN",
            "Sending alert after " +
                "$MAX_REPAIR_ATTEMPTS failed repairs"
        )

        sendAlertEmail()
    }

    private suspend fun sendAlertEmail() {
        try {
            val success =
                EmailSender.sendAlert(
                    deviceName =
                        DeviceIdentity.getName(),
                    reason =
                        "Remote-control engine failed to recover after " +
                            "$MAX_REPAIR_ATTEMPTS attempts"
                )

            if (success) {
                EventLogger.logStage(
                    stage = "GUARDIAN_ALERT",
                    status = "OK"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Alert email send accepted"
                )
            } else {
                EventLogger.logStage(
                    stage = "GUARDIAN_ALERT",
                    status = "FAILED",
                    details =
                        "reason=email_sender_returned_false"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Alert email send returned false"
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            EventLogger.logStage(
                stage = "GUARDIAN_ALERT",
                status = "FAILED",
                details =
                    "reason=" +
                    safeLogSummary(
                        t.message
                            ?: t.javaClass.simpleName
                    )
            )

            EventLogger.log(
                "GUARDIAN",
                "Alert email failed: " +
                    (
                        t.message
                            ?: t.javaClass.simpleName
                    )
            )
        }
    }

    private suspend fun sendCloudflareAlertOnce() {
        val shouldSend =
            synchronized(
                guardianLock
            ) {
                if (cloudflareAlertSent) {
                    false
                } else {
                    cloudflareAlertSent =
                        true

                    true
                }
            }

        if (!shouldSend) {
            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_ALERT",
                status = "SKIPPED",
                details = "reason=already_sent"
            )

            return
        }

        EventLogger.logStage(
            stage = "GUARDIAN_CLOUDFLARE_ALERT",
            status = "REQUESTED",
            details =
                "failedAttempts=$MAX_REPAIR_ATTEMPTS"
        )

        EventLogger.log(
            "GUARDIAN",
            "Sending cloudflare alert after " +
                "$MAX_REPAIR_ATTEMPTS failed repairs"
        )

        sendCloudflareAlertEmail()
    }

    private suspend fun sendCloudflareAlertEmail() {
        try {
            val success =
                EmailSender.sendAlert(
                    deviceName =
                        DeviceIdentity.getName(),
                    reason =
                        "Cloudflare public tunnel failed to recover " +
                            "after $MAX_REPAIR_ATTEMPTS attempts " +
                            "while the remote-control core remained healthy"
                )

            if (success) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_ALERT",
                    status = "OK"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare alert email send accepted"
                )
            } else {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_ALERT",
                    status = "FAILED",
                    details =
                        "reason=email_sender_returned_false"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare alert email send returned false"
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_ALERT",
                status = "FAILED",
                details =
                    "reason=" +
                    safeLogSummary(
                        t.message
                            ?: t.javaClass.simpleName
                    )
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare alert email failed: " +
                    (
                        t.message
                            ?: t.javaClass.simpleName
                    )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Failure state
    // -------------------------------------------------------------------------

    private fun resetFailureState() {
        synchronized(
            guardianLock
        ) {
            resetFailureStateLocked()
        }
    }

    private fun resetFailureStateLocked() {
        consecutiveFailures =
            0

        alertSent =
            false

        automaticRepairSuspended =
            false
    }

    private fun resetCloudflareFailureState() {
        synchronized(
            guardianLock
        ) {
            resetCloudflareFailureStateLocked()
        }
    }

    private fun resetCloudflareFailureStateLocked() {
        cloudflareConsecutiveFailures =
            0

        cloudflareAlertSent =
            false

        cloudflareAutomaticRepairSuspended =
            false
    }

    private fun safeLogSummary(
        message: String
    ): String {
        return message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(300)
    }
}
