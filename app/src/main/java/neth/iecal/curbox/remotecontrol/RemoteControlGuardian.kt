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

            resetFailureStateLocked()
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

        val running =
            checkEngineHealth()

        if (running) {
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
                    "previousFailures=$previousFailures"
            )

            resetFailureState()
            return
        }

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

            RemoteControlManager.start(
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
