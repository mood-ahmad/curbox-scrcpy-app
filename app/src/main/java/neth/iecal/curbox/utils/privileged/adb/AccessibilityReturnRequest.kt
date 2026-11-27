package neth.iecal.curbox.utils.privileged.adb

/**
 * Remembers that the user was just sent to Accessibility settings to enable
 * the CurBox service for Direct ADB pairing, so the service can bring the app
 * back by itself the moment it connects. Only a recent request is honoured, so
 * an ordinary service start (boot, update) never pulls the app to the front.
 */
object AccessibilityReturnRequest {
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    @Volatile
    private var requestedAtMs: Long = 0L

    fun request(nowMs: Long = System.currentTimeMillis()) {
        requestedAtMs = nowMs
    }

    /** Returns true once if a recent request exists, then clears it. */
    fun consume(nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = requestedAtMs
        requestedAtMs = 0L
        return at != 0L && nowMs - at in 0..MAX_AGE_MS
    }
}
