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
            lineBundle = 200,
            timeoutMs = HEALTH_CHECK_TIMEOUT_MS
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
            lineBundle = 200,
            timeoutMs = HEALTH_CHECK_TIMEOUT_MS
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
            lineBundle = 200,
            timeoutMs = HEALTH_CHECK_TIMEOUT_MS
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
                lineBundle = 3600,
                timeoutMs = CLOUDFLARE_REPAIR_TIMEOUT_MS
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

                [ "${'$'}EXPECTED_EXE" = "$target/cloudflared" ] ||
                    return 1

                MATCHING_CANDIDATES=""

                if command -v pidof >/dev/null 2>&1; then
                    MATCHING_CANDIDATES=${'$'}(
                        pidof cloudflared \
                            2>/dev/null || true
                    )
                elif command -v toybox >/dev/null 2>&1 &&
                     toybox pidof --help >/dev/null 2>&1; then
                    MATCHING_CANDIDATES=${'$'}(
                        toybox pidof cloudflared \
                            2>/dev/null || true
                    )
                else
                    return 1
                fi

                for PID in ${'$'}MATCHING_CANDIDATES; do
                    case "${'$'}PID" in
                        ""|*[!0-9]*)
                            continue
                            ;;
                    esac

                    [ "${'$'}PID" -gt 1 ] 2>/dev/null ||
                        continue

                    if pid_matches_executable \
                        "${'$'}PID" \
                        "${'$'}EXPECTED_EXE"; then

                        echo "${'$'}PID"
                    fi
                done

                return 0
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

            if command -v pidof >/dev/null 2>&1; then
                :
            elif command -v toybox >/dev/null 2>&1 &&
                 toybox pidof --help >/dev/null 2>&1; then
                :
            else
                cleanup_repair_token
                echo "CLOUDFLARE_REPAIR_ERROR=targeted_discovery_unavailable"
                exit 0
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
                if pid_matches_executable \
                    "${'$'}PID" \
                    "$target/cloudflared"; then

                    kill -15 "${'$'}PID" \
                        2>/dev/null || true
                fi
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
                if pid_matches_executable \
                    "${'$'}PID" \
                    "$target/cloudflared"; then

                    kill -9 "${'$'}PID" \
                        2>/dev/null || true
                fi
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

            nohup env \
                GODEBUG=netdns=cgo+1 \
                ./cloudflared tunnel \
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
            lineBundle = 1600,
            timeoutMs = WORKDIR_TIMEOUT_MS
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
            rm -f "${'$'}{target}/agent.pgid"
            rm -f "${'$'}{target}/agent.sid"
            rm -f "${'$'}{target}/agent.pgid.tmp"
            rm -f "${'$'}{target}/agent.sid.tmp"
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
     *
     * The orchestration uses the following stages:
     *
     * 1. INVALIDATE_TOKENS
     * 2. READ_SHELL_METADATA
     * 3. COLLECT_PID_FILES
     * 4. LOAD_STORED_AGENT_METADATA
     * 5. LOAD_VERIFIED_AGENT_GROUP
     * 6. COLLECT_ORPHANS
     * 7. TERM_COLLECTED_PIDS
     * 8. WAIT_FOR_GRACEFUL_EXIT
     * 9. KILL_REMAINING_PIDS
     * 10. FINAL_PROCESS_WAIT
     * 11. FINAL_ORPHAN_SCAN
     * 12. VERIFY_PROCESSES
     * 13. VERIFY_PORT
     * 14. REMOVE_PID_FILES
     * 15. COMPLETE
     *
     * CoreService children of the Agent are killed individually as
     * PIDs after strict identity checks. Group signals are only sent
     * to a verified Agent process group (leader alive, PID == PGID
     * == SID, executable matches), and only when the Agent group is
     * distinct from the Cleanup Shell's own group and session.
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

            # --------------------------------------------------
            # Timing instrumentation (diagnostic only).
            #
            # cleanup_now_ms returns current epoch time in
            # milliseconds when the platform supports a reliable
            # millisecond clock, otherwise it falls back to
            # epoch seconds * 1000. If neither is numeric, it
            # prints nothing and never fails the cleanup.
            # --------------------------------------------------
            CTT_START_MS=""
            CTT_VALID=0
            CTS_CURRENT=""
            CTS_START_MS=""

            cleanup_now_ms() {
                CNM_RAW=${'$'}(date +%s%3N 2>/dev/null || true)
                case "${'$'}CNM_RAW" in
                    ""|*[!0-9]*) CNM_RAW="" ;;
                esac
                if [ -n "${'$'}CNM_RAW" ]; then
                    CNM_LEN=${'$'}{#CNM_RAW}
                    if [ "${'$'}CNM_LEN" -lt 13 ] ||
                       [ "${'$'}CNM_LEN" -gt 14 ]; then
                        CNM_RAW=""
                    fi
                fi
                if [ -n "${'$'}CNM_RAW" ]; then
                    printf '%s' "${'$'}CNM_RAW"
                    return 0
                fi
                CNM_SECS=${'$'}(date +%s 2>/dev/null || true)
                case "${'$'}CNM_SECS" in
                    ""|*[!0-9]*) return 0 ;;
                esac
                printf '%s' "${'$'}((CNM_SECS * 1000))"
                return 0
            }

            cleanup_close_current_stage() {
                if [ -z "${'$'}CTS_CURRENT" ]; then
                    return 0
                fi
                CTS_NOW_MS=${'$'}(cleanup_now_ms)
                case "${'$'}CTS_NOW_MS" in
                    ""|*[!0-9]*) CTS_END_VALID=0 ;;
                    *) CTS_END_VALID=1 ;;
                esac
                case "${'$'}CTS_START_MS" in
                    ""|*[!0-9]*) CTS_START_VALID=0 ;;
                    *) CTS_START_VALID=1 ;;
                esac
                if [ "${'$'}CTS_END_VALID" = "1" ] &&
                   [ "${'$'}CTS_START_VALID" = "1" ] &&
                   [ "${'$'}CTT_VALID" = "1" ]; then
                    CTS_DUR=${'$'}((CTS_NOW_MS - CTS_START_MS))
                    CTS_ELAPSED=${'$'}((CTS_NOW_MS - CTT_START_MS))
                    echo "CLEANUP_TIMING_END stage=${'$'}CTS_CURRENT durationMs=${'$'}CTS_DUR elapsedMs=${'$'}CTS_ELAPSED"
                else
                    echo "CLEANUP_TIMING_END stage=${'$'}CTS_CURRENT durationMs=UNAVAILABLE elapsedMs=UNAVAILABLE"
                fi
                CTS_CURRENT=""
                CTS_START_MS=""
                return 0
            }

            cleanup_stage() {
                cleanup_close_current_stage
                CTS_CURRENT="${'$'}1"
                CTS_START_MS=${'$'}(cleanup_now_ms)
                echo "CLEANUP_STAGE=${'$'}1"
                case "${'$'}CTS_START_MS" in
                    ""|*[!0-9]*)
                        echo "CLEANUP_TIMING_START stage=${'$'}1 elapsedMs=UNAVAILABLE"
                        ;;
                    *)
                        if [ "${'$'}CTT_VALID" = "1" ]; then
                            CTS_START_ELAPSED=${'$'}((CTS_START_MS - CTT_START_MS))
                            echo "CLEANUP_TIMING_START stage=${'$'}1 elapsedMs=${'$'}CTS_START_ELAPSED"
                        else
                            echo "CLEANUP_TIMING_START stage=${'$'}1 elapsedMs=UNAVAILABLE"
                        fi
                        ;;
                esac
            }

            normalise_exe() {
                printf '%s' "${'$'}1" \
                    | sed 's/ (deleted)${'$'}//'
            }

            read_process_group_metadata() {
                PID="${'$'}1"
                PROC_STAT="/proc/${'$'}PID/stat"

                [ -r "${'$'}PROC_STAT" ] || return 1

                STAT_LINE=${'$'}(
                    cat "${'$'}PROC_STAT" 2>/dev/null
                ) || return 1

                [ -n "${'$'}STAT_LINE" ] || return 1

                case "${'$'}STAT_LINE" in
                    *") "*)
                        STAT_REST=${'$'}{STAT_LINE#*) }
                        ;;
                    *)
                        return 1
                        ;;
                esac

                set -- ${'$'}STAT_REST

                [ "${'$'}#" -ge 4 ] || return 1

                PROCESS_STATE="${'$'}1"
                PROCESS_PPID="${'$'}2"
                PROCESS_PGID="${'$'}3"
                PROCESS_SID="${'$'}4"

                case "${'$'}PROCESS_PGID" in
                    ""|*[!0-9]*)
                        return 1
                        ;;
                esac

                case "${'$'}PROCESS_SID" in
                    ""|*[!0-9]*)
                        return 1
                        ;;
                esac

                return 0
            }

            valid_numeric_id() {
                case "${'$'}1" in
                    ""|*[!0-9]*) return 1 ;;
                esac
                [ "${'$'}1" -gt 1 ] 2>/dev/null || return 1
                return 0
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

            # --------------------------------------------------
            # Collected PIDs list (space-separated entries of
            # the form <pid>:<type>).
            # --------------------------------------------------
            COLLECTED_LIST=""

            add_collected() {
                AC_PID="${'$'}1"
                AC_TYPE="${'$'}2"

                case "${'$'}AC_PID" in
                    ""|*[!0-9]*) return 0 ;;
                esac

                for AC_ENTRY in ${'$'}COLLECTED_LIST; do
                    if [ "${'$'}{AC_ENTRY%%:*}" = "${'$'}AC_PID" ]; then
                        return 0
                    fi
                done

                COLLECTED_LIST="${'$'}COLLECTED_LIST ${'$'}AC_PID:${'$'}AC_TYPE"
            }

            collected_type() {
                CT_PID="${'$'}1"

                for CT_ENTRY in ${'$'}COLLECTED_LIST; do
                    if [ "${'$'}{CT_ENTRY%%:*}" = "${'$'}CT_PID" ]; then
                        echo "${'$'}{CT_ENTRY##*:}"
                        return 0
                    fi
                done

                return 1
            }

            collected_pid_still_alive() {
                CA_PID="${'$'}1"

                CA_TYPE=${'$'}(collected_type "${'$'}CA_PID")
                [ -n "${'$'}CA_TYPE" ] || return 1

                [ -d "/proc/${'$'}CA_PID" ] || return 1
                kill -0 "${'$'}CA_PID" 2>/dev/null || return 1

                case "${'$'}CA_TYPE" in
                    cloudflared)
                        CA_EXE=${'$'}(
                            readlink "/proc/${'$'}CA_PID/exe" \
                                2>/dev/null || true
                        )
                        CA_EXE=${'$'}(normalise_exe "${'$'}CA_EXE")
                        [ "${'$'}CA_EXE" = "$target/cloudflared" ]
                        ;;
                    agent)
                        CA_EXE=${'$'}(
                            readlink "/proc/${'$'}CA_PID/exe" \
                                2>/dev/null || true
                        )
                        CA_EXE=${'$'}(normalise_exe "${'$'}CA_EXE")
                        [ "${'$'}CA_EXE" = "$target/cloudphone-agent" ]
                        ;;
                    signaling)
                        CA_EXE=${'$'}(
                            readlink "/proc/${'$'}CA_PID/exe" \
                                2>/dev/null || true
                        )
                        CA_EXE=${'$'}(normalise_exe "${'$'}CA_EXE")
                        [ "${'$'}CA_EXE" = "$target/webrtc-signaling" ]
                        ;;
                    core)
                        pid_is_verified_curbox_core_service "${'$'}CA_PID"
                        ;;
                    *)
                        return 1
                        ;;
                esac
            }

            # --------------------------------------------------
            # Shell metadata for the cleanup shell itself.
            # --------------------------------------------------
            CLEANUP_SHELL_PID=${'$'}${'$'}
            CLEANUP_PGID=""
            CLEANUP_SID=""
            CLEANUP_SHELL_METADATA_OK=0

            PROCESS_STATE=""
            PROCESS_PPID=""
            PROCESS_PGID=""
            PROCESS_SID=""

            # --------------------------------------------------
            # Stored Agent metadata (read from files).
            # --------------------------------------------------
            STORED_AGENT_PID=""
            STORED_AGENT_PGID=""
            STORED_AGENT_SID=""

            # --------------------------------------------------
            # Verified Agent group (leader currently alive).
            # --------------------------------------------------
            VERIFIED_AGENT_PID=""
            VERIFIED_AGENT_PGID=""
            VERIFIED_AGENT_SID=""

            # --------------------------------------------------
            # Strict CoreService identity check.
            # --------------------------------------------------
            pid_is_verified_curbox_core_service() {
                PC_PID="${'$'}1"

                case "${'$'}PC_PID" in
                    ""|*[!0-9]*) return 1 ;;
                esac

                [ "${'$'}PC_PID" -gt 1 ] 2>/dev/null || return 1

                kill -0 "${'$'}PC_PID" 2>/dev/null || return 1

                PC_COMM_FILE="/proc/${'$'}PC_PID/comm"
                [ -r "${'$'}PC_COMM_FILE" ] || return 1

                PC_COMM=${'$'}(
                    cat "${'$'}PC_COMM_FILE" 2>/dev/null || true
                )
                case "${'$'}PC_COMM" in
                    app_process|app_process32|app_process64)
                        ;;
                    *)
                        return 1
                        ;;
                esac

                PC_STATUS_FILE="/proc/${'$'}PC_PID/status"
                [ -r "${'$'}PC_STATUS_FILE" ] || return 1

                PC_UID_LINE=${'$'}(
                    grep '^Uid:' "${'$'}PC_STATUS_FILE" \
                        2>/dev/null \
                        | head -n 1
                )
                [ -n "${'$'}PC_UID_LINE" ] || return 1

                set -- ${'$'}PC_UID_LINE
                PC_REAL_UID="${'$'}2"

                [ "${'$'}PC_REAL_UID" = "2000" ] || return 1

                PC_EXE=${'$'}(
                    readlink "/proc/${'$'}PC_PID/exe" \
                        2>/dev/null || true
                )
                PC_EXE=${'$'}(normalise_exe "${'$'}PC_EXE")

                case "${'$'}PC_EXE" in
                    /system/bin/app_process|\
                    /system/bin/app_process32|\
                    /system/bin/app_process64|\
                    /apex/com.android.art/bin/app_process|\
                    /apex/com.android.art/bin/app_process32|\
                    /apex/com.android.art/bin/app_process64)
                        ;;
                    *)
                        return 1
                        ;;
                esac

                PC_CMDLINE_FILE="/proc/${'$'}PC_PID/cmdline"
                [ -r "${'$'}PC_CMDLINE_FILE" ] || return 1

                tr '\000' '\n' \
                    < "${'$'}PC_CMDLINE_FILE" \
                    2>/dev/null \
                    | grep -Fxq 'com.android.helper.CoreService' \
                    || return 1

                read_process_group_metadata "${'$'}PC_PID" \
                    || return 1

                case "${'$'}PROCESS_PGID" in
                    ""|*[!0-9]*) return 1 ;;
                esac
                case "${'$'}PROCESS_SID" in
                    ""|*[!0-9]*) return 1 ;;
                esac

                [ "${'$'}PROCESS_PGID" -gt 1 ] 2>/dev/null || return 1
                [ "${'$'}PROCESS_SID" -gt 1 ] 2>/dev/null || return 1

                [ "${'$'}CLEANUP_SHELL_METADATA_OK" = "1" ] || return 1
                [ "${'$'}PROCESS_PGID" != "${'$'}CLEANUP_PGID" ] || return 1
                [ "${'$'}PROCESS_SID" != "${'$'}CLEANUP_SID" ] || return 1

                PC_MATCHED=0

                if [ -n "${'$'}VERIFIED_AGENT_PGID" ] &&
                   [ "${'$'}PROCESS_PGID" = "${'$'}VERIFIED_AGENT_PGID" ]; then
                    PC_MATCHED=1
                fi
                if [ "${'$'}PC_MATCHED" -eq 0 ] &&
                   [ -n "${'$'}VERIFIED_AGENT_SID" ] &&
                   [ "${'$'}PROCESS_SID" = "${'$'}VERIFIED_AGENT_SID" ]; then
                    PC_MATCHED=1
                fi
                if [ "${'$'}PC_MATCHED" -eq 0 ] &&
                   [ -n "${'$'}STORED_AGENT_PGID" ] &&
                   [ "${'$'}PROCESS_PGID" = "${'$'}STORED_AGENT_PGID" ]; then
                    PC_MATCHED=1
                fi
                if [ "${'$'}PC_MATCHED" -eq 0 ] &&
                   [ -n "${'$'}STORED_AGENT_SID" ] &&
                   [ "${'$'}PROCESS_SID" = "${'$'}STORED_AGENT_SID" ]; then
                    PC_MATCHED=1
                fi

                [ "${'$'}PC_MATCHED" -eq 1 ] || return 1

                return 0
            }
            
            # --------------------------------------------------
            # Targeted CurBox process discovery.
            #
            # Process names produce candidates only. A candidate
            # is never collected until its identity has been
            # verified using the existing strict checks.
            #
            # This deliberately avoids a full /proc traversal.
            # --------------------------------------------------
            DISCOVERY_METHOD=""

            select_discovery_method() {
                if command -v pidof >/dev/null 2>&1; then
                    DISCOVERY_METHOD="pidof"
                    return 0
                fi

                if command -v toybox >/dev/null 2>&1 &&
                   toybox pidof --help >/dev/null 2>&1; then
                    DISCOVERY_METHOD="toybox_pidof"
                    return 0
                fi

                DISCOVERY_METHOD=""
                return 1
            }

            discover_named_pids() {
                case "${'$'}DISCOVERY_METHOD" in
                    pidof)
                        pidof "${'$'}@" \
                            2>/dev/null || true
                        ;;
                    toybox_pidof)
                        toybox pidof "${'$'}@" \
                            2>/dev/null || true
                        ;;
                    *)
                        return 1
                        ;;
                esac

                return 0
            }

            core_discovery_metadata_available() {
                [ "${'$'}CLEANUP_SHELL_METADATA_OK" = "1" ] ||
                    return 1

                if valid_numeric_id "${'$'}VERIFIED_AGENT_PGID" &&
                   [ "${'$'}VERIFIED_AGENT_PGID" != "${'$'}CLEANUP_PGID" ]; then
                    return 0
                fi

                if valid_numeric_id "${'$'}VERIFIED_AGENT_SID" &&
                   [ "${'$'}VERIFIED_AGENT_SID" != "${'$'}CLEANUP_SID" ]; then
                    return 0
                fi

                if valid_numeric_id "${'$'}STORED_AGENT_PGID" &&
                   [ "${'$'}STORED_AGENT_PGID" != "${'$'}CLEANUP_PGID" ]; then
                    return 0
                fi

                if valid_numeric_id "${'$'}STORED_AGENT_SID" &&
                   [ "${'$'}STORED_AGENT_SID" != "${'$'}CLEANUP_SID" ]; then
                    return 0
                fi

                return 1
            }

            collect_curbox_processes() {
                CC_NATIVE_CANDIDATES=0
                CC_CORE_CANDIDATES=0
                CC_VERIFIED=0
                CC_COLLECTED_BEFORE=0
                CC_COLLECTED_AFTER=0

                for CC_ENTRY in ${'$'}COLLECTED_LIST; do
                    CC_COLLECTED_BEFORE=${'$'}((CC_COLLECTED_BEFORE + 1))
                done

                if [ -z "${'$'}DISCOVERY_METHOD" ]; then
                    if ! select_discovery_method; then
                        echo "CLEANUP_FAILED=targeted_discovery_unavailable"
                        cleanup_close_current_stage
                        exit 1
                    fi
                fi

                # ----------------------------------------------
                # Native CurBox processes.
                #
                # pidof only discovers candidate PIDs. Each PID
                # must still match one of our exact executable
                # paths before it can enter COLLECTED_LIST.
                # ----------------------------------------------
                NATIVE_CANDIDATES=${'$'}(
                    discover_named_pids \
                        webrtc-signaling \
                        cloudphone-agent \
                        cloudflared
                )

                for SCAN_PID in ${'$'}NATIVE_CANDIDATES; do
                    if ! valid_numeric_id "${'$'}SCAN_PID"; then
                        continue
                    fi

                    if [ "${'$'}SCAN_PID" = "${'$'}CLEANUP_SHELL_PID" ]; then
                        continue
                    fi

                    CC_NATIVE_CANDIDATES=${'$'}((CC_NATIVE_CANDIDATES + 1))

                    if pid_matches_executable \
                        "${'$'}SCAN_PID" \
                        "$target/webrtc-signaling"; then

                        add_collected \
                            "${'$'}SCAN_PID" \
                            "signaling"

                        CC_VERIFIED=${'$'}((CC_VERIFIED + 1))
                        continue
                    fi

                    if pid_matches_executable \
                        "${'$'}SCAN_PID" \
                        "$target/cloudphone-agent"; then

                        add_collected \
                            "${'$'}SCAN_PID" \
                            "agent"

                        CC_VERIFIED=${'$'}((CC_VERIFIED + 1))
                        continue
                    fi

                    if pid_matches_executable \
                        "${'$'}SCAN_PID" \
                        "$target/cloudflared"; then

                        add_collected \
                            "${'$'}SCAN_PID" \
                            "cloudflared"

                        CC_VERIFIED=${'$'}((CC_VERIFIED + 1))
                        continue
                    fi
                done

                # ----------------------------------------------
                # CoreService.
                #
                # CoreService runs through app_process, so process
                # name alone is never enough. Discovery is skipped
                # completely when no trusted Agent group/session
                # metadata exists.
                # ----------------------------------------------
                if core_discovery_metadata_available; then
                    CORE_CANDIDATES=${'$'}(
                        discover_named_pids \
                            app_process \
                            app_process32 \
                            app_process64
                    )

                    for SCAN_PID in ${'$'}CORE_CANDIDATES; do
                        if ! valid_numeric_id "${'$'}SCAN_PID"; then
                            continue
                        fi

                        if [ "${'$'}SCAN_PID" = "${'$'}CLEANUP_SHELL_PID" ]; then
                            continue
                        fi

                        CC_CORE_CANDIDATES=${'$'}((CC_CORE_CANDIDATES + 1))

                        if pid_is_verified_curbox_core_service \
                            "${'$'}SCAN_PID"; then

                            add_collected \
                                "${'$'}SCAN_PID" \
                                "core"

                            CC_VERIFIED=${'$'}((CC_VERIFIED + 1))
                        fi
                    done
                else
                    echo "CORE_DISCOVERY_SKIPPED reason=no_agent_group_metadata"
                fi

                for CC_ENTRY in ${'$'}COLLECTED_LIST; do
                    CC_COLLECTED_AFTER=${'$'}((CC_COLLECTED_AFTER + 1))
                done

                CC_COLLECTED=${'$'}((CC_COLLECTED_AFTER -CC_COLLECTED_BEFORE))

                echo "CLEANUP_DISCOVERY_SUMMARY stage=${'$'}{CC_SCAN_NAME} method=${'$'}DISCOVERY_METHOD nativeCandidates=${'$'}CC_NATIVE_CANDIDATES coreCandidates=${'$'}CC_CORE_CANDIDATES verified=${'$'}CC_VERIFIED collected=${'$'}CC_COLLECTED"
            }

            # --------------------------------------------------
            # Load verified Agent group.
            # --------------------------------------------------
            load_verified_agent_group() {
                VERIFIED_AGENT_PID=""
                VERIFIED_AGENT_PGID=""
                VERIFIED_AGENT_SID=""

                if [ -z "${'$'}STORED_AGENT_PID" ] ||
                   [ -z "${'$'}STORED_AGENT_PGID" ] ||
                   [ -z "${'$'}STORED_AGENT_SID" ]; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=no_stored_metadata"
                    return 1
                fi

                if ! kill -0 "${'$'}STORED_AGENT_PID" 2>/dev/null; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=leader_dead"
                    return 1
                fi

                LV_EXE=${'$'}(
                    readlink "/proc/${'$'}STORED_AGENT_PID/exe" \
                        2>/dev/null || true
                )
                LV_EXE=${'$'}(normalise_exe "${'$'}LV_EXE")

                if [ "${'$'}LV_EXE" != "$target/cloudphone-agent" ]; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=exe_mismatch"
                    return 1
                fi

                if ! read_process_group_metadata "${'$'}STORED_AGENT_PID"; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=stat_unreadable"
                    return 1
                fi

                if [ "${'$'}PROCESS_PGID" != "${'$'}STORED_AGENT_PGID" ] ||
                   [ "${'$'}PROCESS_SID" != "${'$'}STORED_AGENT_SID" ]; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=metadata_mismatch"
                    return 1
                fi

                if [ "${'$'}STORED_AGENT_PID" != "${'$'}PROCESS_PGID" ] ||
                   [ "${'$'}STORED_AGENT_PID" != "${'$'}PROCESS_SID" ]; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=not_leader"
                    return 1
                fi

                if [ "${'$'}CLEANUP_SHELL_METADATA_OK" != "1" ]; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=shell_metadata_invalid"
                    return 1
                fi

                if [ "${'$'}PROCESS_PGID" = "${'$'}CLEANUP_PGID" ] ||
                   [ "${'$'}PROCESS_SID" = "${'$'}CLEANUP_SID" ]; then
                    echo "AGENT_GROUP_NOT_VERIFIED reason=shell_group_overlap"
                    return 1
                fi

                VERIFIED_AGENT_PID="${'$'}STORED_AGENT_PID"
                VERIFIED_AGENT_PGID="${'$'}PROCESS_PGID"
                VERIFIED_AGENT_SID="${'$'}PROCESS_SID"

                echo "AGENT_GROUP_VERIFIED pid=${'$'}VERIFIED_AGENT_PID pgid=${'$'}VERIFIED_AGENT_PGID sid=${'$'}VERIFIED_AGENT_SID"
                return 0
            }

            # --------------------------------------------------
            # Signal the verified Agent group only after
            # re-checking every invariant.
            # --------------------------------------------------
            signal_verified_agent_group() {
                SV_SIGNAL="${'$'}1"

                [ -n "${'$'}VERIFIED_AGENT_PID" ] || return 1

                kill -0 "${'$'}VERIFIED_AGENT_PID" 2>/dev/null || return 1

                SV_EXE=${'$'}(
                    readlink "/proc/${'$'}VERIFIED_AGENT_PID/exe" \
                        2>/dev/null || true
                )
                SV_EXE=${'$'}(normalise_exe "${'$'}SV_EXE")

                [ "${'$'}SV_EXE" = "$target/cloudphone-agent" ] || return 1

                case "${'$'}VERIFIED_AGENT_PID" in
                    ""|*[!0-9]*) return 1 ;;
                esac
                case "${'$'}VERIFIED_AGENT_PGID" in
                    ""|*[!0-9]*) return 1 ;;
                esac
                case "${'$'}VERIFIED_AGENT_SID" in
                    ""|*[!0-9]*) return 1 ;;
                esac

                [ "${'$'}VERIFIED_AGENT_PID" -gt 1 ] 2>/dev/null || return 1
                [ "${'$'}VERIFIED_AGENT_PGID" -gt 1 ] 2>/dev/null || return 1
                [ "${'$'}VERIFIED_AGENT_SID" -gt 1 ] 2>/dev/null || return 1

                [ "${'$'}VERIFIED_AGENT_PID" = "${'$'}VERIFIED_AGENT_PGID" ] || return 1
                [ "${'$'}VERIFIED_AGENT_PID" = "${'$'}VERIFIED_AGENT_SID" ] || return 1

                read_process_group_metadata "${'$'}VERIFIED_AGENT_PID" \
                    || return 1

                [ "${'$'}PROCESS_PGID" = "${'$'}VERIFIED_AGENT_PGID" ] || return 1
                [ "${'$'}PROCESS_SID" = "${'$'}VERIFIED_AGENT_SID" ] || return 1

                [ "${'$'}CLEANUP_SHELL_METADATA_OK" = "1" ] || return 1
                [ "${'$'}VERIFIED_AGENT_PGID" != "${'$'}CLEANUP_PGID" ] || return 1
                [ "${'$'}VERIFIED_AGENT_SID" != "${'$'}CLEANUP_SID" ] || return 1

                kill "-${'$'}SV_SIGNAL" "-${'$'}VERIFIED_AGENT_PGID" \
                    2>/dev/null || true

                return 0
            }

            # ==================================================
            # Timing: initialize total start.
            # ==================================================
            CTT_START_MS=${'$'}(cleanup_now_ms)
            case "${'$'}CTT_START_MS" in
                ""|*[!0-9]*) CTT_VALID=0 ;;
                *) CTT_VALID=1 ;;
            esac
            echo "CLEANUP_TIMING_TOTAL_START elapsedMs=0"

            # ==================================================
            # Stage 1: INVALIDATE_TOKENS
            # ==================================================
            cleanup_stage INVALIDATE_TOKENS
            rm -f "$target/.launch_token"
            rm -f "$target/.cloudflare_repair_token"

            echo CLEANUP_START

            # ==================================================
            # Stage 2: READ_SHELL_METADATA
            # ==================================================
            cleanup_stage READ_SHELL_METADATA
            if read_process_group_metadata "${'$'}CLEANUP_SHELL_PID"; then
                if valid_numeric_id "${'$'}PROCESS_PGID" &&
                   valid_numeric_id "${'$'}PROCESS_SID"; then
                    CLEANUP_PGID="${'$'}PROCESS_PGID"
                    CLEANUP_SID="${'$'}PROCESS_SID"
                    CLEANUP_SHELL_METADATA_OK=1
                fi
            fi

            # ==================================================
            # Stage 3: COLLECT_PID_FILES
            # ==================================================
            cleanup_stage COLLECT_PID_FILES

            if [ -s "$target/cloudflared.pid" ]; then
                COL_PID=${'$'}(
                    cat "$target/cloudflared.pid" \
                        2>/dev/null || true
                )
                if valid_numeric_id "${'$'}COL_PID" &&
                   pid_matches_executable "${'$'}COL_PID" \
                        "$target/cloudflared"; then
                    add_collected "${'$'}COL_PID" "cloudflared"
                fi
            fi

            if [ -s "$target/agent.pid" ]; then
                COL_PID=${'$'}(
                    cat "$target/agent.pid" \
                        2>/dev/null || true
                )
                if valid_numeric_id "${'$'}COL_PID" &&
                   pid_matches_executable "${'$'}COL_PID" \
                        "$target/cloudphone-agent"; then
                    add_collected "${'$'}COL_PID" "agent"
                fi
            fi

            if [ -s "$target/signaling.pid" ]; then
                COL_PID=${'$'}(
                    cat "$target/signaling.pid" \
                        2>/dev/null || true
                )
                if valid_numeric_id "${'$'}COL_PID" &&
                   pid_matches_executable "${'$'}COL_PID" \
                        "$target/webrtc-signaling"; then
                    add_collected "${'$'}COL_PID" "signaling"
                fi
            fi

            # ==================================================
            # Stage 4: LOAD_STORED_AGENT_METADATA
            # ==================================================
            cleanup_stage LOAD_STORED_AGENT_METADATA

            STORED_AGENT_PID=""
            STORED_AGENT_PGID=""
            STORED_AGENT_SID=""

            STORED_PID_V=""
            STORED_PGID_V=""
            STORED_SID_V=""

            if [ -s "$target/agent.pid" ]; then
                STORED_PID_V=${'$'}(
                    cat "$target/agent.pid" \
                        2>/dev/null || true
                )
            fi

            if [ -s "$target/agent.pgid" ]; then
                STORED_PGID_V=${'$'}(
                    cat "$target/agent.pgid" \
                        2>/dev/null || true
                )
            fi

            if [ -s "$target/agent.sid" ]; then
                STORED_SID_V=${'$'}(
                    cat "$target/agent.sid" \
                        2>/dev/null || true
                )
            fi

            if valid_numeric_id "${'$'}STORED_PID_V" &&
               valid_numeric_id "${'$'}STORED_PGID_V" &&
               valid_numeric_id "${'$'}STORED_SID_V" &&
               [ "${'$'}STORED_PID_V" = "${'$'}STORED_PGID_V" ] &&
               [ "${'$'}STORED_PID_V" = "${'$'}STORED_SID_V" ]; then

                STORED_AGENT_PID="${'$'}STORED_PID_V"
                STORED_AGENT_PGID="${'$'}STORED_PGID_V"
                STORED_AGENT_SID="${'$'}STORED_SID_V"
            fi

            # ==================================================
            # Stage 5: LOAD_VERIFIED_AGENT_GROUP
            # ==================================================
            cleanup_stage LOAD_VERIFIED_AGENT_GROUP
            load_verified_agent_group || true

            # ==================================================
            # Stage 6: COLLECT_ORPHANS (targeted discovery)
            # ==================================================
            cleanup_stage COLLECT_ORPHANS
            CC_SCAN_NAME="COLLECT_ORPHANS"
            collect_curbox_processes

            # ==================================================
            # Stage 7: TERM_COLLECTED_PIDS
            # ==================================================
            cleanup_stage TERM_COLLECTED_PIDS

            if [ -n "${'$'}VERIFIED_AGENT_PID" ]; then
                signal_verified_agent_group 15 || true
            fi

            for TERM_ENTRY in ${'$'}COLLECTED_LIST; do
                TERM_PID="${'$'}{TERM_ENTRY%%:*}"
                if collected_pid_still_alive "${'$'}TERM_PID"; then
                    kill -15 "${'$'}TERM_PID" 2>/dev/null || true
                fi
            done

            # ==================================================
            # Stage 8: WAIT_FOR_GRACEFUL_EXIT
            # ==================================================
            cleanup_stage WAIT_FOR_GRACEFUL_EXIT

            for wait_i in 1 2 3 4 5; do
                WAIT_ANY_ALIVE=0
                for WAIT_ENTRY in ${'$'}COLLECTED_LIST; do
                    WAIT_PID="${'$'}{WAIT_ENTRY%%:*}"
                    if collected_pid_still_alive "${'$'}WAIT_PID"; then
                        WAIT_ANY_ALIVE=1
                        break
                    fi
                done

                [ "${'$'}WAIT_ANY_ALIVE" -eq 0 ] && break

                sleep 1
            done

            # ==================================================
            # Stage 9: KILL_REMAINING_PIDS
            # ==================================================
            cleanup_stage KILL_REMAINING_PIDS

            load_verified_agent_group >/dev/null 2>&1 || true

            if [ -n "${'$'}VERIFIED_AGENT_PID" ]; then
                signal_verified_agent_group 9 || true
            fi

            for KILL_ENTRY in ${'$'}COLLECTED_LIST; do
                KILL_PID="${'$'}{KILL_ENTRY%%:*}"
                if collected_pid_still_alive "${'$'}KILL_PID"; then
                    kill -9 "${'$'}KILL_PID" 2>/dev/null || true
                fi
            done

            # ==================================================
            # Stage 10: FINAL_PROCESS_WAIT
            # ==================================================
            cleanup_stage FINAL_PROCESS_WAIT
            sleep 2

            # ==================================================
            # Stage 11: FINAL_ORPHAN_SCAN (targeted rediscovery)
            # ==================================================
            cleanup_stage FINAL_ORPHAN_SCAN
            CC_SCAN_NAME="FINAL_ORPHAN_SCAN"
            collect_curbox_processes

            for FIN_ENTRY in ${'$'}COLLECTED_LIST; do
                FIN_PID="${'$'}{FIN_ENTRY%%:*}"
                if collected_pid_still_alive "${'$'}FIN_PID"; then
                    kill -9 "${'$'}FIN_PID" 2>/dev/null || true
                fi
            done

            # ==================================================
            # Stage 12: VERIFY_PROCESSES
            #
            # Perform one final targeted rediscovery before
            # declaring cleanup successful. Discovery itself
            # never sends signals; it only adds strictly verified
            # CurBox candidates to COLLECTED_LIST.
            # ==================================================
            cleanup_stage VERIFY_PROCESSES

            CC_SCAN_NAME="VERIFY_PROCESSES"
            collect_curbox_processes

            REMAINING=""

            for VER_ENTRY in ${'$'}COLLECTED_LIST; do
                VER_PID="${'$'}{VER_ENTRY%%:*}"

                if collected_pid_still_alive "${'$'}VER_PID"; then
                    REMAINING="${'$'}REMAINING ${'$'}VER_ENTRY"
                fi
            done

            if [ -n "${'$'}REMAINING" ]; then
                echo "CLEANUP_FAILED=processes_remain"
                echo "REMAINING_PIDS=${'$'}REMAINING"

                for REP_ENTRY in ${'$'}REMAINING; do
                    REP_PID="${'$'}{REP_ENTRY%%:*}"
                    REP_TYPE="${'$'}{REP_ENTRY##*:}"

                    if [ "${'$'}REP_TYPE" = "core" ]; then
                        if read_process_group_metadata "${'$'}REP_PID"; then
                            REP_PGID="${'$'}PROCESS_PGID"
                            REP_SID="${'$'}PROCESS_SID"
                        else
                            REP_PGID=""
                            REP_SID=""
                        fi

                        REP_EXE=${'$'}(
                            readlink "/proc/${'$'}REP_PID/exe" \
                                2>/dev/null || true
                        )

                        echo "type=curbox_core_service"
                        echo "pid=${'$'}REP_PID"
                        echo "exe=${'$'}REP_EXE"
                        echo "pgid=${'$'}REP_PGID"
                        echo "sid=${'$'}REP_SID"
                    else
                        echo "--- PID ${'$'}REP_PID type=${'$'}REP_TYPE ---"

                        echo -n "exe="
                        readlink \
                            "/proc/${'$'}REP_PID/exe" \
                            2>/dev/null || true

                        echo -n "cmdline="
                        tr '\000' ' ' \
                            < "/proc/${'$'}REP_PID/cmdline" \
                            2>/dev/null || true

                        echo ""
                    fi
                done

                cleanup_close_current_stage
                exit 1
            fi

            # ==================================================
            # Stage 13: VERIFY_PORT
            # ==================================================
            cleanup_stage VERIFY_PORT

            if port_is_listening; then
                echo "CLEANUP_FAILED=port_${'$'}{port}_still_listening"

                if command -v ss >/dev/null 2>&1; then
                    ss -ltnp 2>/dev/null \
                        | grep ":$port" \
                        || true
                fi

                cleanup_close_current_stage
                exit 1
            fi

            # ==================================================
            # Stage 14: REMOVE_PID_FILES
            # ==================================================
            cleanup_stage REMOVE_PID_FILES

            rm -f "$target/cloudflared.pid"
            rm -f "$target/agent.pid"
            rm -f "$target/agent.pgid"
            rm -f "$target/agent.sid"
            rm -f "$target/agent.pgid.tmp"
            rm -f "$target/agent.sid.tmp"
            rm -f "$target/signaling.pid"

            # ==================================================
            # Stage 15: COMPLETE
            # ==================================================
            cleanup_stage COMPLETE

            cleanup_close_current_stage
            CTT_NOW_MS=${'$'}(cleanup_now_ms)
            case "${'$'}CTT_NOW_MS" in
                ""|*[!0-9]*) CTT_NOW_VALID=0 ;;
                *) CTT_NOW_VALID=1 ;;
            esac
            if [ "${'$'}CTT_VALID" = "1" ] &&
               [ "${'$'}CTT_NOW_VALID" = "1" ]; then
                CTT_DUR=${'$'}((CTT_NOW_MS - CTT_START_MS))
                echo "CLEANUP_TIMING_TOTAL_END durationMs=${'$'}CTT_DUR"
            else
                echo "CLEANUP_TIMING_TOTAL_END durationMs=UNAVAILABLE"
            fi

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
            lineBundle = 3600,
            timeoutMs = LAUNCH_TIMEOUT_MS
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

            read_process_group_metadata() {
                PID="${'$'}1"
                PROC_STAT="/proc/${'$'}PID/stat"

                [ -r "${'$'}PROC_STAT" ] || return 1

                STAT_LINE=${'$'}(
                    cat "${'$'}PROC_STAT" 2>/dev/null
                ) || return 1

                [ -n "${'$'}STAT_LINE" ] || return 1

                case "${'$'}STAT_LINE" in
                    *") "*)
                        STAT_REST=${'$'}{STAT_LINE#*) }
                        ;;
                    *)
                        return 1
                        ;;
                esac

                set -- ${'$'}STAT_REST

                [ "${'$'}#" -ge 4 ] || return 1

                PROCESS_STATE="${'$'}1"
                PROCESS_PPID="${'$'}2"
                PROCESS_PGID="${'$'}3"
                PROCESS_SID="${'$'}4"

                case "${'$'}PROCESS_PGID" in
                    ""|*[!0-9]*)
                        return 1
                        ;;
                esac

                case "${'$'}PROCESS_SID" in
                    ""|*[!0-9]*)
                        return 1
                        ;;
                esac

                return 0
            }

            valid_numeric_id() {
                case "${'$'}1" in
                    ""|*[!0-9]*) return 1 ;;
                esac
                [ "${'$'}1" -gt 1 ] 2>/dev/null || return 1
                return 0
            }

            PROCESS_STATE=""
            PROCESS_PPID=""
            PROCESS_PGID=""
            PROCESS_SID=""

            # --------------------------------------------------
            # Startup shell metadata (must not be signalled).
            # --------------------------------------------------
            STARTUP_SHELL_PID=${'$'}${'$'}
            STARTUP_SHELL_PGID=""
            STARTUP_SHELL_SID=""
            STARTUP_SHELL_METADATA_OK=0

            if read_process_group_metadata "${'$'}STARTUP_SHELL_PID"; then
                if valid_numeric_id "${'$'}PROCESS_PGID" &&
                   valid_numeric_id "${'$'}PROCESS_SID"; then
                    STARTUP_SHELL_PGID="${'$'}PROCESS_PGID"
                    STARTUP_SHELL_SID="${'$'}PROCESS_SID"
                    STARTUP_SHELL_METADATA_OK=1
                fi
            fi

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

            # --------------------------------------------------
            # Kill CoreService children that still belong to the
            # Agent group/session. Strict identity checks only.
            # --------------------------------------------------
            kill_verified_agent_core_children() {
                KC_SIGNAL="${'$'}1"
                KC_EXPECTED_PGID="${'$'}2"
                KC_EXPECTED_SID="${'$'}3"

                [ "${'$'}STARTUP_SHELL_METADATA_OK" = "1" ] ||
                    return 0

                case "${'$'}KC_SIGNAL" in
                    9|15)
                        ;;
                    *)
                        return 0
                        ;;
                esac

                case "${'$'}KC_EXPECTED_PGID" in
                    ""|*[!0-9]*)
                        return 0
                        ;;
                esac

                case "${'$'}KC_EXPECTED_SID" in
                    ""|*[!0-9]*)
                        return 0
                        ;;
                esac

                [ "${'$'}KC_EXPECTED_PGID" -gt 1 ] 2>/dev/null ||
                    return 0

                [ "${'$'}KC_EXPECTED_SID" -gt 1 ] 2>/dev/null ||
                    return 0

                [ "${'$'}KC_EXPECTED_PGID" != "${'$'}STARTUP_SHELL_PGID" ] ||
                    return 0

                [ "${'$'}KC_EXPECTED_SID" != "${'$'}STARTUP_SHELL_SID" ] ||
                    return 0

                KC_CANDIDATES=""
                KC_DISCOVERY_METHOD=""

                if command -v pidof >/dev/null 2>&1; then
                    KC_DISCOVERY_METHOD="pidof"

                    KC_CANDIDATES=${'$'}(
                        pidof \
                            app_process \
                            app_process32 \
                            app_process64 \
                            com.android.helper.CoreService \
                            2>/dev/null || true
                    )
                elif command -v toybox >/dev/null 2>&1 &&
                     toybox pidof --help >/dev/null 2>&1; then

                    KC_DISCOVERY_METHOD="toybox_pidof"

                    KC_CANDIDATES=${'$'}(
                        toybox pidof \
                            app_process \
                            app_process32 \
                            app_process64 \
                            com.android.helper.CoreService \
                            2>/dev/null || true
                    )
                else
                    echo "STARTUP_CORE_DISCOVERY_SKIPPED reason=targeted_discovery_unavailable"
                    return 0
                fi

                KC_CANDIDATE_COUNT=0
                KC_VERIFIED_COUNT=0

                for KC_PID in ${'$'}KC_CANDIDATES; do
                    case "${'$'}KC_PID" in
                        ""|*[!0-9]*)
                            continue
                            ;;
                    esac

                    [ "${'$'}KC_PID" -gt 1 ] 2>/dev/null ||
                        continue

                    [ "${'$'}KC_PID" = "${'$'}${'$'}" ] &&
                        continue

                    [ "${'$'}KC_PID" = "${'$'}STARTUP_SHELL_PID" ] &&
                        continue

                    KC_CANDIDATE_COUNT=${'$'}((KC_CANDIDATE_COUNT + 1))

                    kill -0 "${'$'}KC_PID" 2>/dev/null ||
                        continue

                    KC_COMM_FILE="/proc/${'$'}KC_PID/comm"

                    [ -r "${'$'}KC_COMM_FILE" ] ||
                        continue

                    KC_COMM=${'$'}(
                        cat "${'$'}KC_COMM_FILE" \
                            2>/dev/null || true
                    )

                    case "${'$'}KC_COMM" in
                        app_process|app_process32|app_process64)
                            ;;
                        *)
                            continue
                            ;;
                    esac

                    KC_STATUS_FILE="/proc/${'$'}KC_PID/status"

                    [ -r "${'$'}KC_STATUS_FILE" ] ||
                        continue

                    KC_UID_LINE=${'$'}(
                        grep '^Uid:' "${'$'}KC_STATUS_FILE" \
                            2>/dev/null \
                            | head -n 1
                    )

                    [ -n "${'$'}KC_UID_LINE" ] ||
                        continue

                    set -- ${'$'}KC_UID_LINE
                    KC_REAL_UID="${'$'}2"

                    [ "${'$'}KC_REAL_UID" = "2000" ] ||
                        continue

                    KC_EXE=${'$'}(
                        readlink "/proc/${'$'}KC_PID/exe" \
                            2>/dev/null || true
                    )

                    KC_EXE=${'$'}(
                        normalise_exe "${'$'}KC_EXE"
                    )

                    case "${'$'}KC_EXE" in
                        /system/bin/app_process|\
                        /system/bin/app_process32|\
                        /system/bin/app_process64|\
                        /apex/com.android.art/bin/app_process|\
                        /apex/com.android.art/bin/app_process32|\
                        /apex/com.android.art/bin/app_process64)
                            ;;
                        *)
                            continue
                            ;;
                    esac

                    KC_CMDLINE_FILE="/proc/${'$'}KC_PID/cmdline"

                    [ -r "${'$'}KC_CMDLINE_FILE" ] ||
                        continue

                    tr '\000' '\n' \
                        < "${'$'}KC_CMDLINE_FILE" \
                        2>/dev/null \
                        | grep -Fxq \
                            'com.android.helper.CoreService' \
                        || continue

                    read_process_group_metadata "${'$'}KC_PID" ||
                        continue

                    case "${'$'}PROCESS_PGID" in
                        ""|*[!0-9]*)
                            continue
                            ;;
                    esac

                    case "${'$'}PROCESS_SID" in
                        ""|*[!0-9]*)
                            continue
                            ;;
                    esac

                    [ "${'$'}PROCESS_PGID" -gt 1 ] 2>/dev/null ||
                        continue

                    [ "${'$'}PROCESS_SID" -gt 1 ] 2>/dev/null ||
                        continue

                    [ "${'$'}PROCESS_PGID" != "${'$'}STARTUP_SHELL_PGID" ] ||
                        continue

                    [ "${'$'}PROCESS_SID" != "${'$'}STARTUP_SHELL_SID" ] ||
                        continue

                    if [ "${'$'}PROCESS_PGID" != "${'$'}KC_EXPECTED_PGID" ] &&
                       [ "${'$'}PROCESS_SID" != "${'$'}KC_EXPECTED_SID" ]; then
                        continue
                    fi

                    # Re-check process identity immediately before
                    # signalling to reduce the PID-reuse race window.
                    kill -0 "${'$'}KC_PID" 2>/dev/null ||
                        continue

                    KC_FINAL_EXE=${'$'}(
                        readlink "/proc/${'$'}KC_PID/exe" \
                            2>/dev/null || true
                    )

                    KC_FINAL_EXE=${'$'}(
                        normalise_exe "${'$'}KC_FINAL_EXE"
                    )

                    [ "${'$'}KC_FINAL_EXE" = "${'$'}KC_EXE" ] ||
                        continue

                    [ -r "/proc/${'$'}KC_PID/cmdline" ] ||
                        continue

                    tr '\000' '\n' \
                        < "/proc/${'$'}KC_PID/cmdline" \
                        2>/dev/null \
                        | grep -Fxq \
                            'com.android.helper.CoreService' \
                        || continue

                    read_process_group_metadata "${'$'}KC_PID" ||
                        continue

                    if [ "${'$'}PROCESS_PGID" != "${'$'}KC_EXPECTED_PGID" ] &&
                       [ "${'$'}PROCESS_SID" != "${'$'}KC_EXPECTED_SID" ]; then
                        continue
                    fi

                    [ "${'$'}PROCESS_PGID" != "${'$'}STARTUP_SHELL_PGID" ] ||
                        continue

                    [ "${'$'}PROCESS_SID" != "${'$'}STARTUP_SHELL_SID" ] ||
                        continue

                    kill "-${'$'}KC_SIGNAL" "${'$'}KC_PID" \
                        2>/dev/null || true

                    KC_VERIFIED_COUNT=${'$'}((KC_VERIFIED_COUNT + 1))
                done

                echo "STARTUP_CORE_DISCOVERY_SUMMARY method=${'$'}KC_DISCOVERY_METHOD candidates=${'$'}KC_CANDIDATE_COUNT verified=${'$'}KC_VERIFIED_COUNT"

                return 0
            }

            # --------------------------------------------------
            # Stop every process started by this launch attempt.
            # --------------------------------------------------
            stop_started_processes() {
                SP_AGENT_PID=""
                SP_AGENT_PGID=""
                SP_AGENT_SID=""
                SP_AGENT_METADATA_VALID=0

                if [ -s agent.pid ]; then
                    SP_AGENT_PID=${'$'}(
                        cat agent.pid 2>/dev/null || true
                    )
                fi

                case "${'$'}SP_AGENT_PID" in
                    ""|*[!0-9]*) SP_AGENT_PID="" ;;
                esac

                if [ -n "${'$'}SP_AGENT_PID" ] &&
                   [ -s agent.pgid ] &&
                   [ -s agent.sid ]; then
                    SP_AGENT_PGID=${'$'}(
                        cat agent.pgid 2>/dev/null || true
                    )
                    SP_AGENT_SID=${'$'}(
                        cat agent.sid 2>/dev/null || true
                    )

                    case "${'$'}SP_AGENT_PGID" in
                        ""|*[!0-9]*) SP_AGENT_PGID="" ;;
                    esac
                    case "${'$'}SP_AGENT_SID" in
                        ""|*[!0-9]*) SP_AGENT_SID="" ;;
                    esac

                    if [ -n "${'$'}SP_AGENT_PGID" ] &&
                       [ -n "${'$'}SP_AGENT_SID" ] &&
                       [ "${'$'}SP_AGENT_PID" = "${'$'}SP_AGENT_PGID" ] &&
                       [ "${'$'}SP_AGENT_PID" = "${'$'}SP_AGENT_SID" ] &&
                       [ "${'$'}SP_AGENT_PID" -gt 1 ] 2>/dev/null &&
                       [ "${'$'}SP_AGENT_PGID" -gt 1 ] 2>/dev/null &&
                       [ "${'$'}SP_AGENT_SID" -gt 1 ] 2>/dev/null; then
                        SP_AGENT_METADATA_VALID=1
                    fi
                fi

                # --------------------------------------------------
                # cloudflared: SIGTERM
                # --------------------------------------------------
                if [ -s cloudflared.pid ]; then
                    SP_PID=${'$'}(
                        cat cloudflared.pid 2>/dev/null || true
                    )
                    if valid_numeric_id "${'$'}SP_PID" &&
                       pid_matches_executable "${'$'}SP_PID" \
                            "$target/cloudflared"; then
                        kill -15 "${'$'}SP_PID" 2>/dev/null || true
                    fi
                fi

                # --------------------------------------------------
                # signaling: SIGTERM
                # --------------------------------------------------
                if [ -s signaling.pid ]; then
                    SP_PID=${'$'}(
                        cat signaling.pid 2>/dev/null || true
                    )
                    if valid_numeric_id "${'$'}SP_PID" &&
                       pid_matches_executable "${'$'}SP_PID" \
                            "$target/webrtc-signaling"; then
                        kill -15 "${'$'}SP_PID" 2>/dev/null || true
                    fi
                fi

                # --------------------------------------------------
                # agent: group SIGTERM if fully verified.
                # --------------------------------------------------
                if [ "${'$'}SP_AGENT_METADATA_VALID" = "1" ] &&
                   [ "${'$'}STARTUP_SHELL_METADATA_OK" = "1" ] &&
                   [ "${'$'}SP_AGENT_PGID" != "${'$'}STARTUP_SHELL_PGID" ] &&
                   [ "${'$'}SP_AGENT_SID" != "${'$'}STARTUP_SHELL_SID" ]; then
                    if kill -0 "${'$'}SP_AGENT_PID" 2>/dev/null &&
                       pid_matches_executable "${'$'}SP_AGENT_PID" \
                            "$target/cloudphone-agent" &&
                       read_process_group_metadata "${'$'}SP_AGENT_PID" &&
                       [ "${'$'}PROCESS_PGID" = "${'$'}SP_AGENT_PGID" ] &&
                       [ "${'$'}PROCESS_SID" = "${'$'}SP_AGENT_SID" ]; then
                        kill "-15" "-${'$'}SP_AGENT_PGID" \
                            2>/dev/null || true
                    fi
                fi

                # --------------------------------------------------
                # agent: PID SIGTERM fallback.
                # --------------------------------------------------
                if [ -n "${'$'}SP_AGENT_PID" ] &&
                   pid_matches_executable "${'$'}SP_AGENT_PID" \
                        "$target/cloudphone-agent"; then
                    kill -15 "${'$'}SP_AGENT_PID" \
                        2>/dev/null || true
                fi

                sleep 1

                # --------------------------------------------------
                # cloudflared: SIGKILL after re-verification.
                # --------------------------------------------------
                if [ -s cloudflared.pid ]; then
                    SP_PID=${'$'}(
                        cat cloudflared.pid 2>/dev/null || true
                    )
                    if valid_numeric_id "${'$'}SP_PID" &&
                       pid_matches_executable "${'$'}SP_PID" \
                            "$target/cloudflared"; then
                        kill -9 "${'$'}SP_PID" 2>/dev/null || true
                    fi
                fi

                # --------------------------------------------------
                # signaling: SIGKILL after re-verification.
                # --------------------------------------------------
                if [ -s signaling.pid ]; then
                    SP_PID=${'$'}(
                        cat signaling.pid 2>/dev/null || true
                    )
                    if valid_numeric_id "${'$'}SP_PID" &&
                       pid_matches_executable "${'$'}SP_PID" \
                            "$target/webrtc-signaling"; then
                        kill -9 "${'$'}SP_PID" 2>/dev/null || true
                    fi
                fi

                # --------------------------------------------------
                # agent: group SIGKILL if fully verified again.
                # --------------------------------------------------
                if [ "${'$'}SP_AGENT_METADATA_VALID" = "1" ] &&
                   [ "${'$'}STARTUP_SHELL_METADATA_OK" = "1" ] &&
                   [ "${'$'}SP_AGENT_PGID" != "${'$'}STARTUP_SHELL_PGID" ] &&
                   [ "${'$'}SP_AGENT_SID" != "${'$'}STARTUP_SHELL_SID" ]; then
                    if kill -0 "${'$'}SP_AGENT_PID" 2>/dev/null &&
                       pid_matches_executable "${'$'}SP_AGENT_PID" \
                            "$target/cloudphone-agent" &&
                       read_process_group_metadata "${'$'}SP_AGENT_PID" &&
                       [ "${'$'}PROCESS_PGID" = "${'$'}SP_AGENT_PGID" ] &&
                       [ "${'$'}PROCESS_SID" = "${'$'}SP_AGENT_SID" ]; then
                        kill "-9" "-${'$'}SP_AGENT_PGID" \
                            2>/dev/null || true
                    fi
                fi

                # --------------------------------------------------
                # agent: PID SIGKILL fallback after re-verification.
                # --------------------------------------------------
                if [ -n "${'$'}SP_AGENT_PID" ] &&
                   pid_matches_executable "${'$'}SP_AGENT_PID" \
                        "$target/cloudphone-agent"; then
                    kill -9 "${'$'}SP_AGENT_PID" \
                        2>/dev/null || true
                fi

                # --------------------------------------------------
                # CoreService children cleanup (targeted discovery).
                # --------------------------------------------------
                if [ "${'$'}SP_AGENT_METADATA_VALID" = "1" ]; then
                    kill_verified_agent_core_children \
                        9 \
                        "${'$'}SP_AGENT_PGID" \
                        "${'$'}SP_AGENT_SID"
                fi
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

                rm -f cloudflared.pid
                rm -f agent.pid
                rm -f agent.pgid
                rm -f agent.sid
                rm -f agent.pgid.tmp
                rm -f agent.sid.tmp
                rm -f signaling.pid
                rm -f "${'$'}TOKEN_FILE"
            }

            export CLASSPATH="$target/libsys_core.so"

            : > launch.log
            : > signaling.log
            : > agent.log

            rm -f signaling.pid
            rm -f agent.pid
            rm -f agent.pgid
            rm -f agent.sid
            rm -f agent.pgid.tmp
            rm -f agent.sid.tmp
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

            # --------------------------------------------------
            # Record Agent PGID / SID atomically.
            # --------------------------------------------------
            AGENT_METADATA_OK=0

            if valid_numeric_id "${'$'}AGENT_PID" &&
               kill -0 "${'$'}AGENT_PID" 2>/dev/null &&
               pid_matches_executable "${'$'}AGENT_PID" \
                    "$target/cloudphone-agent"; then

                if read_process_group_metadata "${'$'}AGENT_PID"; then
                    if valid_numeric_id "${'$'}PROCESS_PGID" &&
                       valid_numeric_id "${'$'}PROCESS_SID" &&
                       [ "${'$'}PROCESS_PGID" = "${'$'}AGENT_PID" ] &&
                       [ "${'$'}PROCESS_SID" = "${'$'}AGENT_PID" ]; then

                        if printf '%s\n' "${'$'}PROCESS_PGID" \
                                > agent.pgid.tmp &&
                           printf '%s\n' "${'$'}PROCESS_SID" \
                                > agent.sid.tmp; then

                            if mv agent.pgid.tmp agent.pgid &&
                               mv agent.sid.tmp agent.sid; then
                                AGENT_METADATA_OK=1
                            else
                                rm -f agent.pgid
                                rm -f agent.sid
                                rm -f agent.pgid.tmp
                                rm -f agent.sid.tmp
                            fi
                        else
                            rm -f agent.pgid
                            rm -f agent.sid
                            rm -f agent.pgid.tmp
                            rm -f agent.sid.tmp
                        fi
                    else
                        rm -f agent.pgid
                        rm -f agent.sid
                        rm -f agent.pgid.tmp
                        rm -f agent.sid.tmp
                    fi
                else
                    rm -f agent.pgid
                    rm -f agent.sid
                    rm -f agent.pgid.tmp
                    rm -f agent.sid.tmp
                fi
            else
                rm -f agent.pgid
                rm -f agent.sid
                rm -f agent.pgid.tmp
                rm -f agent.sid.tmp
            fi

            if [ "${'$'}AGENT_METADATA_OK" = "1" ]; then
                echo "AGENT_PGID=${'$'}PROCESS_PGID" \
                    >> launch.log
                echo "AGENT_SID=${'$'}PROCESS_SID" \
                    >> launch.log
                echo "AGENT_PROCESS_GROUP_VERIFIED=1" \
                    >> launch.log
            else
                echo "AGENT_PROCESS_GROUP_VERIFIED=0" \
                    >> launch.log
            fi

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

            nohup env \
                GODEBUG=netdns=cgo+1 \
                ./cloudflared tunnel \
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
            240_000L

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