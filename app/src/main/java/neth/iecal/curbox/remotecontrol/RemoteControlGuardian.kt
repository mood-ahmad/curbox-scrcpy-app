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
                EventLogger.log(
                    "GUARDIAN",
                    "Start skipped: Guardian is already active"
                )

                return
            }

            resetFailureStateLocked()

            job =
                scope.launch {
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
            EventLogger.log(
                "GUARDIAN",
                "Check skipped: engine lifecycle operation is active"
            )

            return
        }

        if (
            repairInProgress.get()
        ) {
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
                EventLogger.log(
                    "GUARDIAN",
                    "Check skipped: engine is starting"
                )

                return
            }

            is RemoteControlState.Stopping -> {
                EventLogger.log(
                    "GUARDIAN",
                    "Check skipped: engine is stopping"
                )

                return
            }

            else -> Unit
        }

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

            resetFailureState()
            return
        }

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
                    EventLogger.log(
                        "GUARDIAN",
                        "Repair cancelled: engine started normally"
                    )

                    return
                }

                is RemoteControlState.Stopping -> {
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
                EventLogger.log(
                    "GUARDIAN",
                    "Repair cancelled after backoff: " +
                        "remote control disabled"
                )

                resetFailureState()
                return
            }

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
                EventLogger.log(
                    "GUARDIAN",
                    "Repair successful on attempt " +
                        "#$repairAttempt"
                )

                resetFailureState()
                return
            }

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
            return
        }

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
                EventLogger.log(
                    "GUARDIAN",
                    "Alert email send accepted"
                )
            } else {
                EventLogger.log(
                    "GUARDIAN",
                    "Alert email send returned false"
                )
            }
        } catch (t: Throwable) {
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
}
