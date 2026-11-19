package neth.iecal.curbox.utils.privileged

import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.ShizukuRunner

/**
 * Privileged backend backed by the existing Shizuku implementation.
 *
 * This class deliberately delegates command execution to ShizukuRunner
 * without changing its behaviour. It exists so CurBox features can move
 * behind PrivilegedBackend while the current Shizuku path remains intact.
 */
object ShizukuBackend : PrivilegedBackend {

    override val name: String = "Shizuku"

    override fun isAvailable(): Boolean {
        return PermissionUtils.isShizukuAvailable() &&
            PermissionUtils.hasShizukuPermission()
    }

    override fun executeCommand(
        command: String,
        listener: PrivilegedBackend.CommandResultListener,
        lineBundle: Int,
        timeoutMs: Long
    ) {
        ShizukuRunner.executeCommand(
            command = command,
            listener = object : ShizukuRunner.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    listener.onCommandResult(
                        output = output,
                        done = done
                    )
                }

                override fun onCommandError(
                    error: String
                ) {
                    listener.onCommandError(error)
                }
            },
            lineBundle = lineBundle,
            timeoutMs = timeoutMs
        )
    }
}
