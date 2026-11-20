package neth.iecal.curbox.utils.privileged.adb

import android.content.BroadcastReceiver
import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates automatic Wireless ADB pairing.
 *
 * Every automatic pairing attempt owns a unique session ID.
 *
 * Flow:
 *
 * 1. Create a new session ID.
 * 2. Register the main-process bridge receiver for that session.
 * 3. Send ARM_CAPTURE(sessionId).
 * 4. Wait for ARMED_ACK(sessionId).
 * 5. Only after ACK, start mDNS pairing discovery.
 * 6. Combine the captured pairing code with the mDNS endpoint.
 * 7. Pair and establish a verified Direct ADB connection.
 *
 * Captures and acknowledgements belonging to older sessions are ignored.
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

    private var activeSessionId:
        String? = null

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

    private var captureAcknowledged =
        false

    private var pairingDiscoveryStarted =
        false

    enum class Stage {
        INITIALIZING,
        WAITING_FOR_ACCESSIBILITY,
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

    fun start(
        listener: Listener
    ) {
        stop()

        val sessionId =
            UUID.randomUUID()
                .toString()

        synchronized(lock) {
            this.listener =
                listener

            activeSessionId =
                sessionId

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null

            captureArmed =
                false

            captureAcknowledged =
                false

            pairingDiscoveryStarted =
                false

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

        if (!isCurrentSession(sessionId)) {
            return
        }

        try {
            val receiver =
                AdbPairingCaptureBridge.register(
                    context =
                        appContext,
                    expectedSessionId =
                        sessionId,
                    listener =
                        object :
                            AdbPairingCaptureBridge.Listener {

                            override fun onCaptureArmed(
                                sessionId: String
                            ) {
                                handleCaptureAcknowledged(
                                    sessionId
                                )
                            }

                            override fun onPairingInfoDetected(
                                sessionId: String,
                                info:
                                    AdbPairingAccessibilityExtractor.PairingInfo
                            ) {
                                handleAccessibilityInfo(
                                    sessionId =
                                        sessionId,
                                    info =
                                        info
                                )
                            }
                        }
                )

            synchronized(lock) {
                if (
                    !running ||
                    activeSessionId !=
                    sessionId
                ) {
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

        if (!isCurrentSession(sessionId)) {
            return
        }

        publishStage(
            Stage.WAITING_FOR_ACCESSIBILITY
        )

        try {
            /*
             * Mark locally before sending the request so even a very fast ACK
             * cannot race with our local session state.
             */
            synchronized(lock) {
                if (
                    !running ||
                    activeSessionId !=
                    sessionId
                ) {
                    return
                }

                captureArmed =
                    true
            }

            AdbPairingCaptureBridge.armCapture(
                context =
                    appContext,
                sessionId =
                    sessionId
            )
        } catch (t: Throwable) {
            synchronized(lock) {
                if (
                    activeSessionId ==
                    sessionId
                ) {
                    captureArmed =
                        false
                }
            }

            fail(
                t
            )
        }
    }

    /**
     * Stops the active session while preserving CurBox's ADB identity.
     */
    fun stop() {
        val sessionId:
            String?

        val receiver:
            BroadcastReceiver?

        val shouldDisarm:
            Boolean

        synchronized(lock) {
            sessionId =
                activeSessionId

            receiver =
                captureReceiver

            shouldDisarm =
                captureArmed

            running =
                false

            activeSessionId =
                null

            listener =
                null

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null

            captureReceiver =
                null

            captureArmed =
                false

            captureAcknowledged =
                false

            pairingDiscoveryStarted =
                false

            pairingStarted.set(
                false
            )
        }

        if (
            shouldDisarm &&
            !sessionId.isNullOrBlank()
        ) {
            try {
                AdbPairingCaptureBridge.disarmCapture(
                    context =
                        appContext,
                    sessionId =
                        sessionId
                )
            } catch (_: Throwable) {
            }
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

    /**
     * Accessibility is now actually armed for this exact session.
     *
     * mDNS discovery is deliberately not started before this acknowledgement.
     */
    private fun handleCaptureAcknowledged(
        sessionId: String
    ) {
        val shouldStartDiscovery =
            synchronized(lock) {
                if (
                    !running ||
                    activeSessionId !=
                    sessionId ||
                    captureAcknowledged
                ) {
                    false
                } else {
                    captureAcknowledged =
                        true

                    true
                }
            }

        if (!shouldStartDiscovery) {
            return
        }

        publishStage(
            Stage.WAITING_FOR_PAIRING_DIALOG
        )

        startPairingDiscovery(
            sessionId
        )
    }

    private fun startPairingDiscovery(
        sessionId: String
    ) {
        synchronized(lock) {
            if (
                !running ||
                activeSessionId !=
                sessionId ||
                !captureAcknowledged ||
                pairingDiscoveryStarted
            ) {
                return
            }

            pairingDiscoveryStarted =
                true
        }

        try {
            discoveryManager.discoverPairing(
                object :
                    AdbDiscoveryManager.Listener {

                    override fun onEndpointFound(
                        endpoint:
                            AdbDiscoveryManager.Endpoint
                    ) {
                        if (
                            !isCurrentSession(
                                sessionId
                            )
                        ) {
                            return
                        }

                        handlePairingEndpoint(
                            sessionId =
                                sessionId,
                            endpoint =
                                endpoint
                        )
                    }

                    override fun onError(
                        error: Throwable
                    ) {
                        if (
                            !isCurrentSession(
                                sessionId
                            )
                        ) {
                            return
                        }

                        fail(
                            error
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            if (
                isCurrentSession(
                    sessionId
                )
            ) {
                fail(
                    t
                )
            }
        }
    }

    private fun handleAccessibilityInfo(
        sessionId: String,
        info:
            AdbPairingAccessibilityExtractor.PairingInfo
    ) {
        synchronized(lock) {
            if (
                !running ||
                activeSessionId !=
                sessionId ||
                !captureAcknowledged
            ) {
                return
            }

            pairingCode =
                info.code

            accessibilityPort =
                info.port
        }

        tryStartPairing(
            sessionId
        )
    }

    private fun handlePairingEndpoint(
        sessionId: String,
        endpoint:
            AdbDiscoveryManager.Endpoint
    ) {
        synchronized(lock) {
            if (
                !running ||
                activeSessionId !=
                sessionId ||
                !captureAcknowledged
            ) {
                return
            }

            pairingEndpoint =
                endpoint
        }

        tryStartPairing(
            sessionId
        )
    }

    /**
     * Starts pairing only when both independently obtained values belong to
     * the current acknowledged session.
     */
    private fun tryStartPairing(
        sessionId: String
    ) {
        val code:
            String

        val endpoint:
            AdbDiscoveryManager.Endpoint

        synchronized(lock) {
            if (
                !running ||
                activeSessionId !=
                sessionId ||
                !captureAcknowledged
            ) {
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
         * Pairing data has been captured. Accessibility no longer needs access
         * to the Settings pairing dialog while TLS pairing continues.
         */
        disarmCapture(
            sessionId
        )

        unregisterCaptureReceiver()
        discoveryManager.stop()

        publishStage(
            Stage.PAIRING
        )

        Thread(
            {
                performPairing(
                    sessionId =
                        sessionId,
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
        sessionId: String,
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

        if (
            !isCurrentSession(
                sessionId
            )
        ) {
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

        discoverConnectionEndpoint(
            sessionId
        )
    }

    private fun discoverConnectionEndpoint(
        sessionId: String
    ) {
        if (
            !isCurrentSession(
                sessionId
            )
        ) {
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
                    if (
                        !isCurrentSession(
                            sessionId
                        )
                    ) {
                        return
                    }

                    connect(
                        sessionId =
                            sessionId,
                        endpoint =
                            endpoint
                    )
                }

                override fun onError(
                    error: Throwable
                ) {
                    if (
                        !isCurrentSession(
                            sessionId
                        )
                    ) {
                        return
                    }

                    fail(
                        error
                    )
                }
            }
        )
    }

    private fun connect(
        sessionId: String,
        endpoint:
            AdbDiscoveryManager.Endpoint
    ) {
        if (
            !isCurrentSession(
                sessionId
            )
        ) {
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

                if (
                    !isCurrentSession(
                        sessionId
                    )
                ) {
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

                finishConnected(
                    sessionId
                )
            },
            "CurBox-AdbAutoConnect"
        ).start()
    }

    private fun finishConnected(
        sessionId: String
    ) {
        val currentListener:
            Listener?

        synchronized(lock) {
            if (
                !running ||
                activeSessionId !=
                sessionId
            ) {
                return
            }

            running =
                false

            currentListener =
                listener

            listener =
                null

            activeSessionId =
                null

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null

            captureArmed =
                false

            captureAcknowledged =
                false

            pairingDiscoveryStarted =
                false
        }

        /*
         * Normally capture is already disarmed before TLS pairing starts.
         * Calling with this session again is harmless cleanup.
         */
        try {
            AdbPairingCaptureBridge.disarmCapture(
                context =
                    appContext,
                sessionId =
                    sessionId
            )
        } catch (_: Throwable) {
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
        val sessionId:
            String?

        val currentListener:
            Listener?

        synchronized(lock) {
            if (!running) {
                return
            }

            sessionId =
                activeSessionId

            running =
                false

            currentListener =
                listener

            listener =
                null

            activeSessionId =
                null

            pairingCode =
                null

            accessibilityPort =
                null

            pairingEndpoint =
                null

            captureArmed =
                false

            captureAcknowledged =
                false

            pairingDiscoveryStarted =
                false
        }

        if (!sessionId.isNullOrBlank()) {
            try {
                AdbPairingCaptureBridge.disarmCapture(
                    context =
                        appContext,
                    sessionId =
                        sessionId
                )
            } catch (_: Throwable) {
            }
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

    private fun disarmCapture(
        sessionId: String
    ) {
        val shouldDisarm =
            synchronized(lock) {
                if (
                    !captureArmed ||
                    activeSessionId !=
                    sessionId
                ) {
                    false
                } else {
                    captureArmed =
                        false

                    true
                }
            }

        if (!shouldDisarm) {
            return
        }

        try {
            AdbPairingCaptureBridge.disarmCapture(
                context =
                    appContext,
                sessionId =
                    sessionId
            )
        } catch (_: Throwable) {
            /*
             * Pairing can continue because local capture ownership has already
             * been stopped.
             */
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

    private fun isCurrentSession(
        sessionId: String
    ): Boolean {
        return synchronized(lock) {
            running &&
                activeSessionId ==
                sessionId
        }
    }
}
