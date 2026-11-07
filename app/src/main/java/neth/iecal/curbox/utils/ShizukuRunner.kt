package neth.iecal.curbox.utils

import android.content.pm.PackageManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Utility class for executing shell commands using Shizuku.
 */
class ShizukuRunner {

    interface CommandResultListener {

        /**
         * Called when the command produces output.
         *
         * @param output The output from the command execution.
         * @param done True if command execution is complete, false for
         * partial output.
         */
        fun onCommandResult(
            output: String,
            done: Boolean
        ) {
        }

        /**
         * Called when command execution fails.
         *
         * @param error A diagnostic error message.
         */
        fun onCommandError(
            error: String
        ) {
        }
    }

    companion object {

        private const val DEFAULT_TIMEOUT_MS =
            120_000L

        private const val STREAM_JOIN_TIMEOUT_MS =
            1_000L

        private const val DESTROY_JOIN_TIMEOUT_MS =
            1_000L

        /**
         * Executes a shell command using Shizuku.
         *
         * Standard output and standard error are drained concurrently
         * so neither stream can block the native process.
         *
         * The command is bounded by [timeoutMs]. A local JVM watchdog
         * delivers the timeout callback close to [timeoutMs] regardless
         * of Binder latency on waitForTimeout() or destroy().
         *
         * The process exit code determines success or failure.
         * Text written to stderr does not automatically mean that the
         * command failed when the exit code is zero.
         *
         * @param command The shell command to execute.
         * @param listener Listener for command output and failures.
         * @param lineBundle Number of stdout lines to batch before
         * delivering partial output.
         * @param timeoutMs Maximum command runtime in milliseconds.
         * Values less than 1 use the default timeout.
         */
        fun executeCommand(
            command: String,
            listener: CommandResultListener,
            lineBundle: Int = 50,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS
        ) {
            Thread {
                val terminalCallbackDelivered =
                    AtomicBoolean(false)

                fun deliverResultOnce(
                    output: String
                ) {
                    if (
                        terminalCallbackDelivered.compareAndSet(
                            false,
                            true
                        )
                    ) {
                        try {
                            listener.onCommandResult(
                                output,
                                true
                            )
                        } catch (_: Throwable) {
                            /*
                             * Listener failures must not cause another
                             * terminal callback to be delivered.
                             */
                        }
                    }
                }

                fun deliverErrorOnce(
                    error: String
                ) {
                    if (
                        terminalCallbackDelivered.compareAndSet(
                            false,
                            true
                        )
                    ) {
                        try {
                            listener.onCommandError(
                                error
                            )
                        } catch (_: Throwable) {
                            /*
                             * Listener failures must not cause another
                             * terminal callback to be delivered.
                             */
                        }
                    }
                }

                try {
                    val safeLineBundle =
                        lineBundle.coerceAtLeast(1)

                    val safeTimeoutMs =
                        if (timeoutMs > 0L) {
                            timeoutMs
                        } else {
                            DEFAULT_TIMEOUT_MS
                        }

                    /*
                     * Binder availability and application permission are
                     * separate requirements. Check both before creating
                     * the remote process.
                     */
                    if (!Shizuku.pingBinder()) {
                        throw IllegalStateException(
                            "Shizuku service is not running or " +
                                "its Binder is unavailable"
                        )
                    }

                    if (
                        Shizuku.checkSelfPermission() !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        throw SecurityException(
                            "CurBox does not have Shizuku permission. " +
                                "Open Shizuku and authorize CurBox, " +
                                "then try again."
                        )
                    }

                    val service =
                        IShizukuService.Stub.asInterface(
                            Shizuku.getBinder()
                        ) ?: throw IllegalStateException(
                            "Shizuku service is unavailable"
                        )

                    val process =
                        service.newProcess(
                            arrayOf(
                                "sh",
                                "-c",
                                command
                            ),
                            null,
                            null
                        )

                    val finalOutput =
                        StringBuilder()

                    val errorOutput =
                        StringBuilder()

                    val outputReadFailure =
                        AtomicReference<Throwable?>(null)

                    val errorReadFailure =
                        AtomicReference<Throwable?>(null)

                    val destroyRequested =
                        AtomicBoolean(false)

                    /*
                     * Spawn a single daemon thread for process
                     * destruction. The destroy call is fully
                     * non-blocking from the caller's perspective.
                     */
                    fun requestDestroy() {
                        if (
                            destroyRequested.compareAndSet(
                                false,
                                true
                            )
                        ) {
                            thread(
                                start = true,
                                isDaemon = true,
                                name = "CurBox-Shizuku-destroy"
                            ) {
                                try {
                                    process.destroy()
                                } catch (_: Throwable) {
                                    /*
                                     * The Binder or remote process may
                                     * already be dead.
                                     */
                                }
                            }
                        }
                    }

                    /*
                     * Deliver the timeout callback via CAS and then
                     * request process destruction without waiting.
                     * Returns true if this call won the CAS.
                     */
                    fun tryDeliverTimeoutMessage(): Boolean {
                        if (
                            !terminalCallbackDelivered.compareAndSet(
                                false,
                                true
                            )
                        ) {
                            return false
                        }

                        val stdout =
                            synchronized(finalOutput) {
                                finalOutput.toString()
                            }

                        val stderr =
                            synchronized(errorOutput) {
                                errorOutput.toString()
                            }

                        try {
                            listener.onCommandError(
                                buildString {
                                    append(
                                        "Shizuku command timed out after "
                                    )

                                    append(
                                        safeTimeoutMs
                                    )

                                    append(
                                        " ms and termination was requested."
                                    )

                                    if (stderr.isNotBlank()) {
                                        append(
                                            "\n\nstderr:\n"
                                        )

                                        append(stderr)
                                    }

                                    if (stdout.isNotBlank()) {
                                        append(
                                            "\n\nstdout:\n"
                                        )

                                        append(stdout)
                                    }
                                }
                            )
                        } catch (_: Throwable) {
                            /*
                             * Terminal listener failure is ignored.
                             */
                        }

                        /*
                         * Destruction is requested after the callback
                         * has been delivered, satisfying the
                         * callback-before-destroy ordering.
                         */
                        requestDestroy()
                        return true
                    }

                    val outputThread =
                        thread(
                            start = true,
                            isDaemon = true,
                            name = "CurBox-Shizuku-stdout"
                        ) {
                            try {
                                BufferedReader(
                                    InputStreamReader(
                                        FileInputStream(
                                            process
                                                .inputStream
                                                .fileDescriptor
                                        )
                                    )
                                ).use { outputReader ->

                                    val partialOutput =
                                        StringBuilder()

                                    var lineCount =
                                        0

                                    while (
                                        !terminalCallbackDelivered.get()
                                    ) {
                                        val line =
                                            outputReader.readLine()
                                                ?: break

                                        val formattedLine =
                                            "$line\n"

                                        synchronized(finalOutput) {
                                            finalOutput.append(
                                                formattedLine
                                            )
                                        }

                                        partialOutput.append(
                                            formattedLine
                                        )

                                        lineCount++

                                        if (
                                            lineCount >=
                                            safeLineBundle
                                        ) {
                                            val output =
                                                partialOutput.toString()

                                            partialOutput.clear()
                                            lineCount = 0

                                            if (
                                                output.isNotEmpty() &&
                                                !terminalCallbackDelivered.get()
                                            ) {
                                                try {
                                                    listener.onCommandResult(
                                                        output,
                                                        false
                                                    )
                                                } catch (_: Throwable) {
                                                    /*
                                                     * A partial callback failure
                                                     * must not stop stream draining.
                                                     */
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                /*
                                 * Stream failures after terminal callback
                                 * delivery (timeout, destruction, normal
                                 * completion) are expected. Preserve them
                                 * only while no terminal result has been
                                 * delivered.
                                 */
                                if (
                                    !terminalCallbackDelivered.get()
                                ) {
                                    outputReadFailure.set(t)
                                }
                            }
                        }

                    val errorThread =
                        thread(
                            start = true,
                            isDaemon = true,
                            name = "CurBox-Shizuku-stderr"
                        ) {
                            try {
                                BufferedReader(
                                    InputStreamReader(
                                        FileInputStream(
                                            process
                                                .errorStream
                                                .fileDescriptor
                                        )
                                    )
                                ).use { errorReader ->

                                    while (
                                        !terminalCallbackDelivered.get()
                                    ) {
                                        val line =
                                            errorReader.readLine()
                                                ?: break

                                        synchronized(errorOutput) {
                                            errorOutput
                                                .append(line)
                                                .append("\n")
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                if (
                                    !terminalCallbackDelivered.get()
                                ) {
                                    errorReadFailure.set(t)
                                }
                            }
                        }

                    /*
                     * Local JVM watchdog: guarantees that a timeout
                     * callback is delivered close to safeTimeoutMs,
                     * even if the Binder calls for waitForTimeout()
                     * or destroy() are delayed. The watchdog competes
                     * with the command thread through the same CAS
                     * gate, so exactly one terminal callback is
                     * delivered under any race.
                     */
                    val watchdogThread =
                        thread(
                            start = true,
                            isDaemon = true,
                            name = "CurBox-Shizuku-timeout"
                        ) {
                            val interrupted = try {
                                Thread.sleep(
                                    safeTimeoutMs
                                )

                                false
                            } catch (_: InterruptedException) {
                                true
                            }

                            if (interrupted) {
                                return@thread
                            }

                            tryDeliverTimeoutMessage()
                        }

                    /*
                     * Use the timeout-capable Shizuku remote-process API
                     * to keep the command thread bounded. The local
                     * watchdog remains the ultimate fallback.
                     */
                    val completed =
                        try {
                            process.waitForTimeout(
                                safeTimeoutMs,
                                "MILLISECONDS"
                            )
                        } catch (t: Throwable) {
                            watchdogThread.interrupt()

                            deliverErrorOnce(
                                "Failed while waiting for the Shizuku " +
                                    "command to finish: " +
                                    (
                                        t.message
                                            ?: t.javaClass.simpleName
                                        )
                            )

                            requestDestroy()
                            return@Thread
                        }

                    if (!completed) {
                        /*
                         * Try to reserve the timeout via the shared CAS
                         * gate. If the watchdog has already delivered,
                         * this call is a no-op.
                         */
                        tryDeliverTimeoutMessage()

                        watchdogThread.interrupt()
                        return@Thread
                    }

                    /*
                     * The process exited. Stop the local watchdog and
                     * wait briefly for the stream readers to consume
                     * buffered output. Never allow their joins to
                     * block the lifecycle indefinitely.
                     */
                    watchdogThread.interrupt()

                    try {
                        outputThread.join(
                            STREAM_JOIN_TIMEOUT_MS
                        )
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }

                    try {
                        errorThread.join(
                            STREAM_JOIN_TIMEOUT_MS
                        )
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }

                    if (
                        outputThread.isAlive ||
                        errorThread.isAlive
                    ) {
                        requestDestroy()

                        val stdout =
                            synchronized(finalOutput) {
                                finalOutput.toString()
                            }

                        val stderr =
                            synchronized(errorOutput) {
                                errorOutput.toString()
                            }

                        deliverErrorOnce(
                            buildString {
                                append(
                                    "Shizuku command exited, but its output " +
                                        "streams did not close within " +
                                        STREAM_JOIN_TIMEOUT_MS +
                                        " ms."
                                )

                                if (stderr.isNotBlank()) {
                                    append(
                                        "\n\nstderr:\n"
                                    )

                                    append(stderr)
                                }

                                if (stdout.isNotBlank()) {
                                    append(
                                        "\n\nstdout:\n"
                                    )

                                    append(stdout)
                                }
                            }
                        )

                        return@Thread
                    }

                    val exitCode =
                        process.exitValue()

                    val stdout =
                        synchronized(finalOutput) {
                            finalOutput.toString()
                        }

                    val stderr =
                        synchronized(errorOutput) {
                            errorOutput.toString()
                        }

                    val stdoutFailure =
                        outputReadFailure.get()

                    val stderrFailure =
                        errorReadFailure.get()

                    if (
                        (stdoutFailure != null ||
                            stderrFailure != null) &&
                        exitCode != 0
                    ) {
                        val readerErrors =
                            buildString {
                                append(
                                    "Failed to read Shizuku command output."
                                )

                                if (stdoutFailure != null) {
                                    append(
                                        "\nstdout reader: "
                                    )

                                    append(
                                        stdoutFailure.message
                                            ?: stdoutFailure
                                                .javaClass
                                                .simpleName
                                    )
                                }

                                if (stderrFailure != null) {
                                    append(
                                        "\nstderr reader: "
                                    )

                                    append(
                                        stderrFailure.message
                                            ?: stderrFailure
                                                .javaClass
                                                .simpleName
                                    )
                                }

                                if (stdout.isNotBlank()) {
                                    append(
                                        "\n\nstdout:\n"
                                    )

                                    append(stdout)
                                }

                                if (stderr.isNotBlank()) {
                                    append(
                                        "\n\nstderr:\n"
                                    )

                                    append(stderr)
                                }
                            }

                        deliverErrorOnce(
                            readerErrors
                        )

                        return@Thread
                    }

                    if (exitCode == 0) {
                        /*
                         * stderr may contain warnings even when the command
                         * succeeds. Preserve it as diagnostic output.
                         */
                        val completeOutput =
                            buildString {
                                append(stdout)

                                if (stderr.isNotBlank()) {
                                    if (
                                        isNotEmpty() &&
                                        !endsWith("\n")
                                    ) {
                                        append("\n")
                                    }

                                    append(stderr)
                                }
                            }

                        /*
                         * The terminal callback must always contain the
                         * complete captured output. Partial callbacks are
                         * informational streaming only and consumers may
                         * intentionally ignore them until done == true.
                         *
                         * Returning only the modulo/trailing stdout lines
                         * here can produce an empty terminal result when
                         * stdout line count is an exact multiple of
                         * lineBundle. That made a successful launch look
                         * like it returned no output.
                         */
                        deliverResultOnce(
                            completeOutput
                        )
                    } else {
                        deliverErrorOnce(
                            buildString {
                                append(
                                    "Shizuku command failed " +
                                        "with exit code $exitCode."
                                )

                                if (stderr.isNotBlank()) {
                                    append(
                                        "\n\nstderr:\n"
                                    )

                                    append(stderr)
                                }

                                if (stdout.isNotBlank()) {
                                    append(
                                        "\n\nstdout:\n"
                                    )

                                    append(stdout)
                                }
                            }
                        )
                    }
                } catch (t: InterruptedException) {
                    Thread.currentThread().interrupt()

                    deliverErrorOnce(
                        "Shizuku command execution was interrupted."
                    )
                } catch (t: Throwable) {
                    deliverErrorOnce(
                        t.message
                            ?: "An unexpected error occurred " +
                                "while executing the command: " +
                                t.javaClass.simpleName
                    )
                }
            }.apply {
                name =
                    "CurBox-Shizuku-command"

                isDaemon =
                    true

                start()
            }
        }
    }
}