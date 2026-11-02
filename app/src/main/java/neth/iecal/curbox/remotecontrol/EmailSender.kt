package neth.iecal.curbox.remotecontrol

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import neth.iecal.curbox.BuildConfig
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Sends CurBox emails (connection links and alerts) through Gmail SMTP.
 *
 * Credentials come from BuildConfig and are injected at build time
 * from GitHub Secrets.
 *
 * SMTP passwords and other secrets are never written to diagnostics.
 */
object EmailSender {

    private const val TAG = "EmailSender"

    private const val SMTP_HOST = "smtp.gmail.com"
    private const val SMTP_PORT = "587"

    /*
     * These are per-operation JavaMail timeouts.
     *
     * connectiontimeout = TCP connection timeout
     * timeout           = socket read timeout
     * writetimeout      = socket write timeout
     */
    private const val SMTP_CONNECTION_TIMEOUT_MS = 15_000
    private const val SMTP_READ_TIMEOUT_MS = 20_000
    private const val SMTP_WRITE_TIMEOUT_MS = 20_000

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
            details = "network=$networkType"
        )

        send(
            subject = subject,
            body = body,
            diagnosticType = "LINK"
        )
    }

    /**
     * Sends an alert when the Guardian cannot restore
     * the remote-control service.
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

            Open CurBox and tap Retry in Emergency settings.
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
            details = "type=$diagnosticType"
        )

        /*
         * Do not log the credential values.
         *
         * We only record whether required configuration exists.
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
                }.joinToString(",")

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

        try {
            EventLogger.logEmail(
                event = "SMTP_PREPARING",
                details = "host=$SMTP_HOST port=$SMTP_PORT"
            )

            val props =
                Properties().apply {

                    put(
                        "mail.smtp.auth",
                        "true"
                    )

                    put(
                        "mail.smtp.starttls.enable",
                        "true"
                    )

                    put(
                        "mail.smtp.starttls.required",
                        "true"
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
                Session.getInstance(
                    props,
                    object : Authenticator() {

                        override fun getPasswordAuthentication():
                            PasswordAuthentication {

                            return PasswordAuthentication(
                                fromEmail,
                                password
                            )
                        }
                    }
                )

            /*
             * JavaMail protocol debug output is intentionally disabled.
             *
             * We only write our controlled diagnostic entries so credentials
             * and SMTP protocol details cannot accidentally end up in email.log.
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

                    setText(body)
                }

            EventLogger.logEmail(
                event = "SMTP_SEND_START"
            )

            Transport.send(message)

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
                    "type=$diagnosticType durationMs=$durationMs"
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
                t.message
                    ?.replace("\r", " ")
                    ?.replace("\n", " ")
                    ?.take(500)
                    ?: "No error message"

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
                    "message=$errorMessage"
            )

            false
        }
    }
}

