package com.dak.tempmail

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------- RapidAPI (Gmailnator) ----------
private const val HOST = "gmailnator.p.rapidapi.com"
private const val GENERATE_PATH = "/api/emails/generate"

// Inbox ke URLs abhi pakke nahi hain, isliye app inhe baari baari try karti hai
// aur jo chal jaye use yaad rakhti hai.
private val LIST_PATHS = listOf(
    "/api/emails/inbox",
    "/api/inbox",
    "/api/emails/messages",
    "/api/messages",
    "/api/emails/list"
)
private val CONTENT_BASES = listOf(
    "/api/emails/message",
    "/api/message",
    "/api/emails/content"
)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val http = OkHttpClient()

private class ApiResult(val code: Int, val body: String)
private class Cand(val method: String, val path: String, val body: String?)
private class Msg(val id: String, val subject: String, val from: String, val raw: String)

private fun call(key: String, method: String, path: String, json: String?): ApiResult {
    val b = Request.Builder()
        .url("https://$HOST$path")
        .addHeader("x-rapidapi-host", HOST)
        .addHeader("x-rapidapi-key", key)
        .addHeader("accept", "application/json")
    if (method == "POST") {
        b.addHeader("content-type", "application/json")
        b.post((json ?: "{}").toRequestBody(JSON_TYPE))
    } else {
        b.get()
    }
    http.newCall(b.build()).execute().use { r ->
        return ApiResult(r.code, r.body?.string() ?: "")
    }
}

private fun firstWorking(key: String, cands: List<Cand>, prefs: SharedPreferences, cacheKey: String): ApiResult {
    val cached = prefs.getInt(cacheKey, -1)
    val order = if (cached in cands.indices) {
        listOf(cached) + cands.indices.filter { it != cached }
    } else {
        cands.indices.toList()
    }
    var last = ApiResult(0, "")
    for (i in order) {
        val c = cands[i]
        val r = call(key, c.method, c.path, c.body)
        last = r
        if (r.code != 404 && r.code != 405) {
            if (r.code in 200..299) prefs.edit().putInt(cacheKey, i).apply()
            return r
        }
    }
    return last
}

// ---------- helpers ----------
private fun short(s: String): String = s.replace(Regex("\\s+"), " ").take(140)

private fun loadList(p: SharedPreferences, name: String): List<String> {
    return try {
        val a = JSONArray(p.getString(name, "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveList(p: SharedPreferences, name: String, l: List<String>) {
    val a = JSONArray()
    l.forEach { a.put(it) }
    p.edit().putString(name, a.toString()).apply()
}

private fun pick(o: JSONObject, keys: List<String>): String {
    for (k in keys) {
        val v = o.optString(k, "")
        if (v.isNotBlank() && v != "null") return v
    }
    return ""
}

private fun findArray(text: String): JSONArray? {
    val s = text.trim()
    return try {
        if (s.startsWith("[")) {
            JSONArray(s)
        } else {
            val o = JSONObject(s)
            listOf("messages", "data", "emails", "inbox", "items", "results")
                .firstNotNullOfOrNull { k -> o.optJSONArray(k) }
                ?: o.keys().asSequence().firstNotNullOfOrNull { k -> o.optJSONArray(k) }
        }
    } catch (e: Exception) {
        null
    }
}

private fun toMsg(o: JSONObject): Msg {
    val id = pick(o, listOf("id", "messageID", "messageId", "mid", "uid"))
    val subject = pick(o, listOf("subject", "title")).ifBlank { "(no subject)" }
    val fromObj = o.optJSONObject("from")
    var from = if (fromObj != null) {
        pick(fromObj, listOf("name", "address", "email"))
    } else {
        pick(o, listOf("from", "sender", "fromEmail"))
    }
    if (from.isBlank()) from = "?"
    return Msg(id, subject, from, o.toString())
}

private fun plain(s: String): String {
    return try {
        Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    } catch (e: Exception) {
        s
    }
}

/** JSON ke andar se mail ka text nikalta hai (id wagera nahi). */
private fun textOf(raw: String): String {
    return try {
        val o = JSONObject(raw)
        val keys = listOf("subject", "body", "html", "text", "content", "message", "snippet", "intro", "preview")
        val parts = ArrayList<String>()
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank() && v != "null") parts.add(v)
        }
        plain(parts.joinToString("\n"))
    } catch (e: Exception) {
        plain(raw)
    }
}

private fun otpIn(s: String): String {
    val m = Regex("(?<!\\d)(\\d{4,8})(?!\\d)").find(s)
    return m?.groupValues?.get(1) ?: ""
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dak2", MODE_PRIVATE)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1677FF),
                    onPrimary = Color.White,
                    background = Color(0xFFF6F8FB),
                    surface = Color.White
                )
            ) {
                DakApp(prefs)
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp) }
    }
}

@Composable
private fun AddrRow(address: String, current: Boolean, onUse: () -> Unit, onDelete: (() -> Unit)?) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                address,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = if (current) Color(0xFF1677FF) else Color(0xFF101820)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("Use karo") }
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
fun DakApp(prefs: SharedPreferences) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var keyInput by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(prefs.getString("addr", "") ?: "") }
    var history by remember { mutableStateOf(loadList(prefs, "history")) }
    var saved by remember { mutableStateOf(loadList(prefs, "saved")) }
    var tab by remember { mutableStateOf(0) }
    var msgs by remember { mutableStateOf(listOf<Msg>()) }
    var opened by remember { mutableStateOf<Msg?>(null) }
    var openedText by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var restoreInput by remember { mutableStateOf("") }

    val useAddress: (String) -> Unit = { a ->
        address = a
        prefs.edit().putString("addr", a).apply()
        history = (listOf(a) + history).distinct().take(30)
        saveList(prefs, "history", history)
        msgs = emptyList()
        opened = null
        otp = ""
        tab = 0
    }

    val generate: () -> Unit = {
        if (!busy && key.isNotBlank()) {
            scope.launch {
                busy = true
                status = "Naya address ban raha hai..."
                try {
                    val r = withContext(Dispatchers.IO) { call(key, "POST", GENERATE_PATH, "{}") }
                    if (r.code == 200) {
                        val email = JSONObject(r.body).optString("email", "")
                        if (email.isNotBlank()) {
                            useAddress(email)
                            status = "Naya address ban gaya"
                        } else {
                            status = "Jawab mein email nahi mila: " + short(r.body)
                        }
                    } else {
                        status = "Generate error " + r.code + ": " + short(r.body)
                    }
                } catch (e: Exception) {
                    status = "Error: " + (e.message ?: e.javaClass.simpleName)
                }
                busy = false
            }
        }
    }

    val refresh: () -> Unit = {
        if (!busy && key.isNotBlank() && address.isNotBlank()) {
            scope.launch {
                busy = true
                try {
                    val body = JSONObject().put("email", address).toString()
                    val cands = LIST_PATHS.map { Cand("POST", it, body) }
                    val r = withContext(Dispatchers.IO) { firstWorking(key, cands, prefs, "listIdx") }
                    if (r.code in 200..299) {
                        val arr = findArray(r.body)
                        val list = ArrayList<Msg>()
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val o = arr.optJSONObject(i) ?: continue
                                list.add(toMsg(o))
                            }
                        }
                        msgs = list
                        for (m in list) {
                            val code = otpIn(textOf(m.raw))
                            if (code.isNotBlank()) {
                                otp = code
                                break
                            }
                        }
                        status = "Last check: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    } else {
                        status = "Inbox error " + r.code + ": " + short(r.body)
                    }
                } catch (e: Exception) {
                    status = "Error: " + (e.message ?: e.javaClass.simpleName)
                }
                busy = false
            }
        }
    }

    val openMsg: (Msg) -> Unit = { m ->
        opened = m
        openedText = textOf(m.raw)
        scope.launch {
            try {
                val enc = URLEncoder.encode(m.id, "UTF-8")
                val cands = ArrayList<Cand>()
                for (b in CONTENT_BASES) {
                    cands.add(Cand("GET", "$b?messageId=$enc", null))
                    cands.add(Cand("GET", "$b?id=$enc", null))
                    cands.add(Cand("GET", "$b/$enc", null))
                }
                val r = withContext(Dispatchers.IO) { firstWorking(key, cands, prefs, "contIdx") }
                if (r.code in 200..299) {
                    val t = textOf(r.body)
                    if (t.isNotBlank()) openedText = t
                    val code = otpIn(t)
                    if (code.isNotBlank()) otp = code
                } else {
                    status = "Mail kholne mein error " + r.code + ": " + short(r.body)
                }
            } catch (e: Exception) {
                status = "Error: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(address, key) {
        while (true) {
            refresh()
            delay(15000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Dak Temp Mail", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)

        if (key.isBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("RapidAPI key daalein (sirf ek baar)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Key sirf aapke phone mein save hoti hai, code mein nahi.",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("X-RapidAPI-Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val k = keyInput.trim()
                            if (k.length > 10) {
                                key = k
                                prefs.edit().putString("key", k).apply()
                                keyInput = ""
                                status = "Key save ho gayi. Generate dabayein."
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Key save karo") }
                }
            }
        }

        if (otp.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("OTP MIL GAYA", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    Text(
                        otp,
                        fontSize = 34.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(otp)); status = "OTP copy ho gaya" }) {
                        Text("OTP copy karo")
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AAPKA ADDRESS", fontSize = 11.sp, color = Color.Gray)
                SelectionContainer {
                    Text(
                        if (address.isBlank()) "Generate New Address dabayein" else address,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp
                    )
                }
                Button(
                    onClick = generate,
                    enabled = !busy && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Wait..." else "Generate New Address") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { clip.setText(AnnotatedString(address)); status = "Copy ho gaya" },
                        enabled = address.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = {
                            if (address.isNotBlank() && !saved.contains(address)) {
                                saved = saved + address
                                saveList(prefs, "saved", saved)
                                status = "Save ho gaya"
                            }
                        },
                        enabled = address.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Save", fontSize = 13.sp) }
                    OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.weight(1f)) {
                        Text("Restore", fontSize = 13.sp)
                    }
                }
                if (status.isNotBlank()) {
                    Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabButton("Mailbox", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabButton("History", tab == 1, Modifier.weight(1f)) { tab = 1 }
            TabButton("Saved", tab == 2, Modifier.weight(1f)) { tab = 2 }
        }

        when (tab) {
            0 -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${msgs.size} messages", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = refresh) { Text(if (busy) "..." else "Reload") }
                }
                if (msgs.isEmpty()) {
                    Text(
                        "Abhi is address par koi mail nahi aayi. Har 15 second mein check hota hai.",
                        color = Color.Gray
                    )
                }
                msgs.forEach { m ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openMsg(m) }
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(m.subject, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(m.from, fontSize = 13.sp, color = Color.Gray, maxLines = 1)
                        }
                    }
                }
                val o = opened
                if (o != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(o.subject, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("From: " + o.from, fontSize = 12.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                            SelectionContainer {
                                Text(openedText.ifBlank { "(Is mail ka text nahi mila)" })
                            }
                            OutlinedButton(onClick = { opened = null }) { Text("Band karo") }
                        }
                    }
                }
            }

            1 -> {
                if (history.isEmpty()) Text("Abhi koi history nahi hai.", color = Color.Gray)
                history.forEach { a ->
                    AddrRow(a, a == address, { useAddress(a) }, null)
                }
            }

            else -> {
                OutlinedTextField(
                    value = restoreInput,
                    onValueChange = { restoreInput = it },
                    label = { Text("Purana address yahan paste karein") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        val a = restoreInput.trim()
                        if (a.contains("@")) {
                            useAddress(a)
                            restoreInput = ""
                            status = "Address restore ho gaya"
                        }
                    },
                    enabled = restoreInput.contains("@"),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Restore karo") }
                if (saved.isEmpty()) Text("Koi saved address nahi. Upar \"Save\" dabayein.", color = Color.Gray)
                saved.forEach { a ->
                    AddrRow(a, a == address, { useAddress(a) }, {
                        saved = saved.filter { it != a }
                        saveList(prefs, "saved", saved)
                    })
                }
            }
        }
    }
}
