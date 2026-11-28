package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.io.File

/**
 * Cross-process hand-off (the accessibility service runs in its own process):
 * "switch Wireless debugging on for me, then bring me back". The main process
 * writes a request before opening Developer options; the service navigates,
 * flips the switch only if it is off, then records "enabled" and returns to
 * the app, which resumes automatic pairing by itself.
 */
object WirelessEnableRequest {
    private const val REQUEST = "wireless_enable_request"
    private const val ENABLED = "wireless_enabled_resume"
    const val MAX_AGE_MS = 3 * 60 * 1000L

    private fun file(context: Context, name: String) =
        File(context.applicationContext.filesDir, name)

    fun request(context: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            file(context, ENABLED).delete()
            file(context, REQUEST).writeText(nowMs.toString())
        } catch (_: Throwable) {
        }
    }

    fun pending(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val f = file(context, REQUEST)
        if (!f.exists()) false else {
            val at = f.readText().trim().toLongOrNull() ?: 0L
            if (isFresh(at, nowMs)) true else { f.delete(); false }
        }
    } catch (_: Throwable) {
        false
    }

    /** Called by the service once Wireless debugging is confirmed on. */
    fun markEnabled(context: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            file(context, REQUEST).delete()
            file(context, ENABLED).writeText(nowMs.toString())
        } catch (_: Throwable) {
        }
    }

    /** True once, when the app returns after the service enabled it. */
    fun consumeEnabled(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean = try {
        val f = file(context, ENABLED)
        if (!f.exists()) false else {
            val at = f.readText().trim().toLongOrNull() ?: 0L
            f.delete()
            isFresh(at, nowMs)
        }
    } catch (_: Throwable) {
        false
    }

    fun cancel(context: Context) {
        try {
            file(context, REQUEST).delete()
        } catch (_: Throwable) {
        }
    }

    fun isFresh(requestedAtMs: Long, nowMs: Long): Boolean = nowMs - requestedAtMs in 0..MAX_AGE_MS
}
