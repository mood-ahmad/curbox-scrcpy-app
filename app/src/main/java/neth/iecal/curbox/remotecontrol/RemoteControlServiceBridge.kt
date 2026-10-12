package neth.iecal.curbox.remotecontrol

import android.content.Context
import neth.iecal.curbox.utils.ShizukuRunner
import java.io.File

/**
 * Bridge between CurBox and the Scrcpy/WebRTC agent running via Shizuku.
 *
 * Responsibilities:
 * - Copy binaries from app assets to /data/local/tmp/android (executable location).
 * - Start the signaling server and the agent.
 * - Parse the connection link from setup output.
 * - Stop all running processes cleanly.
 */
class RemoteControlServiceBridge(private val context: Context) {

    interface StartCallback {
        fun onSuccess(link: String)
        fun onError(message: String)
    }

    interface SimpleCallback {
        fun onComplete(success: Boolean, message: String?)
    }

    /**
     * Starts the remote control service.
     *
     * Steps:
     * 1. Prepare /data/local/tmp/android with binaries from assets.
     * 2. Kill any existing processes.
     * 3. Launch signaling server.
     * 4. Launch agent.
     * 5. Detect local IP and build the link.
     */
    fun start(callback: StartCallback) {
        prepareWorkDir { prepOk, prepError ->
            if (!prepOk) {
                callback.onError(prepError ?: "Failed to prepare working directory")
                return@prepareWorkDir
            }
            stopInternal {
                launchServices(callback)
            }
        }
    }

    /**
     * Stops all running Scrcpy/WebRTC processes.
     */
    fun stop(callback: SimpleCallback) {
        stopInternal { success ->
            callback.onComplete(success, if (success) null else "pkill reported an error")
        }
    }

    /**
     * Checks whether the agent process is currently running.
     */
    fun isRunning(callback: (Boolean) -> Unit) {
        ShizukuRunner.executeCommand(
            "pgrep -f cloudphone-agent > /dev/null && echo RUNNING || echo STOPPED",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) callback(output.trim().contains("RUNNING"))
                }
                override fun onCommandError(error: String) {
                    callback(false)
                }
            },
            lineBundle = 100
        )
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun prepareWorkDir(callback: (Boolean, String?) -> Unit) {
        val script = buildCopyScript()
        ShizukuRunner.executeCommand(
            script,
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) callback(true, null)
                }
                override fun onCommandError(error: String) {
                    callback(false, error.take(500))
                }
            },
            lineBundle = 1000
        )
    }

    /**
     * Builds a shell script that:
     * - Creates /data/local/tmp/android and subdirs.
     * - Copies every required item from the APK's native library path or asset
     *   staging area into that directory.
     *
     * Note: assets are first extracted to filesDir via copyAssetsToFiles first.
     */
    private fun buildCopyScript(): String {
        val staging = stagingDir().absolutePath
        val target = RemoteControlConfig.WORK_DIR

        return """
            set -e
            mkdir -p $target
            mkdir -p $target/certs
            mkdir -p $target/assets
            cp -f $staging/cloudphone-agent $target/cloudphone-agent
            cp -f $staging/webrtc-signaling $target/webrtc-signaling
            cp -f $staging/libsys_core.so $target/libsys_core.so
            cp -f $staging/certs/server.crt $target/certs/server.crt
            cp -f $staging/certs/server.key $target/certs/server.key
            cp -f $staging/certs/server.p12 $target/certs/server.p12
            cp -f $staging/certs/trusted.pem $target/certs/trusted.pem
            cp -rf $staging/assets/. $target/assets/
            chmod +x $target/cloudphone-agent
            chmod +x $target/webrtc-signaling
            echo COPY_OK
        """.trimIndent()
    }

    private fun launchServices(callback: StartCallback) {
        val script = buildLaunchScript()
        val accumulated = StringBuilder()

        ShizukuRunner.executeCommand(
            script,
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    accumulated.append(output)
                    if (done) {
                        val fullOutput = accumulated.toString()
                        val link = extractLink(fullOutput)
                        if (link != null) {
                            callback.onSuccess(link)
                        } else {
                            callback.onError("Service started but no link was found in output:\n${fullOutput.take(500)}")
                        }
                    }
                }
                override fun onCommandError(error: String) {
                    callback.onError(error.take(500))
                }
            },
            lineBundle = 1000
        )
    }

    private fun buildLaunchScript(): String {
        val target = RemoteControlConfig.WORK_DIR
        val port = RemoteControlConfig.SIGNALING_PORT
        val agentId = RemoteControlConfig.AGENT_ID
        val signalingUrl = RemoteControlConfig.AGENT_SIGNALING_URL
        val agentJar = RemoteControlConfig.AGENT_JAR

        return """
            cd $target
            export CLASSPATH=$target/libsys_core.so
            chmod +x webrtc-signaling
            chmod +x cloudphone-agent
            nohup ./webrtc-signaling -port $port -assets ./assets -cert ./certs/server.crt -key ./certs/server.key > signaling.log 2>&1 &
            sleep 2
            export CP_AGENT_ID="$agentId"
            export CP_AGENT_SIGNALING="$signalingUrl"
            export CP_AGENT_JAR="$agentJar"
            setsid nohup env GODEBUG=asyncpreemptoff=1 ./cloudphone-agent > agent.log 2>&1 &
            sleep 3
            IP=${'$'}(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="src") print ${'$'}(i+1)}')
            if [ -z "${'$'}IP" ]; then
                IP=${'$'}(ifconfig wlan0 2>/dev/null | grep 'inet ' | awk '{print ${'$'}2}' | sed 's/addr://')
            fi
            echo "LINK=https://${'$'}IP:$port"
        """.trimIndent()
    }

    private fun stopInternal(callback: (Boolean) -> Unit) {
        ShizukuRunner.executeCommand(
            "pkill -f webrtc-signaling; pkill -f cloudphone-agent; echo DONE",
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) callback(true)
                }
                override fun onCommandError(error: String) {
                    // pkill returns non-zero if no process matched, which is fine.
                    callback(true)
                }
            },
            lineBundle = 100
        )
    }

    private fun extractLink(output: String): String? {
        return output.lineSequence()
            .firstOrNull { it.startsWith("LINK=") }
            ?.removePrefix("LINK=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun stagingDir(): File = File(context.filesDir, "cloudphone-staging")
}
