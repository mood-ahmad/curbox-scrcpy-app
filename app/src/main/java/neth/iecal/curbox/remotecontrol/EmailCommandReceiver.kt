package neth.iecal.curbox.remotecontrol

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import neth.iecal.curbox.BuildConfig
import java.util.Properties
import javax.mail.Address
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.InternetAddress
import javax.mail.search.FlagTerm

object EmailCommandReceiver {

    private const val IMAP_HOST = "imap.gmail.com"
    private const val IMAP_PORT = "993"
    private const val COMMAND_SUBJECT = "CURBOX SEND"

    private const val POLL_INTERVAL_MS = 30_000L
    private const val RETRY_DELAY_MS = 30_000L
    private const val MAX_MESSAGES_PER_POLL = 25

    private const val PREFS_NAME =
        "email_command_receiver_prefs"

    private const val KEY_PROCESSED_MESSAGE_IDS =
        "processed_message_ids"

    private const val MAX_PROCESSED_MESSAGE_IDS = 50

    private val lock = Any()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    private var receiverJob: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext

        synchronized(lock) {
            if (receiverJob?.isActive == true) {
                EventLogger.logStage(
                    stage = "EMAIL_COMMAND_RECEIVER",
                    status = "SKIPPED",
                    details = "reason=already_running"
                )
                return
            }

            if (!isConfigured()) {
                EventLogger.logStage(
                    stage = "EMAIL_COMMAND_RECEIVER",
                    status = "SKIPPED",
                    details = "reason=not_configured"
                )
                return
            }

            receiverJob = scope.launch {
                EventLogger.logStage(
                    stage = "EMAIL_COMMAND_RECEIVER",
                    status = "STARTED"
                )

                logSafeConfiguration()

                while (isActive) {
                    try {
                        pollInbox(appContext)

                        EventLogger.logStage(
                            stage = "EMAIL_COMMAND_POLL",
                            status = "SLEEPING",
                            details = "delayMs=$POLL_INTERVAL_MS"
                        )

                        delay(POLL_INTERVAL_MS)
                    } catch (t: Throwable) {
                        EventLogger.logStage(
                            stage = "EMAIL_COMMAND_RECEIVER",
                            status = "ERROR",
                            details =
                                "type=${t.javaClass.simpleName}; " +
                                "reason=${safeLogSummary(t.message ?: "unknown")}"
                        )

                        EventLogger.logStage(
                            stage = "EMAIL_COMMAND_POLL",
                            status = "RETRY_SCHEDULED",
                            details = "delayMs=$RETRY_DELAY_MS"
                        )

                        delay(RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            receiverJob?.cancel()
            receiverJob = null
        }

        EventLogger.logStage(
            stage = "EMAIL_COMMAND_RECEIVER",
            status = "STOPPED"
        )
    }

    private fun isConfigured(): Boolean {
        return BuildConfig.COMMAND_EMAIL.isNotBlank() &&
            BuildConfig.COMMAND_PASSWORD.isNotBlank() &&
            BuildConfig.AUTHORIZED_COMMAND_SENDER.isNotBlank()
    }

    private fun logSafeConfiguration() {
        val commandEmail =
            BuildConfig.COMMAND_EMAIL.trim()

        val commandPassword =
            BuildConfig.COMMAND_PASSWORD.trim()

        val authorizedSender =
            BuildConfig.AUTHORIZED_COMMAND_SENDER.trim()

        EventLogger.logStage(
            stage = "EMAIL_COMMAND_CONFIG",
            status = "CHECKED",
            details =
                "commandEmail=${safeLogSummary(commandEmail)}; " +
                "commandEmailPresent=${commandEmail.isNotEmpty()}; " +
                "commandPasswordPresent=${commandPassword.isNotEmpty()}; " +
                "commandPasswordLength=${commandPassword.length}; " +
                "authorizedSender=${safeLogSummary(authorizedSender)}; " +
                "authorizedSenderPresent=${authorizedSender.isNotEmpty()}"
        )
    }

    private fun pollInbox(
        context: Context
    ) {
        var store: Store? = null
        var inbox: Folder? = null

        val startedAt =
            SystemClock.elapsedRealtime()

        EventLogger.logStage(
            stage = "EMAIL_COMMAND_POLL",
            status = "STARTED"
        )

        try {
            val properties = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", IMAP_HOST)
                put("mail.imaps.port", IMAP_PORT)
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.auth", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "15000")
                put("mail.imaps.writetimeout", "15000")
            }

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_IMAP",
                status = "SESSION_CREATING",
                details =
                    "host=$IMAP_HOST; " +
                    "port=$IMAP_PORT; " +
                    "mode=SSL"
            )

            val session =
                Session.getInstance(properties)

            /*
             * Do not enable JavaMail protocol debugging here.
             * Controlled EventLogger diagnostics are sufficient and avoid
             * exposing authentication details.
             */
            session.debug = false

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_IMAP",
                status = "CONNECT_START",
                details =
                    "host=$IMAP_HOST; " +
                    "port=$IMAP_PORT"
            )

            store =
                session.getStore("imaps")

            store.connect(
                IMAP_HOST,
                BuildConfig.COMMAND_EMAIL.trim(),
                BuildConfig.COMMAND_PASSWORD.trim()
            )

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_IMAP",
                status = "CONNECTED"
            )

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_INBOX",
                status = "OPEN_START"
            )

            inbox =
                store.getFolder("INBOX")

            inbox.open(
                Folder.READ_WRITE
            )

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_INBOX",
                status = "OPENED",
                details =
                    "messageCount=${inbox.messageCount}; " +
                    "unreadCount=${safeUnreadCount(inbox)}"
            )

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_SEARCH",
                status = "STARTED",
                details = "filter=UNSEEN"
            )

            val unseenMessages =
                inbox.search(
                    FlagTerm(
                        Flags(Flags.Flag.SEEN),
                        false
                    )
                )

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_SEARCH",
                status = "OK",
                details =
                    "unseenCount=${unseenMessages.size}"
            )

            if (unseenMessages.isEmpty()) {
                EventLogger.logStage(
                    stage = "EMAIL_COMMAND_POLL",
                    status = "OK",
                    details =
                        "result=no_unseen_messages; " +
                        "durationMs=${SystemClock.elapsedRealtime() - startedAt}"
                )
                return
            }

            val messagesToInspect =
                unseenMessages
                    .takeLast(MAX_MESSAGES_PER_POLL)

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_SEARCH",
                status = "INSPECTING",
                details =
                    "count=${messagesToInspect.size}; " +
                    "totalUnseen=${unseenMessages.size}"
            )

            for (message in messagesToInspect) {
                processMessage(
                    context = context,
                    message = message
                )
            }

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_POLL",
                status = "OK",
                details =
                    "result=messages_inspected; " +
                    "count=${messagesToInspect.size}; " +
                    "durationMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
        } catch (t: Throwable) {
            EventLogger.logStage(
                stage = "EMAIL_COMMAND_POLL",
                status = "FAILED",
                details =
                    "type=${t.javaClass.simpleName}; " +
                    "durationMs=${SystemClock.elapsedRealtime() - startedAt}; " +
                    "reason=${safeLogSummary(t.message ?: "unknown")}"
            )

            throw t
        } finally {
            try {
                if (inbox?.isOpen == true) {
                    inbox.close(false)

                    EventLogger.logStage(
                        stage = "EMAIL_COMMAND_INBOX",
                        status = "CLOSED"
                    )
                }
            } catch (t: Throwable) {
                EventLogger.logStage(
                    stage = "EMAIL_COMMAND_INBOX",
                    status = "CLOSE_WARNING",
                    details =
                        "type=${t.javaClass.simpleName}; " +
                        "reason=${safeLogSummary(t.message ?: "unknown")}"
                )
            }

            try {
                if (store?.isConnected == true) {
                    store.close()

                    EventLogger.logStage(
                        stage = "EMAIL_COMMAND_IMAP",
                        status = "DISCONNECTED"
                    )
                }
            } catch (t: Throwable) {
                EventLogger.logStage(
                    stage = "EMAIL_COMMAND_IMAP",
                    status = "CLOSE_WARNING",
                    details =
                        "type=${t.javaClass.simpleName}; " +
                        "reason=${safeLogSummary(t.message ?: "unknown")}"
                )
            }
        }
    }

    private fun processMessage(
        context: Context,
        message: Message
    ) {
        val subject =
            message.subject
                ?.trim()
                .orEmpty()

        EventLogger.logStage(
            stage = "EMAIL_COMMAND_MESSAGE",
            status = "INSPECTED",
            details =
                "subjectMatches=${subject == COMMAND_SUBJECT}"
        )

        if (subject != COMMAND_SUBJECT) {
            return
        }

        EventLogger.logStage(
            stage = "EMAIL_COMMAND",
            status = "RECEIVED",
            details = "command=SEND"
        )

        val sender =
            extractSenderEmail(
                message.from
            )

        val authorizedSender =
            BuildConfig.AUTHORIZED_COMMAND_SENDER
                .trim()
                .lowercase()

        if (
            sender == null ||
            sender != authorizedSender
        ) {
            EventLogger.logStage(
                stage = "EMAIL_COMMAND",
                status = "REJECTED",
                details =
                    "reason=unauthorized_sender; " +
                    "senderPresent=${sender != null}"
            )

            markSeen(message)
            return
        }

        val messageId =
            extractMessageId(message)

        if (
            messageId != null &&
            isMessageProcessed(
                context,
                messageId
            )
        ) {
            EventLogger.logStage(
                stage = "EMAIL_COMMAND",
                status = "SKIPPED",
                details = "reason=duplicate_message"
            )

            markSeen(message)
            return
        }

        EventLogger.logStage(
            stage = "EMAIL_COMMAND",
            status = "ACCEPTED",
            details = "command=SEND"
        )

        /*
         * SEND remains read-only with respect to engine lifecycle.
         * This only asks RemoteControlManager to send the currently
         * verified URLs.
         */
        RemoteControlManager
            .sendCurrentLinksOnRequest(
                context
            )

        if (messageId != null) {
            rememberProcessedMessage(
                context = context,
                messageId = messageId
            )
        }

        markSeen(message)

        EventLogger.logStage(
            stage = "EMAIL_COMMAND",
            status = "DISPATCHED",
            details = "command=SEND"
        )
    }

    private fun extractMessageId(
        message: Message
    ): String? {
        return try {
            message.getHeader(
                "Message-ID"
            )
                ?.firstOrNull()
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }
        } catch (_: Throwable) {
            null
        }
    }

    private fun isMessageProcessed(
        context: Context,
        messageId: String
    ): Boolean {
        val prefs =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        return prefs
            .getStringSet(
                KEY_PROCESSED_MESSAGE_IDS,
                emptySet()
            )
            ?.contains(messageId) == true
    }

    private fun rememberProcessedMessage(
        context: Context,
        messageId: String
    ) {
        val prefs =
            context.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val existing =
            prefs
                .getStringSet(
                    KEY_PROCESSED_MESSAGE_IDS,
                    emptySet()
                )
                ?.toMutableList()
                ?: mutableListOf()

        existing.remove(messageId)
        existing.add(messageId)

        val trimmed =
            if (
                existing.size >
                MAX_PROCESSED_MESSAGE_IDS
            ) {
                existing.takeLast(
                    MAX_PROCESSED_MESSAGE_IDS
                )
            } else {
                existing
            }

        prefs.edit()
            .putStringSet(
                KEY_PROCESSED_MESSAGE_IDS,
                trimmed.toSet()
            )
            .apply()
    }

    private fun extractSenderEmail(
        addresses: Array<Address>?
    ): String? {
        val first =
            addresses
                ?.firstOrNull()
                ?: return null

        val raw =
            when (first) {
                is InternetAddress ->
                    first.address

                else ->
                    first.toString()
            }

        return raw
            ?.trim()
            ?.lowercase()
            ?.takeIf {
                it.isNotEmpty()
            }
    }

    private fun markSeen(
        message: Message
    ) {
        try {
            message.setFlag(
                Flags.Flag.SEEN,
                true
            )

            EventLogger.logStage(
                stage = "EMAIL_COMMAND_MESSAGE",
                status = "MARKED_SEEN"
            )
        } catch (t: Throwable) {
            EventLogger.logStage(
                stage = "EMAIL_COMMAND",
                status = "WARNING",
                details =
                    "reason=failed_to_mark_seen; " +
                    "type=${t.javaClass.simpleName}"
            )
        }
    }

    private fun safeUnreadCount(
        inbox: Folder
    ): Int {
        return try {
            inbox.unreadMessageCount
        } catch (_: Throwable) {
            -1
        }
    }

    private fun safeLogSummary(
        message: String
    ): String {
        return message
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(300)
    }
}
