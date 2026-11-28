package neth.iecal.curbox.utils.privileged

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.Context
import neth.iecal.curbox.BuildConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.privileged.adb.BatteryUnrestrictedNavigator

/**
 * Applies CurBox privileges that can persist after the privileged shell
 * operation itself has finished.
 *
 * Runtime/user-facing permissions such as POST_NOTIFICATIONS deliberately do
 * not belong here. They continue to use Android's normal permission flow.
 */
object PersistentPrivilegesManager {

    data class Result(
        val success: Boolean,
        val output: String = "",
        val error: String? = null
    )

    interface Listener {
        fun onComplete(
            result: Result
        )
    }

    /**
     * Applies the persistent privileges CurBox can configure through an
     * available privileged backend.
     *
     * The command is idempotent:
     * - SYSTEM_ALERT_WINDOW is set to allow.
     * - DND policy access is allowed for CurBox.
     * - WRITE_SECURE_SETTINGS is granted only on builds that support it.
     * - AppBlockerService is appended to the existing enabled accessibility
     *   services list without removing other services.
     */
    fun apply(
        context: Context,
        listener: Listener
    ) {
        val appContext =
            context.applicationContext

        val packageName =
            appContext.packageName

        val accessibilityService =
            "$packageName/${AppBlockerService::class.java.name}"

        val secureSettingsGrant =
            if (
                BuildConfig
                    .SUPPORTS_WRITE_SECURE_SETTINGS
            ) {
                """
                pm grant $packageName android.permission.WRITE_SECURE_SETTINGS
                """.trimIndent()
            } else {
                ""
            }

        val command =
            """
            appops set $packageName SYSTEM_ALERT_WINDOW allow
            $secureSettingsGrant
            cmd notification allow_dnd $packageName

            CURRENT_ACC_SVCS=${'$'}(settings get secure enabled_accessibility_services)

            if [ "${'$'}CURRENT_ACC_SVCS" = "null" ] || [ -z "${'$'}CURRENT_ACC_SVCS" ]; then
                NEW_ACC_SVCS="$accessibilityService"
            else
                NEW_ACC_SVCS="${'$'}CURRENT_ACC_SVCS"

                case ":${'$'}CURRENT_ACC_SVCS:" in
                    *":$accessibilityService:"*)
                        ;;
                    *)
                        NEW_ACC_SVCS="${'$'}NEW_ACC_SVCS:$accessibilityService"
                        ;;
                esac
            fi

            settings put secure enabled_accessibility_services "${'$'}NEW_ACC_SVCS"
            settings put secure accessibility_enabled 1
            """.trimIndent()

        PrivilegedRunner.executeCommand(
            command = command,
            listener =
                object :
                    PrivilegedBackend.CommandResultListener {

                    private val output =
                        StringBuilder()

                    override fun onCommandResult(
                        output: String,
                        done: Boolean
                    ) {
                        this.output.append(
                            output
                        )

                        if (!done) {
                            return
                        }

                        enableServiceProtection(appContext)

                        listener.onComplete(
                            Result(
                                success = true,
                                output =
                                    this.output
                                        .toString()
                            )
                        )
                    }

                    override fun onCommandError(
                        error: String
                    ) {
                        listener.onComplete(
                            Result(
                                success = false,
                                output =
                                    output.toString(),
                                error =
                                    error
                            )
                        )
                    }
                }
        )
    }

    /**
     * Turns on "Keep Horus running" together with the other privileges that
     * are granted after pairing: stores the setting, schedules the watchdog
     * and asks the now-available privileged backend to exempt the app from
     * doze and background limits. Every step is best effort and additive, so
     * a device that refuses one of them is not affected otherwise.
     */
    private fun enableServiceProtection(appContext: Context) {
        try {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    neth.iecal.curbox.utils.DataStoreManager(appContext)
                        .updateServiceProtectionConfig { it.copy(isEnabled = true) }
                } catch (_: Throwable) {
                }
            }
            neth.iecal.curbox.services.ServiceWatchdogJob.schedule(appContext)
            neth.iecal.curbox.utils.ServiceProtectionManager
                .reinforceBackgroundExecution(appContext)
            enableTamperProtection(appContext)
            requestSamsungUnrestrictedBattery(appContext)
            scheduleProtectionVerification(appContext)
        } catch (_: Throwable) {
        }
    }

    /**
     * Samsung keeps its own battery list (Never sleeping / Unrestricted) that
     * the shell cannot change, so the accessibility service taps through the
     * App info page once. Other makers skip this; the manual row stays.
     */
    private fun requestSamsungUnrestrictedBattery(appContext: Context) {
        try {
            if (!android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return
            val prefs = appContext.getSharedPreferences("protection_state", Context.MODE_PRIVATE)
            if (prefs.getBoolean("samsung_battery_requested", false)) return
            if (!neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(
                    appContext,
                    AppBlockerService::class.java
                )
            ) return
            prefs.edit().putBoolean("samsung_battery_requested", true).apply()
            BatteryUnrestrictedNavigator.request(appContext)
            appContext.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + appContext.packageName)
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    /** Logs which protection layers are active a few seconds after pairing. */
    private fun scheduleProtectionVerification(appContext: Context) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val svc = neth.iecal.curbox.utils.ServiceProtectionManager
                val adminActive = neth.iecal.curbox.utils.AntiUninstallManager.isAdminActive(appContext)
                val report =
                    "deviceAdmin=$adminActive; " +
                    "blockerService=${svc.isAppBlockerEnabled(appContext)}; " +
                        "batteryExempt=${svc.isIgnoringBatteryOptimizations(appContext)}; " +
                        "selfHeal=${svc.canSelfHeal()}"
                neth.iecal.curbox.remotecontrol.EventLogger.log("PROTECTION_VERIFY", report)
            } catch (_: Throwable) {
            }
        }, 6000L)
    }

    /**
     * Uninstall protection used to need a manual setup that nothing in the
     * pairing flow triggered. With the privileged shell the device admin can be
     * activated without the system dialog, so protection now turns on by itself
     * after pairing. It can only be lifted by a signed approval to a removal
     * request. Skipped on builds without anti-uninstall and when already on.
     */
    private fun enableTamperProtection(appContext: Context) {
        if (!BuildConfig.SUPPORTS_ANTI_UNINSTALL) return
        try {
            val store = neth.iecal.curbox.utils.DataStoreManager(appContext)
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    if (store.settings.first().antiUninstallConfig2.isEnabled) return@launch
                } catch (_: Throwable) {
                }
                val component =
                    neth.iecal.curbox.utils.AntiUninstallManager.adminComponent(appContext)
                        .flattenToShortString()
                PrivilegedRunner.executeCommand(
                    command = "dpm set-active-admin --user 0 '$component'",
                    listener = object : PrivilegedBackend.CommandResultListener {
                        private val out = StringBuilder()
                        override fun onCommandResult(output: String, done: Boolean) {
                            out.append(output)
                            if (!done) return
                            finishTamperEnable(appContext, store, out.toString())
                        }

                        override fun onCommandError(error: String) {
                            neth.iecal.curbox.remotecontrol.EventLogger.log(
                                "TAMPER_ENABLE",
                                "admin activation failed: " + error.take(120)
                            )
                        }
                    }
                )
            }
        } catch (_: Throwable) {
        }
    }

    private fun finishTamperEnable(
        appContext: Context,
        store: neth.iecal.curbox.utils.DataStoreManager,
        shellOutput: String
    ) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val active =
                    neth.iecal.curbox.utils.AntiUninstallManager.isAdminActive(appContext)
                if (!active) {
                    // Shell refused: open the system screen; the accessibility
                    // service taps Activate and the service finishes the setup.
                    try {
                        neth.iecal.curbox.utils.privileged.adb.AdminActivationRequest.request(appContext)
                        appContext.startActivity(
                            android.content.Intent(
                                android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                            ).putExtra(
                                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                neth.iecal.curbox.utils.AntiUninstallManager.adminComponent(appContext)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) {
                    }
                }
                if (active) {
                    store.updateAntiUninstallConfig {
                        it.copy(
                            isEnabled = true,
                            mode = neth.iecal.curbox.data.models.AntiUninstallMode.REMOTE,
                            unlockRequestedAtMs = 0L
                        )
                    }
                }
                neth.iecal.curbox.remotecontrol.EventLogger.log(
                    "TAMPER_ENABLE",
                    "deviceAdminActive=$active; shell=" + shellOutput.trim().take(120)
                )
            } catch (_: Throwable) {
            }
        }
    }
}
