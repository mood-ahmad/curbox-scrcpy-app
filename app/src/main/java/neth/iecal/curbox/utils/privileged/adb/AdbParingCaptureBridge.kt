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
 * The bridge carries three kinds of messages:
 *
 * 1. ARM_CAPTURE
 *    The main process explicitly starts a pairing-capture session.
 *
 * 2. DISARM_CAPTURE
 *    The main process ends the pairing-capture session.
 *
 * 3. PAIRING_CAPTURED
 *    The Accessibility process sends the detected pairing code and optional
 *    on-screen pairing endpoint information back to the main process.
 *
 * All broadcasts are package-scoped. Dynamically registered receivers are
 * also marked not exported.
 */
object AdbPairingCaptureBridge {

    private const val ACTION_ARM_CAPTURE =
        "neth.iecal.curbox.action.ADB_PAIRING_ARM_CAPTURE"

    private const val ACTION_DISARM_CAPTURE =
        "neth.iecal.curbox.action.ADB_PAIRING_DISARM_CAPTURE"

    private const val ACTION_PAIRING_CAPTURED =
        "neth.iecal.curbox.action.ADB_PAIRING_CAPTURED"

    private const val EXTRA_CODE =
        "pairing_code"

    private const val EXTRA_PORT =
        "pairing_port"

    private const val EXTRA_RAW_ADDRESS =
        "pairing_raw_address"

    interface Listener {

        fun onPairingInfoDetected(
            info:
                AdbPairingAccessibilityExtractor.PairingInfo
        )
    }

    interface CaptureStateListener {

        fun onCaptureArmed()

        fun onCaptureDisarmed()
    }

    /**
     * Tells CurBox's Accessibility process that an explicit automatic-pairing
     * attempt has started.
     */
    fun armCapture(
        context: Context
    ) {
        sendControlBroadcast(
            context =
                context,
            action =
                ACTION_ARM_CAPTURE
        )
    }

    /**
     * Tells CurBox's Accessibility process to stop pairing-dialog extraction.
     */
    fun disarmCapture(
        context: Context
    ) {
        sendControlBroadcast(
            context =
                context,
            action =
                ACTION_DISARM_CAPTURE
        )
    }

    /**
     * Sends pairing information from the Accessibility-service process to
     * CurBox's main process.
     */
    fun publish(
        context: Context,
        info:
            AdbPairingAccessibilityExtractor.PairingInfo
    ) {
        val intent =
            Intent(
                ACTION_PAIRING_CAPTURED
            ).apply {
                setPackage(
                    context.packageName
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
     * Registers the main-process receiver used to receive captured pairing
     * information.
     */
    fun register(
        context: Context,
        listener: Listener
    ): BroadcastReceiver {
        val appContext =
            context.applicationContext

        val receiver =
            object : BroadcastReceiver() {

                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {
                    if (
                        intent?.action !=
                        ACTION_PAIRING_CAPTURED
                    ) {
                        return
                    }

                    val code =
                        intent.getStringExtra(
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
            }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(
                ACTION_PAIRING_CAPTURED
            ),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        return receiver
    }

    /**
     * Registers a receiver inside the Accessibility-service process for the
     * main process' ARM/DISARM messages.
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
                    when (
                        intent?.action
                    ) {
                        ACTION_ARM_CAPTURE ->
                            listener.onCaptureArmed()

                        ACTION_DISARM_CAPTURE ->
                            listener.onCaptureDisarmed()
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

    private fun sendControlBroadcast(
        context: Context,
        action: String
    ) {
        val intent =
            Intent(
                action
            ).apply {
                setPackage(
                    context.packageName
                )
            }

        context.sendBroadcast(
            intent
        )
    }
}

