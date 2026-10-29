package com.dak.tempmail

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/** Gmail, App Password, current address aur saved addresses phone mein save karta hai. */
class Store(ctx: Context) {
    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx,
            "dak_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        ctx.getSharedPreferences("dak_plain", Context.MODE_PRIVATE)
    }

    var gmail: String
        get() = prefs.getString("gmail", "") ?: ""
        set(v) { prefs.edit().putString("gmail", v).apply() }

    var appPassword: String
        get() = prefs.getString("pass", "") ?: ""
        set(v) { prefs.edit().putString("pass", v).apply() }

    var current: String
        get() = prefs.getString("current", "") ?: ""
        set(v) { prefs.edit().putString("current", v).apply() }

    var saved: List<String>
        get() {
            val raw = prefs.getString("saved", "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(it) }
            prefs.edit().putString("saved", arr.toString()).apply()
        }

    /** Backup text (App Password is mein shamil nahi hota). */
    fun exportJson(): String {
        val o = JSONObject()
        o.put("app", "DakTempMail")
        o.put("gmail", gmail)
        o.put("current", current)
        val arr = JSONArray()
        saved.forEach { arr.put(it) }
        o.put("saved", arr)
        return o.toString()
    }

    /** Backup text se restore karta hai. Kamyab ho to true. */
    fun importJson(text: String): Boolean {
        return try {
            val o = JSONObject(text.trim())
            if (o.optString("app") != "DakTempMail") return false
            val g = o.optString("gmail")
            if (g.isNotBlank()) gmail = g
            val cur = o.optString("current")
            if (cur.isNotBlank()) current = cur
            val arr = o.optJSONArray("saved")
            val incoming = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getString(it) }
            saved = (saved + incoming).distinct()
            true
        } catch (e: Exception) {
            false
        }
    }
}
