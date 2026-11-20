package neth.iecal.curbox.utils.privileged.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Cross-process bridge between CurBox's Accessibility process and the main
 * application process during automatic Wireless ADB pairing.
 *
 * AppBlockerService runs in :app_blocker_service, while Direct Access pairing
 * coordination runs in the main process. Static objects therefore cannot be
 * used to transfer the captured pairing information between them.
 *
 * This bridge uses package-scoped explicit broadcasts so pairing information
 * is delivered only inside CurBox.
 */
object AdbPairingCaptureBridge {

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
            info: AdbPairingAccessibilityExtractor.PairingInfo
        )
    }

    /**
     * Sends pairing information from the Accessibility-service process to
     * CurBox's main process.
     */
    fun publish(
        context: Context,
        info: AdbPairingAccessibilityExtractor.PairingInfo
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
     * Registers the main-process receiver used during an active automatic
     * pairing attempt.
     *
     * The returned BroadcastReceiver must later be passed to [unregister].
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
     * Removes a receiver previously returned by [register].
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
}
