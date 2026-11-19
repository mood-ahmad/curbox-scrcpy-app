package neth.iecal.curbox.utils.privileged

/**
 * Central entry point for privileged command execution in CurBox.
 *
 * Features should use this class instead of depending directly on Shizuku
 * or any future privileged-access technology.
 *
 * At this stage Shizuku is the only registered backend. Direct ADB will be
 * added later as a second backend without changing callers.
 */
object PrivilegedRunner {

    private const val DEFAULT_TIMEOUT_MS = 120_000L

    /**
     * Backends in preferred execution order.
     *
     * Shizuku is intentionally the only backend during the foundation phase
     * so existing CurBox behaviour remains unchanged.
     */
    private val backends: List<PrivilegedBackend> =
        listOf(
            ShizukuBackend
        )

    /**
     * Returns the first backend currently ready to execute commands.
     */
    fun getAvailableBackend(): PrivilegedBackend? {
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
        return getAvailableBackend() != null
    }

    /**
     * Returns the name of the backend currently selected for command
     * execution, or null when no backend is available.
     */
    fun activeBackendName(): String? {
        return getAvailableBackend()?.name
    }

    /**
     * Executes a command using the first available privileged backend.
     *
     * Backend selection is centralised here so callers do not need to know
     * whether Shizuku, Direct ADB, or another backend performs the command.
     */
    fun executeCommand(
        command: String,
        listener: PrivilegedBackend.CommandResultListener,
        lineBundle: Int = 50,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ) {
        val backend = getAvailableBackend()

        if (backend == null) {
            listener.onCommandError(
                "No privileged backend is currently available."
            )
            return
        }

        try {
            backend.executeCommand(
                command = command,
                listener = listener,
                lineBundle = lineBundle,
                timeoutMs = timeoutMs
            )
        } catch (t: Throwable) {
            listener.onCommandError(
                buildString {
                    append("Failed to start privileged command using ")
                    append(backend.name)
                    append(": ")
                    append(
                        t.message
                            ?: t.javaClass.simpleName
                    )
                }
            )
        }
    }
}
