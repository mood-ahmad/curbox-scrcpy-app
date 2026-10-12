package neth.iecal.curbox.remotecontrol

import android.content.Context
import neth.iecal.curbox.utils.ShizukuRunner
import java.io.File

/**
 * Bridge between CurBox and the Scrcpy/WebRTC agent running via Shizuku.
 *
 * Android 11+ blocks the shell user from reading /Android/data, so we use
 * "run-as" to read files with the app's own uid and pipe them via tar to
 * /data/local/tmp/android, which the shell user can write to.
 */
class RemoteControlServiceBridge(private val context: Context) {

    interface StartCallback {
        fun onSuccess(link: String)
        fun onError(message: String)
    }

    interface SimpleCallback {
        fun onComplete(success: Boolean, message: String?)
    }

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

    fun stop(callback: SimpleCallback) {
        stopInternal { success ->
            callback.onComplete(success, if (success) null else "pkill reported an error")
        }
    }

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
        ShizukuRunner.executeCommand(
            buildCopyScript(),
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
     * Uses "run-as" to read the app's own files (bypasses the Android 11+
     * restriction on /Android/data) and pipes them to /data/local/tmp/android
     * via tar, preserving the full directory structure.
     */
    private fun buildCopyScript(): String {
        val staging = stagingDir().absolutePath
        val target = RemoteControlConfig.WORK_DIR
        val pkg = context.packageName

        return """
            set -e
            rm -rf $target
            mkdir -p $target
            run-as $pkg sh -c "cd $staging && tar cf - ." | (cd $target && tar xf -)
            chmod +x $target/cloudphone-agent
            chmod +x $target/webrtc-signaling
            echo COPY_OK
        """.trimIndent()
    }

    private fun launchServices(callback: StartCallback) {
        val accumulated = StringBuilder()

        ShizukuRunner.executeCommand(
            buildLaunchScript(),
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    accumulated.append(output)
                    if (done) {
                        val fullOutput = accumulated.toString()
                        val link = extractLink(fullOutput)
                        if (link != null) {
                            callback.onSuccess(link)
                        } else {
                            callback.onError("Service started but no link found:\n${fullOutput.take(500)}")
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

    private fun stagingDir(): File =
        context.getExternalFilesDir("cloudphone-staging")
            ?: File(context.filesDir, "cloudphone-staging")
}
