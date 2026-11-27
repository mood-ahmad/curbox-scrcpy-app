package neth.iecal.curbox.utils.privileged.adb

import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PrivilegedBackend implementation powered by CurBox's verified Direct ADB
 * connection.
 *
 * This backend does not perform pairing or reconnection itself. Those concerns
 * belong to [DirectAccessOrchestrator]. It becomes available only while
 * [AdbChannelManager] reports a live ADB connection.
 */
object DirectAdbBackend : PrivilegedBackend {

    override val name: String =
        "Direct ADB"

    private const val READ_BUFFER_SIZE =
        8 * 1024

    private const val IDLE_POLL_MS =
        20L

    override fun isAvailable(): Boolean {
        return AdbChannelManager.isConnected()
    }

    override fun executeCommand(
        command: String,
        listener: PrivilegedBackend.CommandResultListener,
        lineBundle: Int,
        timeoutMs: Long
    ) {
        val normalizedCommand =
            command.trim()

        if (normalizedCommand.isEmpty()) {
            listener.onCommandError(
                "Direct ADB command must not be empty."
            )
            return
        }

        if (!isAvailable()) {
            listener.onCommandError(
                "Direct ADB is not connected."
            )
            return
        }

        /*
         * Keep network and stream I/O away from the Android main thread.
         */
        Thread(
            {
                executeInBackground(
                    command =
                        normalizedCommand,
                    listener =
                        listener,
                    lineBundle =
                        lineBundle,
                    timeoutMs =
                        timeoutMs
                )
            },
            "CurBox-DirectAdbCommand"
        ).start()
    }

    private fun executeInBackground(
        command: String,
        listener: PrivilegedBackend.CommandResultListener,
        lineBundle: Int,
        timeoutMs: Long
    ) {
        val terminalDelivered =
            AtomicBoolean(false)

        var stream:
            AdbTransportStream? = null

        try {
            val safeTimeout =
                timeoutMs.coerceAtLeast(
                    1L
                )

            /*
             * Use one interactive shell rather than embedding an arbitrary
             * multi-line CurBox command directly inside the ADB service name.
             *
             * This avoids the quoting restrictions of shell:<command> service
             * arguments and lets CurBox send the command as raw shell input.
             */
            val streamResult =
                AdbChannelManager.openService(
                    "shell:"
                )

            if (streamResult.isFailure) {
                throw streamResult.exceptionOrNull()
                    ?: IllegalStateException(
                        "Could not open Direct ADB shell service."
                    )
            }

            stream =
                streamResult.getOrThrow()

            /*
             * Add an unambiguous sentinel after the original command.
             *
             * The sentinel tells CurBox when this command has finished while
             * keeping the interactive shell stream bounded by timeoutMs.
             */
            val token =
                System.nanoTime()

            val marker =
                "__CURBOX_ADB_DONE_" +
                    token +
                    "__"

            val startMarker =
                "__CURBOX_ADB_START_" +
                    token +
                    "__"

            /*
             * The interactive "shell:" service normally runs on a pty that
             * echoes typed input. Turn echo off first, and print the markers
             * from split printf arguments so the echoed command text can never
             * be mistaken for the real marker output. Everything up to the
             * start marker (the echo of this one line) is discarded.
             */
            stream.write(
                (
                    "stty -echo -onlcr 2>/dev/null; " +
                        "printf '%s%s\\n' '" +
                        startMarker.substring(0, 12) +
                        "' '" +
                        startMarker.substring(12) +
                        "'\n"
                    ).toByteArray(
                    StandardCharsets.UTF_8
                )
            )

            val startDeadlineNanos =
                System.nanoTime() +
                    safeTimeout *
                    1_000_000L

            val startBuffer =
                StringBuilder()

            while (true) {
                if (
                    System.nanoTime() >=
                    startDeadlineNanos
                ) {
                    throw IllegalStateException(
                        "Direct ADB shell did not become ready."
                    )
                }

                val startBytes =
                    stream.read()

                if (startBytes.isEmpty()) {
                    Thread.sleep(
                        IDLE_POLL_MS
                    )
                    continue
                }

                startBuffer.append(
                    String(
                        startBytes,
                        StandardCharsets.UTF_8
                    )
                )

                if (
                    startBuffer.indexOf(
                        startMarker
                    ) >= 0
                ) {
                    break
                }
            }

            val payload =
                buildString {
                    append(command)
                    append('\n')
                    append("printf '%s%s\\n' '")
                    append(marker.substring(0, 12))
                    append("' '")
                    append(marker.substring(12))
                    append("'\n")
                }

            stream.write(
                payload.toByteArray(
                    StandardCharsets.UTF_8
                )
            )

            val deadlineNanos =
                System.nanoTime() +
                    safeTimeout *
                    1_000_000L

            val pending =
                StringBuilder()

            val completedOutput =
                ByteArrayOutputStream()

            var deliveredLineCount =
                0

            val normalizedLineBundle =
                lineBundle.coerceAtLeast(
                    1
                )

            while (true) {
                if (
                    System.nanoTime() >=
                    deadlineNanos
                ) {
                    throw IllegalStateException(
                        "Direct ADB command timed out after " +
                            "$safeTimeout ms."
                    )
                }

                val bytes =
                    stream.read()

                if (bytes.isEmpty()) {
                    Thread.sleep(
                        IDLE_POLL_MS
                    )
                    continue
                }

                val chunk =
                    String(
                        bytes,
                        StandardCharsets.UTF_8
                    )

                pending.append(
                    chunk
                )

                val markerIndex =
                    pending.indexOf(
                        marker
                    )

                if (markerIndex >= 0) {
                    val beforeMarker =
                        pending
                            .substring(
                                0,
                                markerIndex
                            )
                            .trimEnd(
                                '\r',
                                '\n'
                            )

                    if (beforeMarker.isNotEmpty()) {
                        completedOutput.write(
                            beforeMarker.toByteArray(
                                StandardCharsets.UTF_8
                            )
                        )
                    }

                    break
                }

                /*
                 * Send partial output in line bundles while keeping an
                 * unfinished trailing line buffered for the next read.
                 */
                while (true) {
                    val newlineIndex =
                        pending.indexOf(
                            "\n"
                        )

                    if (newlineIndex < 0) {
                        break
                    }

                    val line =
                        pending
                            .substring(
                                0,
                                newlineIndex + 1
                            )

                    pending.delete(
                        0,
                        newlineIndex + 1
                    )

                    completedOutput.write(
                        line.toByteArray(
                            StandardCharsets.UTF_8
                        )
                    )

                    deliveredLineCount += 1

                    if (
                        deliveredLineCount >=
                        normalizedLineBundle
                    ) {
                        val partial =
                            completedOutput.toString(
                                StandardCharsets.UTF_8.name()
                            )

                        if (partial.isNotEmpty()) {
                            listener.onCommandResult(
                                partial,
                                false
                            )
                        }

                        completedOutput.reset()
                        deliveredLineCount = 0
                    }
                }
            }

            val finalOutput =
                completedOutput.toString(
                    StandardCharsets.UTF_8.name()
                )

            deliverSuccess(
                listener =
                    listener,
                terminalDelivered =
                    terminalDelivered,
                output =
                    finalOutput
            )
        } catch (
            interrupted:
                InterruptedException
        ) {
            Thread.currentThread()
                .interrupt()

            deliverError(
                listener =
                    listener,
                terminalDelivered =
                    terminalDelivered,
                error =
                    "Direct ADB command was interrupted."
            )
        } catch (
            t: Throwable
        ) {
            deliverError(
                listener =
                    listener,
                terminalDelivered =
                    terminalDelivered,
                error =
                    t.message
                        ?: t.javaClass.simpleName
            )
        } finally {
            try {
                stream?.close()
            } catch (_: Exception) {
                // The terminal result has already been determined.
            }
        }
    }

    private fun deliverSuccess(
        listener: PrivilegedBackend.CommandResultListener,
        terminalDelivered: AtomicBoolean,
        output: String
    ) {
        if (
            !terminalDelivered.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        listener.onCommandResult(
            output,
            true
        )
    }

    private fun deliverError(
        listener: PrivilegedBackend.CommandResultListener,
        terminalDelivered: AtomicBoolean,
        error: String
    ) {
        if (
            !terminalDelivered.compareAndSet(
                false,
                true
            )
        ) {
            return
        }

        listener.onCommandError(
            error
        )
    }
}
