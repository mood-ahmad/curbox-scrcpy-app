package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import neth.iecal.curbox.utils.ShizukuRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RemoteControlServiceBridge(
    context: Context
) {

    private val appContext = context.applicationContext

    interface StartCallback {
        fun onSuccess(link: String, networkType: String)
        fun onError(message: String)
    }

    interface SimpleCallback {
        fun onComplete(success: Boolean, message: String?)
    }

    fun start(callback: StartCallback) {
        if (!startInProgress.compareAndSet(false, true)) {
            callback.onError(
                "Remote-control engine startup is already in progress"
            )
            return
        }

        prepareWorkDir { prepOk, prepError ->
            if (!prepOk) {
                finishStart()

                callback.onError(
                    prepError
                        ?: "Failed to prepare working directory"
                )

                return@prepareWorkDir
            }

            killAllProcesses {
                launchServices(callback)
            }
        }
    }

    fun stop(callback: SimpleCallback) {
        startInProgress.set(false)

        killAllProcesses {
            callback.onComplete(
                true,
                null
            )
        }
    }

    fun isRunning(callback: (Boolean) -> Unit) {
        val command = """
            SIGNALING_COUNT=${'$'}(
                pgrep -f "$PROCESS_SIGNALING_PATTERN" \
                    2>/dev/null \
                    | wc -l
            )

            AGENT_COUNT=${'$'}(
                pgrep -f "$PROCESS_AGENT_PATTERN" \
                    2>/dev/null \
                    | wc -l
            )

            if [ "${'$'}SIGNALING_COUNT" -eq 1 ] &&
               [ "${'$'}AGENT_COUNT" -eq 1 ]; then
                echo RUNNING
            else
                echo STOPPED
            fi
        """.trimIndent()

        ShizukuRunner.executeCommand(
            command,
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (done) {
                        callback(
                            output.lineSequence()
                                .any { it.trim() == "RUNNING" }
                        )
                    }
                }

                override fun onCommandError(error: String) {
                    callback(false)
                }
            },
            lineBundle = 100
        )
    }

    private fun prepareWorkDir(
        callback: (Boolean, String?) -> Unit
    ) {
        val accumulated = StringBuilder()

        ShizukuRunner.executeCommand(
            buildCopyScript(),
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    accumulated.append(output)

                    if (done) {
                        val result = accumulated.toString()

                        if (result.contains("COPY_OK")) {
                            callback(true, null)
                        } else {
                            callback(
                                false,
                                "Working-directory preparation did not finish correctly:\n" +
                                    result.takeLast(800)
                            )
                        }
                    }
                }

                override fun onCommandError(error: String) {
                    callback(
                        false,
                        error.take(800)
                    )
                }
            },
            lineBundle = 1000
        )
    }

    private fun buildCopyScript(): String {
        val staging = stagingDir().absolutePath
        val target = RemoteControlConfig.WORK_DIR
        val pkg = appContext.packageName

        return """
            set -e

            rm -rf "$target"
            mkdir -p "$target"

            run-as "$pkg" sh -c \
                "cd '$staging' && tar cf - ." \
                | (
                    cd "$target"
                    tar xf -
                )

            test -s "$target/cloudphone-agent"
            test -s "$target/webrtc-signaling"
            test -s "$target/cloudflared"
            test -s "$target/libsys_core.so"

            chmod 711 "$target/cloudphone-agent"
            chmod 711 "$target/webrtc-signaling"
            chmod 711 "$target/cloudflared"

            rm -f "$target/agent.log"
            rm -f "$target/signaling.log"
            rm -f "$target/cloudflared.log"
            rm -f "$target/launch.log"

            echo COPY_OK
        """.trimIndent()
    }

    private fun killAllProcesses(
        callback: () -> Unit
    ) {
        val script = """
            pkill -9 -f "$PROCESS_SIGNALING_PATTERN" \
                2>/dev/null || true

            pkill -9 -f "$PROCESS_AGENT_PATTERN" \
                2>/dev/null || true

            pkill -9 -f "$PROCESS_CLOUDFLARED_PATTERN" \
                2>/dev/null || true

            sleep 3

            echo KILLED
        """.trimIndent()

        ShizukuRunner.executeCommand(
            script,
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (done) {
                        callback()
                    }
                }

                override fun onCommandError(error: String) {
                    callback()
                }
            },
            lineBundle = 100
        )
    }

    private fun launchServices(
        callback: StartCallback
    ) {
        val accumulated = StringBuilder()
        val networkMode = detectNetworkMode()

        ShizukuRunner.executeCommand(
            buildLaunchScript(networkMode),
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    accumulated.append(output)

                    if (!done) {
                        return
                    }

                    val fullOutput = accumulated.toString()
                    val link = extractLink(fullOutput)

                    if (link != null) {
                        val network = extractNetwork(fullOutput)

                        finishStart()

                        callback.onSuccess(
                            link,
                            network
                        )
                    } else {
                        finishStart()

                        callback.onError(
                            buildString {
                                appendLine(
                                    "Service startup finished without a usable link."
                                )
                                appendLine()
                                appendLine(
                                    fullOutput.takeLast(1600)
                                )
                            }
                        )
                    }
                }

                override fun onCommandError(error: String) {
                    finishStart()

                    callback.onError(
                        error.take(1000)
                    )
                }
            },
            lineBundle = 2000
        )
    }

    private fun buildLaunchScript(
        networkMode: NetworkMode
    ): String {
        val target = RemoteControlConfig.WORK_DIR
        val port = RemoteControlConfig.SIGNALING_PORT
        val agentId = RemoteControlConfig.AGENT_ID
        val signalingUrl = "wss://127.0.0.1:$port"
        val agentJar = RemoteControlConfig.AGENT_JAR

        val useTunnel =
            networkMode != NetworkMode.WIFI

        val networkLabel =
            if (useTunnel) {
                networkMode.label
            } else {
                "WiFi"
            }

        return """
            set -u

            cd "$target" || exit 1

            export CLASSPATH="$target/libsys_core.so"

            : > launch.log
            : > signaling.log
            : > agent.log

            echo "NETWORK_MODE=$networkLabel" >> launch.log
            echo "START_TIME=${'$'}(date '+%Y-%m-%d %H:%M:%S')" >> launch.log

            pkill -9 -f "$PROCESS_SIGNALING_PATTERN" \
                2>/dev/null || true

            pkill -9 -f "$PROCESS_AGENT_PATTERN" \
                2>/dev/null || true

            pkill -9 -f "$PROCESS_CLOUDFLARED_PATTERN" \
                2>/dev/null || true

            sleep 2

            chmod 711 webrtc-signaling
            chmod 711 cloudphone-agent
            chmod 711 cloudflared

            nohup ./webrtc-signaling \
                -port "$port" \
                -assets ./assets \
                -cert ./certs/server.crt \
                -key ./certs/server.key \
                >> signaling.log 2>&1 &

            SIGNALING_PID=${'$'}!

            echo "SIGNALING_PID=${'$'}SIGNALING_PID" \
                >> launch.log

            SIGNALING_READY=0

            for i in ${'$'}(seq 1 20); do
                if ! kill -0 "${'$'}SIGNALING_PID" 2>/dev/null; then
                    echo "SIGNALING_EXITED=1" >> launch.log
                    break
                fi

                if grep -q "Listening on" signaling.log 2>/dev/null; then
                    SIGNALING_READY=1
                    break
                fi

                sleep 1
            done

            if [ "${'$'}SIGNALING_READY" -ne 1 ]; then
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=signaling server did not become ready"
                echo "--- signaling.log ---"
                tail -n 50 signaling.log 2>/dev/null
                exit 0
            fi

            export CP_AGENT_ID="$agentId"
            export CP_AGENT_SIGNALING="$signalingUrl"
            export CP_AGENT_JAR="$agentJar"

            setsid nohup env \
                GODEBUG=asyncpreemptoff=1 \
                ./cloudphone-agent \
                -expires-at "" \
                >> agent.log 2>&1 &

            AGENT_PID=${'$'}!

            echo "AGENT_PID=${'$'}AGENT_PID" \
                >> launch.log

            AGENT_READY=0

            for i in ${'$'}(seq 1 20); do
                if ! kill -0 "${'$'}AGENT_PID" 2>/dev/null; then
                    echo "AGENT_EXITED=1" >> launch.log
                    break
                fi

                if grep -q \
                    "Handshake verified successfully" \
                    agent.log \
                    2>/dev/null; then

                    AGENT_READY=1
                    break
                fi

                sleep 1
            done

            if [ "${'$'}AGENT_READY" -ne 1 ]; then
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=agent did not complete handshake"
                echo "--- agent.log ---"
                tail -n 50 agent.log 2>/dev/null
                exit 0
            fi

            SIGNALING_COUNT=${'$'}(
                pgrep -f "$PROCESS_SIGNALING_PATTERN" \
                    2>/dev/null \
                    | wc -l
            )

            AGENT_COUNT=${'$'}(
                pgrep -f "$PROCESS_AGENT_PATTERN" \
                    2>/dev/null \
                    | wc -l
            )

            echo "SIGNALING_COUNT=${'$'}SIGNALING_COUNT" \
                >> launch.log

            echo "AGENT_COUNT=${'$'}AGENT_COUNT" \
                >> launch.log

            if [ "${'$'}SIGNALING_COUNT" -ne 1 ] ||
               [ "${'$'}AGENT_COUNT" -ne 1 ]; then

                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=duplicate engine processes detected"
                echo "SIGNALING_COUNT=${'$'}SIGNALING_COUNT"
                echo "AGENT_COUNT=${'$'}AGENT_COUNT"
                exit 0
            fi

            if [ "$useTunnel" = "false" ]; then
                WIFI_IP=${'$'}(
                    ip route get 1.1.1.1 2>/dev/null \
                        | sed -n 's/.* src $$[0-9.]*$$.*/\1/p' \
                        | head -n 1
                )

                if [ -z "${'$'}WIFI_IP" ]; then
                    WIFI_IP=${'$'}(
                        ip -4 addr show 2>/dev/null \
                            | grep -oE \
                                'inet (192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)[0-9.]+' \
                            | awk '{print ${'$'}2}' \
                            | head -n 1
                    )
                fi

                if [ -z "${'$'}WIFI_IP" ]; then
                    echo "NETWORK=WiFi"
                    echo "LINK_ERROR=WiFi address was not found"
                    exit 0
                fi

                echo "NETWORK=WiFi"
                echo "LINK=https://${'$'}WIFI_IP:$port"
                exit 0
            fi

            : > cloudflared.log

            echo "Starting cloudflared..." \
                >> launch.log

            nohup ./cloudflared tunnel \
                --url "https://127.0.0.1:$port" \
                --no-tls-verify \
                >> cloudflared.log 2>&1 &

            CLOUDFLARED_PID=${'$'}!

            echo "CLOUDFLARED_PID=${'$'}CLOUDFLARED_PID" \
                >> launch.log

            URL=""

            for i in ${'$'}(seq 1 45); do
                if ! kill -0 "${'$'}CLOUDFLARED_PID" 2>/dev/null; then
                    echo "CLOUDFLARED_EXITED=1" \
                        >> launch.log
                    break
                fi

                URL=${'$'}(
                    grep -oE \
                        'https://[a-z0-9-]+\.trycloudflare\.com' \
                        cloudflared.log \
                        | head -n 1
                )

                if [ -n "${'$'}URL" ]; then
                    break
                fi

                sleep 1
            done

            echo "NETWORK=$networkLabel"

            if [ -z "${'$'}URL" ]; then
                echo "LINK_ERROR=cloudflared URL not found"
                echo "--- cloudflared.log ---"
                tail -n 60 cloudflared.log 2>/dev/null
                echo "--- launch.log ---"
                cat launch.log 2>/dev/null
            else
                echo "LINK=${'$'}URL"
            fi
        """.trimIndent()
    }

    private fun detectNetworkMode(): NetworkMode {
        val connectivityManager =
            appContext.getSystemService(
                Context.CONNECTIVITY_SERVICE
            ) as? ConnectivityManager
                ?: return NetworkMode.UNKNOWN

        val activeNetwork =
            connectivityManager.activeNetwork
                ?: return NetworkMode.UNKNOWN

        val capabilities =
            connectivityManager.getNetworkCapabilities(
                activeNetwork
            ) ?: return NetworkMode.UNKNOWN

        return when {
            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_CELLULAR
            ) -> NetworkMode.CELLULAR

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_VPN
            ) -> NetworkMode.VPN

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI
            ) -> NetworkMode.WIFI

            capabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_ETHERNET
            ) -> NetworkMode.ETHERNET

            else -> NetworkMode.UNKNOWN
        }
    }

    private fun extractLink(
        output: String
    ): String? {
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("LINK=")
            }
            ?.removePrefix("LINK=")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    private fun extractNetwork(
        output: String
    ): String {
        return output.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("NETWORK=")
            }
            ?.removePrefix("NETWORK=")
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: "Unknown"
    }

    private fun finishStart() {
        startInProgress.set(false)
    }

    private fun stagingDir(): File {
        return File(
            appContext.filesDir,
            "cloudphone-staging"
        )
    }

    private enum class NetworkMode(
        val label: String
    ) {
        WIFI("WiFi"),
        CELLULAR("Mobile"),
        VPN("VPN"),
        ETHERNET("Ethernet"),
        UNKNOWN("Unknown")
    }

    companion object {
        private val startInProgress =
            AtomicBoolean(false)

        private const val PROCESS_SIGNALING_PATTERN =
            "[/]webrtc-signaling"

        private const val PROCESS_AGENT_PATTERN =
            "[/]cloudphone-agent"

        private const val PROCESS_CLOUDFLARED_PATTERN =
            "[/]cloudflared"
    }
}
