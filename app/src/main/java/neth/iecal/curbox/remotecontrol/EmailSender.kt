package neth.iecal.curbox.remotecontrol

import neth.iecal.curbox.BuildConfig
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends CurBox emails (link, alerts) through Gmail SMTP.
 * Credentials come from BuildConfig (injected at build time from GitHub Secrets).
 */
object EmailSender {

    private const val SMTP_HOST = "smtp.gmail.com"
    private const val SMTP_PORT = "587"

    /**
     * Sends the connection link to the configured target email.
     */
    suspend fun sendLink(link: String, networkType: String = "Unknown"): Boolean = withContext(Dispatchers.IO) {
    val device = DeviceIdentity.getName()
    val subject = "[CurBox] $device - Link"
    val body = """
        CurBox remote control is running.

        Device: $device
        Network: $networkType
        Link: $link

        Open the link in any browser to control this device.
        Works from any network.
    """.trimIndent()
    send(subject, body)
    }

    /**
     * Sends an alert email when the guardian fails to repair the service.
     */
    suspend fun sendAlert(deviceName: String, reason: String): Boolean = withContext(Dispatchers.IO) {
        val subject = "[CurBox] $deviceName - ⚠️ Service Down"
        val body = """
            CurBox remote control could not be restored.

            Device: $deviceName
            Reason: $reason

            Open CurBox and tap Retry in Emergency settings.
        """.trimIndent()
        send(subject, body)
    }

    // -------------------------------------------------------------------------

    private suspend fun send(subject: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val fromEmail = BuildConfig.SMTP_EMAIL
        val password = BuildConfig.SMTP_PASSWORD
        val toEmail = BuildConfig.TARGET_EMAIL

        if (fromEmail.isBlank() || password.isBlank() || toEmail.isBlank()) {
            android.util.Log.w("EmailSender", "SMTP credentials missing, skipping email")
            return@withContext false
        }

        try {
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", SMTP_HOST)
                put("mail.smtp.port", SMTP_PORT)
                put("mail.smtp.ssl.trust", SMTP_HOST)
            }

            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(fromEmail, password)
                }
            })

            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(fromEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail))
                this.subject = subject
                setText(body)
            }

            Transport.send(message)
            android.util.Log.i("EmailSender", "Email sent: $subject")
            EventLogger.log("EMAIL", "Sent: $subject")
            true
        } catch (t: Throwable) {
            android.util.Log.e("EmailSender", "Failed to send email", t)
            EventLogger.log("EMAIL", "Failed: ${t.message}")
            false
        }
    }
}
