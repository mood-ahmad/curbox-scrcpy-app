package neth.iecal.curbox.remotecontrol

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object RemoteControlManager {

    private const val PREFS_NAME =
        "remote_control_prefs"

    private const val KEY_ENABLED =
        "enabled"

    private const val KEY_LAST_LOCAL_URL =
        "last_local_url"

    private const val KEY_LAST_PUBLIC_URL =
        "last_public_url"

    private val scope =
        CoroutineScope(Dispatchers.IO)

    private val lifecycleLock =
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
            start(
                context = context,
                reason = StartReason.AUTO_START
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

        EventLogger.log(
            "SERVICE",
            "Start requested: " +
                "reason=${reason.label}, " +
                "generation=$startGeneration"
        )

        scope.launch {
            try {
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
                    EventLogger.log(
                        "SERVICE",
                        "Staging result ignored: " +
                            "stale generation=$startGeneration"
                    )

                    return@launch
                }

                if (!staged) {
                    finishStartWithError(
                        generation = startGeneration,
                        message =
                            "Failed to extract bundled files"
                    )

                    return@launch
                }

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

                currentBridge.start(
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
                                EventLogger.log(
                                    "SERVICE",
                                    "Start success ignored: " +
                                        "stale generation=" +
                                        startGeneration
                                )

                                return
                            }

                            EventLogger.log(
                                "SERVICE",
                                "Started successfully: " +
                                    "reason=${reason.label}, " +
                                    "network=$networkType, " +
                                    "generation=$startGeneration, " +
                                    "localAvailable=${localUrl != null}, " +
                                    "publicAvailable=${publicUrl != null}"
                            )

                            handleSuccessfulLinks(
                                appContext = appContext,
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
                )
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

        val currentBridge:
            RemoteControlServiceBridge

        val restartGeneration:
            Long

        synchronized(lifecycleLock) {
            val selectedBridge =
                bridge

            if (selectedBridge == null) {
                EventLogger.log(
                    "SERVICE",
                    "Restart converted to start: " +
                        "bridge unavailable, " +
                        "reason=${reason.label}"
                )

                activeOperation =
                    LifecycleOperation.IDLE

                start(
                    context = appContext,
                    reason = reason
                )

                return
            }

            if (
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
            }

            operationGeneration++
            restartGeneration =
                operationGeneration

            activeOperation =
                LifecycleOperation.RESTARTING

            _state.value =
                RemoteControlState.Stopping

            currentBridge =
                selectedBridge
        }

        EventLogger.log(
            "SERVICE",
            "Restart requested: " +
                "reason=${reason.label}, " +
                "generation=$restartGeneration"
        )

        currentBridge.stop(
            object :
                RemoteControlServiceBridge.SimpleCallback {

                override fun onComplete(
                    success: Boolean,
                    message: String?
                ) {
                    val shouldRestart =
                        synchronized(
                            lifecycleLock
                        ) {
                            if (
                                operationGeneration !=
                                    restartGeneration
                            ) {
                                false
                            } else {
                                val restartStillRequested =
                                    activeOperation ==
                                        LifecycleOperation.RESTARTING

                                currentLocalUrl =
                                    null

                                currentPublicUrl =
                                    null

                                _state.value =
                                    RemoteControlState.Idle

                                activeOperation =
                                    LifecycleOperation.IDLE

                                restartStillRequested
                            }
                        }

                    if (!shouldRestart) {
                        EventLogger.log(
                            "SERVICE",
                            "Restart continuation skipped: " +
                                "generation=$restartGeneration"
                        )

                        return
                    }

                    if (!success) {
                        EventLogger.log(
                            "SERVICE",
                            "Restart stop phase reported failure: " +
                                (message ?: "No details")
                        )

                        synchronized(lifecycleLock) {
                            _state.value =
                                RemoteControlState.Error(
                                    message
                                        ?: "Failed to stop engine before restart"
                                )
                        }

                        return
                    }

                    EventLogger.log(
                        "SERVICE",
                        "Restart stop phase completed: " +
                            "generation=$restartGeneration"
                    )

                    start(
                        context = appContext,
                        reason = reason
                    )
                }
            }
        )
    }

    fun stop() {
        val currentBridge:
            RemoteControlServiceBridge

        val stopGeneration:
            Long

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
             */
            if (
                activeOperation ==
                    LifecycleOperation.RESTARTING
            ) {
                activeOperation =
                    LifecycleOperation.STOPPING

                EventLogger.log(
                    "SERVICE",
                    "Pending restart cancelled; " +
                        "stop will complete normally"
                )

                return
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
        }

        EventLogger.log(
            "SERVICE",
            "Stop requested: " +
                "generation=$stopGeneration"
        )

        currentBridge.stop(
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
                                    stopGeneration ||
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
                                "stale generation=$stopGeneration"
                        )

                        return
                    }

                    if (success) {
                        EventLogger.log(
                            "SERVICE",
                            "Stopped successfully: " +
                                "generation=$stopGeneration"
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
        )
    }

    fun refresh() {
        val currentBridge =
            synchronized(lifecycleLock) {
                if (
                    activeOperation !=
                        LifecycleOperation.IDLE
                ) {
                    null
                } else {
                    bridge
                }
            } ?: return

        currentBridge.isRunning { running ->
            synchronized(lifecycleLock) {
                if (
                    activeOperation !=
                        LifecycleOperation.IDLE
                ) {
                    return@isRunning
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
            if (!deferred.isCompleted) {
                deferred.complete(running)
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
            EventLogger.log(
                "SERVICE",
                "Start failed: " +
                    "generation=$generation, " +
                    "message=$message"
            )
        } else {
            EventLogger.log(
                "SERVICE",
                "Start failure ignored: " +
                    "stale generation=$generation"
            )
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
        GUARDIAN_REPAIR("GUARDIAN_REPAIR")
    }

    private enum class LifecycleOperation {
        IDLE,
        STARTING,
        STOPPING,
        RESTARTING
    }
}
