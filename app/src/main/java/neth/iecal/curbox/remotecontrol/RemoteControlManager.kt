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

    private const val PREFS_NAME = "remote_control_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_LINK = "last_link"

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableStateFlow<RemoteControlState>(RemoteControlState.Idle)
    val state: StateFlow<RemoteControlState> = _state.asStateFlow()

    private var bridge: RemoteControlServiceBridge? = null
    private var currentLink: String? = null
    private var isStarting = false

    fun init(context: Context) {
        if (bridge == null) {
            bridge = RemoteControlServiceBridge(context.applicationContext)
        }
        EventLogger.init(context)
    }

    fun autoStartIfEnabled(context: Context) {
        init(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_ENABLED, true)
        if (enabled) {
            start(context)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            stop()
        } else {
            start(context)
        }
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun start(context: Context) {
        init(context)
        val appContext = context.applicationContext

        if (isStarting ||
            _state.value is RemoteControlState.Starting ||
            _state.value is RemoteControlState.Ready
        ) {
            return
        }

        isStarting = true
        _state.value = RemoteControlState.Starting
        EventLogger.log("SERVICE", "Start requested")

        scope.launch {
            try {
                val staged = copyAssetsToStaging(appContext)
                if (!staged) {
                    _state.value = RemoteControlState.Error("Failed to extract bundled files")
                    EventLogger.log("SERVICE", "Failed to extract assets")
                    isStarting = false
                    return@launch
                }

                bridge?.start(object : RemoteControlServiceBridge.StartCallback {
                    override fun onSuccess(link: String) {
                        currentLink = link
                        _state.value = RemoteControlState.Ready(link)
                        EventLogger.log("SERVICE", "Started successfully: $link")

                        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val lastLink = prefs.getString(KEY_LAST_LINK, null)
                        prefs.edit().putString(KEY_LAST_LINK, link).apply()

                        if (link != lastLink) {
                            EventLogger.log("EMAIL", "Sending new link to email")
                            scope.launch {
                                EmailSender.sendLink(link)
                            }
                        } else {
                            EventLogger.log("EMAIL", "Link unchanged, skipping email")
                        }
                        isStarting = false
                    }

                    override fun onError(message: String) {
                        _state.value = RemoteControlState.Error(message)
                        EventLogger.log("SERVICE", "Start failed: $message")
                        isStarting = false
                    }
                })
            } catch (t: Throwable) {
                _state.value = RemoteControlState.Error(t.message ?: "Unexpected error")
                EventLogger.log("SERVICE", "Exception: ${t.message}")
                isStarting = false
            }
        }
    }

    /**
     * Stops and restarts the service. Used when the network changes.
     */
    fun restart(context: Context) {
        val b = bridge
        if (b == null) {
            init(context)
            start(context)
            return
        }
        EventLogger.log("SERVICE", "Restart requested (network changed)")
        _state.value = RemoteControlState.Stopping
        b.stop(object : RemoteControlServiceBridge.SimpleCallback {
            override fun onComplete(success: Boolean, message: String?) {
                currentLink = null
                _state.value = RemoteControlState.Idle
                start(context)
            }
        })
    }

    fun stop() {
        val b = bridge ?: return
        if (_state.value is RemoteControlState.Idle ||
            _state.value is RemoteControlState.Stopping
        ) {
            return
        }
        _state.value = RemoteControlState.Stopping
        EventLogger.log("SERVICE", "Stop requested")
        b.stop(object : RemoteControlServiceBridge.SimpleCallback {
            override fun onComplete(success: Boolean, message: String?) {
                currentLink = null
                _state.value = RemoteControlState.Idle
                EventLogger.log("SERVICE", "Stopped")
            }
        })
    }

    fun refresh() {
        val b = bridge ?: return
        b.isRunning { running ->
            val current = _state.value
            when {
                running && current !is RemoteControlState.Ready -> {
                    val link = currentLink
                    if (link != null) {
                        _state.value = RemoteControlState.Ready(link)
                    }
                }
                !running && current is RemoteControlState.Ready -> {
                    _state.value = RemoteControlState.Idle
                    currentLink = null
                }
            }
        }
    }

    fun getCurrentLink(): String? = currentLink

    suspend fun isAgentRunningBlocking(): Boolean {
        val b = bridge ?: return false
        val deferred = CompletableDeferred<Boolean>()
        b.isRunning { running ->
            deferred.complete(running)
        }
        return deferred.await()
    }

    private suspend fun copyAssetsToStaging(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val staging = File(context.filesDir, "cloudphone-staging")
                if (staging.exists()) staging.deleteRecursively()
                staging.mkdirs()

                val assetManager = context.assets
                val root = RemoteControlConfig.ASSETS_ROOT

                copyAssetFile(assetManager, "$root/cloudphone-agent", File(staging, "cloudphone-agent"))
                copyAssetFile(assetManager, "$root/webrtc-signaling", File(staging, "webrtc-signaling"))
                copyAssetFile(assetManager, "$root/libsys_core.so", File(staging, "libsys_core.so"))
                copyAssetFile(assetManager, "$root/bore", File(staging, "bore"))

                val certsDir = File(staging, "certs").apply { mkdirs() }
                copyAssetDir(assetManager, "$root/certs", certsDir)

                val webAssetsDir = File(staging, "assets").apply { mkdirs() }
                copyAssetDir(assetManager, "$root/assets", webAssetsDir)

                true
            } catch (t: Throwable) {
                t.printStackTrace()
                false
            }
        }

    private fun copyAssetFile(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        dest: File
    ) {
        assetManager.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copyAssetDir(
        assetManager: android.content.res.AssetManager,
        assetDir: String,
        destDir: File
    ) {
        val children = assetManager.list(assetDir) ?: emptyArray()
        if (children.isEmpty()) {
            copyAssetFile(assetManager, assetDir, destDir)
            return
        }
        destDir.mkdirs()
        for (child in children) {
            val childAsset = "$assetDir/$child"
            val childDest = File(destDir, child)
            val grandChildren = assetManager.list(childAsset) ?: emptyArray()
            if (grandChildren.isEmpty()) {
                copyAssetFile(assetManager, childAsset, childDest)
            } else {
                childDest.mkdirs()
                copyAssetDir(assetManager, childAsset, childDest)
            }
        }
    }
}
