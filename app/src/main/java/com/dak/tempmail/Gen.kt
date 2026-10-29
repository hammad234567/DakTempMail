package com.dak.tempmail

import java.security.SecureRandom

/**
 * Gmail dots (.) ko ignore karta hai aur "+tag" ko bhi ignore karta hai.
 * Is liye aapke ek Gmail se bohat saare alag addresses ban sakte hain,
 * aur sab ki mail aapke apne inbox mein aati hai.
 */
object AddressGen {
    private const val CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    /** Gmail se base naam nikalta hai (dots aur +tag hata kar). */
    fun base(gmail: String): String? {
        val g = gmail.trim().lowercase()
        val at = g.indexOf('@')
        if (at < 1) return null
        val domain = g.substring(at + 1)
        if (domain != "gmail.com" && domain != "googlemail.com") return null
        val local = g.substring(0, at).substringBefore("+").replace(".", "")
        return if (local.isEmpty()) null else local
    }

    /** mode 0 = dot variant (a.b.c), mode 1 = plus variant (name+abc123). */
    fun make(gmail: String, mode: Int): String? {
        val b = base(gmail) ?: return null
        val rnd = SecureRandom()
        if (mode == 0 && b.length >= 2) {
            val gaps = b.length - 1
            val dots = BooleanArray(gaps) { rnd.nextBoolean() }
            if (dots.none { it }) dots[rnd.nextInt(gaps)] = true
            val sb = StringBuilder()
            for (i in b.indices) {
                sb.append(b[i])
                if (i < gaps && dots[i]) sb.append('.')
            }
            return sb.toString() + "@gmail.com"
        }
        val suffix = (1..6).map { CHARS[rnd.nextInt(CHARS.length)] }.joinToString("")
        return "$b+$suffix@gmail.com"
    }
}
