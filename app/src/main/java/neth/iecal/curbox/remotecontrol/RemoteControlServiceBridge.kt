package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import neth.iecal.curbox.utils.ShizukuRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class RemoteControlServiceBridge(
    context: Context
) {

    private val appContext =
        context.applicationContext

    interface StartCallback {

        /**
         * Startup succeeded with at least one usable link.
         *
         * localUrl  = local-network URL, or null if it could not be produced.
         * publicUrl = real Cloudflare Quick Tunnel URL, or null if it
         *             could not be produced.
         * networkType is informational / diagnostic only.
         */
        fun onSuccess(
            localUrl: String?,
            publicUrl: String?,
            networkType: String
        )

        fun onError(
            message: String
        )
    }

    interface SimpleCallback {

        fun onComplete(
            success: Boolean,
            message: String?
        )
    }

    fun start(
        callback: StartCallback
    ) {
        if (!startInProgress.compareAndSet(false, true)) {
            EventLogger.logStage(
                stage = "BRIDGE_START",
                status = "SKIPPED",
                details = "reason=start_already_in_progress"
            )

            callback.onError(
                "Remote-control engine startup is already in progress"
            )
            return
        }

        val operationId =
            operationCounter.incrementAndGet()

        activeOperationId.set(
            operationId
        )

        EventLogger.logStage(
            stage = "BRIDGE_START",
            status = "REQUESTED",
            details = "operationId=$operationId"
        )

        /*
         * Always stop and verify old processes before replacing
         * the working directory.
         */
        EventLogger.logStage(
            stage = "CLEANUP",
            status = "STARTED",
            details = "operationId=$operationId"
        )

        killAllProcesses { cleanupOk, cleanupError ->
            if (!isCurrentOperation(operationId)) {
                EventLogger.logStage(
                    stage = "CLEANUP",
                    status = "CANCELLED",
                    details = "operationId=$operationId"
                )

                finishStart()
                return@killAllProcesses
            }

            if (!cleanupOk) {
                EventLogger.logStage(
                    stage = "CLEANUP",
                    status = "FAILED",
                    details = "operationId=$operationId; " +
                        safeLogSummary(cleanupError)
                )

                finishStart()

                callback.onError(
                    cleanupError
                        ?: "Failed to stop the previous remote-control engine"
                )

                return@killAllProcesses
            }

            EventLogger.logStage(
                stage = "CLEANUP",
                status = "OK",
                details = "operationId=$operationId"
            )

            EventLogger.logStage(
                stage = "WORKDIR",
                status = "STARTED",
                details = "operationId=$operationId"
            )

            prepareWorkDir { prepOk, prepError ->
                if (!isCurrentOperation(operationId)) {
                    EventLogger.logStage(
                        stage = "WORKDIR",
                        status = "CANCELLED",
                        details = "operationId=$operationId"
                    )

                    finishStart()
                    return@prepareWorkDir
                }

                if (!prepOk) {
                    EventLogger.logStage(
                        stage = "WORKDIR",
                        status = "FAILED",
                        details = "operationId=$operationId; " +
                            safeLogSummary(prepError)
                    )

                    finishStart()

                    callback.onError(
                        prepError
                            ?: "Failed to prepare working directory"
                    )

                    return@prepareWorkDir
                }

                EventLogger.logStage(
                    stage = "WORKDIR",
                    status = "OK",
                    details = "operationId=$operationId"
                )

                EventLogger.logStage(
                    stage = "LAUNCH",
                    status = "STARTED",
                    details = "operationId=$operationId"
                )

                launchServices(
                    operationId = operationId,
                    callback = callback
                )
            }
        }
    }

    fun stop(
        callback: SimpleCallback
    ) {
        /*
         * Invalidate callbacks belonging to any startup that may
         * still be running.
         */
        val operationId =
            operationCounter.incrementAndGet()

        activeOperationId.set(
            operationId
        )

        startInProgress.set(false)

        EventLogger.logStage(
            stage = "BRIDGE_STOP",
            status = "REQUESTED",
            details = "operationId=$operationId"
        )

        killAllProcesses { success, message ->
            if (success) {
                EventLogger.logStage(
                    stage = "BRIDGE_STOP",
                    status = "OK",
                    details = "operationId=$operationId"
                )
            } else {
                EventLogger.logStage(
                    stage = "BRIDGE_STOP",
                    status = "FAILED",
                    details = "operationId=$operationId; " +
                        safeLogSummary(message)
                )
            }

            callback.onComplete(
                success,
                message
            )
        }
    }

    fun isRunning(
        callback: (Boolean) -> Unit
    ) {
        val target =
            RemoteControlConfig.WORK_DIR

        val command = """
            normalise_exe() {
                printf '%s' "${'$'}1" \
                    | sed 's/ (deleted)${'$'}//'
            }

            pid_matches_executable() {
                PID="${'$'}1"
                EXPECTED_EXE="${'$'}2"

                if [ -z "${'$'}PID" ] ||
                   ! kill -0 "${'$'}PID" 2>/dev/null; then
                    return 1
                fi

                ACTUAL_EXE=${'$'}(
                    readlink "/proc/${'$'}PID/exe" \
                        2>/dev/null || true
                )

                ACTUAL_EXE=${'$'}(
                    normalise_exe "${'$'}ACTUAL_EXE"
                )

                [ "${'$'}ACTUAL_EXE" = "${'$'}EXPECTED_EXE" ]
            }

            pid_file_matches() {
                PID_FILE="${'$'}1"
                EXPECTED_EXE="${'$'}2"

                if [ ! -s "${'$'}PID_FILE" ]; then
                    return 1
                fi

                PID=${'$'}(
                    cat "${'$'}PID_FILE" \
                        2>/dev/null || true
                )

                pid_matches_executable \
                    "${'$'}PID" \
                    "${'$'}EXPECTED_EXE"
            }

            if pid_file_matches \
                   "$target/signaling.pid" \
                   "$target/webrtc-signaling" &&
               pid_file_matches \
                   "$target/agent.pid" \
                   "$target/cloudphone-agent"; then

                echo RUNNING
            else
                echo STOPPED
            fi
        """.trimIndent()

        val callbackDelivered =
            AtomicBoolean(false)

        fun completeOnce(
            running: Boolean
        ) {
            if (
                callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                callback(running)
            }
        }

        ShizukuRunner.executeCommand(
            command,
            object :
                ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (!done) {
                        return
                    }

                    val running =
                        output.lineSequence()
                            .map {
                                it.trim()
                            }
                            .any {
                                it == "RUNNING"
                            }

                    completeOnce(
                        running
                    )
                }

                override fun onCommandError(
                    error: String
                ) {
                    completeOnce(
                        false
                    )
                }
            },
            lineBundle = 200
        )
    }

    private fun prepareWorkDir(
        callback: (Boolean, String?) -> Unit
    ) {
        val accumulated =
            StringBuilder()

        val callbackDelivered =
            AtomicBoolean(false)

        fun completeOnce(
            success: Boolean,
            error: String?
        ) {
            if (
                callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                callback(
                    success,
                    error
                )
            }
        }

        ShizukuRunner.executeCommand(
            buildCopyScript(),
            object :
                ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    accumulated.append(
                        output
                    )

                    if (!done) {
                        return
                    }

                    val result =
                        accumulated.toString()

                    if (
                        result.lineSequence()
                            .map {
                                it.trim()
                            }
                            .any {
                                it == "COPY_OK"
                            }
                    ) {
                        completeOnce(
                            true,
                            null
                        )
                    } else {
                        completeOnce(
                            false,
                            "Working-directory preparation " +
                                "did not finish correctly:\n" +
                                result.takeLast(1400)
                        )
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    completeOnce(
                        false,
                        error.take(1400)
                    )
                }
            },
            lineBundle = 1600
        )
    }

    private fun buildCopyScript(): String {
        val staging =
            stagingDir().absolutePath

        val target =
            RemoteControlConfig.WORK_DIR

        val dataBackup =
            "$target.data_backup"

        val pkg =
            appContext.packageName

        return """
            set -e

            target="$target"
            data_backup="$dataBackup"
            restore_tmp="${'$'}{target}/data.restore_tmp"

            # ---------------------------------------------------
            # Pre-step: clean only stale restore_tmp.
            # data_backup is NEVER deleted here.
            # ---------------------------------------------------
            rm -rf "${'$'}{restore_tmp}" 2>/dev/null || true

            # ---------------------------------------------------
            # Step 0: Guard against a stale data backup left by
            # an interrupted deployment.
            #
            # If data_backup exists and target/data is missing,
            # the backup is the only surviving copy. We must
            # recover it BEFORE touching anything else, and only
            # through restore_tmp for any copy-based fallback.
            # ---------------------------------------------------
            if [ -d "${'$'}{data_backup}" ]; then
                if [ -d "${'$'}{target}/data" ]; then
                    # target/data is authoritative; the leftover
                    # backup is safe to discard.
                    rm -rf "${'$'}{data_backup}"
                    echo "STALE_DATA_BACKUP_DISCARDED=1"
                else
                    # Only the backup has data. Recover first.
                    mkdir -p "${'$'}{target}" \
                        2>/dev/null || true

                    rm -rf "${'$'}{restore_tmp}" \
                        2>/dev/null || true

                    if mv "${'$'}{data_backup}" \
                          "${'$'}{target}/data" \
                          2>/dev/null; then
                        echo "STALE_DATA_BACKUP_RECOVERED=1"
                    else
                        if cp -R "${'$'}{data_backup}" \
                              "${'$'}{restore_tmp}" \
                              2>/dev/null; then
                            if [ -d "${'$'}{restore_tmp}" ]; then
                                if mv "${'$'}{restore_tmp}" \
                                      "${'$'}{target}/data" \
                                      2>/dev/null; then
                                    rm -rf "${'$'}{data_backup}" \
                                        2>/dev/null || true

                                    echo "STALE_DATA_BACKUP_RECOVERED=1"
                                else
                                    rm -rf "${'$'}{restore_tmp}" \
                                        2>/dev/null || true

                                    echo "STALE_DATA_BACKUP_UNRECOVERABLE=1"
                                    exit 1
                                fi
                            else
                                rm -rf "${'$'}{restore_tmp}" \
                                    2>/dev/null || true

                                echo "STALE_DATA_BACKUP_UNRECOVERABLE=1"
                                exit 1
                            fi
                        else
                            rm -rf "${'$'}{restore_tmp}" \
                                2>/dev/null || true

                            # Remove any partial target/data so
                            # the next run cannot mistake it for
                            # authoritative data.
                            if [ -d "${'$'}{target}/data" ]; then
                                rm -rf "${'$'}{target}/data" \
                                    2>/dev/null || true
                            fi

                            echo "STALE_DATA_BACKUP_UNRECOVERABLE=1"
                            exit 1
                        fi
                    fi
                fi
            fi

            # ---------------------------------------------------
            # Step 1: Preserve current persistent data.
            # ---------------------------------------------------
            data_preserved=0

            restore_data_on_failure() {
                if [ "${'$'}{data_preserved}" = "1" ] &&
                   [ -d "${'$'}{data_backup}" ]; then

                    mkdir -p "${'$'}{target}" \
                        2>/dev/null || true

                    rm -rf "${'$'}{restore_tmp}" \
                        2>/dev/null || true

                    rm -rf "${'$'}{target}/data" \
                        2>/dev/null || true

                    if mv "${'$'}{data_backup}" \
                          "${'$'}{target}/data" \
                          2>/dev/null; then
                        data_preserved=0
                        return 0
                    fi

                    if cp -R "${'$'}{data_backup}" \
                          "${'$'}{restore_tmp}" \
                          2>/dev/null; then
                        if [ -d "${'$'}{restore_tmp}" ]; then
                            rm -rf "${'$'}{target}/data" \
                                2>/dev/null || true

                            if mv "${'$'}{restore_tmp}" \
                                  "${'$'}{target}/data" \
                                  2>/dev/null; then
                                rm -rf "${'$'}{data_backup}" \
                                    2>/dev/null || true

                                data_preserved=0
                                return 0
                            fi
                        fi
                    fi

                    # Cleanup partial restore_tmp; keep backup.
                    rm -rf "${'$'}{restore_tmp}" \
                        2>/dev/null || true
                fi
            }

            trap 'restore_data_on_failure' EXIT

            if [ -d "${'$'}{target}/data" ]; then
                mv "${'$'}{target}/data" \
                    "${'$'}{data_backup}"

                data_preserved=1

                echo "DATA_PRESERVED=1"
            else
                echo "DATA_PRESERVED=0"
            fi

            # ---------------------------------------------------
            # Step 2: Replace the entire runtime tree.
            # ---------------------------------------------------
            rm -rf "${'$'}{target}"
            mkdir -p "${'$'}{target}"

            run-as "$pkg" sh -c \
                "cd '$staging' && tar cf - ." \
                | (
                    cd "${'$'}{target}"
                    tar xf -
                )

            # ---------------------------------------------------
            # Step 3: Verify that the deployment produced every
            # required runtime component.
            # ---------------------------------------------------
            test -s "${'$'}{target}/cloudphone-agent"
            test -s "${'$'}{target}/webrtc-signaling"
            test -s "${'$'}{target}/cloudflared"
            test -s "${'$'}{target}/libsys_core.so"

            test -d "${'$'}{target}/assets"
            test -s "${'$'}{target}/certs/server.crt"
            test -s "${'$'}{target}/certs/server.key"

            chmod 711 "${'$'}{target}/cloudphone-agent"
            chmod 711 "${'$'}{target}/webrtc-signaling"
            chmod 711 "${'$'}{target}/cloudflared"

            # ---------------------------------------------------
            # Step 4: Restore persistent data.
            #
            # mv is attempted first (same filesystem, atomic).
            # If mv fails, the copy goes through restore_tmp so
            # that a partially copied tree can never appear as
            # a valid target/data. Only after restore_tmp has
            # been successfully moved to target/data is the
            # backup removed. If neither path completes, we fail
            # so the trap can retry, and the backup stays intact.
            # ---------------------------------------------------
            if [ "${'$'}{data_preserved}" = "1" ] &&
               [ -d "${'$'}{data_backup}" ]; then

                rm -rf "${'$'}{target}/data"
                rm -rf "${'$'}{restore_tmp}" \
                    2>/dev/null || true

                if mv "${'$'}{data_backup}" \
                      "${'$'}{target}/data" \
                      2>/dev/null; then
                    data_preserved=0
                else
                    if cp -R "${'$'}{data_backup}" \
                          "${'$'}{restore_tmp}" \
                          2>/dev/null; then
                        if [ -d "${'$'}{restore_tmp}" ]; then
                            rm -rf "${'$'}{target}/data"

                            if mv "${'$'}{restore_tmp}" \
                                  "${'$'}{target}/data" \
                                  2>/dev/null; then
                                rm -rf "${'$'}{data_backup}"

                                data_preserved=0
                            else
                                rm -rf "${'$'}{restore_tmp}"
                                echo "DATA_RESTORE_FAILED=1"
                                exit 1
                            fi
                        else
                            rm -rf "${'$'}{restore_tmp}"
                            echo "DATA_RESTORE_FAILED=1"
                            exit 1
                        fi
                    else
                        rm -rf "${'$'}{restore_tmp}"
                        echo "DATA_RESTORE_FAILED=1"
                        exit 1
                    fi
                fi

                echo "DATA_PRESERVED=1"
            else
                echo "DATA_PRESERVED=0"
            fi

            # ---------------------------------------------------
            # Step 5: Remove old runtime artifacts so the next
            # diagnostics view reflects only the new launch.
            # ---------------------------------------------------
            rm -f "${'$'}{target}/agent.log"
            rm -f "${'$'}{target}/signaling.log"
            rm -f "${'$'}{target}/cloudflared.log"
            rm -f "${'$'}{target}/launch.log"

            rm -f "${'$'}{target}/agent.pid"
            rm -f "${'$'}{target}/signaling.pid"
            rm -f "${'$'}{target}/cloudflared.pid"
            rm -f "${'$'}{target}/.launch_token"

            echo "RUNTIME_DEPLOYED=1"
            echo COPY_OK
        """.trimIndent()
    }

    /**
     * Stops every CurBox-owned native process and verifies that all
     * matching executables have actually exited.
     *
     * Executable paths ending in " (deleted)" are normalised before
     * comparison so processes launched from an older, deleted work
     * directory are still detected.
     */
    private fun killAllProcesses(
        callback: (Boolean, String?) -> Unit
    ) {
        val target =
            RemoteControlConfig.WORK_DIR

        val port =
            RemoteControlConfig.SIGNALING_PORT

        val accumulated =
            StringBuilder()

        val callbackDelivered =
            AtomicBoolean(false)

        fun completeOnce(
            success: Boolean,
            message: String?
        ) {
            if (
                callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                callback(
                    success,
                    message
                )
            }
        }

        val script = """
            set +e

            normalise_exe() {
                printf '%s' "${'$'}1" \
                    | sed 's/ (deleted)${'$'}//'
            }

            pid_matches_executable() {
                PID="${'$'}1"
                EXPECTED_EXE="${'$'}2"

                if [ -z "${'$'}PID" ] ||
                   ! kill -0 "${'$'}PID" 2>/dev/null; then
                    return 1
                fi

                ACTUAL_EXE=${'$'}(
                    readlink "/proc/${'$'}PID/exe" \
                        2>/dev/null || true
                )

                ACTUAL_EXE=${'$'}(
                    normalise_exe "${'$'}ACTUAL_EXE"
                )

                [ "${'$'}ACTUAL_EXE" = "${'$'}EXPECTED_EXE" ]
            }

            list_matching_executable() {
                EXPECTED_EXE="${'$'}1"

                for PROC_DIR in /proc/[0-9]*; do
                    [ -d "${'$'}PROC_DIR" ] || continue

                    PID=${'$'}{PROC_DIR##*/}

                    ACTUAL_EXE=${'$'}(
                        readlink "${'$'}PROC_DIR/exe" \
                            2>/dev/null || true
                    )

                    ACTUAL_EXE=${'$'}(
                        normalise_exe "${'$'}ACTUAL_EXE"
                    )

                    if [ "${'$'}ACTUAL_EXE" = "${'$'}EXPECTED_EXE" ]; then
                        echo "${'$'}PID"
                    fi
                done
            }

            signal_pid_file() {
                SIGNAL="${'$'}1"
                PID_FILE="${'$'}2"
                EXPECTED_EXE="${'$'}3"

                if [ ! -s "${'$'}PID_FILE" ]; then
                    return
                fi

                PID=${'$'}(
                    cat "${'$'}PID_FILE" \
                        2>/dev/null || true
                )

                if pid_matches_executable \
                    "${'$'}PID" \
                    "${'$'}EXPECTED_EXE"; then

                    echo \
                        "SIGNAL_PID_FILE signal=${'$'}SIGNAL pid=${'$'}PID exe=${'$'}EXPECTED_EXE"

                    kill "-${'$'}SIGNAL" \
                        "${'$'}PID" \
                        2>/dev/null || true
                else
                    echo \
                        "STALE_PID_FILE file=${'$'}PID_FILE pid=${'$'}PID"
                fi
            }

            signal_matching_executable() {
                SIGNAL="${'$'}1"
                EXPECTED_EXE="${'$'}2"

                for PID in ${'$'}(
                    list_matching_executable \
                        "${'$'}EXPECTED_EXE"
                ); do
                    echo \
                        "SIGNAL_MATCH signal=${'$'}SIGNAL pid=${'$'}PID exe=${'$'}EXPECTED_EXE"

                    kill "-${'$'}SIGNAL" \
                        "${'$'}PID" \
                        2>/dev/null || true
                done
            }

            matching_processes() {
                list_matching_executable \
                    "$target/cloudflared"

                list_matching_executable \
                    "$target/cloudphone-agent"

                list_matching_executable \
                    "$target/webrtc-signaling"
            }

            port_is_listening() {
                PORT_HEX=${'$'}(
                    printf '%04X' "$port"
                )

                awk \
                    -v port=":${'$'}PORT_HEX" \
                    '
                    ${'$'}2 ~ port "${'$'}" &&
                    ${'$'}4 == "0A" {
                        found = 1
                    }

                    END {
                        if (found) {
                            exit 0
                        }

                        exit 1
                    }
                    ' \
                    /proc/net/tcp \
                    /proc/net/tcp6 \
                    2>/dev/null
            }

            rm -f "$target/.launch_token"

            echo CLEANUP_START

            signal_pid_file \
                15 \
                "$target/cloudflared.pid" \
                "$target/cloudflared"

            signal_pid_file \
                15 \
                "$target/agent.pid" \
                "$target/cloudphone-agent"

            signal_pid_file \
                15 \
                "$target/signaling.pid" \
                "$target/webrtc-signaling"

            signal_matching_executable \
                15 \
                "$target/cloudflared"

            signal_matching_executable \
                15 \
                "$target/cloudphone-agent"

            signal_matching_executable \
                15 \
                "$target/webrtc-signaling"

            for i in ${'$'}(seq 1 5); do
                REMAINING=${'$'}(
                    matching_processes
                )

                if [ -z "${'$'}REMAINING" ]; then
                    break
                fi

                sleep 1
            done

            signal_pid_file \
                9 \
                "$target/cloudflared.pid" \
                "$target/cloudflared"

            signal_pid_file \
                9 \
                "$target/agent.pid" \
                "$target/cloudphone-agent"

            signal_pid_file \
                9 \
                "$target/signaling.pid" \
                "$target/webrtc-signaling"

            signal_matching_executable \
                9 \
                "$target/cloudflared"

            signal_matching_executable \
                9 \
                "$target/cloudphone-agent"

            signal_matching_executable \
                9 \
                "$target/webrtc-signaling"

            sleep 2

            REMAINING=${'$'}(
                matching_processes
            )

            if [ -n "${'$'}REMAINING" ]; then
                echo "CLEANUP_FAILED=processes_remain"
                echo "REMAINING_PIDS=${'$'}REMAINING"

                for PID in ${'$'}REMAINING; do
                    echo "--- PID ${'$'}PID ---"

                    echo -n "exe="
                    readlink \
                        "/proc/${'$'}PID/exe" \
                        2>/dev/null || true

                    echo -n "cmdline="
                    tr '\000' ' ' \
                        < "/proc/${'$'}PID/cmdline" \
                        2>/dev/null || true

                    echo ""
                done

                exit 1
            fi

            if port_is_listening; then
                echo "CLEANUP_FAILED=port_${port}_still_listening"

                if command -v ss >/dev/null 2>&1; then
                    ss -ltnp 2>/dev/null \
                        | grep ":$port" \
                        || true
                fi

                exit 1
            fi

            rm -f "$target/cloudflared.pid"
            rm -f "$target/agent.pid"
            rm -f "$target/signaling.pid"

            echo CLEANUP_OK
        """.trimIndent()

        ShizukuRunner.executeCommand(
            script,
            object :
                ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    accumulated.append(
                        output
                    )

                    if (!done) {
                        return
                    }

                    val result =
                        accumulated.toString()

                    val success =
                        result.lineSequence()
                            .map {
                                it.trim()
                            }
                            .any {
                                it == "CLEANUP_OK"
                            }

                    if (success) {
                        completeOnce(
                            true,
                            null
                        )
                    } else {
                        completeOnce(
                            false,
                            buildString {
                                appendLine(
                                    "Remote-control cleanup could not be verified."
                                )

                                if (result.isNotBlank()) {
                                    appendLine()
                                    append(
                                        result.takeLast(2200)
                                    )
                                }
                            }
                        )
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    val result =
                        accumulated.toString()

                    completeOnce(
                        false,
                        buildString {
                            appendLine(
                                "Remote-control cleanup failed."
                            )

                            if (error.isNotBlank()) {
                                appendLine()
                                appendLine(
                                    error.take(1200)
                                )
                            }

                            if (result.isNotBlank()) {
                                appendLine()
                                append(
                                    result.takeLast(1200)
                                )
                            }
                        }
                    )
                }
            },
            lineBundle = 1800
        )
    }

    private fun launchServices(
        operationId: Long,
        callback: StartCallback
    ) {
        if (!isCurrentOperation(operationId)) {
            EventLogger.logStage(
                stage = "LAUNCH",
                status = "CANCELLED",
                details = "operationId=$operationId"
            )

            finishStart()
            return
        }

        val accumulated =
            StringBuilder()

        val networkMode =
            detectNetworkMode()

        val callbackDelivered =
            AtomicBoolean(false)

        fun finishWithSuccess(
            localUrl: String?,
            publicUrl: String?,
            networkType: String
        ) {
            if (
                !callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            if (!isCurrentOperation(operationId)) {
                EventLogger.logStage(
                    stage = "LAUNCH",
                    status = "CANCELLED",
                    details = "operationId=$operationId"
                )

                finishStart()
                return
            }

            EventLogger.logStage(
                stage = "LAUNCH",
                status = "OK",
                details = "operationId=$operationId; " +
                    "network=$networkType; " +
                    "localAvailable=${localUrl != null}; " +
                    "publicAvailable=${publicUrl != null}"
            )

            finishStart()

            callback.onSuccess(
                localUrl,
                publicUrl,
                networkType
            )
        }

        fun finishWithError(
            message: String
        ) {
            if (
                !callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            if (!isCurrentOperation(operationId)) {
                EventLogger.logStage(
                    stage = "LAUNCH",
                    status = "CANCELLED",
                    details = "operationId=$operationId"
                )

                finishStart()
                return
            }

            EventLogger.logStage(
                stage = "LAUNCH",
                status = "FAILED",
                details = "operationId=$operationId"
            )

            finishStart()

            callback.onError(
                message
            )
        }

        ShizukuRunner.executeCommand(
            buildLaunchScript(
                networkMode = networkMode,
                operationId = operationId
            ),
            object :
                ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    accumulated.append(
                        output
                    )

                    if (!done) {
                        return
                    }

                    val fullOutput =
                        accumulated.toString()

                    val localUrl =
                        extractLocalUrl(
                            fullOutput
                        )

                    val publicUrl =
                        extractPublicUrl(
                            fullOutput
                        )

                    if (localUrl != null || publicUrl != null) {
                        finishWithSuccess(
                            localUrl = localUrl,
                            publicUrl = publicUrl,
                            networkType =
                                extractNetwork(
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
                                    append(
                                        "The launch script returned no output. " +
                                            "Check Launch Log."
                                    )
                                } else {
                                    append(
                                        fullOutput.takeLast(
                                            2600
                                        )
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
                        error.take(1800)
                    )
                }
            },
            lineBundle = 3600
        )
    }

    private fun buildLaunchScript(
        networkMode: NetworkMode,
        operationId: Long
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

        /*
         * Informational / diagnostic only. The network type must never
         * decide between Local and Cloudflare: both are always attempted.
         */
        val networkLabel =
            networkMode.label

        return """
            set -u

            cd "$target" || {
                echo "ERROR: unable to enter work directory"
                exit 1
            }

            TOKEN_FILE="$target/.launch_token"
            EXPECTED_TOKEN="$operationId"

            printf '%s\n' \
                "${'$'}EXPECTED_TOKEN" \
                > "${'$'}TOKEN_FILE"

            token_is_valid() {
                [ -s "${'$'}TOKEN_FILE" ] ||
                    return 1

                CURRENT_TOKEN=${'$'}(
                    cat "${'$'}TOKEN_FILE" \
                        2>/dev/null || true
                )

                [ "${'$'}CURRENT_TOKEN" = "${'$'}EXPECTED_TOKEN" ]
            }

            normalise_exe() {
                printf '%s' "${'$'}1" \
                    | sed 's/ (deleted)${'$'}//'
            }

            pid_matches_executable() {
                PID="${'$'}1"
                EXPECTED_EXE="${'$'}2"

                if [ -z "${'$'}PID" ] ||
                   ! kill -0 "${'$'}PID" 2>/dev/null; then
                    return 1
                fi

                ACTUAL_EXE=${'$'}(
                    readlink "/proc/${'$'}PID/exe" \
                        2>/dev/null || true
                )

                ACTUAL_EXE=${'$'}(
                    normalise_exe "${'$'}ACTUAL_EXE"
                )

                [ "${'$'}ACTUAL_EXE" = "${'$'}EXPECTED_EXE" ]
            }

            stop_started_processes() {
                if [ -s cloudflared.pid ]; then
                    PID=${'$'}(
                        cat cloudflared.pid \
                            2>/dev/null || true
                    )

                    if pid_matches_executable \
                        "${'$'}PID" \
                        "$target/cloudflared"; then

                        kill -15 "${'$'}PID" \
                            2>/dev/null || true
                    fi
                fi

                if [ -s agent.pid ]; then
                    PID=${'$'}(
                        cat agent.pid \
                            2>/dev/null || true
                    )

                    if pid_matches_executable \
                        "${'$'}PID" \
                        "$target/cloudphone-agent"; then

                        kill -15 "${'$'}PID" \
                            2>/dev/null || true
                    fi
                fi

                if [ -s signaling.pid ]; then
                    PID=${'$'}(
                        cat signaling.pid \
                            2>/dev/null || true
                    )

                    if pid_matches_executable \
                        "${'$'}PID" \
                        "$target/webrtc-signaling"; then

                        kill -15 "${'$'}PID" \
                            2>/dev/null || true
                    fi
                fi

                sleep 1

                for FILE_AND_EXE in \
                    "cloudflared.pid:$target/cloudflared" \
                    "agent.pid:$target/cloudphone-agent" \
                    "signaling.pid:$target/webrtc-signaling"
                do
                    PID_FILE="${'$'}{FILE_AND_EXE%%:*}"
                    EXPECTED_EXE="${'$'}{FILE_AND_EXE#*:}"

                    if [ -s "${'$'}PID_FILE" ]; then
                        PID=${'$'}(
                            cat "${'$'}PID_FILE" \
                                2>/dev/null || true
                        )

                        if pid_matches_executable \
                            "${'$'}PID" \
                            "${'$'}EXPECTED_EXE"; then

                            kill -9 "${'$'}PID" \
                                2>/dev/null || true
                        fi
                    fi
                done
            }

            stop_cloudflared_only() {
                if [ -s cloudflared.pid ]; then
                    PID=${'$'}(
                        cat cloudflared.pid \
                            2>/dev/null || true
                    )

                    if pid_matches_executable \
                        "${'$'}PID" \
                        "$target/cloudflared"; then

                        kill -15 "${'$'}PID" \
                            2>/dev/null || true

                        sleep 1

                        if pid_matches_executable \
                            "${'$'}PID" \
                            "$target/cloudflared"; then

                            kill -9 "${'$'}PID" \
                                2>/dev/null || true
                        fi
                    fi
                fi

                rm -f cloudflared.pid
            }

            port_is_listening() {
                PORT_HEX=${'$'}(
                    printf '%04X' "$port"
                )

                awk \
                    -v port=":${'$'}PORT_HEX" \
                    '
                    ${'$'}2 ~ port "${'$'}" &&
                    ${'$'}4 == "0A" {
                        found = 1
                    }

                    END {
                        if (found) {
                            exit 0
                        }

                        exit 1
                    }
                    ' \
                    /proc/net/tcp \
                    /proc/net/tcp6 \
                    2>/dev/null
            }

            abort_startup() {
                REASON="${'$'}1"

                echo \
                    "START_ABORTED=${'$'}REASON" \
                    >> launch.log

                stop_started_processes

                rm -f \
                    cloudflared.pid \
                    agent.pid \
                    signaling.pid \
                    "${'$'}TOKEN_FILE"
            }

            export CLASSPATH="$target/libsys_core.so"

            : > launch.log
            : > signaling.log
            : > agent.log

            rm -f signaling.pid
            rm -f agent.pid
            rm -f cloudflared.pid

            echo "NETWORK_MODE=$networkLabel" \
                >> launch.log

            echo \
                "START_OPERATION_ID=$operationId" \
                >> launch.log

            echo \
                "START_TIME=${'$'}(date '+%Y-%m-%d %H:%M:%S')" \
                >> launch.log

            echo "CLEANUP_DONE=1" \
                >> launch.log

            chmod 711 webrtc-signaling
            chmod 711 cloudphone-agent
            chmod 711 cloudflared

            if ! token_is_valid; then
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=start operation was cancelled"
                abort_startup "token_invalid_before_signaling"
                exit 0
            fi

            if port_is_listening; then
                echo "PORT_${port}_BUSY=1" \
                    >> launch.log

                if command -v ss >/dev/null 2>&1; then
                    ss -ltnp 2>/dev/null \
                        | grep ":$port" \
                        >> launch.log \
                        || true
                fi

                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=signaling port $port is already in use"
                cat launch.log
                abort_startup "signaling_port_busy"
                exit 0
            fi

            echo "SIGNALING_STARTING=1" \
                >> launch.log

            nohup ./webrtc-signaling \
                -port "$port" \
                -assets ./assets \
                -cert ./certs/server.crt \
                -key ./certs/server.key \
                >> signaling.log 2>&1 &

            SIGNALING_PID=${'$'}!

            echo "${'$'}SIGNALING_PID" \
                > signaling.pid

            echo \
                "SIGNALING_PID=${'$'}SIGNALING_PID" \
                >> launch.log

            SIGNALING_READY=0

            for i in ${'$'}(seq 1 20); do
                if ! token_is_valid; then
                    echo "NETWORK=$networkLabel"
                    echo "LINK_ERROR=start operation was cancelled"
                    abort_startup \
                        "token_invalid_during_signaling"
                    exit 0
                fi

                if ! pid_matches_executable \
                    "${'$'}SIGNALING_PID" \
                    "$target/webrtc-signaling"; then

                    echo "SIGNALING_EXITED=1" \
                        >> launch.log

                    break
                fi

                if grep -q \
                    "Listening on" \
                    signaling.log \
                    2>/dev/null; then

                    SIGNALING_READY=1

                    echo "SIGNALING_READY=1" \
                        >> launch.log

                    break
                fi

                sleep 1
            done

            if [ "${'$'}SIGNALING_READY" -ne 1 ]; then
                echo "SIGNALING_READY=0" \
                    >> launch.log

                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=signaling server did not become ready"
                echo "--- signaling.log ---"
                tail -n 100 signaling.log \
                    2>/dev/null
                echo "--- launch.log ---"
                cat launch.log \
                    2>/dev/null

                abort_startup \
                    "signaling_not_ready"

                exit 0
            fi

            export CP_AGENT_ID="$agentId"
            export CP_AGENT_SIGNALING="$signalingUrl"
            export CP_AGENT_JAR="$agentJar"

            if ! token_is_valid; then
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=start operation was cancelled"
                abort_startup \
                    "token_invalid_before_agent"
                exit 0
            fi

            echo "AGENT_STARTING=1" \
                >> launch.log

            setsid nohup env \
                GODEBUG=asyncpreemptoff=1 \
                ./cloudphone-agent \
                -expires-at "" \
                >> agent.log 2>&1 &

            AGENT_PID=${'$'}!

            echo "${'$'}AGENT_PID" \
                > agent.pid

            echo \
                "AGENT_PID=${'$'}AGENT_PID" \
                >> launch.log

            AGENT_READY=0

            for i in ${'$'}(seq 1 20); do
                if ! token_is_valid; then
                    echo "NETWORK=$networkLabel"
                    echo "LINK_ERROR=start operation was cancelled"

                    abort_startup \
                        "token_invalid_during_agent"

                    exit 0
                fi

                if ! pid_matches_executable \
                    "${'$'}AGENT_PID" \
                    "$target/cloudphone-agent"; then

                    echo "AGENT_EXITED=1" \
                        >> launch.log

                    break
                fi

                if grep -q \
                    "Handshake verified successfully" \
                    agent.log \
                    2>/dev/null; then

                    AGENT_READY=1

                    echo "AGENT_READY=1" \
                        >> launch.log

                    break
                fi

                sleep 1
            done

            if [ "${'$'}AGENT_READY" -ne 1 ]; then
                echo "AGENT_READY=0" \
                    >> launch.log

                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=agent did not complete handshake"
                echo "--- agent.log ---"
                tail -n 100 agent.log \
                    2>/dev/null
                echo "--- launch.log ---"
                cat launch.log \
                    2>/dev/null

                abort_startup \
                    "agent_not_ready"

                exit 0
            fi

            if ! pid_matches_executable \
                "${'$'}SIGNALING_PID" \
                "$target/webrtc-signaling"; then

                echo "SIGNALING_HEALTHY=0" \
                    >> launch.log

                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=signaling process stopped after startup"

                abort_startup \
                    "signaling_unhealthy"

                exit 0
            fi

            if ! pid_matches_executable \
                "${'$'}AGENT_PID" \
                "$target/cloudphone-agent"; then

                echo "AGENT_HEALTHY=0" \
                    >> launch.log

                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=agent process stopped after startup"

                abort_startup \
                    "agent_unhealthy"

                exit 0
            fi

            echo "SIGNALING_HEALTHY=1" \
                >> launch.log

            echo "AGENT_HEALTHY=1" \
                >> launch.log

            LOCAL_URL_VALUE=""
            PUBLIC_URL_VALUE=""

            # ------------------------------------------------------
            # Dual-Link path 1: Local URL (always attempted).
            # A failure here never stops Signaling, Agent or Cloudflare.
            # ------------------------------------------------------
            echo "LOCAL_URL_DETECTION_START=1" \
                >> launch.log

            LOCAL_IP=${'$'}(
                ip route get 1.1.1.1 \
                    2>/dev/null \
                    | sed -n \
                        's/.* src \([0-9.]*\).*/\1/p' \
                    | head -n 1
            )

            if [ -z "${'$'}LOCAL_IP" ]; then
                LOCAL_IP=${'$'}(
                    ip -4 addr show \
                        2>/dev/null \
                        | grep -oE \
                            'inet (192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)[0-9.]+' \
                        | awk \
                            '{print ${'$'}2}' \
                        | head -n 1
                )
            fi

            if [ -n "${'$'}LOCAL_IP" ]; then
                LOCAL_URL_VALUE="https://${'$'}LOCAL_IP:$port"

                echo "LOCAL_IP=${'$'}LOCAL_IP" \
                    >> launch.log

                echo "LOCAL_URL_READY=1" \
                    >> launch.log

                echo "LOCAL_URL=${'$'}LOCAL_URL_VALUE" \
                    >> launch.log
            else
                echo "LOCAL_URL_READY=0" \
                    >> launch.log
            fi

            # ------------------------------------------------------
            # Dual-Link path 2: Cloudflare Quick Tunnel (always
            # attempted, whatever the network type is).
            # A failure here never stops Signaling, Agent or Local.
            # ------------------------------------------------------
            if ! token_is_valid; then
                echo "NETWORK=$networkLabel"
                echo "LINK_ERROR=start operation was cancelled"

                abort_startup \
                    "token_invalid_before_cloudflared"

                exit 0
            fi

            : > cloudflared.log

            echo "CLOUDFLARED_STARTING=1" \
                >> launch.log

            nohup ./cloudflared tunnel \
                --url "https://127.0.0.1:$port" \
                --no-tls-verify \
                --no-autoupdate \
                >> cloudflared.log 2>&1 &

            CLOUDFLARED_PID=${'$'}!

            echo "${'$'}CLOUDFLARED_PID" \
                > cloudflared.pid

            echo \
                "CLOUDFLARED_PID=${'$'}CLOUDFLARED_PID" \
                >> launch.log

            URL=""

            for i in ${'$'}(seq 1 45); do
                if ! token_is_valid; then
                    echo "NETWORK=$networkLabel"
                    echo "LINK_ERROR=start operation was cancelled"

                    abort_startup \
                        "token_invalid_during_cloudflared"

                    exit 0
                fi

                if ! pid_matches_executable \
                    "${'$'}CLOUDFLARED_PID" \
                    "$target/cloudflared"; then

                    echo "CLOUDFLARED_EXITED=1" \
                        >> launch.log

                    break
                fi

                # api.trycloudflare.com is the Cloudflare API endpoint,
                # never a real Quick Tunnel. It is rejected explicitly.
                URL=${'$'}(
                    grep -oE \
                        'https://[a-z0-9-]+\.trycloudflare\.com' \
                        cloudflared.log \
                        2>/dev/null \
                        | grep -v \
                            '^https://api\.trycloudflare\.com${'$'}' \
                        | head -n 1
                )

                if [ -n "${'$'}URL" ]; then
                    if pid_matches_executable \
                        "${'$'}CLOUDFLARED_PID" \
                        "$target/cloudflared"; then

                        break
                    fi

                    echo "CLOUDFLARED_EXITED=1" \
                        >> launch.log

                    URL=""

                    break
                fi

                sleep 1
            done

            if [ -n "${'$'}URL" ]; then
                PUBLIC_URL_VALUE="${'$'}URL"

                echo "CLOUDFLARED_READY=1" \
                    >> launch.log

                echo "PUBLIC_URL=${'$'}PUBLIC_URL_VALUE" \
                    >> launch.log
            else
                echo "CLOUDFLARED_READY=0" \
                    >> launch.log

                echo "--- cloudflared.log ---" \
                    >> launch.log

                tail -n 100 cloudflared.log \
                    >> launch.log \
                    2>/dev/null

                # Only the failed tunnel process is stopped here.
                # Signaling and Agent are left untouched.
                stop_cloudflared_only
            fi

            # ------------------------------------------------------
            # Final decision.
            # ------------------------------------------------------
            echo "NETWORK=$networkLabel"

            if [ -n "${'$'}LOCAL_URL_VALUE" ] ||
               [ -n "${'$'}PUBLIC_URL_VALUE" ]; then

                echo "LINKS_READY=1" \
                    >> launch.log

                if [ -n "${'$'}LOCAL_URL_VALUE" ]; then
                    echo "LOCAL_URL=${'$'}LOCAL_URL_VALUE"
                fi

                if [ -n "${'$'}PUBLIC_URL_VALUE" ]; then
                    echo "PUBLIC_URL=${'$'}PUBLIC_URL_VALUE"
                fi
            else
                echo "LINKS_READY=0" \
                    >> launch.log

                echo "LINK_ERROR=no usable local or public link"
                echo "--- cloudflared.log ---"
                tail -n 100 cloudflared.log \
                    2>/dev/null
                echo "--- launch.log ---"
                cat launch.log \
                    2>/dev/null

                abort_startup \
                    "no_links_available"
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

    /**
     * Reads only the "LOCAL_URL=" marker. Never relies on the first
     * https:// found in the output.
     */
    private fun extractLocalUrl(
        output: String
    ): String? {
        return output.lineSequence()
            .map {
                it.trim()
            }
            .firstOrNull {
                it.startsWith(
                    "LOCAL_URL="
                )
            }
            ?.removePrefix(
                "LOCAL_URL="
            )
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    /**
     * Reads only the "PUBLIC_URL=" marker. Defensively rejects the
     * Cloudflare API endpoint (api.trycloudflare.com), which is never
     * a real Quick Tunnel URL.
     */
    private fun extractPublicUrl(
        output: String
    ): String? {
        return output.lineSequence()
            .map {
                it.trim()
            }
            .filter {
                it.startsWith(
                    "PUBLIC_URL="
                )
            }
            .map {
                it.removePrefix(
                    "PUBLIC_URL="
                ).trim()
            }
            .filter {
                it.isNotBlank()
            }
            .firstOrNull {
                !it.contains(
                    "api.trycloudflare.com",
                    ignoreCase = true
                )
            }
    }

    private fun extractNetwork(
        output: String
    ): String {
        return output.lineSequence()
            .map {
                it.trim()
            }
            .firstOrNull {
                it.startsWith(
                    "NETWORK="
                )
            }
            ?.removePrefix(
                "NETWORK="
            )
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: "Unknown"
    }

    private fun safeLogSummary(
        message: String?
    ): String {
        if (message.isNullOrBlank()) {
            return "reason=unknown"
        }

        return message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(300)
    }

    private fun isCurrentOperation(
        operationId: Long
    ): Boolean {
        return startInProgress.get() &&
            activeOperationId.get() ==
                operationId
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

        private val operationCounter =
            AtomicLong(0L)

        private val activeOperationId =
            AtomicLong(0L)
    }
}
