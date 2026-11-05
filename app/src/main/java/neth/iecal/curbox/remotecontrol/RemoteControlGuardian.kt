package neth.iecal.curbox.remotecontrol

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    private val scope =
        CoroutineScope(
            Dispatchers.IO
        )

    private val guardianLock =
        Any()

    private var job:
        Job? = null

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

            resetFailureStateLocked()
            resetCloudflareFailureStateLocked()

            job =
                scope.launch {
                    EventLogger.logStage(
                        stage = "GUARDIAN",
                        status = "STARTED"
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
                                appContext
                            )
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

    /**
     * Stops the Guardian loop and prevents the current repair from
     * launching another recovery attempt.
     */
    fun stop() {
        synchronized(guardianLock) {
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

    private suspend fun tick(
        context: Context
    ) {
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

        EventLogger.logStage(
            stage = "GUARDIAN_CHECK",
            status = "STARTED"
        )

        /*
         * Core health must be evaluated first. A dead core always takes
         * priority over the Cloudflare tunnel because Cloudflare repair
         * assumes Signaling, Agent, and Local are healthy.
         */
        val coreHealthy =
            checkEngineHealth()

        if (!coreHealthy) {
            handleCoreRepair(
                context
            )

            return
        }

        /*
         * Core is healthy. Reset the Core repair state immediately,
         * regardless of whether Cloudflare is healthy. A healthy core
         * means consecutiveFailures / alertSent / automaticRepairSuspended
         * are no longer meaningful and must not affect future Core
         * failure handling.
         */
        resetFailureState()

        /*
         * Core is healthy. Now check whether the public tunnel is
         * still alive. A healthy core with a dead Cloudflare tunnel
         * must never trigger a Full Engine repair.
         */
        val cloudflareHealthy =
            checkCloudflareHealth()

        if (cloudflareHealthy) {
            val previousFailures =
                synchronized(
                    guardianLock
                ) {
                    consecutiveFailures
                }

            if (previousFailures > 0) {
                EventLogger.log(
                    "GUARDIAN",
                    "Engine recovered after " +
                        "$previousFailures failure(s)"
                )
            }

            EventLogger.logStage(
                stage = "GUARDIAN_CHECK",
                status = "HEALTHY",
                details =
                    "core=true; cloudflare=true; " +
                    "previousFailures=$previousFailures"
            )

            resetCloudflareFailureState()
            return
        }

        EventLogger.logStage(
            stage = "GUARDIAN_CLOUDFLARE_CHECK",
            status = "UNHEALTHY"
        )

        handleCloudflareRepair(
            context
        )
    }

    // -------------------------------------------------------------------------
    // Core repair (existing Full Engine Guardian Repair path)
    // -------------------------------------------------------------------------

    private suspend fun handleCoreRepair(
        context: Context
    ) {
        EventLogger.logStage(
            stage = "GUARDIAN_CHECK",
            status = "UNHEALTHY"
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

        try {
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

            val repairAttempt =
                synchronized(
                    guardianLock
                ) {
                    if (
                        consecutiveFailures >=
                            MAX_REPAIR_ATTEMPTS
                    ) {
                        automaticRepairSuspended =
                            true

                        null
                    } else {
                        consecutiveFailures++

                        consecutiveFailures
                    }
                }

            if (repairAttempt == null) {
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

            val backoffMs =
                repairBackoffFor(
                    repairAttempt
                )

            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "SCHEDULED",
                details =
                    "attempt=$repairAttempt; " +
                    "backoffMs=$backoffMs"
            )

            EventLogger.log(
                "GUARDIAN",
                "Engine unhealthy; repair attempt " +
                    "#$repairAttempt scheduled after " +
                    "${backoffMs}ms"
            )

            delay(
                backoffMs
            )

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

            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "STARTED",
                details = "attempt=$repairAttempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Starting Guardian repair attempt " +
                    "#$repairAttempt"
            )

            RemoteControlManager.restart(
                context = context,
                reason =
                    RemoteControlManager
                        .StartReason
                        .GUARDIAN_REPAIR
            )

            /*
             * Give cleanup, file preparation, Signaling, and Agent time
             * to complete their controlled startup.
             */
            delay(
                REPAIR_SETTLE_MS
            )

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
                        "attempt=$repairAttempt; " +
                        "reason=lifecycle_still_active"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair lifecycle still active; " +
                        "waiting for next health check"
                )

                return
            }

            val recovered =
                checkEngineHealth()

            if (recovered) {
                EventLogger.logStage(
                    stage = "GUARDIAN_REPAIR",
                    status = "OK",
                    details = "attempt=$repairAttempt"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Repair successful on attempt " +
                        "#$repairAttempt"
                )

                resetFailureState()
                return
            }

            EventLogger.logStage(
                stage = "GUARDIAN_REPAIR",
                status = "FAILED",
                details = "attempt=$repairAttempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Repair failed on attempt " +
                    "#$repairAttempt"
            )

            if (
                repairAttempt >=
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
            repairInProgress.set(
                false
            )
        }
    }

    // -------------------------------------------------------------------------
    // Cloudflare-only repair path
    // -------------------------------------------------------------------------

    private suspend fun handleCloudflareRepair(
        context: Context
    ) {
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

        try {
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

            if (
                !checkEngineHealth()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=core_became_unhealthy"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled: " +
                        "core became unhealthy before repair"
                )

                return
            }

            val cloudflareRepairAttempt =
                synchronized(
                    guardianLock
                ) {
                    if (
                        cloudflareConsecutiveFailures >=
                            MAX_REPAIR_ATTEMPTS
                    ) {
                        cloudflareAutomaticRepairSuspended =
                            true

                        null
                    } else {
                        cloudflareConsecutiveFailures++

                        cloudflareConsecutiveFailures
                    }
                }

            if (cloudflareRepairAttempt == null) {
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

            val backoffMs =
                repairBackoffFor(
                    cloudflareRepairAttempt
                )

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "SCHEDULED",
                details =
                    "attempt=$cloudflareRepairAttempt; " +
                    "backoffMs=$backoffMs"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare tunnel unhealthy; repair attempt " +
                    "#$cloudflareRepairAttempt scheduled after " +
                    "${backoffMs}ms"
            )

            delay(
                backoffMs
            )

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

            if (
                !checkEngineHealth()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=core_unhealthy_after_backoff"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair cancelled after backoff: " +
                        "core became unhealthy"
                )

                return
            }

            /*
             * If the tunnel recovered by itself during the backoff,
             * do not fire an unnecessary repair.
             */
            if (
                checkCloudflareHealth()
            ) {
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

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "STARTED",
                details = "attempt=$cloudflareRepairAttempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Starting Cloudflare-only repair attempt " +
                    "#$cloudflareRepairAttempt"
            )

            RemoteControlManager.repairPublicTunnel(
                context
            )

            delay(
                REPAIR_SETTLE_MS
            )

            if (
                RemoteControlManager.isLifecycleBusy()
            ) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "PENDING",
                    details =
                        "attempt=$cloudflareRepairAttempt; " +
                        "reason=lifecycle_still_active"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair lifecycle still active; " +
                        "waiting for next health check"
                )

                return
            }

            val coreStillHealthy =
                checkEngineHealth()

            if (!coreStillHealthy) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "FAILED",
                    details =
                        "attempt=$cloudflareRepairAttempt; " +
                        "reason=core_unhealthy_after_repair"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair attempt " +
                        "#$cloudflareRepairAttempt failed: " +
                        "core became unhealthy after repair"
                )

                return
            }

            val cloudflareRecovered =
                checkCloudflareHealth()

            if (cloudflareRecovered) {
                EventLogger.logStage(
                    stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                    status = "OK",
                    details = "attempt=$cloudflareRepairAttempt"
                )

                EventLogger.log(
                    "GUARDIAN",
                    "Cloudflare repair successful on attempt " +
                        "#$cloudflareRepairAttempt"
                )

                resetCloudflareFailureState()
                return
            }

            EventLogger.logStage(
                stage = "GUARDIAN_CLOUDFLARE_REPAIR",
                status = "FAILED",
                details = "attempt=$cloudflareRepairAttempt"
            )

            EventLogger.log(
                "GUARDIAN",
                "Cloudflare repair failed on attempt " +
                    "#$cloudflareRepairAttempt"
            )

            if (
                cloudflareRepairAttempt >=
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
            cloudflareRepairInProgress.set(
                false
            )
        }
    }

    /**
     * Runs the process health check with a timeout so a missing Shizuku
     * callback cannot block the Guardian loop indefinitely.
     */
    private suspend fun checkEngineHealth(): Boolean {
        val result =
            withTimeoutOrNull(
                HEALTH_CHECK_TIMEOUT_MS
            ) {
                RemoteControlManager
                    .isAgentRunningBlocking()
            }

        if (result == null) {
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

            return false
        }

        return result
    }

    /**
     * Runs the cloudflared health check with a timeout so a missing
     * Shizuku callback cannot block the Guardian loop indefinitely.
     */
    private suspend fun checkCloudflareHealth(): Boolean {
        val result =
            withTimeoutOrNull(
                HEALTH_CHECK_TIMEOUT_MS
            ) {
                RemoteControlManager
                    .isCloudflaredRunningBlocking()
            }

        if (result == null) {
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

            return false
        }

        return result
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
