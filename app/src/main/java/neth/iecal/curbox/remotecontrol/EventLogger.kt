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
 * Keeps the general SharedPreferences ring-buffer and also provides
 * a dedicated email.log file for SMTP diagnostics.
 *
 * Startup stages can be recorded in a consistent structured format so
 * diagnostics can show exactly where the remote-control lifecycle
 * stopped or failed.
 */
object EventLogger {

    private const val TAG =
        "EventLogger"

    private const val PREFS_NAME =
        "remote_control_events"

    private const val KEY_EVENTS =
        "events"

    private const val MAX_EVENTS =
        200

    private const val EMAIL_LOG_FILE =
        "email.log"

    private const val MAX_EMAIL_LOG_BYTES =
        256 * 1024L

    private const val MAX_GENERAL_MESSAGE_LENGTH =
        4000

    private const val MAX_EMAIL_VALUE_LENGTH =
        2000

    private var prefs:
        SharedPreferences? = null

    private var appContext:
        Context? = null

    private val dateFormat =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Locale.US
        )

    private val lock =
        Any()

    fun init(
        context: Context
    ) {
        synchronized(lock) {
            val applicationContext =
                context.applicationContext

            if (appContext == null) {
                appContext =
                    applicationContext
            }

            if (prefs == null) {
                prefs =
                    applicationContext
                        .getSharedPreferences(
                            PREFS_NAME,
                            Context.MODE_PRIVATE
                        )
            }
        }
    }

    /**
     * Appends a normal CurBox event to the general ring-buffer.
     *
     * The message is sanitised to keep every event on a single line.
     * Credentials, authentication tokens and other secrets must never
     * be passed to this logger.
     */
    fun log(
        type: String,
        message: String
    ) {
        synchronized(lock) {
            val p =
                prefs ?: return

            val timestamp =
                dateFormat.format(
                    Date()
                )

            val safeType =
                sanitiseGeneralValue(
                    type
                )
                    .ifBlank {
                        "EVENT"
                    }

            val safeMessage =
                sanitiseGeneralValue(
                    message
                )

            val event =
                "[$timestamp] $safeType - $safeMessage"

            val existing =
                p.getString(
                    KEY_EVENTS,
                    ""
                ) ?: ""

            val lines =
                existing
                    .lineSequence()
                    .filter {
                        it.isNotBlank()
                    }
                    .toMutableList()

            lines.add(
                event
            )

            while (
                lines.size >
                MAX_EVENTS
            ) {
                lines.removeAt(
                    0
                )
            }

            p.edit()
                .putString(
                    KEY_EVENTS,
                    lines.joinToString(
                        "\n"
                    )
                )
                .apply()

            Log.d(
                TAG,
                "$safeType - $safeMessage"
            )
        }
    }

    /**
     * Records one structured remote-control lifecycle stage.
     *
     * Example:
     *
     * START - REQUESTED reason=AUTO_START
     * STAGING - OK
     * CLEANUP - FAILED reason=...
     * WORKDIR - OK
     * LAUNCH - STARTED
     *
     * Callers own the meaning of the stage and status. This logger only
     * guarantees a consistent, searchable diagnostic representation.
     */
    fun logStage(
        stage: String,
        status: String,
        details: String? = null
    ) {
        val safeStage =
            sanitiseGeneralValue(
                stage
            )
                .ifBlank {
                    "UNKNOWN_STAGE"
                }

        val safeStatus =
            sanitiseGeneralValue(
                status
            )
                .ifBlank {
                    "UNKNOWN_STATUS"
                }

        val safeDetails =
            details
                ?.let(
                    ::sanitiseGeneralValue
                )
                ?.takeIf {
                    it.isNotBlank()
                }

        val message =
            if (safeDetails == null) {
                safeStatus
            } else {
                "$safeStatus $safeDetails"
            }

        log(
            type = safeStage,
            message = message
        )
    }

    /**
     * Writes an entry to the dedicated email diagnostics log.
 tokens, or other
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

                rotateEmailLogIfNeeded(
                    logFile
                )

                val timestamp =
                    dateFormat.format(
                        Date()
                    )

                val safeEvent =
                    sanitiseEmailLogValue(
                        event
                    )
                        .ifBlank {
                            "EMAIL_EVENT"
                        }

                val safeDetails =
                    details
                        ?.let(
                            ::sanitiseEmailLogValue
                        )
                        ?.takeIf {
                            it.isNotBlank()
                        }

                val line =
                    if (safeDetails != null) {
                        "[$timestamp] $safeEvent - $safeDetails\n"
                    } else {
                        "[$timestamp] $safeEvent\n"
                    }

                logFile.appendText(
                    line
                )

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

            Unit
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
                    (
                        t.message
                            ?: t.javaClass.simpleName
                    )
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

                Unit
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "Failed to clear email diagnostics",
                    t
                )

                Unit
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

            val safeLimit =
                limit.coerceAtLeast(
                    0
                )

            if (safeLimit == 0) {
                return emptyList()
            }

            val existing =
                p.getString(
                    KEY_EVENTS,
                    ""
                ) ?: ""

            return existing
                .lineSequence()
                .filter {
                    it.isNotBlank()
                }
                .toList()
                .takeLast(
                    safeLimit
                )
                .reversed()
        }
    }

    /**
     * Returns all stored general events in chronological order.
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
                ?.remove(
                    KEY_EVENTS
                )
                ?.apply()

            Unit
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
            file.length() >=
                MAX_EMAIL_LOG_BYTES
        ) {
            file.writeText(
                "[${dateFormat.format(Date())}] " +
                    "LOG_ROTATED - previous email diagnostics removed\n"
            )
        }
    }

    /**
     * Keeps general diagnostic entries single-line and bounded.
     *
     * Secret values must never be passed to this logger in the first
     * place.
     */
    private fun sanitiseGeneralValue(
        value: String
    ): String {
        return value
            .replace(
                "\r",
                " "
            )
            .replace(
                "\n",
                " "
            )
            .trim()
            .take(
                MAX_GENERAL_MESSAGE_LENGTH
            )
    }

    /**
     * Keeps email diagnostic entries single-line and bounded.
     *
     * Secret values must never be passed to this logger in the first
     * place.
     */
    private fun sanitiseEmailLogValue(
        value: String
    ): String {
        return value
            .replace(
                "\r",
                " "
            )
            .replace(
                "\n",
                " "
            )
            .trim()
            .take(
                MAX_EMAIL_VALUE_LENGTH
            )
    }
}
