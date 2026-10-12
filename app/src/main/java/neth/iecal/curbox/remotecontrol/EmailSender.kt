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
 * Sends the Scrcpy connection link automatically by email.
 *
 * Credentials (SMTP email, password, and target address) are injected at build
 * time from GitHub Secrets and stored in BuildConfig.
 */
object EmailSender {

    private const val SMTP_HOST = "smtp.gmail.com"
    private const val SMTP_PORT = "587"

    /**
     * Sends the connection link to the configured target email.
     * Runs on IO dispatcher. Failures are swallowed (logged only) so the link
     * remains available in the UI even if email sending fails.
     */
    suspend fun sendLink(link: String): Boolean = withContext(Dispatchers.IO) {
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
                subject = "CurBox Scrcpy Link"
                setText(
                    """
                    Scrcpy remote control is now running.

                    Open this link in a browser on any device to control your phone:

                    $link

                    Notes:
                    - The link works from any network (Wi-Fi or mobile data).
                    - If the link stops working, open CurBox and tap Refresh.
                    - To stop the service, open CurBox and tap Stop.
                    """.trimIndent()
                )
            }

            Transport.send(message)
            android.util.Log.i("EmailSender", "Link sent to $toEmail")
            true
        } catch (t: Throwable) {
            android.util.Log.e("EmailSender", "Failed to send link email", t)
            false
        }
    }
}
