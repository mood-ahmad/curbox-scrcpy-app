package neth.iecal.curbox.utils

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

        /**
         * Executes a shell command using Shizuku.
         *
         * Standard output and standard error are drained concurrently
         * so neither stream can block the native process.
         *
         * The process exit code determines success or failure.
         * Text written to stderr does not automatically mean that the
         * command failed when the exit code is zero.
         *
         * @param command The shell command to execute.
         * @param listener Listener for command output and failures.
         * @param lineBundle Number of stdout lines to batch before
         * delivering partial output.
         */
        fun executeCommand(
            command: String,
            listener: CommandResultListener,
            lineBundle: Int = 50
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

                    val outputThread =
                        thread(
                            start = true,
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

                                    while (true) {
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
                                                partialOutput
                                                    .toString()

                                            partialOutput.clear()
                                            lineCount = 0

                                            if (
                                                output.isNotEmpty() &&
                                                !terminalCallbackDelivered.get()
                                            ) {
                                                try {
                                                    listener
                                                        .onCommandResult(
                                                            output,
                                                            false
                                                        )
                                                } catch (_: Throwable) {
                                                    /*
                                                     * A partial-output
                                                     * listener failure must
                                                     * not stop stream draining.
                                                     */
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                outputReadFailure.set(t)
                            }
                        }

                    val errorThread =
                        thread(
                            start = true,
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

                                    while (true) {
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
                                errorReadFailure.set(t)
                            }
                        }

                    /*
                     * Both streams are drained concurrently while the
                     * process runs. This prevents either pipe from
                     * filling and blocking the process.
                     */
                    val exitCode =
                        process.waitFor()

                    /*
                     * Wait for both readers to consume all remaining
                     * output before delivering the final callback.
                     */
                    outputThread.join()
                    errorThread.join()

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
                        stdoutFailure != null ||
                        stderrFailure != null
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
                         * stderr may contain harmless warnings even when
                         * the command succeeded. Preserve it as diagnostic
                         * output instead of treating it as a failure.
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
                         * stdout may already have been delivered through
                         * partial callbacks. Only undelivered trailing
                         * output needs to be included in the terminal
                         * callback.
                         *
                         * For compatibility, an empty final callback is
                         * still delivered with done=true.
                         */
                        val trailingLineCount =
                            stdout
                                .lineSequence()
                                .count {
                                    it.isNotEmpty()
                                } % safeLineBundle

                        val terminalOutput =
                            if (
                                safeLineBundle == Int.MAX_VALUE ||
                                stdout
                                    .lineSequence()
                                    .count {
                                        it.isNotEmpty()
                                    } < safeLineBundle
                            ) {
                                completeOutput
                            } else {
                                buildString {
                                    if (trailingLineCount > 0) {
                                        append(
                                            stdout
                                                .lineSequence()
                                                .filter {
                                                    it.isNotEmpty()
                                                }
                                                .toList()
                                                .takeLast(
                                                    trailingLineCount
                                                )
                                                .joinToString(
                                                    separator = "\n",
                                                    postfix = "\n"
                                                )
                                        )
                                    }

                                    if (stderr.isNotBlank()) {
                                        append(stderr)
                                    }
                                }
                            }

                        deliverResultOnce(
                            terminalOutput
                        )
                    } else {
                        val error =
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

                        deliverErrorOnce(
                            error
                        )
                    }
                } catch (t: Throwable) {
                    deliverErrorOnce(
                        t.message
                            ?: "An unexpected error occurred " +
                                "while executing the command: " +
                                t.javaClass.simpleName
                    )
                }
            }.apply {
                name = "CurBox-Shizuku-command"
                start()
            }
        }
    }
}
