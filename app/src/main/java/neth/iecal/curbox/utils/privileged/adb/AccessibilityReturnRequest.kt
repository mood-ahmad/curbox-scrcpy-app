package neth.iecal.curbox.utils.privileged.adb

import android.content.Context
import java.io.File

/**
 * Remembers that the user was just sent to Accessibility settings to enable
 * the CurBox service for Direct ADB pairing, so the service can bring the app
 * back by itself the moment it connects. The accessibility service runs in its
 * own process, so the request is a small file in the app's files directory.
 * Only a recent request is honoured, so an ordinary service start (boot,
 * update) never pulls the app to the front.
 */
object AccessibilityReturnRequest {
    private const val FILE_NAME = "a11y_return_request"
    const val MAX_AGE_MS = 10 * 60 * 1000L

    fun request(context: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME)
                .writeText(nowMs.toString())
        } catch (_: Throwable) {
        }
    }

    /** Returns true once if a recent request exists, then clears it. */
    fun consume(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        return try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (!file.exists()) return false
            val at = file.readText().trim().toLongOrNull() ?: 0L
            file.delete()
            isFresh(at, nowMs)
        } catch (_: Throwable) {
            false
        }
    }

    fun isFresh(requestedAtMs: Long, nowMs: Long): Boolean =
        requestedAtMs != 0L && nowMs - requestedAtMs in 0..MAX_AGE_MS
}
