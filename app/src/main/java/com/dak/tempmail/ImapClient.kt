package com.dak.tempmail

import java.util.Properties
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.InternetAddress

data class Mail(
    val id: Int,
    val from: String,
    val to: String,
    val subject: String,
    val date: Long,
    val body: String,
    val isHtml: Boolean
)

/** Gmail ke INBOX ko IMAP se padhta hai aur sirf us address ki mails dikhata hai. */
object ImapClient {

    fun fetch(user: String, pass: String, address: String, scan: Int = 300, limit: Int = 40): List<Mail> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", "imap.gmail.com")
            put("mail.imaps.port", "993")
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "25000")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect("imap.gmail.com", user, pass)
        try {
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_ONLY)
            try {
                val total = folder.messageCount
                if (total == 0) return emptyList()
                val start = maxOf(1, total - scan + 1)
                val msgs = folder.getMessages(start, total)
                val fp = FetchProfile()
                fp.add(FetchProfile.Item.ENVELOPE)
                folder.fetch(msgs, fp)

                val target = address.trim().lowercase()
                val out = ArrayList<Mail>()
                for (m in msgs.reversed()) {
                    if (out.size >= limit) break
                    val rec = m.allRecipients ?: continue
                    val addrs = rec.map { ((it as? InternetAddress)?.address ?: it.toString()).lowercase() }
                    if (!addrs.contains(target)) continue

                    val plain = StringBuilder()
                    val html = StringBuilder()
                    try {
                        collect(m, plain, html)
                    } catch (e: Exception) {
                        plain.append("(Mail ka text nahi khul saka)")
                    }
                    val useHtml = html.isNotEmpty()
                    val body = if (useHtml) html.toString() else plain.toString()
                    val from = m.from?.firstOrNull()?.let {
                        val ia = it as? InternetAddress
                        if (ia != null && !ia.personal.isNullOrBlank()) "${ia.personal} <${ia.address}>" else it.toString()
                    } ?: "?"
                    out.add(
                        Mail(
                            id = m.messageNumber,
                            from = from,
                            to = target,
                            subject = m.subject ?: "(no subject)",
                            date = m.sentDate?.time ?: 0L,
                            body = body,
                            isHtml = useHtml
                        )
                    )
                }
                return out
            } finally {
                try { folder.close(false) } catch (e: Exception) { }
            }
        } finally {
            try { store.close() } catch (e: Exception) { }
        }
    }

    private fun collect(p: Part, plain: StringBuilder, html: StringBuilder) {
        when {
            p.isMimeType("multipart/*") -> {
                val mp = p.content as Multipart
                for (i in 0 until mp.count) collect(mp.getBodyPart(i), plain, html)
            }
            p.isMimeType("text/plain") -> {
                if (!Part.ATTACHMENT.equals(p.disposition, ignoreCase = true)) {
                    plain.append(p.content as? String ?: "")
                }
            }
            p.isMimeType("text/html") -> {
                if (!Part.ATTACHMENT.equals(p.disposition, ignoreCase = true)) {
                    html.append(p.content as? String ?: "")
                }
            }
        }
    }
}
