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
 * - Existing paired installations attempt normal reconnect first.
 *
 * - First-time setup prefers automatic pairing. Accessibility captures the
 *   temporary pairing code while mDNS discovers the pairing endpoint.
 *
 * - Manual pairing-code entry remains available as a fallback.
 *
 * - Once automatic pairing succeeds, later discovery/connection failures are
 *   reported separately so callers do not request another pairing operation.
 *
 * - Automatic lifecycle recovery is enabled only after a pairing flow has
 *   completed and produced a verified Direct ADB connection.
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
        WAITING_FOR_ACCESSIBILITY,
        WAITING_FOR_PAIRING_DIALOG,
        DISCOVERING_PAIRING,
        PAIRING,
        PAIRED,
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

        fun onConnected() {
        }

        fun onWirelessDebuggingRequired(
            cause: Throwable?
        ) {
        }

        fun onPairingRequired(
            cause: Throwable?
        ) {
        }

        /**
         * Pairing with Android has already succeeded, but establishing the
         * normal Direct ADB connection afterwards failed.
         *
         * The caller should retry normal connection rather than asking for
         * another pairing code.
         */
        fun onPairedConnectionFailed(
            cause: Throwable
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

    private val autoPairingCoordinator =
        AdbAutoPairingCoordinator(
            appContext
        )

    private val operationCounter =
        AtomicLong(
            0L
        )

    @Volatile
    private var currentState =
        State.IDLE

    val state: State
        get() = currentState

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
            operationId =
                operationId,
            listener =
                listener,
            onReady = {
                startReconnect(
                    operationId =
                        operationId,
                    listener =
                        listener
                )
            }
        )
    }

    fun startAutoPairing(
        listener: Listener
    ) {
        val operationId =
            beginNewOperation()

        // Step 1 of the single pairing path: Accessibility must be granted
        // before anything else (Developer options, Wireless debugging, pairing).
        if (
            !AccessibilityServiceStatus
                .isAppBlockerServiceEnabled(
                    appContext
                )
        ) {
            listener.onError(
                IllegalStateException(
                    "Horus Accessibility service must be enabled before automatic pairing."
                )
            )

            return
        }

        publishState(
            operationId,
            listener,
            State.INITIALIZING
        )

        ensureWirelessDebugging(
            operationId =
                operationId,
            listener =
                listener,
            onReady = {
                startAutomaticPairing(
                    operationId =
                        operationId,
                    listener =
                        listener
                )
            }
        )
    }

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
            operationId =
                operationId,
            listener =
                listener,
            onReady = {
                startManualPairing(
                    operationId =
                        operationId,
                    pairingCode =
                        pairingCode,
                    listener =
                        listener
                )
            }
        )
    }

    fun isWirelessDebuggingEnabled(): Boolean {
        return WirelessDebuggingController.isEnabled(
            appContext
        )
    }

    fun cancel() {
        operationCounter.incrementAndGet()

        connectionManager.cancel()
        pairingManager.cancel()
        autoPairingCoordinator.stop()

        currentState =
            if (
                AdbChannelManager.isConnected()
            ) {
                State.CONNECTED
            } else {
                State.IDLE
            }
    }

    fun disconnect() {
        operationCounter.incrementAndGet()

        connectionManager.cancel()
        pairingManager.cancel()
        autoPairingCoordinator.stop()

        AdbChannelManager.disconnect()

        currentState =
            State.IDLE
    }

    fun isConnected(): Boolean {
        return AdbChannelManager.isConnected()
    }

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
            context =
                appContext,
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

    private fun startAutomaticPairing(
        operationId: Long,
        listener: Listener
    ) {
        if (!isCurrent(operationId)) {
            return
        }

        var pairingSucceeded =
            false

        autoPairingCoordinator.start(
            object :
                AdbAutoPairingCoordinator.Listener {

                    override fun onStageChanged(
                        stage:
                            AdbAutoPairingCoordinator.Stage
                    ) {
                        if (!isCurrent(operationId)) {
                            return
                        }

                        if (
                            stage ==
                            AdbAutoPairingCoordinator.Stage.PAIRED
                        ) {
                            pairingSucceeded =
                                true
                        }

                        val mappedState =
                            when (stage) {
                                AdbAutoPairingCoordinator.Stage.INITIALIZING ->
                                    State.INITIALIZING

                                AdbAutoPairingCoordinator.Stage.WAITING_FOR_ACCESSIBILITY ->
                                    State.WAITING_FOR_ACCESSIBILITY

                                AdbAutoPairingCoordinator.Stage.WAITING_FOR_PAIRING_DIALOG ->
                                    State.WAITING_FOR_PAIRING_DIALOG

                                AdbAutoPairingCoordinator.Stage.WAITING_FOR_DISCOVERY ->
                                    State.DISCOVERING_PAIRING

                                AdbAutoPairingCoordinator.Stage.PAIRING ->
                                    State.PAIRING

                                AdbAutoPairingCoordinator.Stage.PAIRED ->
                                    State.PAIRED

                                AdbAutoPairingCoordinator.Stage.DISCOVERING_CONNECTION ->
                                    State.DISCOVERING_CONNECTION

                                AdbAutoPairingCoordinator.Stage.CONNECTING ->
                                    State.CONNECTING

                                AdbAutoPairingCoordinator.Stage.CONNECTED ->
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

                        enableRecoveryAfterPairing()

                        bringCurboxToFront()

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

                        if (pairingSucceeded) {
                            /*
                             * Android has already accepted CurBox's persistent
                             * ADB identity. Do not present another pairing flow
                             * merely because post-pair discovery or connection
                             * failed.
                             */
                            publishState(
                                operationId,
                                listener,
                                State.ERROR
                            )

                            listener.onPairedConnectionFailed(
                                error
                            )

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

    private fun startManualPairing(
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

                        enableRecoveryAfterPairing()

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
     * After automatic pairing finishes the user is still inside Android
     * Settings. Bring CurBox back so onboarding can continue.
     */
    private fun bringCurboxToFront() {
        // The accessibility service may start activities from the background
        // and presses Back until Horus is foreground; ask it to do so first.
        try {
            appContext.sendBroadcast(
                android.content.Intent("neth.iecal.curbox.action.RETURN_TO_APP")
                    .setPackage(appContext.packageName)
            )
        } catch (_: Throwable) {
        }
        try {
            val launch =
                appContext
                    .packageManager
                    .getLaunchIntentForPackage(
                        appContext.packageName
                    )
                    ?: return

            launch.addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            appContext.startActivity(
                launch
            )
        } catch (_: Throwable) {
            // Best effort: background activity starts can be restricted.
        }
    }

    private fun enableRecoveryAfterPairing() {
        DirectAccessLifecycleManager
            .setRecoveryEnabled(
                context =
                    appContext,
                enabled =
                    true
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
        autoPairingCoordinator.stop()

        return operationCounter
            .incrementAndGet()
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
