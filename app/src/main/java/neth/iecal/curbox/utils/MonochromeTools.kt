package neth.iecal.curbox.utils

import android.content.Context
import android.provider.Settings
import android.util.Log
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import neth.iecal.curbox.utils.privileged.PrivilegedRunner

class GrayscaleControl {

    companion object {
        private const val DALTONIZER = "accessibility_display_daltonizer"
        private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"

        // 0 = full monochrome
        private const val MONOCHROME_MODE = 0

        /**
         * Grants WRITE_SECURE_SETTINGS to the app through the currently
         * available privileged backend.
         *
         * Needed only once; after that grayscale toggles can work directly
         * when this build supports WRITE_SECURE_SETTINGS.
         */
        fun grantWriteSecureSettings(
            context: Context,
            onDone: (success: Boolean) -> Unit
        ) {
            PrivilegedRunner.executeCommand(
                command =
                    "pm grant ${context.packageName} " +
                        "android.permission.WRITE_SECURE_SETTINGS",
                listener = object :
                    PrivilegedBackend.CommandResultListener {

                    override fun onCommandResult(
                        output: String,
                        done: Boolean
                    ) {
                        if (done) {
                            onDone(true)
                        }
                    }

                    override fun onCommandError(
                        error: String
                    ) {
                        Log.e(
                            "GrayscaleControl",
                            "pm grant failed: $error"
                        )
                        onDone(false)
                    }
                }
            )
        }
    }

    private val commandListener =
        object : PrivilegedBackend.CommandResultListener {

            override fun onCommandResult(
                output: String,
                done: Boolean
            ) {
                Log.d(
                    "monochrome output: ",
                    output
                )
            }

            override fun onCommandError(
                error: String
            ) {
                Log.d(
                    "monochrome error: ",
                    error
                )
            }
        }

    /**
     * Enable grayscale mode.
     */
    fun enableGrayscale(
        context: Context
    ) {
        if (
            writeDaltonizer(
                context,
                enable = true
            )
        ) {
            return
        }

        PrivilegedRunner.executeCommand(
            command = withSelfGrant(
                context,
                "settings put secure " +
                    "$DALTONIZER $MONOCHROME_MODE && " +
                    "settings put secure " +
                    "$DALTONIZER_ENABLED 1"
            ),
            listener = commandListener
        )
    }

    /**
     * Disable grayscale mode.
     */
    fun disableGrayscale(
        context: Context
    ) {
        if (
            writeDaltonizer(
                context,
                enable = false
            )
        ) {
            return
        }

        PrivilegedRunner.executeCommand(
            command = withSelfGrant(
                context,
                "settings put secure $DALTONIZER_ENABLED 0"
            ),
            listener = commandListener
        )
    }

    /**
     * Toggle grayscale mode based on current state.
     */
    fun toggleGrayscale(
        context: Context
    ) {
        if (isGrayscaleEnabled(context)) {
            disableGrayscale(context)
        } else {
            enableGrayscale(context)
        }
    }

    /**
     * Get current grayscale state.
     *
     * Reading secure settings needs no WRITE_SECURE_SETTINGS permission.
     */
    fun isGrayscaleEnabled(
        context: Context
    ): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            DALTONIZER_ENABLED,
            0
        ) == 1
    }

    /**
     * Writes the daltonizer settings directly when this build supports
     * WRITE_SECURE_SETTINGS and the permission has already been granted.
     *
     * Returns false when a privileged shell fallback should be used instead.
     */
    private fun writeDaltonizer(
        context: Context,
        enable: Boolean
    ): Boolean {
        if (!BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) {
            return false
        }

        if (!PermissionUtils.hasWriteSecureSettings(context)) {
            return false
        }

        return try {
            val resolver =
                context.contentResolver

            if (enable) {
                Settings.Secure.putInt(
                    resolver,
                    DALTONIZER,
                    MONOCHROME_MODE
                )

                Settings.Secure.putInt(
                    resolver,
                    DALTONIZER_ENABLED,
                    1
                )
            } else {
                Settings.Secure.putInt(
                    resolver,
                    DALTONIZER_ENABLED,
                    0
                )
            }

            true
        } catch (e: Exception) {
            Log.e(
                "GrayscaleControl",
                "Direct secure settings write failed",
                e
            )
            false
        }
    }

    /**
     * While a privileged shell is still required, grant this app
     * WRITE_SECURE_SETTINGS in the same command so later toggles can use
     * Settings.Secure directly.
     */
    private fun withSelfGrant(
        context: Context,
        command: String
    ): String {
        if (!BuildConfig.SUPPORTS_WRITE_SECURE_SETTINGS) {
            return command
        }

        return "pm grant ${context.packageName} " +
            "android.permission.WRITE_SECURE_SETTINGS; $command"
    }
}
