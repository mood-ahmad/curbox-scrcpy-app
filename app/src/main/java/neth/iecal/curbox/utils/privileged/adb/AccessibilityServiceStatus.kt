package neth.iecal.curbox.utils.privileged.adb

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import neth.iecal.curbox.services.AppBlockerService

/**
 * Reads whether CurBox's AppBlockerService is currently enabled as an Android
 * Accessibility service.
 *
 * This object performs read-only state inspection. It never changes Android
 * Accessibility settings.
 */
object AccessibilityServiceStatus {

    fun isAppBlockerServiceEnabled(
        context: Context
    ): Boolean {
        val appContext =
            context.applicationContext

        val expectedComponent =
            ComponentName(
                appContext,
                AppBlockerService::class.java
            )

        val enabledServices =
            try {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (_: Throwable) {
                null
            }
                ?: return false

        if (enabledServices.isBlank()) {
            return false
        }

        return enabledServices
            .split(
                ':'
            )
            .mapNotNull { flattenedComponent ->
                ComponentName.unflattenFromString(
                    flattenedComponent.trim()
                )
            }
            .any { enabledComponent ->
                enabledComponent ==
                    expectedComponent
            }
    }
}
