package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import android.provider.Settings
import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import neth.iecal.curbox.utils.privileged.PrivilegedRunner

/**
 * Reads and changes Android's Wireless Debugging state.
 *
 * AOSP exposes the state through Settings.Global.ADB_WIFI_ENABLED.
 *
 * CurBox never treats successful command submission as proof that Wireless
 * Debugging changed. The state is read again after the privileged command
 * completes and only then is success reported.
 */
object WirelessDebuggingController {

    private const val ADB_WIFI_ENABLED =
        "adb_wifi_enabled"

    interface Listener {

        /**
         * Called after the requested state has been verified.
         */
        fun onResult(
            enabled: Boolean
        )

        /**
         * Called when the privileged operation failed or Android did not apply
         * the requested state.
         */
        fun onError(
            error: String
        )
    }

    /**
     * Returns Android's current Wireless Debugging setting.
     *
     * Android uses 0 for disabled and a non-zero value for enabled.
     */
    fun isEnabled(
        context: Context
    ): Boolean {
        return try {
            Settings.Global.getInt(
                context.contentResolver,
                ADB_WIFI_ENABLED,
                0
            ) != 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Enables Wireless Debugging through the currently available privileged
     * backend.
     *
     * If Android already reports it enabled, no shell command is executed.
     */
    fun enable(
        context: Context,
        listener: Listener
    ) {
        setEnabled(
            context = context,
            enabled = true,
            listener = listener
        )
    }

    /**
     * Disables Wireless Debugging through the currently available privileged
     * backend.
     */
    fun disable(
        context: Context,
        listener: Listener
    ) {
        setEnabled(
            context = context,
            enabled = false,
            listener = listener
        )
    }

    private fun setEnabled(
        context: Context,
        enabled: Boolean,
        listener: Listener
    ) {
        val appContext =
            context.applicationContext

        if (
            isEnabled(appContext) ==
            enabled
        ) {
            listener.onResult(enabled)
            return
        }

        if (neth.iecal.curbox.utils.privileged.PermissionKeeper.hasSecureSettings(appContext)) {
            try {
                Settings.Global.putInt(
                    appContext.contentResolver,
                    ADB_WIFI_ENABLED,
                    if (enabled) 1 else 0
                )
                val actual = isEnabled(appContext)
                if (actual == enabled) {
                    listener.onResult(actual)
                    return
                }
            } catch (_: Throwable) {
            }
        }

        if (!PrivilegedRunner.isAvailable()) {
            listener.onError(
                "No privileged backend is available to change Wireless Debugging."
            )
            return
        }

        val requestedValue =
            if (enabled) {
                1
            } else {
                0
            }

        PrivilegedRunner.executeCommand(
            command =
                "settings put global " +
                    "$ADB_WIFI_ENABLED $requestedValue",
            listener =
                object :
                    PrivilegedBackend.CommandResultListener {

                    override fun onCommandResult(
                        output: String,
                        done: Boolean
                    ) {
                        if (!done) {
                            return
                        }

                        val actualState =
                            isEnabled(appContext)

                        if (actualState == enabled) {
                            listener.onResult(
                                actualState
                            )
                        } else {
                            listener.onError(
                                "Android did not apply the requested " +
                                    "Wireless Debugging state."
                            )
                        }
                    }

                    override fun onCommandError(
                        error: String
                    ) {
                        listener.onError(
                            "Failed to change Wireless Debugging: $error"
                        )
                    }
                }
        )
    }
}
