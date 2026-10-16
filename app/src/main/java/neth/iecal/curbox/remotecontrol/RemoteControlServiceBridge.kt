package neth.iecal.curbox.remotecontrol

import android.content.Context
import neth.iecal.curbox.utils.ShizukuRunner
import java.io.File

/**
 * Bridge between CurBox and the Scrcpy/WebRTC agent running via Shizuku.
 *
 * Uses "run-as" to copy files from the app's private dir to /data/local/tmp.
 *
 * Network strategy:
 *  - WiFi:   the link is the local IP (https://192.168.x.x:8443) — same as original Scrcpy.
 *  - Mobile: the local IP is behind CGNAT and unreachable, so we start a
 *            cloudflared Quick Tunnel to expose the signaling server publicly.
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
            chmod +x $target/cloudflared
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
                            callback.onError("Service started but no link found:\n${fullOutput.take(800)}")
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
        val signalingUrl = "wss://127.0.0.1:$port"
        val agentJar = RemoteControlConfig.AGENT_JAR

        return """
            cd $target
            export CLASSPATH=$target/libsys_core.so

            # --- 1) Start signaling server (HTTPS on port 8443)
            chmod +x webrtc-signaling
            nohup ./webrtc-signaling -port $port -assets ./assets -cert ./certs/server.crt -key ./certs/server.key > signaling.log 2>&1 &
            sleep 3

            # --- 2) Start agent
            chmod +x cloudphone-agent
            export CP_AGENT_ID="$agentId"
            export CP_AGENT_SIGNALING="$signalingUrl"
            export CP_AGENT_JAR="$agentJar"
            setsid nohup env GODEBUG=asyncpreemptoff=1 ./cloudphone-agent -expires-at "" > agent.log 2>&1 &
            sleep 6

            # --- 3) Detect network type.
            # WiFi interface starts with "wlan". Mobile data uses "rmnet".
            WIFI_IF=${'$'}(ip -o link show 2>/dev/null | grep -oE 'wlan[0-9]+' | head -n1)

            if [ -n "${'$'}WIFI_IF" ]; then
                # --- WiFi: use local IP, no tunnel needed.
                IP=${'$'}(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if(${'$'}i=="src") print ${'$'}(i+1)}')
                if [ -z "${'$'}IP" ]; then
                    IP=${'$'}(ifconfig ${'$'}WIFI_IF 2>/dev/null | grep 'inet ' | awk '{print ${'$'}2}' | sed 's/addr://')
                fi
                echo "NETWORK=WIFI"
                echo "LINK=https://${'$'}IP:$port"
            else
                # --- Mobile data: start cloudflared Quick Tunnel.
                chmod +x cloudflared
                rm -f cloudflared.log
                nohup ./cloudflared tunnel --url https://127.0.0.1:$port --no-tls-verify > cloudflared.log 2>&1 &
                # Wait for the tunnel URL to appear in the log.
                for i in ${'$'}(seq 1 20); do
                    if grep -qE 'https://[a-z0-9-]+\.trycloudflare\.com' cloudflared.log 2>/dev/null; then
                        break
                    fi
                    sleep 1
                done
                URL=${'$'}(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' cloudflared.log | head -n1)
                if [ -z "${'$'}URL" ]; then
                    echo "NETWORK=MOBILE"
                    echo "LINK_ERROR=cloudflared URL not found"
                    echo "--- cloudflared.log (tail) ---"
                    tail -n 30 cloudflared.log 2>/dev/null
                else
                    echo "NETWORK=MOBILE"
                    echo "LINK=${'$'}URL"
                fi
            fi
        """.trimIndent()
    }

    private fun stopInternal(callback: (Boolean) -> Unit) {
        ShizukuRunner.executeCommand(
            "pkill -f webrtc-signaling; pkill -f cloudphone-agent; pkill -f cloudflared; echo DONE",
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

    private fun stagingDir(): File = File(context.filesDir, "cloudphone-staging")
}
