package neth.iecal.curbox.remotecontrol

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hidden guardian that monitors the remote-control engine.
 *
 * The Guardian only attempts recovery when the engine is expected
 * to be running and is not currently starting or stopping.
 *
 * No notifications and no UI.
 */
object RemoteControlGuardian {

    private const val CHECK_INTERVAL_MS = 30_000L
    private const val INITIAL_SETTLE_MS = 20_000L
    private const val REPAIR_SETTLE_MS = 20_000L

    private const val MAX_REPAIR_ATTEMPTS = 3

    private val scope =
        CoroutineScope(Dispatchers.IO)

    private var job: Job? = null

    private var consecutiveFailures = 0
    private var alertSent = false

    private val repairInProgress =
        AtomicBoolean(false)

    /**
     * Starts the Guardian loop.
     *
     * Safe to call multiple times.
     */
    fun start(context: Context) {
        if (job?.isActive == true) {
            return
        }

        val appContext =
            context.applicationContext

        job = scope.launch {
            EventLogger.log(
                "GUARDIAN",
                "Guardian started"
            )

            /*
             * Give normal application startup enough time
             * before the Guardian begins health checks.
             */
            delay(INITIAL_SETTLE_MS)

            while (isActive) {
                try {
                    tick(appContext)
                } catch (t: Throwable) {
                    EventLogger.log(
                        "GUARDIAN",
                        "Tick exception: " +
                            (t.message ?: t.javaClass.simpleName)
                    )
                }

                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the Guardian loop.
     */
    fun stop() {
        job?.cancel()
        job = null

        repairInProgress.set(false)

        consecutiveFailures = 0
        alertSent = false

        EventLogger.log(
            "GUARDIAN",
            "Guardian stopped"
        )
    }

    // -------------------------------------------------------------------------

    private suspend fun tick(
        context: Context
    ) {
        /*
         * Do nothing when remote control is disabled.
         */
        if (!RemoteControlManager.isEnabled(context)) {
            resetFailureState()

            EventLogger.log(
                "GUARDIAN",
                "Check skipped: remote control disabled"
            )

            return
        }

        /*
         * Never interfere while another recovery is still running.
         */
        if (repairInProgress.get()) {
            EventLogger.log(
                "GUARDIAN",
                "Check skipped: repair already in progress"
            )

            return
        }

        /*
         * Most important protection:
         *
         * If RemoteControlManager is already starting or stopping
         * the engine, Guardian must not start another copy.
         */
        when (RemoteControlManager.state.value) {
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
            RemoteControlManager.isAgentRunningBlocking()

        if (running) {
            if (consecutiveFailures > 0) {
                EventLogger.log(
                    "GUARDIAN",
                    "Engine recovered after $consecutiveFailures failure(s)"
                )
            }

            resetFailureState()
            return
        }

        /*
         * Engine is not healthy.
         *
         * Only one repair attempt can enter this section.
         */
        if (!repairInProgress.compareAndSet(false, true)) {
            EventLogger.log(
                "GUARDIAN",
                "Repair skipped: another repair acquired the lock"
            )

            return
        }

        try {
            /*
             * Re-check the manager state after acquiring the lock.
             *
             * The state may have changed between the first check
             * and entering the recovery section.
             */
            when (RemoteControlManager.state.value) {
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

            consecutiveFailures++

            EventLogger.log(
                "GUARDIAN",
                "Engine unhealthy, repair attempt #$consecutiveFailures"
            )

            RemoteControlManager.start(context)

            /*
             * Give Signaling + Agent enough time to initialise.
             */
            delay(REPAIR_SETTLE_MS)

            /*
             * If startup is still legitimately running,
             * don't launch yet another repair attempt.
             */
            if (
                RemoteControlManager.state.value
                    is RemoteControlState.Starting
            ) {
                EventLogger.log(
                    "GUARDIAN",
                    "Repair still starting; waiting for next check"
                )

                return
            }

            val recovered =
                RemoteControlManager.isAgentRunningBlocking()

            if (recovered) {
                EventLogger.log(
                    "GUARDIAN",
                    "Repair successful"
                )

                resetFailureState()
                return
            }

            EventLogger.log(
                "GUARDIAN",
                "Repair failed (attempt #$consecutiveFailures)"
            )

            if (
                consecutiveFailures >= MAX_REPAIR_ATTEMPTS &&
                !alertSent
            ) {
                alertSent = true

                EventLogger.log(
                    "GUARDIAN",
                    "Sending alert after $MAX_REPAIR_ATTEMPTS failed repairs"
                )

                sendAlertEmail()
            }
        } finally {
            repairInProgress.set(false)
        }
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
                    "Alert email sent"
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
                    (t.message ?: t.javaClass.simpleName)
            )
        }
    }

    private fun resetFailureState() {
        consecutiveFailures = 0
        alertSent = false
    }
}
