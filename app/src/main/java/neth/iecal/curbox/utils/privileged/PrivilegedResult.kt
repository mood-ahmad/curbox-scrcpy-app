package neth.iecal.curbox.utils.privileged

/**
 * Represents the final result of a privileged command execution.
 *
 * This model is backend-independent so callers do not need to know whether
 * the command was executed through Shizuku, Direct ADB, or another backend.
 */
sealed class PrivilegedResult {

    /**
     * The command completed successfully.
     */
    data class Success(
        val output: String = "",
        val backendName: String
    ) : PrivilegedResult()

    /**
     * The command failed.
     */
    data class Failure(
        val error: String,
        val backendName: String? = null
    ) : PrivilegedResult()
}
