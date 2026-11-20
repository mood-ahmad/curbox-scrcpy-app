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
     * Arms automatic pairing in the main process.
     *
     * Pairing information captured by AppBlockerService arrives through the
     * cross-process bridge, while pairing endpoint discovery runs through mDNS.
     */
    fun start(
        listener: Listener
    ) {
        synchronized(lock) {
            stopLocked()

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

        publishStage(
            Stage.WAITING_FOR_PAIRING_DIALOG
        )

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
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
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
         * Both inputs have been captured.
         *
         * The bridge receiver is no longer needed. The Settings-side extractor
         * is disabled separately by the Accessibility process after capture.
         */
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
        val currentListener =
            synchronized(lock) {
                if (!running) {
                    return
                }

                running =
                    false

                val value =
                    listener

                listener =
                    null

                pairingCode =
                    null

                accessibilityPort =
                    null

                pairingEndpoint =
                    null

                value
            }

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
        val currentListener =
            synchronized(lock) {
                if (!running) {
                    return
                }

                running =
                    false

                val value =
                    listener

                listener =
                    null

                pairingCode =
                    null

                accessibilityPort =
                    null

                pairingEndpoint =
                    null

                value
            }

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

    private fun stopLocked() {
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

        val receiver =
            captureReceiver

        captureReceiver =
            null

        AdbPairingCaptureBridge.unregister(
            context =
                appContext,
            receiver =
                receiver
        )

        discoveryManager.stop()
    }
}
