package neth.iecal.curbox.utils.privileged

/**
 * Common contract for any backend capable of executing privileged commands.
 *
 * CurBox can have multiple implementations of this interface, such as:
 * - Shizuku
 * - Direct ADB
 *
 * Features using privileged access should depend on this interface rather
 * than depending directly on a specific privileged-access technology.
 */
interface PrivilegedBackend {

    /**
     * Human-readable identifier for diagnostics and logging.
     */
    val name: String

    /**
     * Returns true when this backend is currently ready to execute commands.
     */
    fun isAvailable(): Boolean

    /**
     * Executes a privileged shell command.
     *
     * Implementations are responsible for running the command away from the
     * main thread and delivering exactly one terminal result: either
     * [CommandResultListener.onCommandResult] with done=true or
     * [CommandResultListener.onCommandError].
     *
     * Partial output may be delivered with done=false.
     */
    fun executeCommand(
        command: String,
        listener: CommandResultListener,
        lineBundle: Int = 50,
        timeoutMs: Long = 120_000L
    )

    interface CommandResultListener {

        fun onCommandResult(
            output: String,
            done: Boolean
        ) {
        }

        fun onCommandError(
            error: String
        ) {
        }
    }
}
