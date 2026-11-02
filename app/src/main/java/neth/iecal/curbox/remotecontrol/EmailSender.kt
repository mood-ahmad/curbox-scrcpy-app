package neth.iecal.curbox.remotecontrol

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import java.util.Properties
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends CurBox connection links and alerts through Gmail SMTP.
 *
 * Credentials are injected into BuildConfig at build time.
 *
 * SMTP passwords and secrets are never written to diagnostics.
 */
object EmailSender {

    private const val TAG = "EmailSender"

    private const val SMTP_HOST =
        "smtp.gmail.com"

    /*
     * Gmail implicit SSL/TLS port.
     *
     * We intentionally use port 465 instead of STARTTLS on 587
     * because diagnostics showed TLS-upgrade failures on the device.
     */
    private const val SMTP_PORT =
        "465"

    private const val SMTP_CONNECTION_TIMEOUT_MS =
        15_000

    private const val SMTP_READ_TIMEOUT_MS =
        20_000

    private const val SMTP_WRITE_TIMEOUT_MS =
        20_000

    /**
     * Sends the current CurBox connection link.
     */
    suspend fun sendLink(
        link: String,
        networkType: String = "Unknown"
    ): Boolean = withContext(Dispatchers.IO) {

        val device =
            DeviceIdentity.getName()

        val subject =
            "[CurBox] $device - Link"

        val body = """
            CurBox remote control is running.

            Device: $device
            Network: $networkType
            Link: $link

            Open the link in any browser to control this device.
            Works from any network.
        """.trimIndent()

        EventLogger.logEmail(
            event = "LINK_SEND_REQUESTED",
            details =
                "network=$networkType"
        )

        send(
            subject = subject,
            body = body,
            diagnosticType = "LINK"
        )
    }

    /**
     * Sends an alert when Guardian cannot restore the engine.
     */
    suspend fun sendAlert(
        deviceName: String,
        reason: String
    ): Boolean = withContext(Dispatchers.IO) {

        val subject =
            "[CurBox] $deviceName - Service Down"

        val body = """
            CurBox remote control could not be restored.

            Device: $deviceName
            Reason: $reason

            Open CurBox and check Remote Control diagnostics.
        """.trimIndent()

        EventLogger.logEmail(
            event = "ALERT_SEND_REQUESTED"
        )

        send(
            subject = subject,
            body = body,
            diagnosticType = "ALERT"
        )
    }

    // -------------------------------------------------------------------------

    private suspend fun send(
        subject: String,
        body: String,
        diagnosticType: String
    ): Boolean = withContext(Dispatchers.IO) {

        val startedAt =
            SystemClock.elapsedRealtime()

        val fromEmail =
            BuildConfig.SMTP_EMAIL.trim()

        val password =
            BuildConfig.SMTP_PASSWORD

        val toEmail =
            BuildConfig.TARGET_EMAIL.trim()

        EventLogger.logEmail(
            event = "SEND_REQUESTED",
            details =
                "type=$diagnosticType"
        )

        /*
         * Never log the actual credential values.
         */
        if (
            fromEmail.isBlank() ||
            password.isBlank() ||
            toEmail.isBlank()
        ) {
            val missing =
                buildList {

                    if (fromEmail.isBlank()) {
                        add("SMTP_EMAIL")
                    }

                    if (password.isBlank()) {
                        add("SMTP_PASSWORD")
                    }

                    if (toEmail.isBlank()) {
                        add("TARGET_EMAIL")
                    }
                }
                    .joinToString(",")

            Log.w(
                TAG,
                "SMTP configuration is incomplete"
            )

            EventLogger.log(
                "EMAIL",
                "SMTP configuration missing"
            )

            EventLogger.logEmail(
                event = "SEND_SKIPPED",
                details = "missing=$missing"
            )

            return@withContext false
        }

        var transport: Transport? = null

        try {
            EventLogger.logEmail(
                event = "SMTP_PREPARING",
                details =
                    "host=$SMTP_HOST port=$SMTP_PORT mode=SSL"
            )

            val props =
                Properties().apply {

                    put(
                        "mail.transport.protocol",
                        "smtp"
                    )

                    put(
                        "mail.smtp.host",
                        SMTP_HOST
                    )

                    put(
                        "mail.smtp.port",
                        SMTP_PORT
                    )

                    put(
                        "mail.smtp.auth",
                        "true"
                    )

                    /*
                     * Implicit SSL/TLS.
                     *
                     * STARTTLS is deliberately disabled because
                     * the connection is encrypted from the start.
                     */
                    put(
                        "mail.smtp.ssl.enable",
                        "true"
                    )

                    put(
                        "mail.smtp.starttls.enable",
                        "false"
                    )

                    put(
                        "mail.smtp.starttls.required",
                        "false"
                    )

                    put(
                        "mail.smtp.ssl.trust",
                        SMTP_HOST
                    )

                    put(
                        "mail.smtp.connectiontimeout",
                        SMTP_CONNECTION_TIMEOUT_MS.toString()
                    )

                    put(
                        "mail.smtp.timeout",
                        SMTP_READ_TIMEOUT_MS.toString()
                    )

                    put(
                        "mail.smtp.writetimeout",
                        SMTP_WRITE_TIMEOUT_MS.toString()
                    )
                }

            EventLogger.logEmail(
                event = "SMTP_SESSION_CREATING"
            )

            val session =
                Session.getInstance(props)

            /*
             * Keep JavaMail protocol debugging disabled.
             *
             * Our controlled diagnostics contain everything
             * needed without risking credential exposure.
             */
            session.debug = false

            EventLogger.logEmail(
                event = "MESSAGE_CREATING"
            )

            val message =
                MimeMessage(session).apply {

                    setFrom(
                        InternetAddress(fromEmail)
                    )

                    setRecipients(
                        Message.RecipientType.TO,
                        InternetAddress.parse(
                            toEmail
                        )
                    )

                    this.subject =
                        subject

                    setText(
                        body,
                        Charsets.UTF_8.name()
                    )

                    saveChanges()
                }

            EventLogger.logEmail(
                event = "SMTP_CONNECT_START",
                details =
                    "host=$SMTP_HOST port=$SMTP_PORT"
            )

            transport =
                session.getTransport("smtp")

            transport.connect(
                SMTP_HOST,
                SMTP_PORT.toInt(),
                fromEmail,
                password
            )

            EventLogger.logEmail(
                event = "SMTP_CONNECTED"
            )

            EventLogger.logEmail(
                event = "SMTP_SEND_START"
            )

            transport.sendMessage(
                message,
                message.allRecipients
            )

            val durationMs =
                SystemClock.elapsedRealtime() -
                    startedAt

            Log.i(
                TAG,
                "Email sent successfully: $subject"
            )

            EventLogger.log(
                "EMAIL",
                "Sent: $subject"
            )

            EventLogger.logEmail(
                event = "SEND_OK",
                details =
                    "type=$diagnosticType " +
                    "durationMs=$durationMs"
            )

            true

        } catch (t: Throwable) {

            val durationMs =
                SystemClock.elapsedRealtime() -
                    startedAt

            val errorType =
                t.javaClass.simpleName
                    .ifBlank {
                        t.javaClass.name
                    }

            val errorMessage =
                sanitiseErrorMessage(
                    t.message
                        ?: "No error message"
                )

            /*
             * JavaMail often wraps the useful TLS/socket error
             * inside another exception.
             */
            val cause =
                findDeepestCause(t)

            val causeType =
                cause.javaClass.simpleName
                    .ifBlank {
                        cause.javaClass.name
                    }

            val causeMessage =
                sanitiseErrorMessage(
                    cause.message
                        ?: "No cause message"
                )

            Log.e(
                TAG,
                "Failed to send email",
                t
            )

            EventLogger.log(
                "EMAIL",
                "Failed: $errorType - $errorMessage"
            )

            EventLogger.logEmail(
                event = "SEND_FAILED",
                details =
                    "type=$diagnosticType " +
                    "errorType=$errorType " +
                    "durationMs=$durationMs " +
                    "message=$errorMessage " +
                    "causeType=$causeType " +
                    "cause=$causeMessage"
            )

            if (
                t is MessagingException &&
                t.nextException != null
            ) {
                val next =
                    t.nextException!!

                EventLogger.logEmail(
                    event = "SMTP_NEXT_EXCEPTION",
                    details =
                        "type=${next.javaClass.simpleName} " +
                        "message=${sanitiseErrorMessage(next.message ?: "No message")}"
                )
            }

            false

        } finally {

            try {
                if (
                    transport != null &&
                    transport.isConnected
                ) {
                    transport.close()

                    EventLogger.logEmail(
                        event = "SMTP_CONNECTION_CLOSED"
                    )
                }
            } catch (closeError: Throwable) {
                Log.w(
                    TAG,
                    "Failed to close SMTP transport",
                    closeError
                )

                EventLogger.logEmail(
                    event = "SMTP_CLOSE_WARNING",
                    details =
                        "type=${closeError.javaClass.simpleName}"
                )
            }
        }
    }

    private fun findDeepestCause(
        throwable: Throwable
    ): Throwable {

        var current =
            throwable

        val visited =
            HashSet<Throwable>()

        while (
            current.cause != null &&
            current.cause !== current &&
            visited.add(current)
        ) {
            current =
                current.cause!!
        }

        return current
    }

    private fun sanitiseErrorMessage(
        message: String
    ): String {

        return message
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
            .take(500)
    }
}
