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

    private val appContext =
        context.applicationContext

    interface StartCallback {

        fun onSuccess(
            link: String,
            networkType: String
        )

        fun onError(message: String)
    }

    interface SimpleCallback {

        fun onComplete(
            success: Boolean,
            message: String?
        )
    }

    fun start(callback: StartCallback) {
        if (!startInProgress.compareAndSet(false, true)) {
            callback.onError(
                "Remote-control engine startup is already in progress"
            )
            return
        }

        /*
         * Stop old processes before replacing the working directory.
         *
         * This preserves the old PID files long enough for cleanup.
         */
        killAllProcesses {
            prepareWorkDir { prepOk, prepError ->
                if (!prepOk) {
                    finishStart()

                    callback.onError(
                        prepError
                            ?: "Failed to prepare working directory"
                    )

                    return@prepareWorkDir
                }

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

    fun isRunning(
        callback: (Boolean) -> Unit
    ) {
        val target =
            RemoteControlConfig.WORK_DIR

        val command = """
            is_pid_alive() {
                PID_FILE="${'$'}1"

                if [ ! -s "${'$'}PID_FILE" ]; then
                    return 1
                fi

                PID=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)

                if [ -z "${'$'}PID" ]; then
                    return 1
                fi

                kill -0 "${'$'}PID" 2>/dev/null
            }

            if is_pid_alive "$target/signaling.pid" &&
               is_pid_alive "$target/agent.pid"; then
                echo RUNNING
            else
                echo STOPPED
            fi
        """.trimIndent()

        var callbackDelivered = false

        fun completeOnce(running: Boolean) {
            if (!callbackDelivered) {
                callbackDelivered = true
                callback(running)
            }
        }

        ShizukuRunner.executeCommand(
            command,
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (!done) {
                        return
                    }

                    val running =
                        output.lineSequence()
                            .map { it.trim() }
                            .any { it == "RUNNING" }

                    completeOnce(running)
                }

                override fun onCommandError(
                    error: String
                ) {
                    completeOnce(false)
                }
            },
            lineBundle = 100
        )
    }

    private fun prepareWorkDir(
        callback: (Boolean, String?) -> Unit
    ) {
        val accumulated =
            StringBuilder()

        var callbackDelivered = false

        fun completeOnce(
            success: Boolean,
            error: String?
        ) {
            if (!callbackDelivered) {
                callbackDelivered = true
                callback(success, error)
            }
        }

        ShizukuRunner.executeCommand(
            buildCopyScript(),
            object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    accumulated.append(output)

                    if (!done) {
                        return
                    }

                    val result =
                        accumulated.toString()

                    if (result.contains("COPY_OK")) {
                        completeOnce(
                            true,
                            null
                        )
                    } else {
                        completeOnce(
                            false,
                            "Working-directory preparation did not finish correctly:\n" +
                                result.takeLast(1000)
                        )
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    completeOnce(
                        false,
                        error.take(1000)
                    )
                }
            },
            lineBundle = 1200
        )
    }

    private fun buildCopyScript(): String {
        val staging =
            stagingDir().absolutePath

        val target =
            RemoteControlConfig.WORK_DIR

        val pkg =
            appContext.packageName

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

            rm -f "$target/agent.pid"
            rm -f "$target/signaling.pid"
            rm -f "$target/cloudflared.pid"

            echo COPY_OK
        """.trimIndent()
    }

    /**
     * Stops processes using their stored PIDs.
     *
     * A /proc executable check is also performed for builds created
     * before PID files were introduced.
     */
    private fun killAllProcesses(
        callback: () -> Unit
    ) {
        val target =
            RemoteControlConfig.WORK_DIR

        var callbackDelivered = false

        fun completeOnce() {
            if (!callbackDelivered) {
                callbackDelivered = true
                callback()
            }
        }

        val script = """
            kill_pid_file() {
                PID_FILE="${'$'}1"

                if [ ! -s "${'$'}PID_FILE" ]; then
                    return
                fi

                PID=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)

                if [ -n "${'$'}PID" ]; then
                    kill -15 "${'$'}PID" 2>/dev/null || true
                fi
            }

            force_kill_pid_file() {
                PID_FILE="${'$'}1"

                if [ ! -s "${'$'}PID_FILE" ]; then
                    return
                fi

                PID=${'$'}(cat "${'$'}PID_FILE" 2>/dev/null)

                if [ -n "${'$'}PID" ] &&
                   kill -0 "${'$'}PID" 2>/dev/null; then
                    kill -9 "${'$'}PID" 2>/dev/null || true
                fi
            }

            kill_matching_executable() {
                EXPECTED_EXE="${'$'}1"

                for PROC_DIR in /proc/[0-9]*; do
                    PID=${'$'}{PROC_DIR##*/}

                    EXE=${'$'}(
                        readlink "${'$'}PROC_DIR/exe" \
                            2>/dev/null || true
                    )

                    if [ "${'$'}EXE" = "${'$'}EXPECTED_EXE" ]; then
                        kill -9 "${'$'}PID" 2>/dev/null || true
                    fi
                done
            }

            kill_pid_file "$target/cloudflared.pid"
            kill_pid_file "$target/agent.pid"
            kill_pid_file "$target/signaling.pid"

            sleep 2

            force_kill_pid_file "$target/cloudflared.pid"
            force_kill_pid_file "$target/agent.pid"
            force_kill_pid_file "$target/signaling.pid"

            kill_matching_executable "$target/cloudflared"
            kill_matching_executable "$target/cloudphone-agent"
            kill_matching_executable "$target/webrtc-signaling"

            sleep 2

            rm -f "$target/cloudflared.pid"
            rm -f "$target/agent.pid"
            rm -f "$target/signaling.pid"

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
                        completeOnce()
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    completeOnce()
                }
            },
            lineBundle = 300
        )
    }

    private fun launchServices(
        callback: StartCallback
    ) {
        val accumulated =
            StringBuilder()

        val networkMode =
            detectNetworkMode()

        var callbackDelivered = false

        fun finishWithSuccess(
            link: String,
            networkType: String
        ) {
            if (callbackDelivered) {
                return
            }

            callbackDelivered = true
            finishStart()

            callback.onSuccess(
                link,
                networkType
            )
        }

        fun finishWithError(
            message: String
        ) {
            if (callbackDelivered) {
                return
            }

            callbackDelivered = true
            finishStart()

            callback.onError(message)
        }

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

                    val fullOutput =
                        accumulated.toString()

                    val link =
                        extractLink(fullOutput)

                    if (link != null) {
                        finishWithSuccess(
                            link = link,
                            networkType = extractNetwork(
                                fullOutput
                            )
                        )
                    } else {
                        finishWithError(
                            buildString {
                                appendLine(
                                    "Service startup finished without a usable link."
                                )

                                appendLine()

                                if (fullOutput.isBlank()) {
                                    appendLine(
                                        "The launch script returned no output. Check Launch Log."
                                    )
                                } else {
                                    appendLine(
                                        fullOutput.takeLast(2200)
                                    )
                                }
                            }
                        )
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    finishWithError(
                        error.take(1400)
                    )
                }
            },
            lineBundle = 3000
        )
    }

    private fun buildLaunchScript(
        networkMode: NetworkMode
    ): String {
        val target =
            RemoteControlConfig.WORK_DIR

        val port =
            RemoteControlConfig.SIGNALING_PORT

        val agentId =
            RemoteControlConfig.AGENT_ID

        val signalingUrl =
            "wss://127.0.0.1:$port"

        val agentJar =
            RemoteControlConfig.AGENT_JAR

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

            cd "$target" || {
                echo "ERROR: unable to enter work directory"
                exit 1
            }

            export CLASSPATH="$target/libsys_core.so"

            : > launch.log
            : > signaling.log
            : > agent.log

            rm -f signaling.pid
            rm -f agent.pid
            rm -f cloudflared.pid

            echo "NETWORK_MODE=$networkLabel" >> launch.log
            echo "START_TIME=${'$'}(date '+%Y-%m-%d %H:%M:%S')" >> launch.log
            echo "CLEANUP_DONE=1" >> launch.log

            chmod 711 webrtc-signaling
            chmod 711 cloudphone-agent
            chmod 711 cloudflared

            echo "SIGNALING_STARTING=1" >> launch.log

            nohup ./webrtc-signaling \
                -port "$port" \
                -assets ./assets \
                -cert ./certs/server.crt \
                -key ./certs/server.key \
                >> signaling.log 2>&1 &

            SIGNALING_PID=${'$'}!
            echo "${'$'}SIGNALING_PID" > signaling.pid

            echo "SIGNALING_PID=${'$'}SIGNALING_PID" \
                >> launch.log

            SIGNALING_READY=0

            for i in ${'$'}(seq 1 20); do
                if ! kill -0 "${'$'}SIGNALING_PID" 2>/dev/null; then
                    echo "SIGNALING_EXITED=1" >> launch.log
                    break
                fi

                if grep -q \
                    "Listening on" \
                    signaling.log \
                    2>/dev/null; then

                    SIGNALING_READY=1
                    echo "SIGNALING_READY=1" >> launch.log
                    break
                fi

                sleep 1
            done

            if [ "${'$'}SIGNALING_READY" -ne 1 ]; then
                echo "SIGNALING_READY=0" >> launch.log
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=signaling server did not become ready"
                echo "--- signaling.log ---"
                tail -n 80 signaling.log 2>/dev/null
                echo "--- launch.log ---"
                cat launch.log 2>/dev/null
                exit 0
            fi

            export CP_AGENT_ID="$agentId"
            export CP_AGENT_SIGNALING="$signalingUrl"
            export CP_AGENT_JAR="$agentJar"

            echo "AGENT_STARTING=1" >> launch.log

            setsid nohup env \
                GODEBUG=asyncpreemptoff=1 \
                ./cloudphone-agent \
                -expires-at "" \
                >> agent.log 2>&1 &

            AGENT_PID=${'$'}!
            echo "${'$'}AGENT_PID" > agent.pid

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
                    echo "AGENT_READY=1" >> launch.log
                    break
                fi

                sleep 1
            done

            if [ "${'$'}AGENT_READY" -ne 1 ]; then
                echo "AGENT_READY=0" >> launch.log
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=agent did not complete handshake"
                echo "--- agent.log ---"
                tail -n 80 agent.log 2>/dev/null
                echo "--- launch.log ---"
                cat launch.log 2>/dev/null
                exit 0
            fi

            if ! kill -0 "${'$'}SIGNALING_PID" 2>/dev/null; then
                echo "SIGNALING_HEALTHY=0" >> launch.log
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=signaling process stopped after startup"
                exit 0
            fi

            if ! kill -0 "${'$'}AGENT_PID" 2>/dev/null; then
                echo "AGENT_HEALTHY=0" >> launch.log
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=agent process stopped after startup"
                exit 0
            fi

            echo "SIGNALING_HEALTHY=1" >> launch.log
            echo "AGENT_HEALTHY=1" >> launch.log

            if [ "$useTunnel" = "false" ]; then
                echo "WIFI_IP_DETECTION_START=1" >> launch.log

                WIFI_IP=${'$'}(
                    ip route get 1.1.1.1 2>/dev/null \
                        | sed -n \
                            's/.* src $$[0-9.]*$$.*/\1/p' \
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
                    echo "WIFI_IP_NOT_FOUND=1" >> launch.log
                    echo "NETWORK=WiFi"
                    echo "LINK_ERROR=WiFi address was not found"
                    echo "--- launch.log ---"
                    cat launch.log 2>/dev/null
                    exit 0
                fi

                echo "WIFI_IP=${'$'}WIFI_IP" >> launch.log
                echo "LINK_READY=1" >> launch.log

                echo "NETWORK=WiFi"
                echo "LINK=https://${'$'}WIFI_IP:$port"
                exit 0
            fi

            : > cloudflared.log

            echo "CLOUDFLARED_STARTING=1" >> launch.log

            nohup ./cloudflared tunnel \
                --url "https://127.0.0.1:$port" \
                --no-tls-verify \
                >> cloudflared.log 2>&1 &

            CLOUDFLARED_PID=${'$'}!
            echo "${'$'}CLOUDFLARED_PID" > cloudflared.pid

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
                    echo "CLOUDFLARED_READY=1" >> launch.log
                    break
                fi

                sleep 1
            done

            echo "NETWORK=$networkLabel"

            if [ -z "${'$'}URL" ]; then
                echo "CLOUDFLARED_READY=0" >> launch.log
                echo "LINK_ERROR=cloudflared URL not found"
                echo "--- cloudflared.log ---"
                tail -n 80 cloudflared.log 2>/dev/null
                echo "--- launch.log ---"
                cat launch.log 2>/dev/null
            else
                echo "LINK_READY=1" >> launch.log
                echo "PUBLIC_URL=${'$'}URL" >> launch.log
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
    }
}
