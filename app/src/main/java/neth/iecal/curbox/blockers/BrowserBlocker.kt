package neth.iecal.curbox.blockers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import neth.iecal.curbox.blockers.BaseBlocker
import androidx.core.net.toUri
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST

class BrowserBlocker(val service: AccessibilityService) : BaseBlocker() {

    // Cache for packages CONFIRMED as browsers
    private val cacheBlockedBrowserApps: HashSet<String> = hashSetOf()

    // Cache for packages CONFIRMED as non-browsers
    private val cacheNotBlockedBrowserApps: HashSet<String> = hashSetOf()

    var isTurnedOn = false
    fun isAppBrowser(event: AccessibilityEvent?): Boolean {
        if(!isTurnedOn || event == null) return false
        val packageName = event.packageName?.toString() ?: return false

        if (cacheBlockedBrowserApps.contains(packageName)) {
            return true
        }
        if (cacheNotBlockedBrowserApps.contains(packageName)) {
            return false
        }

        val isBrowser = resolveIsBrowser(service, packageName) && !URL_BAR_ID_LIST.containsKey(packageName)

        if (isBrowser) {
            cacheBlockedBrowserApps.add(packageName)
        } else {
            cacheNotBlockedBrowserApps.add(packageName)
        }

        return isBrowser
    }

    private fun resolveIsBrowser(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, "https://github.com/mood-ahmad/curbox-scrcpy-app#readme".toUri())
        intent.setPackage(packageName)

        val pm = context.packageManager
        // MATCH_DEFAULT_ONLY is usually safer/faster than 0
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

        Log.d("packages",activities.toString())
        return activities.isNotEmpty()
    }
}