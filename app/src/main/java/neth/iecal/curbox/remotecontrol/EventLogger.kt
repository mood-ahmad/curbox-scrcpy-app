package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple ring-buffer event logger for the remote control feature.
 * Keeps the last MAX_EVENTS events in SharedPreferences.
 * Thread-safe.
 */
object EventLogger {

    private const val PREFS_NAME = "remote_control_events"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 100

    private var prefs: SharedPreferences? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    /**
     * Appends a new event with the current timestamp.
     */
    fun log(type: String, message: String) {
        synchronized(lock) {
            val p = prefs ?: return
            val timestamp = dateFormat.format(Date())
            val event = "[$timestamp] $type - $message"

            val existing = p.getString(KEY_EVENTS, "") ?: ""
            val lines = existing.split("\n")
                .filter { it.isNotBlank() }
                .toMutableList()
            lines.add(event)

            while (lines.size > MAX_EVENTS) {
                lines.removeAt(0)
            }

            p.edit().putString(KEY_EVENTS, lines.joinToString("\n")).apply()
        }
    }

    /**
     * Returns the most recent events (newest first).
     */
    fun getRecent(limit: Int = 20): List<String> {
        synchronized(lock) {
            val p = prefs ?: return emptyList()
            val existing = p.getString(KEY_EVENTS, "") ?: ""
            return existing.split("\n")
                .filter { it.isNotBlank() }
                .takeLast(limit)
                .reversed()
        }
    }

    /**
     * Returns all stored events as a single string.
     */
    fun getAll(): String {
        synchronized(lock) {
            val p = prefs ?: return ""
            return p.getString(KEY_EVENTS, "") ?: ""
        }
    }

    /**
     * Clears all stored events.
     */
    fun clear() {
        synchronized(lock) {
            prefs?.edit()?.remove(KEY_EVENTS)?.apply()
        }
    }
}
