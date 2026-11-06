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

    interface CloudflareRepairCallback {

        fun onSuccess(
            publicUrl: String
        )

        fun onError(
            message: String
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

        /*
         * Invalidate any Cloudflare-only repair that may still be
         * running. Any in-flight repair callback will observe a
         * stale generation and refuse to deliver a success result.
         * The repair lock is released defensively so that future
         * repairs remain possible even if a repair script is still
         * unwinding.
         */
        cloudflareRepairGeneration.incrementAndGet()
        cloudflareRepairInProgress.set(false)

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

        /*
         * Invalidate any in-flight Cloudflare-only repair so that
         * its script cannot launch a fresh cloudflared after this
         * stop, and so that its callback will not deliver a stale
         * success result.
         */
        cloudflareRepairGeneration.incrementAndGet()
        cloudflareRepairInProgress.set(false)

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

    fun isCloudflaredRunning(
        callback: (Boolean) -> Unit
    ) {
        val target =
            RemoteControlConfig.WORK_DIR

        val command = """
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

            if [ ! -s "$target/cloudflared.pid" ]; then
                echo CLOUDFLARED_STOPPED
                exit 0
            fi

            PID=${'$'}(
                cat "$target/cloudflared.pid" \
                    2>/dev/null || true
            )

            if pid_matches_executable \
                "${'$'}PID" \
                "$target/cloudflared"; then

                echo CLOUDFLARED_RUNNING
            else
                echo CLOUDFLARED_STOPPED
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
                                it == "CLOUDFLARED_RUNNING"
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

    private fun writeCloudflareRepairGeneration(
        generation: Long,
        callback: (Boolean) -> Unit
    ) {
        val target =
            RemoteControlConfig.WORK_DIR

        val command = """
            set +e

            target="$target"
            token_file="${'$'}target/.cloudflare_repair_token"
            tmp_file="${'$'}target/.cloudflare_repair_token.tmp"
            gen="$generation"

            test -d "${'$'}target" || {
                echo TOKEN_WRITE_FAILED
                exit 0
            }

            printf '%s\n' "${'$'}gen" > "${'$'}tmp_file" || {
                rm -f "${'$'}tmp_file"
                echo TOKEN_WRITE_FAILED
                exit 0
            }

            mv "${'$'}tmp_file" "${'$'}token_file" || {
                rm -f "${'$'}tmp_file"
                echo TOKEN_WRITE_FAILED
                exit 0
            }

            ACTUAL=${'$'}(cat "${'$'}token_file" 2>/dev/null || true)

            if [ "${'$'}ACTUAL" = "${'$'}gen" ]; then
                echo TOKEN_WRITE_OK
            else
                echo TOKEN_WRITE_FAILED
            fi
        """.trimIndent()

        val callbackDelivered =
            AtomicBoolean(false)

        fun completeOnce(
            success: Boolean
        ) {
            if (
                callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                callback(success)
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

                    val success =
                        output.lineSequence()
                            .map {
                                it.trim()
                            }
                            .any {
                                it == "TOKEN_WRITE_OK"
                            }

                    completeOnce(success)
                }

                override fun onCommandError(
                    error: String
                ) {
                    completeOnce(false)
                }
            },
            lineBundle = 200
        )
    }

    fun restartCloudflaredOnly(
        callback: CloudflareRepairCallback
    ) {
        if (startInProgress.get()) {
            EventLogger.logStage(
                stage = "CLOUDFLARE_REPAIR",
                status = "SKIPPED",
                details = "reason=full_start_in_progress"
            )

            callback.onError(
                "Cloudflare repair cannot start while engine startup is in progress"
            )

            return
        }

        if (!cloudflareRepairInProgress.compareAndSet(false, true)) {
            EventLogger.logStage(
                stage = "CLOUDFLARE_REPAIR",
                status = "SKIPPED",
                details = "reason=repair_already_in_progress"
            )

            callback.onError(
                "Cloudflare repair is already in progress"
            )

            return
        }

        /*
         * Every repair owns a unique generation. A newer generation
         * can only be produced by start(), stop(), or another repair.
         * If any of those happen while we are running, this repair
         * becomes stale and must not deliver a success result, nor
         * start cloudflared after stop() has killed everything.
         */
        val repairGeneration =
            cloudflareRepairGeneration.incrementAndGet()

        EventLogger.logStage(
            stage = "CLOUDFLARE_REPAIR",
            status = "REQUESTED",
            details = "operation=cloudflared_only"
        )

        val accumulated =
            StringBuilder()

        val callbackDelivered =
            AtomicBoolean(false)

        fun completeOnce(
            success: Boolean,
            publicUrl: String?,
            message: String?
        ) {
            if (
                !callbackDelivered.compareAndSet(
                    false,
                    true
                )
            ) {
                return
            }

            val currentGeneration =
                cloudflareRepairGeneration.get()

            val stale =
                currentGeneration != repairGeneration

            /*
             * Release the repair lock only when our generation is
             * still the current one. If a newer generation exists,
             * either start()/stop() already reset the flag, or a
             * newer repair owns it via CAS(false, true) — in both
             * cases we must not clobber it.
             */
            if (!stale) {
                cloudflareRepairInProgress.set(false)
            }

            if (stale) {
                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_generation"
                )

                return
            }

            if (success && publicUrl != null) {
                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR",
                    status = "OK",
                    details = "publicUrlAvailable=true"
                )

                callback.onSuccess(
                    publicUrl
                )
            } else {
                val safeMessage =
                    message
                        ?: "Cloudflare repair failed"

                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR",
                    status = "FAILED",
                    details = safeLogSummary(safeMessage)
                )

                callback.onError(
                    safeMessage
                )
            }
        }

        /*
         * The generation token is written here, by the Kotlin bridge,
         * before the repair script is ever executed. The script is
         * strictly read-only with respect to the token file so that a
         * superseded repair cannot recreate it after Full Start or
         * Full Stop has invalidated it.
         */
        writeCloudflareRepairGeneration(
            repairGeneration
        ) { writeOk ->
            if (cloudflareRepairGeneration.get() != repairGeneration) {
                EventLogger.logStage(
                    stage = "CLOUDFLARE_REPAIR",
                    status = "CANCELLED",
                    details = "reason=stale_before_script"
                )

                return@writeCloudflareRepairGeneration
            }

            if (!writeOk) {
                completeOnce(
                    false,
                    null,
                    "Failed to persist Cloudflare repair generation token"
                )

                return@writeCloudflareRepairGeneration
            }

            if (!cloudflareRepairInProgress.get()) {
                completeOnce(
                    false,
                    null,
                    "Cloudflare repair was cancelled"
                )

                return@writeCloudflareRepairGeneration
            }

            ShizukuRunner.executeCommand(
                buildCloudflareRepairScript(
                    repairGeneration
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

                        val successMarker =
                            fullOutput.lineSequence()
                                .map {
                                    it.trim()
                                }
                                .any {
                                    it == "CLOUDFLARE_REPAIR_OK"
                                }

                        val cancelledMarker =
                            fullOutput.lineSequence()
                                .map {
                                    it.trim()
                                }
                                .any {
                                    it == "CLOUDFLARE_REPAIR_CANCELLED"
                                }

                        if (cancelledMarker) {
                            completeOnce(
                                false,
                                null,
                                "Cloudflare repair was cancelled"
                            )

                            return
                        }

                        if (successMarker) {
                            val publicUrl =
                                extractPublicUrl(
                                    fullOutput
                                )

                            if (publicUrl != null) {
                                completeOnce(
                                    true,
                                    publicUrl,
                                    null
                                )
                            } else {
                                completeOnce(
                                    false,
                                    null,
                                    buildString {
                                        appendLine(
                                            "Cloudflare repair reported OK but no valid URL was parsed."
                                        )

                                        if (fullOutput.isNotBlank()) {
                                            appendLine()
                                            append(
                                                fullOutput.takeLast(2400)
                                            )
                                        }
                                    }
                                )
                            }
                        } else {
                            completeOnce(
                                false,
                                null,
                                buildString {
                                    appendLine(
                                        "Cloudflare repair failed."
                                    )

                                    if (fullOutput.isNotBlank()) {
                                        appendLine()
                                        append(
                                            fullOutput.takeLast(2400)
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
                            null,
                            buildString {
                                appendLine(
                                    "Cloudflare repair failed."
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
                lineBundle = 3600
            )
        }
    }

    private fun buildCloudflareRepairScript(
        repairGeneration: Long
    ): String {
        val target =
            RemoteControlConfig.WORK_DIR

        val port =
            RemoteControlConfig.SIGNALING_PORT

        return """
            set +e

            EXPECTED_REPAIR_GEN="$repairGeneration"
            repair_token_file="$target/.cloudflare_repair_token"

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

            repair_token_is_valid() {
                [ -s "${'$'}repair_token_file" ] ||
                    return 1

                CURRENT=${'$'}(
                    cat "${'$'}repair_token_file" \
                        2>/dev/null || true
                )

                [ "${'$'}CURRENT" = "${'$'}EXPECTED_REPAIR_GEN" ]
            }

            cleanup_repair_token() {
                if [ -s "${'$'}repair_token_file" ]; then
                    CURRENT=${'$'}(
                        cat "${'$'}repair_token_file" \
                            2>/dev/null || true
                    )

                    if [ "${'$'}CURRENT" = "${'$'}EXPECTED_REPAIR_GEN" ]; then
                        rm -f "${'$'}repair_token_file"
                    fi
                fi
            }

            kill_our_cloudflared() {
                OUR_PID="${'$'}1"

                if [ -z "${'$'}OUR_PID" ]; then
                    return
                fi

                if pid_matches_executable \
                    "${'$'}OUR_PID" \
                    "$target/cloudflared"; then

                    kill -15 "${'$'}OUR_PID" \
                        2>/dev/null || true

                    sleep 1

                    if pid_matches_executable \
                        "${'$'}OUR_PID" \
                        "$target/cloudflared"; then

                        kill -9 "${'$'}OUR_PID" \
                            2>/dev/null || true
                    fi
                fi

                if [ -s cloudflared.pid ]; then
                    CURRENT_PID=${'$'}(
                        cat cloudflared.pid \
                            2>/dev/null || true
                    )

                    if [ "${'$'}CURRENT_PID" = "${'$'}OUR_PID" ]; then
                        rm -f cloudflared.pid
                    fi
                fi
            }

            abort_repair_cancelled() {
                cleanup_repair_token

                echo "CLOUDFLARE_REPAIR_CANCELLED"
                exit 0
            }

            # ---------------------------------------------------
            # The generation token is written by the Kotlin bridge
            # before this script is executed. This script is
            # strictly read-only with respect to the token file
            # so that a superseded repair cannot recreate it
            # after Full Start or Full Stop has invalidated it.
            # ---------------------------------------------------
            if ! repair_token_is_valid; then
                abort_repair_cancelled
            fi

            # ---------------------------------------------------
            # Gate 0: Required runtime files must exist.
            # ---------------------------------------------------
            if [ ! -s "$target/cloudflared" ] ||
               [ ! -s "$target/webrtc-signaling" ] ||
               [ ! -s "$target/cloudphone-agent" ]; then

                cleanup_repair_token
                echo "CLOUDFLARE_REPAIR_ERROR=missing_runtime_files"
                exit 0
            fi

            # ---------------------------------------------------
            # Gate 1: Signaling must be healthy.
            # ---------------------------------------------------
            if ! repair_token_is_valid; then
                abort_repair_cancelled
            fi

            SIGNALING_OK=0

            if [ -s "$target/signaling.pid" ]; then
                SIG_PID=${'$'}(
                    cat "$target/signaling.pid" \
                        2>/dev/null || true
                )

                if pid_matches_executable \
                    "${'$'}SIG_PID" \
                    "$target/webrtc-signaling"; then

                    SIGNALING_OK=1
                fi
            fi

            if [ "${'$'}SIGNALING_OK" -ne 1 ]; then
                cleanup_repair_token
                echo "CLOUDFLARE_REPAIR_ERROR=core_not_healthy"
                exit 0
            fi

            # ---------------------------------------------------
            # Gate 2: Agent must be healthy.
            # ---------------------------------------------------
            if ! repair_token_is_valid; then
                abort_repair_cancelled
            fi

            AGENT_OK=0

            if [ -s "$target/agent.pid" ]; then
                AGENT_PID=${'$'}(
                    cat "$target/agent.pid" \
                        2>/dev/null || true
                )

                if pid_matches_executable \
                    "${'$'}AGENT_PID" \
                    "$target/cloudphone-agent"; then

                    AGENT_OK=1
                fi
            fi

            if [ "${'$'}AGENT_OK" -ne 1 ]; then
                cleanup_repair_token
                echo "CLOUDFLARE_REPAIR_ERROR=core_not_healthy"
                exit 0
            fi

            # ---------------------------------------------------
            # Step 1: Stop any cloudflared instance only.
            #
            # Signaling and Agent are never signalled here.
            # Persistent data and working directory are untouched.
            # ---------------------------------------------------
            if ! repair_token_is_valid; then
                abort_repair_cancelled
            fi

            if [ -s "$target/cloudflared.pid" ]; then
                OLD_PID=${'$'}(
                    cat "$target/cloudflared.pid" \
                        2>/dev/null || true
                )

                if pid_matches_executable \
                    "${'$'}OLD_PID" \
                    "$target/cloudflared"; then

                    kill -15 "${'$'}OLD_PID" \
                        2>/dev/null || true
                fi
            fi

            for PID in ${'$'}(
                list_matching_executable \
                    "$target/cloudflared"
            ); do
                kill -15 "${'$'}PID" \
                    2>/dev/null || true
            done

            for i in ${'$'}(seq 1 5); do
                REMAINING=${'$'}(
                    list_matching_executable \
                        "$target/cloudflared"
                )

                if [ -z "${'$'}REMAINING" ]; then
                    break
                fi

                sleep 1
            done

            for PID in ${'$'}(
                list_matching_executable \
                    "$target/cloudflared"
            ); do
                kill -9 "${'$'}PID" \
                    2>/dev/null || true
            done

            sleep 1

            REMAINING=${'$'}(
                list_matching_executable \
                    "$target/cloudflared"
            )

            if [ -n "${'$'}REMAINING" ]; then
                cleanup_repair_token
                echo "CLOUDFLARE_REPAIR_ERROR=cloudflared_still_running"
                exit 0
            fi

            rm -f "$target/cloudflared.pid"

            if ! repair_token_is_valid; then
                abort_repair_cancelled
            fi

            # ---------------------------------------------------
            # Step 2: Start a fresh Quick Tunnel only.
            # ---------------------------------------------------
            cd "$target" || {
                cleanup_repair_token
                echo "CLOUDFLARE_REPAIR_ERROR=cannot_enter_workdir"
                exit 0
            }

            if ! repair_token_is_valid; then
                abort_repair_cancelled
            fi

            : > cloudflared.log

            nohup ./cloudflared tunnel \
                --url "https://127.0.0.1:$port" \
                --no-tls-verify \
                --no-autoupdate \
                >> cloudflared.log 2>&1 &

            CLOUDFLARED_PID=${'$'}!

            echo "${'$'}CLOUDFLARED_PID" \
                > cloudflared.pid

            URL=""

            for i in ${'$'}(seq 1 45); do
                if ! repair_token_is_valid; then
                    kill_our_cloudflared \
                        "${'$'}CLOUDFLARED_PID"

                    abort_repair_cancelled
                fi

                if ! pid_matches_executable \
                    "${'$'}CLOUDFLARED_PID" \
                    "$target/cloudflared"; then

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

                    URL=""

                    break
                fi

                sleep 1
            done

            # ---------------------------------------------------
            # Step 3: Verify outcome.
            # ---------------------------------------------------
            if ! repair_token_is_valid; then
                kill_our_cloudflared \
                    "${'$'}CLOUDFLARED_PID"

                abort_repair_cancelled
            fi

            if [ -z "${'$'}URL" ]; then
                kill_our_cloudflared \
                    "${'$'}CLOUDFLARED_PID"

                cleanup_repair_token

                echo "CLOUDFLARE_REPAIR_ERROR=no_url_after_timeout"
                echo "--- cloudflared.log ---"
                tail -n 100 cloudflared.log \
                    2>/dev/null

                exit 0
            fi

            if ! pid_matches_executable \
                "${'$'}CLOUDFLARED_PID" \
                "$target/cloudflared"; then

                if [ -s cloudflared.pid ]; then
                    CURRENT_PID=${'$'}(
                        cat cloudflared.pid \
                            2>/dev/null || true
                    )

                    if [ "${'$'}CURRENT_PID" = "${'$'}CLOUDFLARED_PID" ]; then
                        rm -f cloudflared.pid
                    fi
                fi

                cleanup_repair_token

                echo "CLOUDFLARE_REPAIR_ERROR=cloudflared_died"
                echo "--- cloudflared.log ---"
                tail -n 100 cloudflared.log \
                    2>/dev/null

                exit 0
            fi

            if ! repair_token_is_valid; then
                kill_our_cloudflared \
                    "${'$'}CLOUDFLARED_PID"

                abort_repair_cancelled
            fi

            cleanup_repair_token

            echo "CLOUDFLARE_REPAIR_OK"
            echo "PUBLIC_URL=${'$'}URL"
        """.trimIndent()
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
            rm -f "${'$'}{target}/.cloudflare_repair_token"

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

            cleanup_stage() {
                echo "CLEANUP_STAGE=${'$'}1"
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

            list_matching_executables_once() {
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

                    case "${'$'}ACTUAL_EXE" in
                        "$target/cloudflared"|"$target/cloudphone-agent"|"$target/webrtc-signaling")
                            echo "${'$'}PID"
                            ;;
                    esac
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

            signal_matching_executables() {
                SIGNAL="${'$'}1"

                for PID in ${'$'}(list_matching_executables_once); do
                    echo \
                        "SIGNAL_MATCH signal=${'$'}SIGNAL pid=${'$'}PID"

                    kill "-${'$'}SIGNAL" \
                        "${'$'}PID" \
                        2>/dev/null || true
                done
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

            cleanup_stage INVALIDATE_TOKENS
            rm -f "$target/.launch_token"
            rm -f "$target/.cloudflare_repair_token"

            echo CLEANUP_START

            cleanup_stage TERM_PID_FILES
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

            cleanup_stage TERM_EXECUTABLE_SCAN
            signal_matching_executables 15

            cleanup_stage WAIT_FOR_GRACEFUL_EXIT
            for i in ${'$'}(seq 1 5); do
                REMAINING=${'$'}(
                    list_matching_executables_once
                )

                if [ -z "${'$'}REMAINING" ]; then
                    break
                fi

                sleep 1
            done

            cleanup_stage KILL_PID_FILES
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

            cleanup_stage KILL_EXECUTABLE_SCAN
            signal_matching_executables 9

            cleanup_stage FINAL_PROCESS_WAIT
            sleep 2

            cleanup_stage VERIFY_PROCESSES
            REMAINING=${'$'}(
                list_matching_executables_once
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

            cleanup_stage VERIFY_PORT
            if port_is_listening; then
                echo "CLEANUP_FAILED=port_${port}_still_listening"

                if command -v ss >/dev/null 2>&1; then
                    ss -ltnp 2>/dev/null \
                        | grep ":$port" \
                        || true
                fi

                exit 1
            fi

            cleanup_stage REMOVE_PID_FILES
            rm -f "$target/cloudflared.pid"
            rm -f "$target/agent.pid"
            rm -f "$target/signaling.pid"

            cleanup_stage COMPLETE
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
                    synchronized(accumulated) {
                        accumulated.append(
                            output
                        )
                    }

                    if (!done) {
                        return
                    }

                    val result =
                        synchronized(accumulated) {
                            accumulated.toString()
                        }

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
                                        result.takeLast(3000)
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
                        synchronized(accumulated) {
                            accumulated.toString()
                        }

                    completeOnce(
                        false,
                        buildString {
                            appendLine(
                                "Remote-control cleanup failed."
                            )

                            if (error.isNotBlank()) {
                                appendLine()
                                appendLine(
                                    error.take(1800)
                                )
                            }

                            if (result.isNotBlank()) {
                                appendLine()
                                appendLine(
                                    "Cleanup progress:"
                                )
                                append(
                                    result.takeLast(3000)
                                )
                            }
                        }
                    )
                }
            },
            lineBundle = 1,
            timeoutMs = CLEANUP_TIMEOUT_MS
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

        private const val CLEANUP_TIMEOUT_MS =
            45_000L

        private const val HEALTH_CHECK_TIMEOUT_MS =
            15_000L

        private const val WORKDIR_TIMEOUT_MS =
            120_000L

        private const val LAUNCH_TIMEOUT_MS =
            120_000L

        private const val CLOUDFLARE_REPAIR_TIMEOUT_MS =
            90_000L

        private val startInProgress =
            AtomicBoolean(false)

        private val operationCounter =
            AtomicLong(0L)

        private val activeOperationId =
            AtomicLong(0L)

        private val cloudflareRepairInProgress =
            AtomicBoolean(false)

        private val cloudflareRepairGeneration =
            AtomicLong(0L)
    }
}
