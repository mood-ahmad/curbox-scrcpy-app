package neth.iecal.curbox.remotecontrol

import android.content.Context
import neth.iecal.curbox.utils.ShizukuRunner
import java.io.File

class RemoteControlServiceBridge(private val context: Context) {

    interface StartCallback {
        fun onSuccess(link: String, networkType: String)
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
            killAllProcesses {
                launchServices(callback)
            }
        }
    }

    fun stop(callback: SimpleCallback) {
        killAllProcesses {
            callback.onComplete(true, null)
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

    private fun killAllProcesses(callback: () -> Unit) {
        val script = """
            pkill -9 -f webrtc-signaling 2>/dev/null
            pkill -9 -f cloudphone-agent 2>/dev/null
            pkill -9 -f cloudflared 2>/dev/null
            pkill -9 -f libsys_core.so 2>/dev/null
            sleep 3
            echo KILLED
        """.trimIndent()

        ShizukuRunner.executeCommand(
            script,
            object : ShizukuRunner.CommandResultListener {
                override fun onCommandResult(output: String, done: Boolean) {
                    if (done) callback()
                }
                override fun onCommandError(error: String) {
                    callback()
                }
            },
            lineBundle = 100
        )
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
                            val network = extractNetwork(fullOutput)
                            callback.onSuccess(link, network)
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

            chmod +x webrtc-signaling
            nohup ./webrtc-signaling -port $port -assets ./assets -cert ./certs/server.crt -key ./certs/server.key > signaling.log 2>&1 &
            sleep 5

            chmod +x cloudphone-agent
            export CP_AGENT_ID="$agentId"
            export CP_AGENT_SIGNALING="$signalingUrl"
            export CP_AGENT_JAR="$agentJar"
            setsid nohup env GODEBUG=asyncpreemptoff=1 ./cloudphone-agent -expires-at "" > agent.log 2>&1 &
            sleep 8

            WIFI_IF=""
            WIFI_IP=""

            echo "DEBUG: all interfaces:"
            ls /sys/class/net/

            for iface_dir in /sys/class/net/*; do
                iface_name=${'$'}(basename "${'$'}iface_dir")

                if [ "${'$'}iface_name" = "lo" ]; then continue; fi

                IS_WIRELESS=0
                if [ -d "${'$'}iface_dir/wireless" ]; then
                    IS_WIRELESS=1
                elif [ -e "${'$'}iface_dir/phy80211" ]; then
                    IS_WIRELESS=1
                fi

                if [ "${'$'}IS_WIRELESS" = "1" ]; then
                    echo "DEBUG: found wireless iface: ${'$'}iface_name"

                    CANDIDATE_IP=${'$'}(ip -4 addr show "${'$'}iface_name" 2>/dev/null | grep -oE 'inet [0-9.]+' | awk '{print ${'$'}2}' | head -n1)

                    if [ -z "${'$'}CANDIDATE_IP" ]; then
                        CANDIDATE_IP=${'$'}(ifconfig "${'$'}iface_name" 2>/dev/null | grep 'inet ' | awk '{print ${'$'}2}' | sed 's/addr://' | head -n1)
                    fi

                    if [ -n "${'$'}CANDIDATE_IP" ]; then
                        WIFI_IF="${'$'}iface_name"
                        WIFI_IP="${'$'}CANDIDATE_IP"
                        break
                    fi
                fi
            done

            echo "DEBUG: WIFI_IF=${'$'}WIFI_IF WIFI_IP=${'$'}WIFI_IP"

            if [ -n "${'$'}WIFI_IP" ]; then
                echo "NETWORK=WiFi"
                echo "LINK=https://${'$'}WIFI_IP:$port"
            else
                chmod +x cloudflared
                rm -f cloudflared.log
                nohup ./cloudflared tunnel --url https://127.0.0.1:$port --no-tls-verify > cloudflared.log 2>&1 &
                for i in ${'$'}(seq 1 25); do
                    if grep -qE 'https://[a-z0-9-]+-[a-z0-9-]+-[a-z0-9-]+\.trycloudflare\.com' cloudflared.log 2>/dev/null; then
                        break
                    fi
                    sleep 1
                done
                URL=${'$'}(grep -oE 'https://[a-z0-9-]+-[a-z0-9-]+-[a-z0-9-]+\.trycloudflare\.com' cloudflared.log | head -n1)
                if [ -z "${'$'}URL" ]; then
                    echo "NETWORK=Mobile"
                    echo "LINK_ERROR=cloudflared URL not found"
                    echo "--- cloudflared.log (tail) ---"
                    tail -n 30 cloudflared.log 2>/dev/null
                else
                    echo "NETWORK=Mobile"
                    echo "LINK=${'$'}URL"
                fi
            fi
        """.trimIndent()
    }

    private fun extractLink(output: String): String? {
        return output.lineSequence()
            .firstOrNull { it.startsWith("LINK=") }
            ?.removePrefix("LINK=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractNetwork(output: String): String {
        return output.lineSequence()
            .firstOrNull { it.startsWith("NETWORK=") }
            ?.removePrefix("NETWORK=")
            ?.trim()
            ?: "Unknown"
    }

    private fun stagingDir(): File = File(context.filesDir, "cloudphone-staging")
}
