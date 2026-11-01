package com.umar.bulkmailer

import java.io.File
import java.util.Date
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/** Sends through Gmail SMTP (smtp.gmail.com:587, STARTTLS) using a Google App Password. Reuses one connection. */
class MailSender(private val account: Account) {

    private val host = "smtp.gmail.com"
    private val port = 587

    private val session: Session = Session.getInstance(Properties().apply {
        put("mail.smtp.host", host)
        put("mail.smtp.port", port.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", "true")
        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.writetimeout", "30000")
    })

    private var transport: Transport? = null

    private fun connected(): Transport {
        transport?.let { if (it.isConnected) return it }
        val t = session.getTransport("smtp")
        t.connect(host, port, account.email.trim(), account.appPassword.replace(" ", ""))
        transport = t
        return t
    }

    fun send(to: String, subject: String, body: String, attachments: List<File>) {
        val msg = MimeMessage(session)
        val personal = account.senderName.trim().ifEmpty { null }
        msg.setFrom(InternetAddress(account.email.trim(), personal, "UTF-8"))
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
        msg.setSubject(subject, "UTF-8")
        msg.sentDate = Date()

        if (attachments.isEmpty()) {
            msg.setText(body, "UTF-8")
        } else {
            val mp = MimeMultipart()
            mp.addBodyPart(MimeBodyPart().apply { setText(body, "UTF-8") })
            for (f in attachments) {
                mp.addBodyPart(MimeBodyPart().apply {
                    attachFile(f)
                    fileName = f.name
                })
            }
            msg.setContent(mp)
        }
        msg.saveChanges()
        connected().sendMessage(msg, msg.allRecipients)
    }

    fun close() {
        try { transport?.close() } catch (_: Exception) { }
        transport = null
    }

    companion object {
        fun isFatal(e: Throwable): Boolean {
            val m = e.message.orEmpty()
            return e is AuthenticationFailedException || m.contains("5.7.8") || m.contains("5.4.5") ||
                m.contains("limit exceeded", ignoreCase = true)
        }

        fun friendly(e: Throwable): String {
            val m = e.message.orEmpty()
            return when {
                e is AuthenticationFailedException || m.contains("5.7.8") || m.contains("535") ->
                    "Gmail rejected the login. Check your Gmail address and use a 16-character App Password (not your normal password)."
                m.contains("5.4.5") || m.contains("limit exceeded", ignoreCase = true) ->
                    "Gmail's daily sending limit has been reached. Try again in 24 hours."
                m.contains("UnknownHost", ignoreCase = true) || e is java.net.UnknownHostException ->
                    "No internet connection."
                m.contains("Invalid Addresses", ignoreCase = true) -> "Invalid email address."
                else -> m.lineSequence().firstOrNull()?.take(200) ?: e.javaClass.simpleName
            }
        }
    }
}
