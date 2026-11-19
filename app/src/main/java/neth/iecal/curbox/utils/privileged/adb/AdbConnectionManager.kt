package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates normal Direct ADB connection and reconnection after CurBox has
 * already been paired.
 *
 * This class never performs pairing and never asks for a pairing code.
 *
 * Its job is:
 * 1. Ensure the Direct ADB transport is initialized.
 * 2. Reuse CurBox's persistent ADB identity.
 * 3. Discover the current TLS connection endpoint through mDNS.
 * 4. Connect and let the transport perform its health verification.
 *
 * If authentication is no longer accepted by the device, the caller can
 * escalate to the separate pairing flow.
 */
class AdbConnectionManager(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val discoveryManager =
        AdbDiscoveryManager(
            appContext
        )

    private val operationLock =
        Any()

    @Volatile
    private var activeOperation:
        ConnectionOperation? = null

    enum class Stage {
        INITIALIZING,
        DISCOVERING,
        CONNECTING,
        CONNECTED
    }

    interface Listener {

        fun onStageChanged(
            stage: Stage
        ) {
        }

        /**
         * A verified Direct ADB connection is ready.
         */
        fun onConnected()

        /**
         * Discovery, initialization, or connection failed.
         */
        fun onError(
            error: Throwable
        )
    }

    /**
     * Ensures that Direct ADB is connected.
     *
     * If the transport already reports a live connection, no discovery or
     * reconnect attempt is performed.
     */
    fun ensureConnected(
        listener: Listener
    ) {
        val operation =
            ConnectionOperation(
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
     * Cancels the current discovery/reconnect operation.
     *
     * This does not disconnect an already established ADB connection.
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
     * Returns true while discovery or connection work is still in progress.
     */
    fun isConnecting(): Boolean {
        return synchronized(operationLock) {
            activeOperation != null
        }
    }

    private inner class ConnectionOperation(
        private val listener: Listener
    ) {

        private val finished =
            AtomicBoolean(false)

        fun start() {
            publishStage(
                Stage.INITIALIZING
            )

            val initialization =
                AdbChannelManager.initialize(
                    appContext
                )

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

            /*
             * Avoid discovery and reconnect work when a verified transport is
             * already alive.
             */
            if (AdbChannelManager.isConnected()) {
                finishConnected()
                return
            }

            discoverConnectionEndpoint()
        }

        fun cancel() {
            finished.set(true)
            discoveryManager.stop()
        }

        private fun discoverConnectionEndpoint() {
            if (!isCurrent()) {
                return
            }

            publishStage(
                Stage.DISCOVERING
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
                        finishWithError(
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
            if (!isCurrent()) {
                return
            }

            publishStage(
                Stage.CONNECTING
            )

            /*
             * libadb performs socket/TLS work here, so keep it away from the
             * Android main thread.
             */
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
                                "Direct ADB connection failed."
                            )
                    )

                    return@Thread
                }

                /*
                 * LibAdbTransport.connect() already performs an actual shell
                 * health check before reporting success.
                 */
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

            listener.onError(
                error
            )
        }

        /**
         * Completes this operation exactly once and detaches it from the
         * manager.
         */
        private fun finishOnce(): Boolean {
            if (
                !finished.compareAndSet(
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
            if (finished.get()) {
                return false
            }

            return synchronized(operationLock) {
                activeOperation ===
                    this
            }
        }
    }
}
