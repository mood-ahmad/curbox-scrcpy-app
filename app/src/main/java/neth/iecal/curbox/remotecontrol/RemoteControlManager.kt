package neth.iecal.curbox.remotecontrol

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * High level orchestrator for the Scrcpy remote control feature.
 *
 * Responsibilities:
 * - Extract bundled assets into a staging directory accessible by the shell user
 *   (external files dir) so Shizuku can copy them.
 * - Delegate start/stop to [RemoteControlServiceBridge].
 * - Send the connection link by email automatically on success.
 * - Expose the current state as a [StateFlow] for the UI.
 */
object RemoteControlManager {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _state = MutableStateFlow<RemoteControlState>(RemoteControlState.Idle)
    val state: StateFlow<RemoteControlState> = _state.asStateFlow()

    private var bridge: RemoteControlServiceBridge? = null
    private var currentLink: String? = null

    /**
     * Initializes the manager with an application context.
     * Safe to call multiple times.
     */
    fun init(context: Context) {
        if (bridge == null) {
            bridge = RemoteControlServiceBridge(context.applicationContext)
        }
    }

    /**
     * Starts the remote control service:
     * 1. Copy assets to staging dir (external, so Shizuku can read).
     * 2. Launch signaling + agent via Shizuku.
     * 3. Send the link by email.
     */
    fun start(context: Context) {
        init(context)
        val appContext = context.applicationContext

        if (_state.value is RemoteControlState.Starting ||
            _state.value is RemoteControlState.Ready
        ) {
            return
        }

        _state.value = RemoteControlState.Starting

        scope.launch {
            try {
                // 1. Extract assets to staging
                val staged = copyAssetsToStaging(appContext)
                if (!staged) {
                    _state.value = RemoteControlState.Error("Failed to extract bundled files")
                    return@launch
                }

                // 2. Start service
                bridge?.start(object : RemoteControlServiceBridge.StartCallback {
                    override fun onSuccess(link: String) {
                        currentLink = link
                        _state.value = RemoteControlState.Ready(link)

                        // 3. Send email automatically
                        scope.launch {
                            EmailSender.sendLink(link)
                        }
                    }

                    override fun onError(message: String) {
                        _state.value = RemoteControlState.Error(message)
                    }
                })
            } catch (t: Throwable) {
                _state.value = RemoteControlState.Error(t.message ?: "Unexpected error")
            }
        }
    }

    /**
     * Stops the running service and clears the link.
     */
    fun stop() {
        val b = bridge ?: return
        if (_state.value is RemoteControlState.Idle ||
            _state.value is RemoteControlState.Stopping
        ) {
            return
        }
        _state.value = RemoteControlState.Stopping
        b.stop(object : RemoteControlServiceBridge.SimpleCallback {
            override fun onComplete(success: Boolean, message: String?) {
                currentLink = null
                _state.value = RemoteControlState.Idle
            }
        })
    }

    /**
     * Refreshes state by checking whether the agent is running.
     */
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
                    // If we don't have a link but service is running, leave state as-is.
                }
                !running && current is RemoteControlState.Ready -> {
                    _state.value = RemoteControlState.Idle
                    currentLink = null
                }
            }
        }
    }

    /**
     * Returns the current active link or null if none.
     */
    fun getCurrentLink(): String? = currentLink

    // -------------------------------------------------------------------------
    // Asset extraction
    // -------------------------------------------------------------------------

    private suspend fun copyAssetsToStaging(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // IMPORTANT: use the external files dir so the Shizuku (shell)
                // user can read the staged files. The internal files dir is
                // not accessible from the shell process.
                val staging = context.getExternalFilesDir("cloudphone-staging")
                    ?: File(context.filesDir, "cloudphone-staging")

                if (staging.exists()) staging.deleteRecursively()
                staging.mkdirs()

                val assetManager = context.assets
                val root = RemoteControlConfig.ASSETS_ROOT

                copyAssetFile(assetManager, "$root/cloudphone-agent", File(staging, "cloudphone-agent"))
                copyAssetFile(assetManager, "$root/webrtc-signaling", File(staging, "webrtc-signaling"))
                copyAssetFile(assetManager, "$root/libsys_core.so", File(staging, "libsys_core.so"))

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
            // It's a file
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
