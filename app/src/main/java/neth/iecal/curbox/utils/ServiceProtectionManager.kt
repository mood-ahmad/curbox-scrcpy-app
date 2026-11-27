package neth.iecal.curbox.utils

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import kotlinx.coroutines.flow.first
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.receivers.AdminReceiver
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.privileged.PrivilegedBackend
import neth.iecal.curbox.utils.privileged.PrivilegedRunner

/**
 * The brain behind the Service Protection screen and the watchdog.
 *
 * It checks how well protected the service is and, when a privileged backend
 * is available, repairs the things an OEM battery manager tends to break:
 * it turns the accessibility service back on and keeps the app out of doze
 * and background limits.
 *
 * Nothing here can make the app truly unkillable. A force stop still wins.
 * The goal is only to make the service hard to kill and fast to bring back.
 */
object ServiceProtectionManager {

    private const val TAG = "ServiceProtection"

    fun isAppBlockerEnabled(
        context: Context
    ): Boolean =
        PermissionUtils.isAccessibilityServiceEnabled(
            context,
            AppBlockerService::class.java
        )

    /**
     * Self-healing requires at least one privileged backend that is currently
     * ready to execute commands.
     *
     * During the foundation phase this still means Shizuku. Direct ADB will
     * later be able to satisfy the same requirement without changing callers.
     */
    fun canSelfHeal(): Boolean =
        PrivilegedRunner.isAvailable()

    fun isIgnoringBatteryOptimizations(
        context: Context
    ): Boolean {
        return try {
            val powerManager =
                context.getSystemService(
                    Context.POWER_SERVICE
                ) as PowerManager

            powerManager.isIgnoringBatteryOptimizations(
                context.packageName
            )
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Intent that lets the user drop battery optimization for the app.
     *
     * When this build holds REQUEST_IGNORE_BATTERY_OPTIMIZATIONS we can show
     * the one-tap allow dialog. Otherwise we open the battery optimization
     * settings list, which does not require that permission.
     */
    fun ignoreBatteryOptimizationsIntent(
        context: Context
    ): Intent =
        if (
            canRequestIgnoreBatteryOptimizations(
                context
            )
        ) {
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse(
                    "package:${context.packageName}"
                )
            )
        } else {
            Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            )
        }

    /**
     * True only when the permission is available to this installed build.
     */
    private fun canRequestIgnoreBatteryOptimizations(
        context: Context
    ): Boolean =
        context.checkSelfPermission(
            android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Checks the AppBlocker accessibility service and repairs it when a
     * privileged backend is available.
     *
     * Background-execution protections are also reinforced.
     */
    fun healNow(
        context: Context
    ) {
        try {
            if (!canSelfHeal()) {
                return
            }

            reinforceBackgroundExecution(context)

            if (!isAppBlockerEnabled(context)) {
                reEnableAccessibilityServices(
                    context
                )
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "healNow failed",
                e
            )
        }
    }

    /**
     * Adds the CurBox accessibility service back into the secure settings
     * list through the currently available privileged backend.
     */
    private fun reEnableAccessibilityServices(
        context: Context
    ) {
        val wanted =
            listOf(
                ComponentName(
                    context,
                    AppBlockerService::class.java
                ).flattenToString()
            )

        val current =
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

        val existing =
            linkedSetOf<String>()

        if (!current.isNullOrEmpty()) {
            val splitter =
                TextUtils.SimpleStringSplitter(':')

            splitter.setString(current)

            while (splitter.hasNext()) {
                existing.add(
                    splitter.next()
                )
            }
        }

        existing.addAll(wanted)

        val merged =
            existing.joinToString(":")

        runShell(
            command =
                "settings put secure " +
                    "enabled_accessibility_services '$merged'; " +
                    "settings put secure accessibility_enabled 1",
            successMessage =
                "Re-enabled accessibility service using privileged backend"
        )
    }

    /**
     * Keeps CurBox out of doze and allows its background execution through
     * the currently available privileged backend.
     */
    /**
     * MIUI keeps "Autostart" as app-op 10008, which the shell may grant.
     * Other makers have no shell-grantable autostart switch, so this is empty
     * there and the OEM screen stays the manual fallback.
     */
    private fun miuiAutostartCommand(pkg: String): String {
        val maker = android.os.Build.MANUFACTURER.lowercase()
        return if (
            maker.contains("xiaomi") ||
            maker.contains("redmi") ||
            maker.contains("poco")
        ) {
            "; appops set $pkg 10008 allow"
        } else {
            ""
        }
    }

    fun reinforceBackgroundExecution(
        context: Context
    ) {
        if (!canSelfHeal()) {
            return
        }

        val pkg =
            context.packageName

        runShell(
            command =
                "dumpsys deviceidle whitelist +$pkg; " +
                    "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow; " +
                    "cmd appops set $pkg RUN_IN_BACKGROUND allow" +
                    miuiAutostartCommand(pkg),
            successMessage =
                "Reinforced background execution using privileged backend"
        )
    }

    /**
     * The strongest protection tier.
     *
     * Makes CurBox a device owner when Android allows it. The command itself
     * is executed through whichever privileged backend is available.
     */
    fun setDeviceOwner(
        context: Context
    ) {
        if (!canSelfHeal()) {
            return
        }

        val admin =
            ComponentName(
                context,
                AdminReceiver::class.java
            ).flattenToString()

        runShell(
            command =
                "dpm set-device-owner '$admin'",
            successMessage =
                "Device owner command completed using privileged backend"
        )
    }

    fun isDeviceOwner(
        context: Context
    ): Boolean {
        val devicePolicyManager =
            context.getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as DevicePolicyManager

        return devicePolicyManager.isDeviceOwnerApp(
            context.packageName
        )
    }

    /**
     * Returns false when tamper protection still requires the device admin.
     */
    @Suppress("DEPRECATION")
    suspend fun removeDeviceAdmin(
        context: Context
    ): Boolean {
        if (!BuildConfig.SUPPORTS_ANTI_UNINSTALL) {
            return false
        }

        if (
            DataStoreManager(context)
                .settings
                .first()
                .antiUninstallConfig2
                .isEnabled
        ) {
            return false
        }

        val devicePolicyManager =
            context.getSystemService(
                Context.DEVICE_POLICY_SERVICE
            ) as DevicePolicyManager

        if (
            devicePolicyManager.isDeviceOwnerApp(
                context.packageName
            )
        ) {
            devicePolicyManager.clearDeviceOwnerApp(
                context.packageName
            )
        } else {
            AntiUninstallManager.removeProtection(
                context
            )
        }

        return true
    }

    /**
     * Executes a shell command through the unified privileged-access layer.
     */
    private fun runShell(
        command: String,
        successMessage: String? = null
    ) {
        PrivilegedRunner.executeCommand(
            command = command,
            listener = object :
                PrivilegedBackend.CommandResultListener {

                override fun onCommandResult(
                    output: String,
                    done: Boolean
                ) {
                    if (
                        done &&
                        successMessage != null
                    ) {
                        Log.i(
                            TAG,
                            successMessage
                        )
                    }
                }

                override fun onCommandError(
                    error: String
                ) {
                    Log.e(
                        TAG,
                        "shell error: $error"
                    )
                }
            }
        )
    }
}
