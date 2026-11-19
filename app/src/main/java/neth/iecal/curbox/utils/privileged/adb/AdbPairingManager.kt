package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates CurBox's initial Wireless ADB pairing flow.
 *
 * Responsibilities:
 * 1. Ensure the Direct ADB transport is initialized.
 * 2. Discover the temporary Wireless ADB pairing endpoint.
 * 3. Pair using the user-provided pairing code.
 * 4. Rediscover the separate TLS connection endpoint.
 * 5. Connect using CurBox's persistent ADB identity.
 *
 * UI code should not need to know about mDNS service types, hosts, ports,
 * or transport implementation details.
 */
class AdbPairingManager(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val discoveryManager =
        AdbDiscoveryManager(appContext)

    private val operationLock =
        Any()

    @Volatile
    private var activeOperation:
        PairingOperation? = null

    enum class Stage {
        INITIALIZING,
        DISCOVERING_PAIRING,
        PAIRING,
        DISCOVERING_CONNECTION,
        CONNECTING,
        CONNECTED
    }

    interface Listener {

        /**
         * Reports progress so the setup UI can display a simple status.
         */
        fun onStageChanged(
            stage: Stage
        ) {
        }

        /**
         * Pairing and the first verified ADB connection completed.
         */
        fun onConnected()

        /**
         * The current pairing operation failed.
         */
        fun onError(
            error: Throwable
        )
    }

    /**
     * Starts a complete pairing and initial connection attempt.
     *
     * Only one attempt is allowed at a time. Starting a new attempt cancels
     * the previous discovery operation first.
     */
    fun pairAndConnect(
        pairingCode: String,
        listener: Listener
    ) {
        val normalizedCode =
            pairingCode.trim()

        if (normalizedCode.isEmpty()) {
            listener.onError(
                IllegalArgumentException(
                    "ADB pairing code must not be empty."
                )
            )

            return
        }

        val operation =
            PairingOperation(
                pairingCode =
                    normalizedCode,
                listener =
                    listener
            )

        synchronized(operationLock) {
            activeOperation?.cancel()

            activeOperation =
                operation
        }

        operation.start()
    }

    /**
     * Cancels the active setup operation.
     *
     * Cancellation stops mDNS discovery. An ADB library call already in
     * progress cannot be forcefully interrupted here, so operation identity
     * checks prevent a cancelled attempt from publishing later results.
     */
    fun cancel() {
        val operation =
            synchronized(operationLock) {
                val current =
                    activeOperation

                activeOperation =
                    null

                current
            }

        operation?.cancel()
        discoveryManager.stop()
    }

    /**
     * Returns true while this manager owns an unfinished pairing operation.
     */
    fun isPairing(): Boolean {
        return synchronized(operationLock) {
            activeOperation != null
        }
    }

    private inner class PairingOperation(
        private val pairingCode: String,
        private val listener: Listener
    ) {

        private val cancelled =
            AtomicBoolean(false)

        fun start() {
            publishStage(
                Stage.INITIALIZING
            )

            val initialization =
                AdbChannelManager.initialize()

            if (initialization.isFailure) {
                finishWithError(
                    initialization.exceptionOrNull()
                        ?: IllegalStateException(
                            "Direct ADB initialization failed."
                        )
                )

                return
            }

            if (!isCurrent()) {
                return
            }

            discoverPairingEndpoint()
        }

        fun cancel() {
            cancelled.set(true)
            discoveryManager.stop()
        }

        private fun discoverPairingEndpoint() {
            publishStage(
                Stage.DISCOVERING_PAIRING
            )

            discoveryManager.discoverPairing(
                object :
                    AdbDiscoveryManager.Listener {

                    override fun onEndpointFound(
                        endpoint:
                            AdbDiscoveryManager.Endpoint
                    ) {
                        if (!isCurrent()) {
                            return
                        }

                        performPairing(
                            endpoint
                        )
                    }

                    override fun onError(
                        error: Throwable
                    ) {
                        finishWithError(error)
                    }
                }
            )
        }

        private fun performPairing(
            endpoint:
                AdbDiscoveryManager.Endpoint
        ) {
            publishStage(
                Stage.PAIRING
            )

            /*
             * libadb pairing performs network/TLS work and must not block the
             * Android main thread.
             */
            Thread {
                val result =
                    AdbChannelManager.pair(
                        host =
                            endpoint.host,
                        port =
                            endpoint.port,
                        pairingCode =
                            pairingCode
                    )

                if (!isCurrent()) {
                    return@Thread
                }

                if (result.isFailure) {
                    finishWithError(
                        result.exceptionOrNull()
                            ?: IllegalStateException(
                                "ADB pairing failed."
                            )
                    )

                    return@Thread
                }

                discoverConnectionEndpoint()
            }.start()
        }

        private fun discoverConnectionEndpoint() {
            if (!isCurrent()) {
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
                        if (!isCurrent()) {
                            return
                        }

                        connect(
                            endpoint
                        )
                    }

                    override fun onError(
                        error: Throwable
                    ) {
                        finishWithError(error)
                    }
                }
            )
        }

        private fun connect(
            endpoint:
                AdbDiscoveryManager.Endpoint
        ) {
            publishStage(
                Stage.CONNECTING
            )

            Thread {
                val result =
                    AdbChannelManager.connect(
                        host =
                            endpoint.host,
                        port =
                            endpoint.port
                    )

                if (!isCurrent()) {
                    return@Thread
                }

                if (result.isFailure) {
                    finishWithError(
                        result.exceptionOrNull()
                            ?: IllegalStateException(
                                "ADB connection failed after pairing."
                            )
                    )

                    return@Thread
                }

                finishConnected()
            }.start()
        }

        private fun publishStage(
            stage: Stage
        ) {
            if (!isCurrent()) {
                return
            }

            listener.onStageChanged(
                stage
            )
        }

        private fun finishConnected() {
            if (!finishOnce()) {
                return
            }

            listener.onStageChanged(
                Stage.CONNECTED
            )

            listener.onConnected()
        }

        private fun finishWithError(
            error: Throwable
        ) {
            if (!finishOnce()) {
                return
            }

            listener.onError(error)
        }

        /**
         * Detaches this operation exactly once.
         */
        private fun finishOnce(): Boolean {
            if (
                !cancelled.compareAndSet(
                    false,
                    true
                )
            ) {
                return false
            }

            discoveryManager.stop()

            synchronized(operationLock) {
                if (
                    activeOperation ===
                    this
                ) {
                    activeOperation =
                        null
                }
            }

            return true
        }

        private fun isCurrent(): Boolean {
            if (cancelled.get()) {
                return false
            }

            return synchronized(operationLock) {
                activeOperation ===
                    this
            }
        }
    }
}
