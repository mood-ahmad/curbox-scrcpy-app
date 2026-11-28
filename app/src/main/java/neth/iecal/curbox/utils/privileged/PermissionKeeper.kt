package neth.iecal.curbox.utils.privileged

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.utils.ServiceProtectionManager
import neth.iecal.curbox.utils.privileged.adb.AdbChannelManager
import neth.iecal.curbox.utils.privileged.adb.DirectAccessLifecycleManager
import neth.iecal.curbox.utils.privileged.adb.WirelessDebuggingController

/**
 * Keeps Horus's own permissions on without any screens, once the one-time
 * WRITE_SECURE_SETTINGS grant has happened (done over ADB right after the
 * first pairing).
 *
 * With that permission Horus can write the secure/global settings directly:
 *  - turn Wireless debugging back on (adb_wifi_enabled),
 *  - put its accessibility service back into enabled_accessibility_services,
 * and then reconnect Direct ADB. Called from boot, app start, the periodic
 * watchdog and settings change observers. It does nothing before the grant.
 */
object PermissionKeeper {
    private const val TAG = "PermissionKeeper"
    private const val MIN_GAP_MS = 8_000L

    @Volatile private var lastRun = 0L
    @Volatile private var observing = false

    fun hasSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Idempotent, cheap and safe to call often. */
    fun ensure(context: Context, reason: String) {
        val app = context.applicationContext
        if (!hasSecureSettings(app)) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - lastRun < MIN_GAP_MS) return
            lastRun = now
        }
        try {
            ensureAccessibility(app)
            if (DirectAccessLifecycleManager.isRecoveryEnabled(app)) {
                if (!WirelessDebuggingController.isEnabled(app)) {
                    Log.i(TAG, "wireless debugging was off ($reason), turning it on")
                    WirelessDebuggingController.enable(app, object : WirelessDebuggingController.Listener {
                        override fun onResult(enabled: Boolean) {
                            if (enabled) reconnect()
                        }

                        override fun onError(error: String) {
                            Log.w(TAG, "could not enable wireless debugging: $error")
                        }
                    })
                } else {
                    reconnect()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ensure failed ($reason)", t)
        }
    }

    private fun reconnect() {
        if (!AdbChannelManager.isConnected()) {
            DirectAccessLifecycleManager.requestRecovery()
        }
    }

    private fun ensureAccessibility(app: Context) {
        if (ServiceProtectionManager.isAppBlockerEnabled(app)) return
        val me = ComponentName(app, AppBlockerService::class.java).flattenToString()
        val current = Settings.Secure.getString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val parts = current.split(':').filter { it.isNotBlank() }.toMutableList()
        if (parts.none { it == me }) parts.add(me)
        Settings.Secure.putString(
            app.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            parts.joinToString(":")
        )
        Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Log.i(TAG, "accessibility service re-enabled")
    }

    /** Re-apply whenever Wireless debugging or the accessibility list changes. */
    fun startObserving(context: Context) {
        if (observing) return
        val app = context.applicationContext
        try {
            val handler = Handler(Looper.getMainLooper())
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    handler.postDelayed({ ensure(app, "setting changed") }, 1_500L)
                }
            }
            app.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, observer
            )
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer
            )
            observing = true
        } catch (t: Throwable) {
            Log.e(TAG, "observer registration failed", t)
        }
    }
}
