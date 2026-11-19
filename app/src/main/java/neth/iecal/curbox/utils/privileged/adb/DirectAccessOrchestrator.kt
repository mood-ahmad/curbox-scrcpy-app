package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level coordinator for CurBox Direct Access.
 *
 * This is the entry point that UI and lifecycle components should use instead
 * of talking directly to Wireless Debugging, discovery, pairing, connection,
 * or transport managers.
 *
 * Bootstrap rules:
 *
 * - If Wireless Debugging is already enabled, CurBox can immediately attempt
 *   discovery and reconnection.
 *
 * - If Wireless Debugging is disabled but another privileged backend can
 *   enable it, CurBox attempts to enable and verify it before ADB discovery.
 *
 * - Direct ADB itself is never treated as a way to bootstrap Wireless
 *   Debugging while Direct ADB is disconnected.
 *
 * - Automatic lifecycle recovery is enabled only after an explicit pairing
 *   flow has completed and produced a verified Direct ADB connection.
 */
class DirectAccessOrchestrator(
    context: Context
) {

    enum class State {
        IDLE,
        INITIALIZING,
        ENABLING_WIRELESS_DEBUGGING,
        WIRELESS_DEBUGGING_REQUIRED,
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
         * Direct ADB is connected and has passed transport health
         * verification.
         */
        fun onConnected() {
        }

        /**
         * Wireless Debugging could not be enabled automatically.
         *
         * The caller can direct the user to Android's Wireless Debugging
         * settings and retry after it has been enabled.
         */
        fun onWirelessDebuggingRequired(
            cause: Throwable?
        ) {
        }

        /**
         * Automatic reconnect did not succeed.
         *
         * The caller may now offer the explicit pairing-code flow.
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

    private val appContext =
        context.applicationContext

    private val connectionManager =
        AdbConnectionManager(
            appContext
        )

    private val pairingManager =
        AdbPairingManager(
            appContext
        )

    private val operationCounter =
        AtomicLong(0L)

    @Volatile
    private var currentState =
        State.IDLE

    val state: State
        get() = currentState

    /**
     * Attempts to restore an existing Direct ADB relationship.
     *
     * This never starts pairing automatically and never asks for a pairing
     * code. Wireless Debugging is checked first because there is no useful ADB
     * discovery/reconnect operation while Android has Wireless Debugging
     * disabled.
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

        ensureWirelessDebugging(
            operationId = operationId,
            listener = listener,
            onReady = {
                startReconnect(
                    operationId = operationId,
                    listener = listener
                )
            }
        )
    }

    /**
     * Starts the explicit pairing flow after the user supplies the pairing
     * code shown by Android Wireless Debugging.
     *
     * Wireless Debugging is checked first because the pairing mDNS endpoint is
     * advertised by Android's Wireless Debugging pairing server.
     */
    fun pairAndConnect(
        pairingCode: String,
        listener: Listener
    ) {
        val operationId =
            beginNewOperation()

        publishState(
            operationId,
            listener,
            State.INITIALIZING
        )

        ensureWirelessDebugging(
            operationId = operationId,
            listener = listener,
            onReady = {
                startPairing(
                    operationId = operationId,
                    pairingCode = pairingCode,
                    listener = listener
                )
            }
        )
    }

    /**
     * Returns Android's current Wireless Debugging setting.
     */
    fun isWirelessDebuggingEnabled(): Boolean {
        return WirelessDebuggingController.isEnabled(
            appContext
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
     * and lifecycle-recovery preference for future reconnect attempts.
     *
     * Wireless Debugging itself is intentionally left unchanged.
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

    /**
     * Ensures Android reports Wireless Debugging as enabled before CurBox
     * starts any discovery operation.
     *
     * WirelessDebuggingController verifies the state again after attempting a
     * privileged change, so reaching [onReady] means the setting read-back
     * succeeded.
     */
    private fun ensureWirelessDebugging(
        operationId: Long,
        listener: Listener,
        onReady: () -> Unit
    ) {
        if (!isCurrent(operationId)) {
            return
        }

        if (
            WirelessDebuggingController.isEnabled(
                appContext
            )
        ) {
            onReady()
            return
        }

        publishState(
            operationId,
            listener,
            State.ENABLING_WIRELESS_DEBUGGING
        )

        WirelessDebuggingController.enable(
            context = appContext,
            listener =
                object :
                    WirelessDebuggingController.Listener {

                    override fun onResult(
                        enabled: Boolean
                    ) {
                        if (!isCurrent(operationId)) {
                            return
                        }

                        if (enabled) {
                            onReady()
                        } else {
                            reportWirelessDebuggingRequired(
                                operationId =
                                    operationId,
                                listener =
                                    listener,
                                cause =
                                    IllegalStateException(
                                        "Wireless Debugging remains disabled."
                                    )
                            )
                        }
                    }

                    override fun onError(
                        error: String
                    ) {
                        if (!isCurrent(operationId)) {
                            return
                        }

                        reportWirelessDebuggingRequired(
                            operationId =
                                operationId,
                            listener =
                                listener,
                            cause =
                                IllegalStateException(
                                    error
                                )
                        )
                    }
                }
        )
    }

    /**
     * Starts normal reconnect after Wireless Debugging is known to be enabled.
     */
    private fun startReconnect(
        operationId: Long,
        listener: Listener
    ) {
        if (!isCurrent(operationId)) {
            return
        }

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
                         * Do not destroy the stored ADB identity here.
                         *
                         * Discovery or authentication failure does not prove
                         * that the identity itself should be reset.
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
     * Starts explicit pairing after Wireless Debugging is known to be enabled.
     *
     * Lifecycle recovery is enabled only after this explicit setup flow has
     * produced a verified connection.
     */
    private fun startPairing(
        operationId: Long,
        pairingCode: String,
        listener: Listener
    ) {
        if (!isCurrent(operationId)) {
            return
        }

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

                        /*
                         * The explicit pairing flow has now produced a
                         * verified Direct ADB connection. From this point on,
                         * lifecycle recovery may reuse the stored identity.
                         */
                        DirectAccessLifecycleManager
                            .setRecoveryEnabled(
                                context =
                                    appContext,
                                enabled =
                                    true
                            )

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

    private fun reportWirelessDebuggingRequired(
        operationId: Long,
        listener: Listener,
        cause: Throwable?
    ) {
        if (!isCurrent(operationId)) {
            return
        }

        publishState(
            operationId,
            listener,
            State.WIRELESS_DEBUGGING_REQUIRED
        )

        listener.onWirelessDebuggingRequired(
            cause
        )
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
