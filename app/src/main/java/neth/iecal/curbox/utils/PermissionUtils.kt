package neth.iecal.curbox.utils

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat.startActivity
import neth.iecal.curbox.services.AppBlockerService

object PermissionUtils {
    fun openAccessibilityServiceScreen(context: Context, cls: Class<*>) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val componentName = ComponentName(context, cls)
            intent.putExtra(":settings:fragment_args_key", componentName.flattenToString())
            val bundle = Bundle()
            bundle.putString(":settings:fragment_args_key", componentName.flattenToString())
            intent.putExtra(":settings:show_fragment_args", bundle)
            context.startActivity(intent,)
        } catch (e: Exception) {
            e.printStackTrace()
            // Fallback to general Accessibility Settings
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isNotificationPermissionGiven(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponentName = ComponentName(context, serviceClass)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == expectedComponentName) {
                return true
            }
        }
        return false
    }

    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasOverlayPermission(context) &&
                isNotificationPermissionGiven(context) &&
                isAccessibilityServiceEnabled(context, AppBlockerService::class.java)
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * True when any privileged backend (Shizuku or Direct ADB) can run commands.
     */
    fun hasPrivilegedAccess(): Boolean {
        return try {
            neth.iecal.curbox.utils.privileged.PrivilegedRunner.isAvailable()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Opens the Direct Access screen so the user can pair Direct ADB.
     */
    fun openDirectAccess(context: Context) {
        val intent = android.content.Intent(
            context,
            neth.iecal.curbox.ui.activity.FragmentActivity::class.java
        ).apply {
            putExtra(
                "fragment",
                neth.iecal.curbox.ui.fragments.main.DirectAccessFragment.FRAGMENT_ID
            )
        }
        context.startActivity(intent)
    }
}
