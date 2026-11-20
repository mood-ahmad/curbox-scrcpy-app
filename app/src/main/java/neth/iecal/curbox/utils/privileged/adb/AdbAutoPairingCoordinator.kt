package neth.iecal.curbox.utils.privileged.adb

import android.content.BroadcastReceiver
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates automatic Wireless ADB pairing.
 *
 * Two independent signals have to meet:
 *
 * 1. The Accessibility-service process captures the temporary pairing code
 *    and sends it to the main process through AdbPairingCaptureBridge.
 *
 * 2. mDNS discovers the current _adb-tls-pairing._tcp endpoint.
 *
 * Either signal may arrive first. The coordinator stores both and starts
 * pairing exactly once when both are available.
 *
 * Accessibility capture is explicitly armed only while this coordinator owns
 * an active automatic-pairing session.
 */
class AdbAutoPairingCoordinator(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val discoveryManager =
        AdbDiscoveryManager(
            appContext
        )

    private val lock =
        Any()

    private val pairingStarted =
        AtomicBoolean(false)

    @Volatile
    private var running =
        false

    private var pairingCode:
        String? = null

    private var accessibilityPort:
        Int? = null

    private var pairingEndpoint:
        AdbDiscoveryManager.Endpoint? = null

    private var listener:
        Listener? = null

    private var captureReceiver:
        BroadcastReceiver? = null

    private var captureArmed =
        false

    enum class Stage {
        INITIALIZING,
        WAITING_FOR_PAIRING_DIALOG,
        WAITING_FOR_DISCOVERY,
        PAIRING,
        DISCOVERING_CONNECTION,
        CONNECTING,
        CONNECTED
    }

    interface Listener {

        fun onStageChanged(
            stage: Stage
        ) {
        }

        fun onConnected()

        fun onError(
            error: Throwable
        )
    }

    /**
     * Starts one automatic-pairing session.
     *
     * The main process first prepares its receiver, then explicitly arms the
     * Accessibility-service process. This ordering prevents a captured code
     * from being sent before the main process is ready to consume it.
     */
    fun start(
        listener: Listener
    ) {
        stop()

        synchronized(lock) {
            this.listener =
                listener

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null

            pairingStarted.set(
                false
            )

            running =
                true
        }

        publishStage(
            Stage.INITIALIZING
        )

        val initialization =
            AdbChannelManager.initialize(
                appContext
            )

        if (initialization.isFailure) {
            fail(
                initialization.exceptionOrNull()
                    ?: IllegalStateException(
                        "Direct ADB initialization failed."
                    )
            )

            return
        }

        if (!isRunning()) {
            return
        }

        try {
            val receiver =
                AdbPairingCaptureBridge.register(
                    context =
                        appContext,
                    listener =
                        object :
                            AdbPairingCaptureBridge.Listener {

                            override fun onPairingInfoDetected(
                                info:
                                    AdbPairingAccessibilityExtractor.PairingInfo
                            ) {
                                handleAccessibilityInfo(
                                    info
                                )
                            }
                        }
                )

            synchronized(lock) {
                if (!running) {
                    AdbPairingCaptureBridge.unregister(
                        context =
                            appContext,
                        receiver =
                            receiver
                    )

                    return
                }

                captureReceiver =
                    receiver
            }
        } catch (t: Throwable) {
            fail(
                t
            )

            return
        }

        if (!isRunning()) {
            return
        }

        /*
         * The main-process receiver is ready. The Accessibility process may
         * now begin inspecting Android Settings for the pairing dialog.
         */
        try {
            AdbPairingCaptureBridge.armCapture(
                appContext
            )

            synchronized(lock) {
                if (!running) {
                    AdbPairingCaptureBridge.disarmCapture(
                        appContext
                    )

                    return
                }

                captureArmed =
                    true
            }
        } catch (t: Throwable) {
            fail(
                t
            )

            return
        }

        publishStage(
            Stage.WAITING_FOR_PAIRING_DIALOG
        )

        try {
            discoveryManager.discoverPairing(
                object :
                    AdbDiscoveryManager.Listener {

                    override fun onEndpointFound(
                        endpoint:
                            AdbDiscoveryManager.Endpoint
                    ) {
                        handlePairingEndpoint(
                            endpoint
                        )
                    }

                    override fun onError(
                        error: Throwable
                    ) {
                        fail(
                            error
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            fail(
                t
            )
        }
    }

    /**
     * Stops the current automatic-pairing session without deleting CurBox's
     * persistent ADB identity.
     */
    fun stop() {
        val receiver:
            BroadcastReceiver?

        val shouldDisarm:
            Boolean

        synchronized(lock) {
            running =
                false

            listener =
                null

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null

            pairingStarted.set(
                false
            )

            receiver =
                captureReceiver

            captureReceiver =
                null

            shouldDisarm =
                captureArmed

            captureArmed =
                false
        }

        if (shouldDisarm) {
            AdbPairingCaptureBridge.disarmCapture(
                appContext
            )
        }

        AdbPairingCaptureBridge.unregister(
            context =
                appContext,
            receiver =
                receiver
        )

        discoveryManager.stop()
    }

    fun isRunning(): Boolean {
        return synchronized(lock) {
            running
        }
    }

    private fun handleAccessibilityInfo(
        info:
            AdbPairingAccessibilityExtractor.PairingInfo
    ) {
        synchronized(lock) {
            if (!running) {
                return
            }

            pairingCode =
                info.code

            accessibilityPort =
                info.port
        }

        tryStartPairing()
    }

    private fun handlePairingEndpoint(
        endpoint:
            AdbDiscoveryManager.Endpoint
    ) {
        synchronized(lock) {
            if (!running) {
                return
            }

            pairingEndpoint =
                endpoint
        }

        tryStartPairing()
    }

    /**
     * Starts network pairing only after both the captured code and mDNS
     * endpoint are available.
     */
    private fun tryStartPairing() {
        val code:
            String

        val endpoint:
            AdbDiscoveryManager.Endpoint

        synchronized(lock) {
            if (!running) {
                return
            }

            val currentCode =
                pairingCode

            val currentEndpoint =
                pairingEndpoint

            if (currentCode == null) {
                publishStage(
                    Stage.WAITING_FOR_PAIRING_DIALOG
                )

                return
            }

            if (currentEndpoint == null) {
                publishStage(
                    Stage.WAITING_FOR_DISCOVERY
                )

                return
            }

            if (
                !pairingStarted.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            code =
                currentCode

            endpoint =
                currentEndpoint
        }

        /*
         * The pairing dialog has already yielded the information CurBox needs.
         * Stop Accessibility capture immediately instead of leaving it armed
         * while TLS pairing and connection continue.
         */
        disarmCapture()

        unregisterCaptureReceiver()
        discoveryManager.stop()

        publishStage(
            Stage.PAIRING
        )

        Thread(
            {
                performPairing(
                    endpoint =
                        endpoint,
                    code =
                        code
                )
            },
            "CurBox-AdbAutoPair"
        ).start()
    }

    private fun performPairing(
        endpoint:
            AdbDiscoveryManager.Endpoint,
        code: String
    ) {
        val result =
            AdbChannelManager.pair(
                host =
                    endpoint.host,
                port =
                    endpoint.port,
                pairingCode =
                    code
            )

        if (!isRunning()) {
            return
        }

        if (result.isFailure) {
            fail(
                result.exceptionOrNull()
                    ?: IllegalStateException(
                        "Automatic ADB pairing failed."
                    )
            )

            return
        }

        discoverConnectionEndpoint()
    }

    private fun discoverConnectionEndpoint() {
        if (!isRunning()) {
            return
        }

        publishStage(
            Stage.DISCOVERING_CONNECTION
        )

        discoveryManager.discoverConnection(
            object :
                AdbDiscoveryManager.Listener {

                override fun onEndpointFound(
                    endpoint:
                        AdbDiscoveryManager.Endpoint
                ) {
                    if (!isRunning()) {
                        return
                    }

                    connect(
                        endpoint
                    )
                }

                override fun onError(
                    error: Throwable
                ) {
                    fail(
                        error
                    )
                }
            }
        )
    }

    private fun connect(
        endpoint:
            AdbDiscoveryManager.Endpoint
    ) {
        if (!isRunning()) {
            return
        }

        discoveryManager.stop()

        publishStage(
            Stage.CONNECTING
        )

        Thread(
            {
                val result =
                    AdbChannelManager.connect(
                        host =
                            endpoint.host,
                        port =
                            endpoint.port
                    )

                if (!isRunning()) {
                    return@Thread
                }

                if (result.isFailure) {
                    fail(
                        result.exceptionOrNull()
                            ?: IllegalStateException(
                                "Direct ADB connection failed after automatic pairing."
                            )
                    )

                    return@Thread
                }

                finishConnected()
            },
            "CurBox-AdbAutoConnect"
        ).start()
    }

    private fun finishConnected() {
        val currentListener:
            Listener?

        synchronized(lock) {
            if (!running) {
                return
            }

            running =
                false

            currentListener =
                listener

            listener =
                null

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null
        }

        disarmCapture()
        unregisterCaptureReceiver()
        discoveryManager.stop()

        currentListener
            ?.onStageChanged(
                Stage.CONNECTED
            )

        currentListener
            ?.onConnected()
    }

    private fun fail(
        error: Throwable
    ) {
        val currentListener:
            Listener?

        synchronized(lock) {
            if (!running) {
                return
            }

            running =
                false

            currentListener =
                listener

            listener =
                null

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null
        }

        disarmCapture()
        unregisterCaptureReceiver()
        discoveryManager.stop()

        currentListener
            ?.onError(
                error
            )
    }

    private fun publishStage(
        stage: Stage
    ) {
        val currentListener =
            synchronized(lock) {
                if (!running) {
                    return
                }

                listener
            }

        currentListener
            ?.onStageChanged(
                stage
            )
    }

    private fun disarmCapture() {
        val shouldDisarm =
            synchronized(lock) {
                if (!captureArmed) {
                    false
                } else {
                    captureArmed =
                        false

                    true
                }
            }

        if (shouldDisarm) {
            try {
                AdbPairingCaptureBridge.disarmCapture(
                    appContext
                )
            } catch (_: Throwable) {
                /*
                 * Local state is already disarmed. Cleanup must not replace the
                 * original pairing result with a broadcast-cleanup failure.
                 */
            }
        }
    }

    private fun unregisterCaptureReceiver() {
        val receiver =
            synchronized(lock) {
                val current =
                    captureReceiver

                captureReceiver =
                    null

                current
            }

        AdbPairingCaptureBridge.unregister(
            context =
                appContext,
            receiver =
                receiver
        )
    }
}
