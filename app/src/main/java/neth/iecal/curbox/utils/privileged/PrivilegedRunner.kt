package neth.iecal.curbox.utils.privileged

import neth.iecal.curbox.utils.privileged.adb.DirectAdbBackend

/**
 * Central entry point for privileged command execution in CurBox.
 *
 * Features should use this class instead of depending directly on Shizuku,
 * Direct ADB, or any other privileged-access technology.
 *
 * Backend selection is centralised here so existing feature callers do not
 * need to know which privileged transport performs a command.
 */
object PrivilegedRunner {

    private const val DEFAULT_TIMEOUT_MS =
        120_000L

    /**
     * Backends in preferred execution order.
     *
     * Shizuku intentionally remains first so existing CurBox behaviour stays
     * unchanged whenever Shizuku is available.
     *
     * Direct ADB acts as the fallback once its channel is paired, connected,
     * and verified.
     */
    private val backends:
        List<PrivilegedBackend> =
        listOf(
            ShizukuBackend,
            DirectAdbBackend
        )

    /**
     * Returns the first backend currently ready to execute commands.
     */
    fun getAvailableBackend():
        PrivilegedBackend? {

        return backends.firstOrNull { backend ->
            try {
                backend.isAvailable()
            } catch (_: Throwable) {
                false
            }
        }
    }

    /**
     * Returns true when at least one privileged backend is ready.
     */
    fun isAvailable(): Boolean {
        return getAvailableBackend() !=
            null
    }

    /**
     * Returns the name of the backend currently selected for command
     * execution, or null when no backend is available.
     */
    fun activeBackendName(): String? {
        return getAvailableBackend()
            ?.name
    }

    /**
     * Executes a command using the first available privileged backend.
     *
     * Current preference:
     *
     * 1. Shizuku
     * 2. Direct ADB
     *
     * Direct ADB therefore provides persistent privileged execution without
     * changing the behaviour of devices where Shizuku is already available.
     */
    fun executeCommand(
        command: String,
        listener:
            PrivilegedBackend.CommandResultListener,
        lineBundle: Int = 50,
        timeoutMs: Long =
            DEFAULT_TIMEOUT_MS
    ) {
        val backend =
            getAvailableBackend()

        if (backend == null) {
            listener.onCommandError(
                "No privileged backend is currently available."
            )

            return
        }

        try {
            backend.executeCommand(
                command =
                    command,
                listener =
                    listener,
                lineBundle =
                    lineBundle,
                timeoutMs =
                    timeoutMs
            )
        } catch (t: Throwable) {
            listener.onCommandError(
                buildString {
                    append(
                        "Failed to start privileged command using "
                    )

                    append(
                        backend.name
                    )

                    append(
                        ": "
                    )

                    append(
                        t.message
                            ?: t.javaClass.simpleName
                    )
                }
            )
        }
    }
}
