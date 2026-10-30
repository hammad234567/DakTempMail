package com.dak.tempmail

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
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
private const val INBOX_PATH = "/api/inbox"
private const val API_KEY = "31ad4a2a42mshde492d6a579a4dep1b77e9jsn743a3a025918"

// Inbox list URL: tries these in order and remembers the one that works.
private val LIST_PATHS = listOf("/api/inbox", "/api/emails/inbox")

private val Blue = Color(0xFF1677FF)
private val Ink = Color(0xFF101820)
private val Muted = Color(0xFF5B6670)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
private val http = OkHttpClient()

private class ApiResult(val code: Int, val body: String)
private class Msg(val id: String, val subject: String, val from: String, val raw: String)
private class DomainOpt(val key: String, val title: String, val desc: String)

// Domain options. If the API uses different names, only this list needs to change.
private val DOMAIN_OPTS = listOf(
    DomainOpt("public_googlemail", "Google Mail", "Googlemail variations (@googlemail.com)"),
    DomainOpt("public_dotgmail", "Gmail Dot", "Gmail with dot(.) variations (@gmail.com)"),
    DomainOpt("public_plusgmail", "Gmail Plus", "Gmail with plus(+) variations (@gmail.com)")
)

private fun call(method: String, path: String, json: String?): ApiResult {
    val b = Request.Builder()
        .url("https://$HOST$path")
        .addHeader("x-rapidapi-host", HOST)
        .addHeader("x-rapidapi-key", API_KEY)
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

private fun listInbox(prefs: SharedPreferences, address: String): ApiResult {
    val body = JSONObject().put("email", address).toString()
    val cached = prefs.getInt("listIdx2", -1)
    val order = if (cached in LIST_PATHS.indices) {
        listOf(cached) + LIST_PATHS.indices.filter { it != cached }
    } else {
        LIST_PATHS.indices.toList()
    }
    var last = ApiResult(0, "")
    for (i in order) {
        val r = call("POST", LIST_PATHS[i], body)
        last = r
        if (r.code != 404 && r.code != 405) {
            if (r.code in 200..299) prefs.edit().putInt("listIdx2", i).apply()
            return r
        }
    }
    return last
}

private fun optionsBody(picked: List<String>): String {
    if (picked.isEmpty()) return "{}"
    return JSONObject().put("options", JSONArray(picked)).toString()
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

/** Extracts the readable text from a JSON mail (not the ids). */
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

private fun otpIn(s: String, loose: Boolean = false): String {
    val kw = Regex("(?i)(?:code|otp|pin|passcode|verification|verify)\\D{0,40}(?<!\\d)(\\d{4,8})(?!\\d)").find(s)
    if (kw != null) return kw.groupValues[1]
    if (!loose) return ""
    val six = Regex("(?<!\\d)(\\d{6})(?!\\d)").find(s)
    return six?.groupValues?.get(1) ?: ""
}

private fun pathId(id: String): String {
    return if (Regex("[A-Za-z0-9_=.-]+").matches(id)) id else URLEncoder.encode(id, "UTF-8")
}

// ---------- UI ----------
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dak2", MODE_PRIVATE)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue,
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
private fun OtpCard(otp: String, onCopy: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OTP RECEIVED", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
            Text(
                otp,
                fontSize = 34.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )
            OutlinedButton(onClick = onCopy) { Text("Copy OTP") }
        }
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
                color = if (current) Blue else Ink
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("Use") }
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
    var loadingMail by remember { mutableStateOf(false) }
    var showDomains by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf(setOf<String>()) }

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

    val generate: (List<String>) -> Unit = { picked ->
        if (!busy) {
            scope.launch {
                busy = true
                status = "Generating new address..."
                try {
                    var note = ""
                    var r = withContext(Dispatchers.IO) { call("POST", GENERATE_PATH, optionsBody(picked)) }
                    if (r.code != 200 && picked.isNotEmpty()) {
                        r = withContext(Dispatchers.IO) { call("POST", GENERATE_PATH, "{}") }
                        note = " (the API did not accept the domain choice)"
                    }
                    if (r.code == 200) {
                        val o = JSONObject(r.body)
                        val email = o.optString("email", "")
                        if (email.isNotBlank()) {
                            useAddress(email)
                            status = "New address created, type: " + o.optString("type", "?") + note
                        } else {
                            status = "No email in response: " + short(r.body)
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
        if (!busy && address.isNotBlank()) {
            scope.launch {
                busy = true
                try {
                    val r = withContext(Dispatchers.IO) { listInbox(prefs, address) }
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
        loadingMail = true
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { call("GET", INBOX_PATH + "/" + pathId(m.id), null) }
                if (r.code in 200..299) {
                    var t = textOf(r.body)
                    if (t.isBlank()) t = r.body.take(1500)
                    openedText = t
                } else {
                    status = "Could not open mail, error " + r.code + ": " + short(r.body)
                }
            } catch (e: Exception) {
                status = "Error: " + (e.message ?: e.javaClass.simpleName)
            }
            loadingMail = false
        }
    }

    LaunchedEffect(address) {
        while (true) {
            refresh()
            delay(15000)
        }
    }

    if (showDomains) {
        AlertDialog(
            onDismissRequest = { showDomains = false },
            title = { Text("Select Domains", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Choose which domains to use for email generation. Leave all off for a random one.", fontSize = 13.sp, color = Muted)
                    DOMAIN_OPTS.forEach { d ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF6F8FB))) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, fontWeight = FontWeight.SemiBold)
                                    Text(d.desc, fontSize = 12.sp, color = Muted)
                                }
                                Switch(
                                    checked = sel.contains(d.key),
                                    onCheckedChange = { on ->
                                        sel = if (on) sel + d.key else sel - d.key
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showDomains = false
                    generate(DOMAIN_OPTS.map { it.key }.filter { sel.contains(it) })
                }) { Text("Generate Email") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDomains = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = Color(0xFFF6F8FB),
        topBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Blue)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Dak Temp Mail", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Blue) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    indicatorColor = Color(0x33FFFFFF),
                    unselectedIconColor = Color(0xB3FFFFFF),
                    unselectedTextColor = Color(0xB3FFFFFF)
                )
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                    icon = { Text("✉", fontSize = 20.sp) }, label = { Text("Email") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 }, colors = colors,
                    icon = { Text("▤", fontSize = 20.sp) }, label = { Text("Inbox") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 }, colors = colors,
                    icon = { Text("⇄", fontSize = 20.sp) }, label = { Text("Switch Email") }
                )
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (tab) {
                0 -> {
                    Text(
                        "Email Generator",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "Generate secure temporary email addresses instantly",
                        color = Muted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    if (otp.isNotBlank()) {
                        OtpCard(otp) { clip.setText(AnnotatedString(otp)); status = "OTP copied" }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("CURRENT EMAIL ADDRESS", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink)
                            SelectionContainer {
                                Text(
                                    if (address.isBlank()) "Tap Generate New Email" else address,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { clip.setText(AnnotatedString(address)); status = "Copied" },
                        enabled = address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy to Clipboard") }
                    Button(
                        onClick = { showDomains = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Please wait..." else "Generate New Email") }
                    OutlinedButton(
                        onClick = {
                            if (address.isNotBlank() && !saved.contains(address)) {
                                saved = saved + address
                                saveList(prefs, "saved", saved)
                                status = "Saved"
                            }
                        },
                        enabled = address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save Email") }
                    Text("Restore Email", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = restoreInput,
                        onValueChange = { restoreInput = it },
                        label = { Text("Paste an old address here") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val a = restoreInput.trim()
                            if (a.contains("@")) {
                                useAddress(a)
                                restoreInput = ""
                                status = "Address restored"
                            }
                        },
                        enabled = restoreInput.contains("@"),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore Email") }
                    if (status.isNotBlank()) {
                        Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                    }
                }

                1 -> {
                    val o = opened
                    if (o != null) {
                        OutlinedButton(onClick = { opened = null }) { Text("< Back to Inbox") }
                        Text(o.subject, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("From: " + o.from, fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace)
                        val mailOtp = otpIn(openedText, true)
                        if (mailOtp.isNotBlank()) {
                            OtpCard(mailOtp) { clip.setText(AnnotatedString(mailOtp)); status = "OTP copied" }
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                                SelectionContainer {
                                    Text(
                                        if (loadingMail) "Loading mail..."
                                        else openedText.ifBlank { "(No text found in this mail)" }
                                    )
                                }
                            }
                        }
                        if (status.isNotBlank()) {
                            Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                        }
                    } else {
                        Text("Inbox", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text(address.ifBlank { "No address yet" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                        if (otp.isNotBlank()) {
                            OtpCard(otp) { clip.setText(AnnotatedString(otp)); status = "OTP copied" }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${msgs.size} messages", fontWeight = FontWeight.SemiBold)
                            OutlinedButton(onClick = refresh) { Text(if (busy) "..." else "Reload") }
                        }
                        if (msgs.isEmpty()) {
                            Text("No mail yet for this address. Checks every 15 seconds.", color = Muted)
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
                                    Text(m.from, fontSize = 13.sp, color = Muted, maxLines = 1)
                                }
                            }
                        }
                        if (status.isNotBlank()) {
                            Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                        }
                    }
                }

                else -> {
                    Text("Switch Email", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Saved", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (saved.isEmpty()) Text("No saved addresses. Tap Save Email on the Email tab.", color = Muted)
                    saved.forEach { a ->
                        AddrRow(a, a == address, { useAddress(a) }, {
                            saved = saved.filter { it != a }
                            saveList(prefs, "saved", saved)
                        })
                    }
                    Text("History", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (history.isEmpty()) Text("No history yet.", color = Muted)
                    history.forEach { a ->
                        AddrRow(a, a == address, { useAddress(a) }, null)
                    }
                }
            }
        }
    }
}
