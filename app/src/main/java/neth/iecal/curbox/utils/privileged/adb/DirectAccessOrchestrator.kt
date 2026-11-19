package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level coordinator for CurBox Direct Access.
 *
 * This is the entry point that UI and lifecycle components should use instead
 * of talking directly to discovery, pairing, or connection managers.
 */
class DirectAccessOrchestrator(
    context: Context
) {

    enum class State {
        IDLE,
        INITIALIZING,
        DISCOVERING,
        PAIRING_REQUIRED,
        DISCOVERING_PAIRING,
        PAIRING,
        DISCOVERING_CONNECTION,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    interface Listener {

        fun onStateChanged(
            state: State
        ) {
        }

        /**
         * Direct ADB is connected and passed transport health verification.
         */
        fun onConnected() {
        }

        /**
         * Automatic reconnect did not succeed.
         *
         * The caller may now offer the explicit pairing flow.
         */
        fun onPairingRequired(
            cause: Throwable?
        ) {
        }

        fun onError(
            error: Throwable
        ) {
        }
    }

    private val connectionManager =
        AdbConnectionManager(
            context.applicationContext
        )

    private val pairingManager =
        AdbPairingManager(
            context.applicationContext
        )

    private val operationCounter =
        AtomicLong(0L)

    @Volatile
    private var currentState =
        State.IDLE

    val state: State
        get() = currentState

    /**
     * Attempts to restore/use an existing Direct ADB relationship.
     *
     * This method never starts pairing automatically and never asks for a
     * pairing code. If reconnect cannot be completed, the caller receives
     * [Listener.onPairingRequired].
     */
    fun ensureConnected(
        listener: Listener
    ) {
        val operationId =
            beginNewOperation()

        publishState(
            operationId,
            listener,
            State.INITIALIZING
        )

        connectionManager.ensureConnected(
            object :
                AdbConnectionManager.Listener {

                override fun onStageChanged(
                    stage:
                        AdbConnectionManager.Stage
                ) {
                    if (!isCurrent(operationId)) {
                        return
                    }

                    val mappedState =
                        when (stage) {
                            AdbConnectionManager.Stage.INITIALIZING ->
                                State.INITIALIZING

                            AdbConnectionManager.Stage.DISCOVERING ->
                                State.DISCOVERING

                            AdbConnectionManager.Stage.CONNECTING ->
                                State.CONNECTING

                            AdbConnectionManager.Stage.CONNECTED ->
                                State.CONNECTED
                        }

                    publishState(
                        operationId,
                        listener,
                        mappedState
                    )
                }

                override fun onConnected() {
                    if (!isCurrent(operationId)) {
                        return
                    }

                    publishState(
                        operationId,
                        listener,
                        State.CONNECTED
                    )

                    listener.onConnected()
                }

                override fun onError(
                    error: Throwable
                ) {
                    if (!isCurrent(operationId)) {
                        return
                    }

                    /*
                     * At this level we deliberately do not destroy the stored
                     * ADB identity. Failure to reconnect can mean Wireless
                     * Debugging is off, discovery failed, the endpoint changed,
                     * or the device no longer trusts the identity.
                     *
                     * Later recovery layers can distinguish these cases.
                     */
                    publishState(
                        operationId,
                        listener,
                        State.PAIRING_REQUIRED
                    )

                    listener.onPairingRequired(
                        error
                    )
                }
            }
        )
    }

    /**
     * Starts the explicit pairing flow after the user supplies the pairing
     * code shown by Android Wireless Debugging.
     */
    fun pairAndConnect(
        pairingCode: String,
        listener: Listener
    ) {
        val operationId =
            beginNewOperation()

        pairingManager.pairAndConnect(
            pairingCode =
                pairingCode,
            listener =
                object :
                    AdbPairingManager.Listener {

                    override fun onStageChanged(
                        stage:
                            AdbPairingManager.Stage
                    ) {
                        if (!isCurrent(operationId)) {
                            return
                        }

                        val mappedState =
                            when (stage) {
                                AdbPairingManager.Stage.INITIALIZING ->
                                    State.INITIALIZING

                                AdbPairingManager.Stage.DISCOVERING_PAIRING ->
                                    State.DISCOVERING_PAIRING

                                AdbPairingManager.Stage.PAIRING ->
                                    State.PAIRING

                                AdbPairingManager.Stage.DISCOVERING_CONNECTION ->
                                    State.DISCOVERING_CONNECTION

                                AdbPairingManager.Stage.CONNECTING ->
                                    State.CONNECTING

                                AdbPairingManager.Stage.CONNECTED ->
                                    State.CONNECTED
                            }

                        publishState(
                            operationId,
                            listener,
                            mappedState
                        )
                    }

                    override fun onConnected() {
                        if (!isCurrent(operationId)) {
                            return
                        }

                        publishState(
                            operationId,
                            listener,
                            State.CONNECTED
                        )

                        listener.onConnected()
                    }

                    override fun onError(
                        error: Throwable
                    ) {
                        if (!isCurrent(operationId)) {
                            return
                        }

                        publishState(
                            operationId,
                            listener,
                            State.ERROR
                        )

                        listener.onError(
                            error
                        )
                    }
                }
        )
    }

    /**
     * Cancels discovery/setup activity owned by this orchestrator.
     *
     * An already established ADB session is intentionally left connected.
     */
    fun cancel() {
        operationCounter.incrementAndGet()

        connectionManager.cancel()
        pairingManager.cancel()

        currentState =
            if (AdbChannelManager.isConnected()) {
                State.CONNECTED
            } else {
                State.IDLE
            }
    }

    /**
     * Disconnects the current ADB session while preserving CurBox's identity
     * for future reconnect attempts.
     */
    fun disconnect() {
        operationCounter.incrementAndGet()

        connectionManager.cancel()
        pairingManager.cancel()

        AdbChannelManager.disconnect()

        currentState =
            State.IDLE
    }

    /**
     * Current verified connection state from the shared ADB channel.
     */
    fun isConnected(): Boolean {
        return AdbChannelManager.isConnected()
    }

    private fun beginNewOperation(): Long {
        connectionManager.cancel()
        pairingManager.cancel()

        return operationCounter.incrementAndGet()
    }

    private fun isCurrent(
        operationId: Long
    ): Boolean {
        return operationCounter.get() ==
            operationId
    }

    private fun publishState(
        operationId: Long,
        listener: Listener,
        state: State
    ) {
        if (!isCurrent(operationId)) {
            return
        }

        currentState =
            state

        listener.onStateChanged(
            state
        )
    }
}
