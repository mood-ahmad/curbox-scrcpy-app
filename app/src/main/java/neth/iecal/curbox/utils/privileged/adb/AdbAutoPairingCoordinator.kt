package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates automatic Wireless ADB pairing.
 *
 * Two independent signals have to meet:
 *
 * 1. Accessibility discovers the temporary six-digit pairing code.
 * 2. mDNS discovers the current _adb-tls-pairing._tcp endpoint.
 *
 * Either signal may arrive first. The coordinator stores both and starts
 * pairing exactly once when both are available.
 *
 * Pairing itself remains owned by AdbChannelManager.
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

        /**
         * Automatic pairing and the verified initial ADB connection succeeded.
         */
        fun onConnected()

        /**
         * Automatic pairing failed.
         */
        fun onError(
            error: Throwable
        )
    }

    /**
     * Arms automatic pairing.
     *
     * From this point until [stop], the Accessibility extractor is allowed to
     * inspect Android Settings events for the Wireless Debugging pairing
     * dialog.
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

        /*
         * Accessibility and mDNS are intentionally armed together.
         *
         * Android may show the code before NSD resolves the advertised
         * pairing service, or NSD may resolve first.
         */
        AdbPairingAccessibilityExtractor.start(
            object :
                AdbPairingAccessibilityExtractor.Listener {

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

    /**
     * Cancels automatic pairing.
     *
     * Calling this repeatedly is safe.
     */
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
        if (!isRunning()) {
            return
        }

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
        if (!isRunning()) {
            return
        }

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
     * Starts pairing only when both independently discovered pieces are ready.
     *
     * The mDNS endpoint is authoritative. The port read from Accessibility is
     * retained only as diagnostic/fallback information and is not allowed to
     * override a resolved mDNS endpoint.
     */
    private fun tryStartPairing() {
        if (!isRunning()) {
            return
        }

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
         * No more pairing-dialog scanning or pairing-service discovery is
         * required after both values have been captured.
         */
        AdbPairingAccessibilityExtractor.stop()
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

    /**
     * Pairing and normal ADB connection use different dynamically advertised
     * endpoints, so rediscover the TLS connection service after pairing.
     */
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

        AdbPairingAccessibilityExtractor.stop()
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

        AdbPairingAccessibilityExtractor.stop()
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

        AdbPairingAccessibilityExtractor.stop()
        discoveryManager.stop()
    }
}
