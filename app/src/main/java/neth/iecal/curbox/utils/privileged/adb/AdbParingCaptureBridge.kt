package neth.iecal.curbox.utils.privileged.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Cross-process bridge used by CurBox automatic Wireless ADB pairing.
 *
 * Direct Access coordination runs in the main application process while
 * AppBlockerService runs in :app_blocker_service.
 *
 * Every automatic-pairing attempt has a unique session ID.
 *
 * The bridge carries four kinds of messages:
 *
 * 1. ARM_CAPTURE
 *    Main process -> Accessibility process.
 *    Starts capture for one specific session.
 *
 * 2. ARMED_ACK
 *    Accessibility process -> Main process.
 *    Confirms that the extractor and navigator are armed for that session.
 *
 * 3. PAIRING_CAPTURED
 *    Accessibility process -> Main process.
 *    Carries the pairing code and optional on-screen endpoint information.
 *
 * 4. DISARM_CAPTURE
 *    Main process -> Accessibility process.
 *    Ends capture for the matching session.
 *
 * All broadcasts are package-scoped. Dynamically registered receivers are
 * also marked not exported.
 */
object AdbPairingCaptureBridge {

    private const val ACTION_ARM_CAPTURE =
        "neth.iecal.curbox.action.ADB_PAIRING_ARM_CAPTURE"

    private const val ACTION_ARMED_ACK =
        "neth.iecal.curbox.action.ADB_PAIRING_ARMED_ACK"

    private const val ACTION_DISARM_CAPTURE =
        "neth.iecal.curbox.action.ADB_PAIRING_DISARM_CAPTURE"

    private const val ACTION_PAIRING_CAPTURED =
        "neth.iecal.curbox.action.ADB_PAIRING_CAPTURED"

    private const val EXTRA_SESSION_ID =
        "pairing_session_id"

    private const val EXTRA_CODE =
        "pairing_code"

    private const val EXTRA_PORT =
        "pairing_port"

    private const val EXTRA_RAW_ADDRESS =
        "pairing_raw_address"

    interface Listener {

        /**
         * Called after the Accessibility process confirms that capture is
         * armed for this exact pairing session.
         */
        fun onCaptureArmed(
            sessionId: String
        ) {
        }

        /**
         * Called when pairing information belonging to this exact session is
         * received from the Accessibility process.
         */
        fun onPairingInfoDetected(
            sessionId: String,
            info:
                AdbPairingAccessibilityExtractor.PairingInfo
        )
    }

    interface CaptureStateListener {

        /**
         * Main process requested capture for [sessionId].
         *
         * The Accessibility process must arm its extractor/navigator and then
         * call [acknowledgeArmed].
         */
        fun onCaptureArmRequested(
            sessionId: String
        )

        /**
         * Main process ended [sessionId].
         */
        fun onCaptureDisarmRequested(
            sessionId: String
        )
    }

    /**
     * Requests that the Accessibility process arm automatic pairing capture.
     */
    fun armCapture(
        context: Context,
        sessionId: String
    ) {
        requireSessionId(
            sessionId
        )

        sendControlBroadcast(
            context =
                context,
            action =
                ACTION_ARM_CAPTURE,
            sessionId =
                sessionId
        )
    }

    /**
     * Confirms to the main process that Accessibility capture is ready.
     */
    fun acknowledgeArmed(
        context: Context,
        sessionId: String
    ) {
        requireSessionId(
            sessionId
        )

        sendSessionBroadcast(
            context =
                context,
            action =
                ACTION_ARMED_ACK,
            sessionId =
                sessionId
        )
    }

    /**
     * Requests that the Accessibility process stop capture for [sessionId].
     */
    fun disarmCapture(
        context: Context,
        sessionId: String
    ) {
        requireSessionId(
            sessionId
        )

        sendControlBroadcast(
            context =
                context,
            action =
                ACTION_DISARM_CAPTURE,
            sessionId =
                sessionId
        )
    }

    /**
     * Sends captured pairing information from the Accessibility process to the
     * main process.
     */
    fun publish(
        context: Context,
        sessionId: String,
        info:
            AdbPairingAccessibilityExtractor.PairingInfo
    ) {
        requireSessionId(
            sessionId
        )

        val intent =
            Intent(
                ACTION_PAIRING_CAPTURED
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    EXTRA_SESSION_ID,
                    sessionId
                )

                putExtra(
                    EXTRA_CODE,
                    info.code
                )

                putExtra(
                    EXTRA_PORT,
                    info.port ?: -1
                )

                putExtra(
                    EXTRA_RAW_ADDRESS,
                    info.rawAddress
                )
            }

        context.sendBroadcast(
            intent
        )
    }

    /**
     * Registers the main-process receiver.
     *
     * It receives both the Accessibility-ready acknowledgement and captured
     * pairing information.
     *
     * [expectedSessionId] prevents data from a previous pairing attempt from
     * being delivered into the current attempt.
     */
    fun register(
        context: Context,
        expectedSessionId: String,
        listener: Listener
    ): BroadcastReceiver {
        requireSessionId(
            expectedSessionId
        )

        val appContext =
            context.applicationContext

        val receiver =
            object : BroadcastReceiver() {

                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {
                    val currentIntent =
                        intent
                            ?: return

                    val sessionId =
                        currentIntent
                            .getStringExtra(
                                EXTRA_SESSION_ID
                            )
                            ?.trim()
                            .orEmpty()

                    if (
                        sessionId !=
                        expectedSessionId
                    ) {
                        return
                    }

                    when (
                        currentIntent.action
                    ) {
                        ACTION_ARMED_ACK -> {
                            listener.onCaptureArmed(
                                sessionId
                            )
                        }

                        ACTION_PAIRING_CAPTURED -> {
                            deliverPairingCapture(
                                intent =
                                    currentIntent,
                                sessionId =
                                    sessionId,
                                listener =
                                    listener
                            )
                        }
                    }
                }
            }

        val filter =
            IntentFilter().apply {
                addAction(
                    ACTION_ARMED_ACK
                )

                addAction(
                    ACTION_PAIRING_CAPTURED
                )
            }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return receiver
    }

    /**
     * Registers the receiver living in :app_blocker_service.
     *
     * ARM and DISARM requests are delivered with their session IDs so the
     * Accessibility process can reject a stale DISARM belonging to an older
     * attempt.
     */
    fun registerCaptureStateReceiver(
        context: Context,
        listener: CaptureStateListener
    ): BroadcastReceiver {
        val appContext =
            context.applicationContext

        val receiver =
            object : BroadcastReceiver() {

                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {
                    val currentIntent =
                        intent
                            ?: return

                    val sessionId =
                        currentIntent
                            .getStringExtra(
                                EXTRA_SESSION_ID
                            )
                            ?.trim()
                            .orEmpty()

                    if (
                        sessionId.isEmpty()
                    ) {
                        return
                    }

                    when (
                        currentIntent.action
                    ) {
                        ACTION_ARM_CAPTURE -> {
                            listener.onCaptureArmRequested(
                                sessionId
                            )
                        }

                        ACTION_DISARM_CAPTURE -> {
                            listener.onCaptureDisarmRequested(
                                sessionId
                            )
                        }
                    }
                }
            }

        val filter =
            IntentFilter().apply {
                addAction(
                    ACTION_ARM_CAPTURE
                )

                addAction(
                    ACTION_DISARM_CAPTURE
                )
            }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return receiver
    }

    /**
     * Removes a dynamically registered receiver.
     */
    fun unregister(
        context: Context,
        receiver: BroadcastReceiver?
    ) {
        if (receiver == null) {
            return
        }

        try {
            context
                .applicationContext
                .unregisterReceiver(
                    receiver
                )
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
    }

    private fun deliverPairingCapture(
        intent: Intent,
        sessionId: String,
        listener: Listener
    ) {
        val code =
            intent
                .getStringExtra(
                    EXTRA_CODE
                )
                ?.trim()
                .orEmpty()

        if (
            code.length != 6 ||
            code.any {
                !it.isDigit()
            }
        ) {
            return
        }

        val rawPort =
            intent.getIntExtra(
                EXTRA_PORT,
                -1
            )

        val port =
            rawPort.takeIf {
                it in 1..65535
            }

        val rawAddress =
            intent.getStringExtra(
                EXTRA_RAW_ADDRESS
            )

        listener.onPairingInfoDetected(
            sessionId =
                sessionId,
            info =
                AdbPairingAccessibilityExtractor.PairingInfo(
                    code =
                        code,
                    port =
                        port,
                    rawAddress =
                        rawAddress
                )
        )
    }

    private fun sendControlBroadcast(
        context: Context,
        action: String,
        sessionId: String
    ) {
        sendSessionBroadcast(
            context =
                context,
            action =
                action,
            sessionId =
                sessionId
        )
    }

    private fun sendSessionBroadcast(
        context: Context,
        action: String,
        sessionId: String
    ) {
        val intent =
            Intent(
                action
            ).apply {
                setPackage(
                    context.packageName
                )

                putExtra(
                    EXTRA_SESSION_ID,
                    sessionId
                )
            }

        context.sendBroadcast(
            intent
        )
    }

    private fun requireSessionId(
        sessionId: String
    ) {
        require(
            sessionId.isNotBlank()
        ) {
            "ADB pairing session ID must not be blank."
        }
    }
}
