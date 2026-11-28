package neth.iecal.curbox.utils.privileged.adb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens the Wireless debugging page directly (language independent: no menu
 * labels needed), falling back to Developer options, then Settings.
 */
object SettingsLauncher {
    private const val SETTINGS_PKG = "com.android.settings"
    private const val WIRELESS_ACTIVITY = "com.android.settings.Settings\$WirelessDebuggingActivity"

    fun openWirelessDebugging(context: Context): Boolean = try {
        context.startActivity(
            Intent().setClassName(SETTINGS_PKG, WIRELESS_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Throwable) {
        false
    }

    fun openDeveloperOptions(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Throwable) {
            false
        }
    } catch (_: Throwable) {
        false
    }

    /** Direct Wireless debugging page when the device allows it, else Developer options. */
    fun openBest(context: Context): Boolean =
        openWirelessDebugging(context) || openDeveloperOptions(context)
}
