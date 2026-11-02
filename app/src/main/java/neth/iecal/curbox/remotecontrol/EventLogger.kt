package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event logger for CurBox remote-control features.
 *
 * Keeps the existing SharedPreferences ring-buffer and also provides
 * a dedicated email.log file for SMTP diagnostics.
 */
object EventLogger {

    private const val TAG = "EventLogger"

    private const val PREFS_NAME = "remote_control_events"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 100

    private const val EMAIL_LOG_FILE = "email.log"
    private const val MAX_EMAIL_LOG_BYTES = 256 * 1024L

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val dateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        )

    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) {
                appContext = context.applicationContext
            }

            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(
                        PREFS_NAME,
                        Context.MODE_PRIVATE
                    )
            }
        }
    }

    /**
     * Appends a normal CurBox event to the existing ring-buffer.
     */
    fun log(
        type: String,
        message: String
    ) {
        synchronized(lock) {
            val p = prefs ?: return

            val timestamp =
                dateFormat.format(Date())

            val event =
                "[$timestamp] $type - $message"

            val existing =
                p.getString(KEY_EVENTS, "")
                    ?: ""

            val lines =
                existing
                    .split("\n")
                    .filter { it.isNotBlank() }
                    .toMutableList()

            lines.add(event)

            while (lines.size > MAX_EVENTS) {
                lines.removeAt(0)
            }

            p.edit()
                .putString(
                    KEY_EVENTS,
                    lines.joinToString("\n")
                )
                .apply()
        }
    }

    /**
     * Writes an entry to the dedicated email diagnostics log.
     *
     * Never pass SMTP passwords, authentication tokens, or other
     * secrets into this function.
     */
    fun logEmail(
        event: String,
        details: String? = null
    ) {
        synchronized(lock) {
            val context =
                appContext ?: return

            try {
                val logFile =
                    File(
                        context.filesDir,
                        EMAIL_LOG_FILE
                    )

                rotateEmailLogIfNeeded(logFile)

                val timestamp =
                    dateFormat.format(Date())

                val safeEvent =
                    sanitiseLogValue(event)

                val safeDetails =
                    details
                        ?.let(::sanitiseLogValue)
                        ?.takeIf { it.isNotBlank() }

                val line =
                    if (safeDetails != null) {
                        "[$timestamp] $safeEvent - $safeDetails\n"
                    } else {
                        "[$timestamp] $safeEvent\n"
                    }

                logFile.appendText(line)

                Log.d(
                    TAG,
                    "Email diagnostic event: $safeEvent"
                )
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Failed to write email diagnostics",
                    t
                )
            }
        }
    }

    /**
     * Returns the full dedicated email diagnostics log.
     */
    fun getEmailLog(): String {
        synchronized(lock) {
            val context =
                appContext ?: return ""

            return try {
                val file =
                    File(
                        context.filesDir,
                        EMAIL_LOG_FILE
                    )

                if (!file.exists()) {
                    ""
                } else {
                    file.readText()
                }
            } catch (t: Throwable) {
                "Unable to read email.log: " +
                    (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Clears the dedicated email diagnostics log only.
     */
    fun clearEmailLog() {
        synchronized(lock) {
            val context =
                appContext ?: return

            try {
                val file =
                    File(
                        context.filesDir,
                        EMAIL_LOG_FILE
                    )

                if (file.exists()) {
                    file.delete()
                }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Failed to clear email diagnostics",
                    t
                )
            }
        }
    }

    /**
     * Returns the most recent general events, newest first.
     */
    fun getRecent(
        limit: Int = 20
    ): List<String> {
        synchronized(lock) {
            val p =
                prefs ?: return emptyList()

            val existing =
                p.getString(KEY_EVENTS, "")
                    ?: ""

            return existing
                .split("\n")
                .filter { it.isNotBlank() }
                .takeLast(limit)
                .reversed()
        }
    }

    /**
     * Returns all stored general events.
     */
    fun getAll(): String {
        synchronized(lock) {
            val p =
                prefs ?: return ""

            return p.getString(
                KEY_EVENTS,
                ""
            ) ?: ""
        }
    }

    /**
     * Clears the general event ring-buffer.
     *
     * email.log is intentionally not deleted here.
     */
    fun clear() {
        synchronized(lock) {
            prefs
                ?.edit()
                ?.remove(KEY_EVENTS)
                ?.apply()
        }
    }

    /**
     * Keeps email.log from growing indefinitely.
     */
    private fun rotateEmailLogIfNeeded(
        file: File
    ) {
        if (
            file.exists() &&
            file.length() >= MAX_EMAIL_LOG_BYTES
        ) {
            file.writeText(
                "[${dateFormat.format(Date())}] " +
                    "LOG_ROTATED - previous email diagnostics removed\n"
            )
        }
    }

    /**
     * Avoids accidental multiline/noisy diagnostic entries.
     *
     * Secret values must never be passed to this logger in the first place.
     */
    private fun sanitiseLogValue(
        value: String
    ): String {
        return value
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .take(2000)
    }
}
