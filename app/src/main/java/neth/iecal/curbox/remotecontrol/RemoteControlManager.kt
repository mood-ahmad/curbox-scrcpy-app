package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

object RemoteControlManager {

    private const val PREFS_NAME =
        "remote_control_prefs"

    private const val KEY_ENABLED =
        "enabled"

    private const val START_TIMEOUT_MS =
        600_000L

    private const val STOP_TIMEOUT_MS =
        270_000L

    private const val RESTART_STOP_TIMEOUT_MS =
        270_000L

    private const val CLOUDFLARE_REPAIR_TIMEOUT_MS =
        120_000L

    private const val SHIZUKU_AUTO_START_MAX_ATTEMPTS =
        6

    private const val SHIZUKU_AUTO_START_RETRY_DELAY_MS =
        2_000L

    private const val KEY_LAST_LOCAL_URL =
        "last_local_url"

    private const val KEY_LAST_PUBLIC_URL =
        "last_public_url"

    private val scope =
        CoroutineScope(Dispatchers.IO)

    private val lifecycleLock =
        Any()

    private val bridgeDispatchLock =
        Any()

    private val _state =
        MutableStateFlow<RemoteControlState>(
            RemoteControlState.Idle
        )

    val state: StateFlow<RemoteControlState> =
        _state.asStateFlow()

    private var bridge:
        RemoteControlServiceBridge? = null

    private var currentLocalUrl:
        String? = null

    private var currentPublicUrl:
        String? = null

    /*
     * Identifies the currently active lifecycle operation.
     *
     * All reads and writes must happen while holding lifecycleLock.
     */
    private var activeOperation =
        LifecycleOperation.IDLE

    /*
     * Every new lifecycle operation receives a new generation.
     *
     * Callbacks from older operations are ignored when their generation
     * no longer matches the current one.
     */
    private var operationGeneration =
        0L

    fun init(context: Context) {
        synchronized(lifecycleLock) {
            if (bridge == null) {
                bridge =
                    RemoteControlServiceBridge(
                        context.applicationContext
                    )
            }
        }

        EventLogger.init(context)
    }

    fun autoStartIfEnabled(
        context: Context
    ) {
        init(context)

        val prefs =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val enabled =
            prefs.getBoolean(
                KEY_ENABLED,
                true
            )

        if (enabled) {
            EventLogger.logStage(
                stage = "AUTO_START",
                status = "ENABLED"
            )

            start(
                context = context,
                reason = StartReason.AUTO_START
            )
        } else {
            EventLogger.logStage(
                stage = "AUTO_START",
                status = "SKIPPED",
                details = "reason=feature_disabled"
            )
        }
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean
    ) {
        val prefs =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                enabled
            )
            .apply()

        if (enabled) {
            start(
                context = context,
                reason = StartReason.FEATURE_ENABLED
            )
        } else {
            stop()
        }
    }

    fun isEnabled(
        context: Context
    ): Boolean {
        return context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )
            .getBoolean(
                KEY_ENABLED,
                true
            )
    }

    /**
     * Starts the engine.
     *
     * The default reason represents a user-triggered start, preserving
     * compatibility with existing UI call sites.
     */
    fun start(
        context: Context,
        reason: StartReason = StartReason.USER_START
    ) {
        init(context)

        val appContext =
            context.applicationContext

        val startGeneration: Long

        synchronized(lifecycleLock) {
            val currentState =
                _state.value

            if (
                activeOperation != LifecycleOperation.IDLE ||
                currentState is RemoteControlState.Starting ||
                currentState is RemoteControlState.Stopping ||
                currentState is RemoteControlState.Ready
            ) {
                EventLogger.logStage(
                    stage = "START",
                    status = "SKIPPED",
                    details =
                        "reason=${reason.label}; " +
                        "operation=$activeOperation; " +
                        "state=${currentState.javaClass.simpleName}"
                )

                EventLogger.log(
                    "SERVICE",
                    "Start skipped: " +
                        "reason=${reason.label}, " +
                        "operation=$activeOperation, " +
                        "state=${currentState.javaClass.simpleName}"
                )

                return
            }

            operationGeneration++
            startGeneration =
                operationGeneration

            activeOperation =
                LifecycleOperation.STARTING

            _state.value =
                RemoteControlState.Starting
        }

        EventLogger.logStage(
            stage = "START",
            status = "REQUESTED",
            details =
                "reason=${reason.label}; " +
                "generation=$startGeneration"
        )

        EventLogger.log(
            "SERVICE",
            "Start requested: " +
                "reason=${reason.label}, " +
                "generation=$startGeneration"
        )

        scheduleLifecycleTimeout(
            generation = startGeneration,
            expectedOperation = LifecycleOperation.STARTING,
            timeoutMs = START_TIMEOUT_MS,
            stage = "START",
            timeoutMessage =
                "Remote-control startup timed out",
            timeoutStateFactory = {
                RemoteControlState.Error(
                    "Remote-control startup timed out"
                )
            }
        )

        scope.launch {
            try {
                if (!isCurrentOperation(
                        generation = startGeneration,
                        expectedOperation =
                            LifecycleOperation.STARTING
                    )
                ) {
                    EventLogger.logStage(
                        stage = "SHIZUKU_READY",
                        status = "CANCELLED",
                        details =
                            "generation=$startGeneration; " +
                            "reason=stale_before_readiness"
                    )

                    return@launch
                }

                val readiness: ShizukuReadiness =
                    if (reason == StartReason.AUTO_START) {
                        EventLogger.logStage(
                            stage = "SHIZUKU_READY",
                            status = "STARTED",
                            details =
                                "generation=$startGeneration; " +
                                "reason=${reason.label}"
                        )

                        awaitShizukuReadyForAutoStart(
                            generation = startGeneration,
                            reason = reason
                        )
                    } else {
                        EventLogger.logStage(
                            stage = "SHIZUKU_READY",
                            status = "STARTED",
                            details =
                                "generation=$startGeneration; " +
                                "reason=${reason.label}"
                        )

                        val single =
                            checkShizukuReadiness()

                        EventLogger.logStage(
                            stage = "SHIZUKU_READY",
                            status =
                                if (single ==
                                    ShizukuReadiness.READY
                                ) {
                                    "OK"
                                } else {
                                    "FAILED"
                                },
                            details =
                                "generation=$startGeneration; " +
                                "reason=${reason.label}; " +
                                "result=${single.name}"
                        )

                        single
                    }

                if (!isCurrentOperation(
                        generation = startGeneration,
                        expectedOperation =
                            LifecycleOperation.STARTING
                    )
                ) {
                    EventLogger.logStage(
                        stage = "SHIZUKU_READY",
                        status = "CANCELLED",
                        details =
                            "generation=$startGeneration; " +
                            "reason=stale_after_readiness"
                    )

                    return@launch
                }

                when (readiness) {
                    ShizukuReadiness.BINDER_UNAVAILABLE -> {
                        finishStartWithError(
                            generation = startGeneration,
                            message =
                                "Shizuku service is unavailable"
                        )

                        return@launch
                    }

                    ShizukuReadiness.PERMISSION_DENIED -> {
                        finishStartWithError(
                            generation = startGeneration,
                            message =
                                "Shizuku permission is not granted"
                        )

                        return@launch
                    }

                    ShizukuReadiness.READY -> {
                        // Continue
                    }
                }

                EventLogger.logStage(
                    stage = "STAGING",
                    status = "STARTED",
                    details = "generation=$startGeneration"
                )

                val staged =
                    copyAssetsToStaging(
                        appContext
                    )

                if (!isCurrentOperation(
                        generation = startGeneration,
                        expectedOperation =
                            LifecycleOperation.STARTING
                    )
                ) {
                    EventLogger.logStage(
                        stage = "STAGING",
                        status = "CANCELLED",
                        details =
                            "generation=$startGeneration; " +
                            "reason=stale_operation"
                    )

                    EventLogger.log(
                        "SERVICE",
                        "Staging result ignored: " +
                            "stale generation=$startGeneration"
                    )

                    return@launch
                }

                if (!staged) {
                    EventLogger.logStage(
                        stage = "STAGING",
                        status = "FAILED",
                        details = "generation=$startGeneration"
                    )

                    finishStartWithError(
                        generation = startGeneration,
                        message =
                            "Failed to extract bundled files"
                    )

                    return@launch
                }

                EventLogger.logStage(
                    stage = "STAGING",
                    status = "OK",
                    details = "generation=$startGeneration"
                )

                val currentBridge =
                    synchronized(lifecycleLock) {
                        bridge
                    }

                if (currentBridge == null) {
                    finishStartWithError(
                        generation = startGeneration,
                        message =
                            "Remote-control bridge is unavailable"
                    )

                    return@launch
                }

                if (!isCurrentOperation(
                        generation = startGeneration,
                        expectedOperation =
                            LifecycleOperation.STARTING
                    )
                ) {
                    EventLogger.logStage(
                        stage = "START",
                        status = "CANCELLED",
                        details =
                            "generation=$startGeneration; " +
                            "reason=stale_before_bridge_start"
                    )

                    return@launch
                }

                val startCallback =
                    object :
                        RemoteControlServiceBridge.StartCallback {

                        override fun onSuccess(
                            localUrl: String?,
                            publicUrl: String?,
                            networkType: String
                        ) {
                            val accepted =
                                synchronized(
                                    lifecycleLock
                                ) {
                                    if (
                                        operationGeneration !=
                                            startGeneration ||
                                        activeOperation !=
                                            LifecycleOperation.STARTING
                                    ) {
                                        false
                                    } else {
                                        currentLocalUrl =
                                            localUrl

                                        currentPublicUrl =
                                            publicUrl

                                        _state.value =
                                            RemoteControlState.Ready(
                                                localUrl = localUrl,
                                                publicUrl = publicUrl
                                            )

                                        activeOperation =
                                            LifecycleOperation.IDLE

                                        true
                                    }
                                }

                            if (!accepted) {
                                EventLogger.logStage(
                                    stage = "START",
                                    status = "CANCELLED",
                                    details =
                                        "generation=$startGeneration; " +
                                        "reason=stale_success_callback"
                                )

                                EventLogger.log(
                                    "SERVICE",
                                    "Start success ignored: " +
                                        "stale generation=" +
                                        startGeneration
                                )

                                return
                            }

                            EventLogger.logStage(
                                stage = "START",
                                status = "OK",
                                details =
                                    "reason=${reason.label}; " +
                                    "generation=$startGeneration; " +
                                    "network=$networkType; " +
                                    "localAvailable=${localUrl != null}; " +
                                    "publicAvailable=${publicUrl != null}"
                            )

                            EventLogger.log(
                                "SERVICE",
                                "Started successfully: " +
                                    "reason=${reason.label}, " +
                                    "network=$networkType, " +
                                    "generation=$startGeneration, " +
                                    "localAvailable=${localUrl != null}, " +
                                    "publicAvailable=${publicUrl != null}"
                            )

                            handleSuccessfulStartEmail(
                                appContext = appContext,
                                reason = reason,
                                localUrl = localUrl,
                                publicUrl = publicUrl,
                                networkType = networkType
                            )
                        }

                        override fun onError(
                            message: String
                        ) {
                            finishStartWithError(
                                generation =
                                    startGeneration,
                                message =
                                    message
                            )
                        }
                    }

                var bridgeStartDispatched =
                    false

                synchronized(bridgeDispatchLock) {
                    val stillCurrent =
                        synchronized(lifecycleLock) {
                            operationGeneration ==
                                startGeneration &&
                                activeOperation ==
                                    LifecycleOperation.STARTING
                        }

                    if (stillCurrent) {
                        currentBridge.start(
                            startCallback
                        )

                        bridgeStartDispatched =
                            true
                    }
                }

                if (!bridgeStartDispatched) {
                    EventLogger.logStage(
                        stage = "START",
                        status = "CANCELLED",
                        details =
                            "generation=$startGeneration; " +
                            "reason=stale_before_bridge_start"
                    )

                    return@launch
                }
            } catch (t: Throwable) {
                finishStartWithError(
                    generation =
                        startGeneration,
                    message =
                        t.message
                            ?: "Unexpected error"
                )
            }
        }
    }

    /**
     * Restarts the engine in one serialized lifecycle operation.
     *
     * Repeated restart requests are ignored while another start, stop,
     * or restart is still active.
     */
    fun restart(
        context: Context,
        reason: StartReason =
            StartReason.NETWORK_CHANGE
    ) {
        init(context)

        val appContext =
            context.applicationContext

        var currentBridge:
            RemoteControlServiceBridge? = null

        var restartGeneration =
            0L

        var bridgeMissing =
            false

        var restartAccepted =
            false

        synchronized(lifecycleLock) {
            val selectedBridge =
                bridge

            if (selectedBridge == null) {
                bridgeMissing = true
            } else if (
                activeOperation != LifecycleOperation.IDLE ||
                _state.value is RemoteControlState.Starting ||
                _state.value is RemoteControlState.Stopping
            ) {
                EventLogger.log(
                    "SERVICE",
                    "Restart skipped: " +
                        "reason=${reason.label}, " +
                        "operation=$activeOperation, " +
                        "state=${_state.value.javaClass.simpleName}"
                )

                return
            } else {
                operationGeneration++
                restartGeneration =
                    operationGeneration

                activeOperation =
                    LifecycleOperation.RESTARTING

                _state.value =
                    RemoteControlState.Stopping

                currentBridge =
                    selectedBridge

                restartAccepted =
                    true
            }
        }

        if (bridgeMissing) {
            EventLogger.log(
                "SERVICE",
                "Restart converted to start: " +
                    "bridge unavailable, " +
                    "reason=${reason.label}"
            )

            start(
                context = appContext,
                reason = reason
            )

            return
        }

        if (!restartAccepted) {
            return
        }

        val bridgeToUse =
            currentBridge ?: return

        val restartGen =
            restartGeneration

        EventLogger.log(
            "SERVICE",
            "Restart requested: " +
                "reason=${reason.label}, " +
                "generation=$restartGen"
        )

        scheduleLifecycleTimeout(
            generation = restartGen,
            expectedOperation = LifecycleOperation.RESTARTING,
            timeoutMs = RESTART_STOP_TIMEOUT_MS,
            stage = "RESTART",
            timeoutMessage =
                "Remote-control restart stop phase timed out",
            timeoutStateFactory = {
                RemoteControlState.Error(
                    "Remote-control restart stop phase timed out"
                )
            }
        )

        val restartCallback =
            object :
                RemoteControlServiceBridge.SimpleCallback {

                override fun onComplete(
                    success: Boolean,
                    message: String?
                ) {
                    var action =
                        RestartCallbackAction.IGNORE

                    var errorText:
                        String? = null

                    synchronized(lifecycleLock) {
                        if (
                            operationGeneration ==
                                restartGen
                        ) {
                            when (activeOperation) {
                                LifecycleOperation.RESTARTING -> {
                                    if (success) {
                                        currentLocalUrl =
                                            null

                                        currentPublicUrl =
                                            null

                                        activeOperation =
                                            LifecycleOperation.IDLE

                                        _state.value =
                                            RemoteControlState.Idle

                                        action =
                                            RestartCallbackAction.START
                                    } else {
                                        currentLocalUrl =
                                            null

                                        currentPublicUrl =
                                            null

                                        activeOperation =
                                            LifecycleOperation.IDLE

                                        errorText =
                                            message
                                                ?: "Failed to stop engine before restart"

                                        _state.value =
                                            RemoteControlState.Error(
                                                errorText!!
                                            )

                                        action =
                                            RestartCallbackAction.STOP_FAILED
                                    }
                                }

                                LifecycleOperation.STOPPING -> {
                                    if (success) {
                                        currentLocalUrl =
                                            null

                                        currentPublicUrl =
                                            null

                                        activeOperation =
                                            LifecycleOperation.IDLE

                                        _state.value =
                                            RemoteControlState.Idle

                                        action =
                                            RestartCallbackAction.CONVERTED_STOP_OK
                                    } else {
                                        currentLocalUrl =
                                            null

                                        currentPublicUrl =
                                            null

                                        activeOperation =
                                            LifecycleOperation.IDLE

                                        errorText =
                                            message
                                                ?: "Failed to stop remote-control engine"

                                        _state.value =
                                            RemoteControlState.Error(
                                                errorText!!
                                            )

                                        action =
                                            RestartCallbackAction.CONVERTED_STOP_FAILED
                                    }
                                }

                                else -> {
                                    // Stale operation - ignore.
                                }
                            }
                        }
                    }

                    when (action) {
                        RestartCallbackAction.START -> {
                            EventLogger.logStage(
                                stage = "RESTART",
                                status = "STOP_OK",
                                details =
                                    "generation=$restartGen; " +
                                    "reason=${reason.label}"
                            )

                            start(
                                context = appContext,
                                reason = reason
                            )
                        }

                        RestartCallbackAction.STOP_FAILED -> {
                            EventLogger.logStage(
                                stage = "RESTART",
                                status = "STOP_FAILED",
                                details =
                                    "generation=$restartGen; " +
                                    "reason=${safeLogSummary(
                                        errorText ?: "unknown"
                                    )}"
                            )

                            EventLogger.log(
                                "SERVICE",
                                "Restart stop phase reported failure: " +
                                    (errorText ?: "No details")
                            )
                        }

                        RestartCallbackAction.CONVERTED_STOP_OK -> {
                            EventLogger.logStage(
                                stage = "RESTART",
                                status = "CONVERTED_TO_STOP",
                                details =
                                    "generation=$restartGen; " +
                                    "result=success"
                            )

                            EventLogger.log(
                                "SERVICE",
                                "Pending restart cancelled; " +
                                    "stop completed successfully"
                            )
                        }

                        RestartCallbackAction.CONVERTED_STOP_FAILED -> {
                            EventLogger.logStage(
                                stage = "RESTART",
                                status = "CONVERTED_TO_STOP",
                                details =
                                    "generation=$restartGen; " +
                                    "result=failure; " +
                                    "reason=${safeLogSummary(
                                        errorText ?: "unknown"
                                    )}"
                            )

                            EventLogger.log(
                                "SERVICE",
                                "Pending restart cancelled; " +
                                    "stop failed: " +
                                    (errorText ?: "No details")
                            )
                        }

                        RestartCallbackAction.IGNORE -> {
                            EventLogger.logStage(
                                stage = "RESTART",
                                status = "CANCELLED",
                                details =
                                    "generation=$restartGen; " +
                                    "reason=stale_callback"
                            )

                            EventLogger.log(
                                "SERVICE",
                                "Restart continuation skipped: " +
                                    "generation=$restartGen"
                            )
                        }
                    }
                }
            }

        synchronized(bridgeDispatchLock) {
            bridgeToUse.stop(
                restartCallback
            )
        }
    }

    fun stop() {
        var convertedRestartToStop =
            false

        var convertedRestartGeneration =
            0L

        var currentBridge:
            RemoteControlServiceBridge? = null

        var stopGeneration =
            0L

        var shouldScheduleBridgeStop =
            false

        synchronized(lifecycleLock) {
            val selectedBridge =
                bridge
                    ?: return

            /*
             * If a restart is currently waiting for its stop callback,
             * convert that restart into a plain stop.
             *
             * The existing Bridge stop operation continues, but its
             * callback will no longer start the engine again.
             *
             * A Stop Watchdog is scheduled outside the lock so that the
             * converted STOPPING state is released if the Bridge callback
             * never arrives.
             */
            if (
                activeOperation ==
                    LifecycleOperation.RESTARTING
            ) {
                convertedRestartToStop =
                    true

                convertedRestartGeneration =
                    operationGeneration

                activeOperation =
                    LifecycleOperation.STOPPING
            } else {
                /*
                 * If a Cloudflare-only repair is in progress, do not
                 * return. Fall through to the normal stop path so the
                 * engine is actually stopped. operationGeneration++
                 * below invalidates any pending Cloudflare repair
                 * callback, and the bridge's stop path cancels its
                 * internal repair generation as well.
                 */
                if (
                    activeOperation ==
                        LifecycleOperation.CLOUDFLARE_REPAIRING
                ) {
                    EventLogger.log(
                        "SERVICE",
                        "Stop requested during Cloudflare repair; " +
                            "repair callback will be invalidated and " +
                            "engine will stop normally"
                    )
                }

                if (
                    activeOperation ==
                        LifecycleOperation.STOPPING ||
                    _state.value is
                        RemoteControlState.Stopping
                ) {
                    EventLogger.log(
                        "SERVICE",
                        "Stop skipped: stop already in progress"
                    )

                    return
                }

                if (
                    activeOperation ==
                        LifecycleOperation.IDLE &&
                    _state.value is
                        RemoteControlState.Idle
                ) {
                    return
                }

                operationGeneration++
                stopGeneration =
                    operationGeneration

                activeOperation =
                    LifecycleOperation.STOPPING

                _state.value =
                    RemoteControlState.Stopping

                currentBridge =
                    selectedBridge

                shouldScheduleBridgeStop =
                    true
            }
        }

        if (convertedRestartToStop) {
            EventLogger.log(
                "SERVICE",
                "Pending restart cancelled; " +
                    "stop will complete normally"
            )

            scheduleLifecycleTimeout(
                generation = convertedRestartGeneration,
                expectedOperation = LifecycleOperation.STOPPING,
                timeoutMs = STOP_TIMEOUT_MS,
                stage = "STOP",
                timeoutMessage =
                    "Remote-control stop timed out; shutdown could not be verified",
                timeoutStateFactory = {
                    RemoteControlState.Error(
                        "Remote-control stop timed out; shutdown could not be verified"
                    )
                }
            )

            return
        }

        if (!shouldScheduleBridgeStop) {
            return
        }

        val bridgeToUse =
            currentBridge ?: return

        val stopGen =
            stopGeneration

        EventLogger.log(
            "SERVICE",
            "Stop requested: " +
                "generation=$stopGen"
        )

        scheduleLifecycleTimeout(
            generation = stopGen,
            expectedOperation = LifecycleOperation.STOPPING,
            timeoutMs = STOP_TIMEOUT_MS,
            stage = "STOP",
            timeoutMessage =
                "Remote-control stop timed out; shutdown could not be verified",
            timeoutStateFactory = {
                RemoteControlState.Error(
                    "Remote-control stop timed out; shutdown could not be verified"
                )
            }
        )

        val stopCallback =
            object :
                RemoteControlServiceBridge.SimpleCallback {

                override fun onComplete(
                    success: Boolean,
                    message: String?
                ) {
                    val accepted =
                        synchronized(
                            lifecycleLock
                        ) {
                            if (
                                operationGeneration !=
                                    stopGen ||
                                activeOperation !=
                                    LifecycleOperation.STOPPING
                            ) {
                                false
                            } else {
                                currentLocalUrl =
                                    null

                                currentPublicUrl =
                                    null

                                activeOperation =
                                    LifecycleOperation.IDLE

                                _state.value =
                                    if (success) {
                                        RemoteControlState.Idle
                                    } else {
                                        RemoteControlState.Error(
                                            message
                                                ?: "Failed to stop remote-control engine"
                                        )
                                    }

                                true
                            }
                        }

                    if (!accepted) {
                        EventLogger.log(
                            "SERVICE",
                            "Stop callback ignored: " +
                                "stale generation=$stopGen"
                        )

                        return
                    }

                    if (success) {
                        EventLogger.log(
                            "SERVICE",
                            "Stopped successfully: " +
                                "generation=$stopGen"
                        )
                    } else {
                        EventLogger.log(
                            "SERVICE",
                            "Stop failed: " +
                                (message ?: "No details")
                        )
                    }
                }
            }

        synchronized(bridgeDispatchLock) {
            bridgeToUse.stop(
                stopCallback
            )
        }
    }

    /**
     * Repairs only the Cloudflare public tunnel while Signaling, Agent,
     * and Local URL remain untouched.
     *
     * Preconditions:
     *  - Lifecycle is IDLE.
     *  - State is Ready (core + local are healthy).
     *
     * State remains Ready during the repair because core and local are
     * still valid. Only currentPublicUrl is replaced on success. On
     * failure, currentLocalUrl and currentPublicUrl are preserved and
     * the state stays Ready.
     */
    fun repairPublicTunnel(
        context: Context
    ) {
        init(context)

        val appContext =
            context.applicationContext

        val selectedBridge:
            RemoteControlServiceBridge

        val repairGeneration:
            Long

        synchronized(lifecycleLock) {
            val bridgeOrNull =
                bridge

            if (bridgeOrNull == null) {
                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR_MANAGER",
                    status = "SKIPPED",
                    details = "reason=bridge_unavailable"
                )

                return
            }

            if (
                activeOperation !=
                    LifecycleOperation.IDLE
            ) {
                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR_MANAGER",
                    status = "SKIPPED",
                    details =
                        "reason=lifecycle_busy; " +
                        "operation=$activeOperation"
                )

                return
            }

            if (
                _state.value !is
                    RemoteControlState.Ready
            ) {
                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR_MANAGER",
                    status = "SKIPPED",
                    details = "reason=engine_not_ready"
                )

                return
            }

            operationGeneration++
            repairGeneration =
                operationGeneration

            activeOperation =
                LifecycleOperation.CLOUDFLARE_REPAIRING

            /*
             * Intentionally do not modify _state, currentLocalUrl,
             * or currentPublicUrl here. Core and Local remain healthy
             * while the public tunnel is being repaired.
             */

            selectedBridge =
                bridgeOrNull
        }

        EventLogger.logStage(
            stage = "CLOUDFLARE_REPAIR_MANAGER",
            status = "REQUESTED",
            details = "generation=$repairGeneration"
        )

        scheduleLifecycleTimeout(
            generation = repairGeneration,
            expectedOperation =
                LifecycleOperation.CLOUDFLARE_REPAIRING,
            timeoutMs = CLOUDFLARE_REPAIR_TIMEOUT_MS,
            stage = "CLOUDFLARE_REPAIR_MANAGER",
            timeoutMessage =
                "Cloudflare public-tunnel repair timed out",
            timeoutStateFactory = {
                RemoteControlState.Ready(
                    localUrl = currentLocalUrl,
                    publicUrl = currentPublicUrl
                )
            }
        )

        val repairCallback =
            object :
                RemoteControlServiceBridge.CloudflareRepairCallback {

                override fun onSuccess(
                    publicUrl: String
                ) {
                    var accepted = false
                    var localUrl: String? = null

                    synchronized(lifecycleLock) {
                        if (
                            operationGeneration !=
                                repairGeneration ||
                            activeOperation !=
                                LifecycleOperation.CLOUDFLARE_REPAIRING
                        ) {
                            accepted = false
                        } else {
                            localUrl =
                                currentLocalUrl

                            currentPublicUrl =
                                publicUrl

                            _state.value =
                                RemoteControlState.Ready(
                                    localUrl = localUrl,
                                    publicUrl = publicUrl
                                )

                            activeOperation =
                                LifecycleOperation.IDLE

                            accepted = true
                        }
                    }

                    if (!accepted) {
                        EventLogger.logStage(
                            stage = "CLOUDFLARE_REPAIR_MANAGER",
                            status = "CANCELLED",
                            details =
                                "generation=$repairGeneration; " +
                                "reason=stale_success_callback"
                        )

                        return
                    }

                    EventLogger.logStage(
                        stage = "CLOUDFLARE_REPAIR_MANAGER",
                        status = "OK",
                        details =
                            "generation=$repairGeneration; " +
                            "localAvailable=${localUrl != null}; " +
                            "publicAvailable=true"
                    )

                    EventLogger.log(
                        "EMAIL",
                        "Automatic links email skipped: reason=CLOUDFLARE_REPAIR"
                    )
                }

                override fun onError(
                    message: String
                ) {
                    val accepted =
                        synchronized(lifecycleLock) {
                            if (
                                operationGeneration !=
                                    repairGeneration ||
                                activeOperation !=
                                    LifecycleOperation.CLOUDFLARE_REPAIRING
                            ) {
                                false
                            } else {
                                /*
                                 * Cloudflare-only repair failed. Core and
                                 * Local are still healthy, so the engine
                                 * remains Ready and existing URLs are
                                 * preserved.
                                 */
                                activeOperation =
                                    LifecycleOperation.IDLE

                                true
                            }
                        }

                    if (!accepted) {
                        EventLogger.logStage(
                            stage = "CLOUDFLARE_REPAIR_MANAGER",
                            status = "CANCELLED",
                            details =
                                "generation=$repairGeneration; " +
                                "reason=stale_error_callback"
                        )

                        return
                    }

                    EventLogger.logStage(
                        stage = "CLOUDFLARE_REPAIR_MANAGER",
                        status = "FAILED",
                        details =
                            "generation=$repairGeneration; " +
                            "reason=${safeLogSummary(message)}"
                    )

                    EventLogger.log(
                        "SERVICE",
                        "Cloudflare repair failed: " +
                            "generation=$repairGeneration, " +
                            "message=$message"
                    )
                }
            }

        var repairDispatched =
            false

        synchronized(bridgeDispatchLock) {
            val stillCurrent =
                synchronized(lifecycleLock) {
                    operationGeneration ==
                        repairGeneration &&
                        activeOperation ==
                            LifecycleOperation.CLOUDFLARE_REPAIRING
                }

            if (stillCurrent) {
                selectedBridge.restartCloudflaredOnly(
                    repairCallback
                )

                repairDispatched =
                    true
            }
        }

        if (!repairDispatched) {
            EventLogger.logStage(
                stage = "CLOUDFLARE_REPAIR_MANAGER",
                status = "CANCELLED",
                details =
                    "generation=$repairGeneration; " +
                    "reason=stale_before_bridge_dispatch"
            )

            return
        }
    }

    fun refresh() {
        val refreshGeneration: Long
        val currentBridge: RemoteControlServiceBridge

        synchronized(lifecycleLock) {
            if (
                activeOperation !=
                    LifecycleOperation.IDLE
            ) {
                return
            }

            val bridgeOrNull =
                bridge ?: return

            refreshGeneration =
                operationGeneration

            currentBridge =
                bridgeOrNull
        }

        currentBridge.isRunning { running ->
            synchronized(lifecycleLock) {
                if (
                    operationGeneration !=
                        refreshGeneration
                ) {
                    return@synchronized
                }

                if (
                    activeOperation !=
                        LifecycleOperation.IDLE
                ) {
                    return@synchronized
                }

                val currentState =
                    _state.value

                when {
                    running &&
                        currentState !is
                            RemoteControlState.Ready -> {

                        val localUrl =
                            currentLocalUrl

                        val publicUrl =
                            currentPublicUrl

                        if (
                            localUrl != null ||
                            publicUrl != null
                        ) {
                            _state.value =
                                RemoteControlState.Ready(
                                    localUrl = localUrl,
                                    publicUrl = publicUrl
                                )
                        }
                    }

                    !running &&
                        currentState is
                            RemoteControlState.Ready -> {

                        _state.value =
                            RemoteControlState.Idle

                        currentLocalUrl =
                            null

                        currentPublicUrl =
                            null
                    }
                }
            }
        }
    }

    fun getCurrentLocalUrl(): String? {
        return synchronized(lifecycleLock) {
            currentLocalUrl
        }
    }

    fun getCurrentPublicUrl(): String? {
        return synchronized(lifecycleLock) {
            currentPublicUrl
        }
    }

    /**
     * Compatibility getter for call sites that have not yet been migrated
     * to Dual-Link. Returns the local URL when available, otherwise the
     * public URL. Dual-Link logic must not depend on this getter.
     */
    fun getCurrentLink(): String? {
        return synchronized(lifecycleLock) {
            currentLocalUrl ?: currentPublicUrl
        }
    }

    suspend fun isAgentRunningBlocking(): Boolean {
        val currentBridge =
            synchronized(lifecycleLock) {
                bridge
            } ?: return false

        val deferred =
            CompletableDeferred<Boolean>()

        currentBridge.isRunning { running ->
            try {
                deferred.complete(running)
            } catch (_: Throwable) {
                // Ignore duplicate or late completions.
            }
        }

        return deferred.await()
    }

    /**
     * Returns whether cloudflared itself is currently running.
     *
     * This is intentionally separate from isAgentRunningBlocking().
     * Guardian distinguishes:
     *  - Core unhealthy (Signaling + Agent).
     *  - Core healthy but Cloudflare unhealthy.
     */
    suspend fun isCloudflaredRunningBlocking(): Boolean {
        val currentBridge =
            synchronized(lifecycleLock) {
                bridge
            } ?: return false

        val deferred =
            CompletableDeferred<Boolean>()

        currentBridge.isCloudflaredRunning { running ->
            try {
                deferred.complete(running)
            } catch (_: Throwable) {
                // Ignore duplicate or late completions.
            }
        }

        return deferred.await()
    }

    fun isLifecycleBusy(): Boolean {
        return synchronized(lifecycleLock) {
            activeOperation !=
                LifecycleOperation.IDLE
        }
    }

    private fun isCurrentOperation(
        generation: Long,
        expectedOperation: LifecycleOperation
    ): Boolean {
        return synchronized(lifecycleLock) {
            operationGeneration ==
                generation &&
                activeOperation ==
                    expectedOperation
        }
    }

    private fun checkShizukuReadiness():
        ShizukuReadiness {
        val binderReady =
            try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                return ShizukuReadiness.BINDER_UNAVAILABLE
            }

        if (!binderReady) {
            return ShizukuReadiness.BINDER_UNAVAILABLE
        }

        return try {
            val permission =
                Shizuku.checkSelfPermission()

            if (
                permission ==
                PackageManager.PERMISSION_GRANTED
            ) {
                ShizukuReadiness.READY
            } else {
                ShizukuReadiness.PERMISSION_DENIED
            }
        } catch (_: Throwable) {
            ShizukuReadiness.BINDER_UNAVAILABLE
        }
    }

    private suspend fun awaitShizukuReadyForAutoStart(
        generation: Long,
        reason: StartReason
    ): ShizukuReadiness {
        var attempt = 0

        while (
            attempt <
            SHIZUKU_AUTO_START_MAX_ATTEMPTS
        ) {
            attempt++

            val readiness =
                checkShizukuReadiness()

            if (
                readiness ==
                ShizukuReadiness.READY
            ) {
                EventLogger.logStage(
                    stage = "SHIZUKU_READY",
                    status = "OK",
                    details =
                        "generation=$generation; " +
                        "reason=${reason.label}; " +
                        "attempt=$attempt"
                )

                return readiness
            }

            if (
                readiness ==
                ShizukuReadiness.PERMISSION_DENIED
            ) {
                EventLogger.logStage(
                    stage = "SHIZUKU_READY",
                    status = "FAILED",
                    details =
                        "generation=$generation; " +
                        "reason=${reason.label}; " +
                        "attempt=$attempt; " +
                        "result=permission_denied"
                )

                return readiness
            }

            // BINDER_UNAVAILABLE
            if (
                attempt >=
                SHIZUKU_AUTO_START_MAX_ATTEMPTS
            ) {
                EventLogger.logStage(
                    stage = "SHIZUKU_READY",
                    status = "FAILED",
                    details =
                        "generation=$generation; " +
                        "reason=${reason.label}; " +
                        "attempt=$attempt; " +
                        "result=binder_unavailable"
                )

                return readiness
            }

            if (!isCurrentOperation(
                    generation = generation,
                    expectedOperation =
                        LifecycleOperation.STARTING
                )
            ) {
                EventLogger.logStage(
                    stage = "SHIZUKU_READY",
                    status = "CANCELLED",
                    details =
                        "generation=$generation; " +
                        "reason=${reason.label}; " +
                        "attempt=$attempt; " +
                        "stage=before_delay"
                )

                return ShizukuReadiness.BINDER_UNAVAILABLE
            }

            EventLogger.logStage(
                stage = "SHIZUKU_READY",
                status = "RETRY",
                details =
                    "generation=$generation; " +
                    "reason=${reason.label}; " +
                    "attempt=$attempt; " +
                    "delayMs=$SHIZUKU_AUTO_START_RETRY_DELAY_MS"
            )

            delay(
                SHIZUKU_AUTO_START_RETRY_DELAY_MS
            )

            if (!isCurrentOperation(
                    generation = generation,
                    expectedOperation =
                        LifecycleOperation.STARTING
                )
            ) {
                EventLogger.logStage(
                    stage = "SHIZUKU_READY",
                    status = "CANCELLED",
                    details =
                        "generation=$generation; " +
                        "reason=${reason.label}; " +
                        "attempt=$attempt; " +
                        "stage=after_delay"
                )

                return ShizukuReadiness.BINDER_UNAVAILABLE
            }
        }

        return ShizukuReadiness.BINDER_UNAVAILABLE
    }

    private fun finishStartWithError(
        generation: Long,
        message: String
    ) {
        val accepted =
            synchronized(lifecycleLock) {
                if (
                    operationGeneration !=
                        generation ||
                    activeOperation !=
                        LifecycleOperation.STARTING
                ) {
                    false
                } else {
                    currentLocalUrl =
                        null

                    currentPublicUrl =
                        null

                    activeOperation =
                        LifecycleOperation.IDLE

                    _state.value =
                        RemoteControlState.Error(
                            message
                        )

                    true
                }
            }

        if (accepted) {
            EventLogger.logStage(
                stage = "START",
                status = "FAILED",
                details =
                    "generation=$generation; " +
                    "reason=${safeLogSummary(message)}"
            )

            EventLogger.log(
                "SERVICE",
                "Start failed: " +
                    "generation=$generation, " +
                    "message=$message"
            )
        } else {
            EventLogger.logStage(
                stage = "START",
                status = "CANCELLED",
                details =
                    "generation=$generation; " +
                    "reason=stale_error_callback"
            )

            EventLogger.log(
                "SERVICE",
                "Start failure ignored: " +
                    "stale generation=$generation"
            )
        }
    }

    private fun safeLogSummary(
        message: String
    ): String {
        return message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(300)
    }

    private fun scheduleLifecycleTimeout(
        generation: Long,
        expectedOperation: LifecycleOperation,
        timeoutMs: Long,
        stage: String,
        timeoutMessage: String,
        timeoutStateFactory: () -> RemoteControlState
    ) {
        if (
            expectedOperation ==
            LifecycleOperation.STARTING
        ) {
            scheduleStartTimeout(
                generation = generation,
                timeoutMs = timeoutMs,
                stage = stage,
                timeoutMessage = timeoutMessage,
                timeoutStateFactory =
                    timeoutStateFactory
            )

            return
        }

        scope.launch {
            delay(timeoutMs)

            var accepted = false

            synchronized(lifecycleLock) {
                if (
                    operationGeneration == generation &&
                    activeOperation == expectedOperation
                ) {
                    operationGeneration++

                    when (expectedOperation) {
                        LifecycleOperation.STOPPING,
                        LifecycleOperation.RESTARTING -> {
                            currentLocalUrl =
                                null

                            currentPublicUrl =
                                null
                        }

                        LifecycleOperation.CLOUDFLARE_REPAIRING -> {
                            /*
                             * Core and Local may still be healthy.
                             * Preserve existing URLs.
                             */
                        }

                        LifecycleOperation.STARTING -> {
                            /*
                             * Handled by scheduleStartTimeout.
                             */
                        }

                        LifecycleOperation.IDLE -> {
                            /*
                             * No lifecycle operation is active.
                             */
                        }
                    }

                    activeOperation =
                        LifecycleOperation.IDLE

                    _state.value =
                        timeoutStateFactory()

                    accepted = true
                }
            }

            if (accepted) {
                EventLogger.logStage(
                    stage = stage,
                    status = "TIMEOUT",
                    details =
                        "generation=$generation; " +
                        "operation=$expectedOperation; " +
                        "timeoutMs=$timeoutMs; " +
                        "reason=${safeLogSummary(timeoutMessage)}"
                )

                EventLogger.log(
                    "SERVICE",
                    "$timeoutMessage: generation=$generation, timeoutMs=$timeoutMs"
                )
            }
        }
    }

    private fun scheduleStartTimeout(
        generation: Long,
        timeoutMs: Long,
        stage: String,
        timeoutMessage: String,
        timeoutStateFactory: () -> RemoteControlState
    ) {
        scope.launch {
            delay(timeoutMs)

            var bridgeForCleanup:
                RemoteControlServiceBridge? = null

            var cleanupGeneration =
                0L

            var accepted =
                false

            synchronized(lifecycleLock) {
                if (
                    operationGeneration == generation &&
                    activeOperation ==
                        LifecycleOperation.STARTING
                ) {
                    operationGeneration++

                    cleanupGeneration =
                        operationGeneration

                    currentLocalUrl =
                        null

                    currentPublicUrl =
                        null

                    /*
                     * Transition to STOPPING rather than IDLE so that
                     * no new Start can begin while the Bridge cleanup
                     * is still running.
                     */
                    activeOperation =
                        LifecycleOperation.STOPPING

                    _state.value =
                        timeoutStateFactory()

                    bridgeForCleanup =
                        bridge

                    accepted =
                        true
                }
            }

            if (!accepted) {
                return@launch
            }

            EventLogger.logStage(
                stage = stage,
                status = "TIMEOUT",
                details =
                    "generation=$generation; " +
                    "operation=STARTING; " +
                    "timeoutMs=$timeoutMs; " +
                    "reason=${safeLogSummary(timeoutMessage)}"
            )

            EventLogger.log(
                "SERVICE",
                "$timeoutMessage: generation=$generation, timeoutMs=$timeoutMs"
            )

            EventLogger.logStage(
                stage = "START_TIMEOUT_CLEANUP",
                status = "STARTED",
                details =
                    "generation=$cleanupGeneration"
            )

            scheduleStartTimeoutCleanupWatchdog(
                cleanupGeneration =
                    cleanupGeneration
            )

            val bridgeRef =
                bridgeForCleanup

            if (bridgeRef == null) {
                var released =
                    false

                synchronized(lifecycleLock) {
                    if (
                        operationGeneration ==
                        cleanupGeneration &&
                        activeOperation ==
                            LifecycleOperation.STOPPING
                    ) {
                        operationGeneration++

                        activeOperation =
                            LifecycleOperation.IDLE

                        released =
                            true
                    }
                }

                if (released) {
                    EventLogger.logStage(
                        stage =
                            "START_TIMEOUT_CLEANUP",
                        status = "OK",
                        details =
                            "generation=$cleanupGeneration; " +
                            "reason=bridge_unavailable"
                    )
                }

                return@launch
            }

            val cleanupCallback =
                object :
                    RemoteControlServiceBridge.SimpleCallback {

                    override fun onComplete(
                        success: Boolean,
                        message: String?
                    ) {
                        var acceptedCleanup =
                            false

                        synchronized(lifecycleLock) {
                            if (
                                operationGeneration ==
                                    cleanupGeneration &&
                                activeOperation ==
                                    LifecycleOperation.STOPPING
                            ) {
                                operationGeneration++

                                activeOperation =
                                    LifecycleOperation.IDLE

                                /*
                                 * Keep the Error state from the
                                 * startup timeout.
                                 */

                                acceptedCleanup =
                                    true
                            }
                        }

                        if (acceptedCleanup) {
                            if (success) {
                                EventLogger.logStage(
                                    stage =
                                        "START_TIMEOUT_CLEANUP",
                                    status = "OK",
                                    details =
                                        "generation=$cleanupGeneration"
                                )
                            } else {
                                EventLogger.logStage(
                                    stage =
                                        "START_TIMEOUT_CLEANUP",
                                    status = "FAILED",
                                    details =
                                        "generation=$cleanupGeneration; " +
                                        "reason=${safeLogSummary(
                                            message ?: "unknown"
                                        )}"
                                )

                                EventLogger.log(
                                    "SERVICE",
                                    "Start timeout cleanup stop failed: " +
                                        (message ?: "No details")
                                )
                            }
                        } else {
                            EventLogger.logStage(
                                stage =
                                    "START_TIMEOUT_CLEANUP",
                                status = "CANCELLED",
                                details =
                                    "generation=$cleanupGeneration; " +
                                    "reason=stale_cleanup_callback"
                            )
                        }
                    }
                }

            synchronized(bridgeDispatchLock) {
                bridgeRef.stop(
                    cleanupCallback
                )
            }
        }
    }

    private fun scheduleStartTimeoutCleanupWatchdog(
        cleanupGeneration: Long
    ) {
        scope.launch {
            delay(STOP_TIMEOUT_MS)

            var accepted =
                false

            synchronized(lifecycleLock) {
                if (
                    operationGeneration ==
                        cleanupGeneration &&
                    activeOperation ==
                        LifecycleOperation.STOPPING
                ) {
                    operationGeneration++

                    activeOperation =
                        LifecycleOperation.IDLE

                    /*
                     * Preserve the Error state produced by the startup
                     * timeout.
                     */

                    accepted =
                        true
                }
            }

            if (accepted) {
                EventLogger.logStage(
                    stage = "START_TIMEOUT_CLEANUP",
                    status = "TIMEOUT",
                    details =
                        "generation=$cleanupGeneration; " +
                        "reason=cleanup_timeout"
                )

                EventLogger.log(
                    "SERVICE",
                    "Start timeout cleanup could not be verified: " +
                        "generation=$cleanupGeneration"
                )
            }
        }
    }

    private fun handleSuccessfulStartEmail(
        appContext: Context,
        reason: StartReason,
        localUrl: String?,
        publicUrl: String?,
        networkType: String
    ) {
        when (reason) {
            StartReason.AUTO_START -> {
                /*
                 * AUTO_START must prove that both access paths were created
                 * before announcing readiness. The Ready email itself is sent
                 * by EmailSender and intentionally contains no access URLs.
                 */
                if (localUrl == null || publicUrl == null) {
                    EventLogger.log(
                        "EMAIL",
                        "Ready email skipped: startup did not produce both links"
                    )
                    return
                }

                scope.launch {
                    try {
                        val sent = EmailSender.sendReadyStatus(
                            networkType = networkType
                        )
                        EventLogger.log(
                            "EMAIL",
                            if (sent) {
                                "Ready status email sent"
                            } else {
                                "Ready status email failed"
                            }
                        )
                    } catch (t: Throwable) {
                        EventLogger.log(
                            "EMAIL",
                            "Ready status email failed with exception: " +
                                (t.message ?: t.javaClass.simpleName)
                        )
                    }
                }
            }

            StartReason.NETWORK_CHANGE,
            StartReason.NETWORK_RECONNECTED -> {
                handleSuccessfulLinks(
                    appContext = appContext,
                    localUrl = localUrl,
                    publicUrl = publicUrl,
                    networkType = networkType
                )
            }

            StartReason.USER_START,
            StartReason.FEATURE_ENABLED,
            StartReason.GUARDIAN_REPAIR -> {
                EventLogger.log(
                    "EMAIL",
                    "Automatic links email skipped: reason=${reason.label}"
                )
            }
        }
    }

    /**
     * Sends the currently active links on an explicit external SEND request.
     * This method never starts, stops, restarts, cleans, or repairs the engine.
     */
    fun sendCurrentLinksOnRequest(
        context: Context
    ) {
        init(context)
        val snapshot = synchronized(lifecycleLock) {
            if (
                activeOperation != LifecycleOperation.IDLE ||
                _state.value !is RemoteControlState.Ready
            ) {
                null
            } else {
                Pair(currentLocalUrl, currentPublicUrl)
            }
        }

        if (snapshot == null) {
            EventLogger.logStage(
                stage = "MANUAL_SEND",
                status = "SKIPPED",
                details = "reason=engine_not_ready"
            )
            return
        }

        val localUrl = snapshot.first
        val publicUrl = snapshot.second
        if (localUrl == null && publicUrl == null) {
            EventLogger.logStage(
                stage = "MANUAL_SEND",
                status = "SKIPPED",
                details = "reason=no_current_links"
            )
            return
        }

        EventLogger.logStage(
            stage = "MANUAL_SEND",
            status = "REQUESTED",
            details =
                "localAvailable=${localUrl != null}; " +
                "publicAvailable=${publicUrl != null}"
        )

        scope.launch {
            try {
                val sent = EmailSender.sendLinks(
                    localUrl = localUrl,
                    publicUrl = publicUrl,
                    networkType = "Manual SEND"
                )
                EventLogger.logStage(
                    stage = "MANUAL_SEND",
                    status = if (sent) "OK" else "FAILED"
                )
            } catch (t: Throwable) {
                EventLogger.logStage(
                    stage = "MANUAL_SEND",
                    status = "FAILED",
                    details = "reason=${safeLogSummary(t.message ?: t.javaClass.simpleName)}"
                )
            }
        }
    }

    private fun handleSuccessfulLinks(
        appContext: Context,
        localUrl: String?,
        publicUrl: String?,
        networkType: String
    ) {
        val prefs =
            appContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val lastLocalUrl =
            prefs.getString(
                KEY_LAST_LOCAL_URL,
                null
            )

        val lastPublicUrl =
            prefs.getString(
                KEY_LAST_PUBLIC_URL,
                null
            )

        val linksUnchanged =
            localUrl == lastLocalUrl &&
                publicUrl == lastPublicUrl

        if (linksUnchanged) {
            EventLogger.log(
                "EMAIL",
                "Links unchanged, skipping email"
            )

            return
        }

        EventLogger.log(
            "EMAIL",
            "Sending new links to email: " +
                "localAvailable=${localUrl != null}, " +
                "publicAvailable=${publicUrl != null}"
        )

        scope.launch {
            try {
                val sent =
                    EmailSender.sendLinks(
                        localUrl = localUrl,
                        publicUrl = publicUrl,
                        networkType = networkType
                    )

                if (!sent) {
                    EventLogger.log(
                        "EMAIL",
                        "Links email failed; " +
                            "keeping previous sent-link state for retry"
                    )

                    return@launch
                }

                val linksStillCurrent =
                    synchronized(lifecycleLock) {
                        currentLocalUrl == localUrl &&
                            currentPublicUrl == publicUrl
                    }

                if (!linksStillCurrent) {
                    EventLogger.log(
                        "EMAIL",
                        "Sent-link state update skipped: " +
                            "links are no longer current"
                    )

                    return@launch
                }

                val editor =
                    prefs.edit()

                if (localUrl != null) {
                    editor.putString(
                        KEY_LAST_LOCAL_URL,
                        localUrl
                    )
                } else {
                    editor.remove(
                        KEY_LAST_LOCAL_URL
                    )
                }

                if (publicUrl != null) {
                    editor.putString(
                        KEY_LAST_PUBLIC_URL,
                        publicUrl
                    )
                } else {
                    editor.remove(
                        KEY_LAST_PUBLIC_URL
                    )
                }

                editor.apply()

                EventLogger.log(
                    "EMAIL",
                    "Links email sent; sent-link state updated"
                )
            } catch (t: Throwable) {
                EventLogger.log(
                    "EMAIL",
                    "Links email failed with exception; " +
                        "keeping previous sent-link state for retry: " +
                        (t.message
                            ?: t.javaClass.simpleName)
                )
            }
        }
    }

    private suspend fun copyAssetsToStaging(
        context: Context
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val staging =
                File(
                    context.filesDir,
                    "cloudphone-staging"
                )

            if (staging.exists()) {
                staging.deleteRecursively()
            }

            if (!staging.mkdirs() &&
                !staging.isDirectory
            ) {
                throw IllegalStateException(
                    "Unable to create staging directory"
                )
            }

            val assetManager =
                context.assets

            val root =
                RemoteControlConfig.ASSETS_ROOT

            copyAssetFile(
                assetManager,
                "$root/cloudphone-agent",
                File(
                    staging,
                    "cloudphone-agent"
                )
            )

            copyAssetFile(
                assetManager,
                "$root/webrtc-signaling",
                File(
                    staging,
                    "webrtc-signaling"
                )
            )

            copyAssetFile(
                assetManager,
                "$root/libsys_core.so",
                File(
                    staging,
                    "libsys_core.so"
                )
            )

            copyAssetFile(
                assetManager,
                "$root/cloudflared",
                File(
                    staging,
                    "cloudflared"
                )
            )

            val certsDir =
                File(
                    staging,
                    "certs"
                )

            if (!certsDir.mkdirs() &&
                !certsDir.isDirectory
            ) {
                throw IllegalStateException(
                    "Unable to create certs staging directory"
                )
            }

            copyAssetDir(
                assetManager,
                "$root/certs",
                certsDir
            )

            val webAssetsDir =
                File(
                    staging,
                    "assets"
                )

            if (!webAssetsDir.mkdirs() &&
                !webAssetsDir.isDirectory
            ) {
                throw IllegalStateException(
                    "Unable to create web-assets staging directory"
                )
            }

            copyAssetDir(
                assetManager,
                "$root/assets",
                webAssetsDir
            )

            true
        } catch (t: Throwable) {
            EventLogger.log(
                "SERVICE",
                "Failed to prepare staging directory: " +
                    (t.message
                        ?: t.javaClass.simpleName)
            )

            false
        }
    }

    private fun copyAssetFile(
        assetManager:
            android.content.res.AssetManager,
        assetPath: String,
        destination: File
    ) {
        assetManager.open(
            assetPath
        ).use { input ->

            FileOutputStream(
                destination
            ).use { output ->

                input.copyTo(
                    output
                )
            }
        }
    }

    private fun copyAssetDir(
        assetManager:
            android.content.res.AssetManager,
        assetDir: String,
        destinationDir: File
    ) {
        val children =
            assetManager.list(
                assetDir
            ) ?: emptyArray()

        if (children.isEmpty()) {
            copyAssetFile(
                assetManager,
                assetDir,
                destinationDir
            )

            return
        }

        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }

        for (child in children) {
            val childAsset =
                "$assetDir/$child"

            val childDestination =
                File(
                    destinationDir,
                    child
                )

            val grandChildren =
                assetManager.list(
                    childAsset
                ) ?: emptyArray()

            if (grandChildren.isEmpty()) {
                copyAssetFile(
                    assetManager,
                    childAsset,
                    childDestination
                )
            } else {
                if (!childDestination.exists()) {
                    childDestination.mkdirs()
                }

                copyAssetDir(
                    assetManager,
                    childAsset,
                    childDestination
                )
            }
        }
    }

    enum class StartReason(
        val label: String
    ) {
        AUTO_START("AUTO_START"),
        USER_START("USER_START"),
        FEATURE_ENABLED("FEATURE_ENABLED"),
        NETWORK_CHANGE("NETWORK_CHANGE"),
        NETWORK_RECONNECTED("NETWORK_RECONNECTED"),
        GUARDIAN_REPAIR("GUARDIAN_REPAIR")
    }

    private enum class LifecycleOperation {
        IDLE,
        STARTING,
        STOPPING,
        RESTARTING,
        CLOUDFLARE_REPAIRING
    }

    private enum class ShizukuReadiness {
        READY,
        BINDER_UNAVAILABLE,
        PERMISSION_DENIED
    }

    private enum class RestartCallbackAction {
        IGNORE,
        START,
        STOP_FAILED,
        CONVERTED_STOP_OK,
        CONVERTED_STOP_FAILED
    }
}