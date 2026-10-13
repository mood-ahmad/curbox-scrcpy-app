package neth.iecal.curbox.remotecontrol

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hidden guardian that keeps the remote control service alive.
 *
 * Runs every [CHECK_INTERVAL_MS] milliseconds.
 * If the agent is not running, it attempts a repair (restart).
 * Logs every action, and after [MAX_REPAIR_ATTEMPTS] consecutive failures
 * it sends an alert email.
 *
 * No notifications, no UI.
 */
object RemoteControlGuardian {

    private const val CHECK_INTERVAL_MS = 30_000L
    private const val MAX_REPAIR_ATTEMPTS = 3
    private const val SETTLE_AFTER_START_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var consecutiveFailures = 0
    private var alertSent = false

    /**
     * Starts the guardian loop. Safe to call multiple times.
     */
    fun start(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext

        job = scope.launch {
            EventLogger.log("GUARDIAN", "Guardian started")

            // Give the app a moment after launch before the first check.
            delay(SETTLE_AFTER_START_MS)

            while (isActive) {
                try {
                    tick(appContext)
                } catch (t: Throwable) {
                    EventLogger.log("GUARDIAN", "Tick exception: ${t.message}")
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Stops the guardian loop.
     */
    fun stop() {
        job?.cancel()
        job = null
        EventLogger.log("GUARDIAN", "Guardian stopped")
    }

    // -------------------------------------------------------------------------

    private suspend fun tick(context: Context) {
        // Only run if the feature is enabled.
        if (!RemoteControlManager.isEnabled(context)) {
            return
        }

        val running = RemoteControlManager.isAgentRunningBlocking()

        if (running) {
            if (consecutiveFailures > 0) {
                EventLogger.log("GUARDIAN", "Agent recovered after $consecutiveFailures failure(s)")
            }
            consecutiveFailures = 0
            alertSent = false
            return
        }

        // Agent is down. Try to repair.
        consecutiveFailures++
        EventLogger.log(
            "GUARDIAN",
            "Agent down, repair attempt #$consecutiveFailures"
        )

        RemoteControlManager.start(context)

        // Wait a bit to see if the repair worked.
        delay(SETTLE_AFTER_START_MS)

        val recovered = RemoteControlManager.isAgentRunningBlocking()
        if (recovered) {
            EventLogger.log("GUARDIAN", "Repair successful")
            consecutiveFailures = 0
            alertSent = false
            return
        }

        // Repair failed.
        EventLogger.log("GUARDIAN", "Repair failed (attempt #$consecutiveFailures)")

        if (consecutiveFailures >= MAX_REPAIR_ATTEMPTS && !alertSent) {
            alertSent = true
            EventLogger.log("GUARDIAN", "Sending alert email after $MAX_REPAIR_ATTEMPTS failures")
            sendAlertEmail(context)
        }
    }

    private suspend fun sendAlertEmail(context: Context) {
        try {
            EmailSender.sendAlert(
                deviceName = DeviceIdentity.getName(),
                reason = "Agent failed to start after $MAX_REPAIR_ATTEMPTS attempts"
            )
        } catch (t: Throwable) {
            EventLogger.log("GUARDIAN", "Alert email failed: ${t.message}")
        }
    }
}
