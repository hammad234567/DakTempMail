package com.dak.tempmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Teal = Color(0xFF0B6E5C)
private val Bg = Color(0xFFEEF2F5)
private val Ink = Color(0xFF101820)
private val Muted = Color(0xFF5B6670)
private val Alert = Color(0xFFB3380A)
private val SuccessGreen = Color(0xFF2E7D32)
private val SuccessBg = Color(0xFFE8F5E9)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Teal,
                    onPrimary = Color.White,
                    background = Bg,
                    surface = Color.White,
                    onSurface = Ink
                )
            ) {
                DakApp(store)
            }
        }
    }
}

@Composable
fun DakApp(store: Store) {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current

    var gmail by remember { mutableStateOf(store.gmail) }
    var pass by remember { mutableStateOf(store.appPassword) }
    var address by remember { mutableStateOf(store.current) }
    var saved by remember { mutableStateOf(store.saved) }
    var mode by remember { mutableStateOf(0) }
    var tab by remember {
        mutableStateOf(if (store.gmail.isBlank() || store.appPassword.isBlank()) 3 else 0)
    }
    var mails by remember { mutableStateOf(listOf<Mail>()) }
    var status by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var reader by remember { mutableStateOf<Mail?>(null) }
    
    // OTP کو محفوظ کرنے کے لیے
    var extractedOtp by remember { mutableStateOf("") }

    var gmailInput by remember { mutableStateOf(store.gmail) }
    var passInput by remember { mutableStateOf(store.appPassword) }
    var importText by remember { mutableStateOf("") }

    val newAddress: () -> Unit = {
        val a = AddressGen.make(gmail, mode)
        if (a != null) {
            address = a
            store.current = a
            mails = emptyList()
            extractedOtp = "" // ایڈریس بدلا تو پرانا OTP صاف کر دیں
            info = "Naya address ban gaya"
        } else {
            info = "Pehle Settings mein apna @gmail.com address daalein"
        }
    }

    val refresh: () -> Unit = {
        if (!loading && gmail.isNotBlank() && pass.isNotBlank() && address.isNotBlank()) {
            scope.launch {
                loading = true
                try {
                    val r = withContext(Dispatchers.IO) { ImapClient.fetch(gmail, pass, address) }
                    mails = r
                    
                    // 🌟 OTP نکالنے کا لاجک
                    if (r.isNotEmpty()) {
                        val latestMail = r.first() // سب سے نئی ای میل
                        // یہاں intro کی جگہ body کر دیا گیا ہے
                        val textToSearch = "${latestMail.subject} ${latestMail.body}"
                        val regex = Regex("\\b(\\d{4,8})\\b")
                        val match = regex.find(textToSearch)
                        if (match != null) {
                            extractedOtp = match.value
                        }
                    }
                    // 🌟 OTP لاجک ختم

                    status = "Last check: " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                } catch (e: javax.mail.AuthenticationFailedException) {
                    status = "Gmail ya App Password galat hai. Settings check karein."
                } catch (e: Exception) {
                    status = "Error: " + (e.message ?: e.javaClass.simpleName)
                }
                loading = false
            }
        }
    }

    LaunchedEffect(gmail) {
        if (address.isBlank() && gmail.isNotBlank()) newAddress()
    }

    LaunchedEffect(address, gmail, pass) {
        while (true) {
            refresh()
            delay(10000) // ہر 10 سیکنڈ بعد چیک کرے گا
        }
    }

    val open = reader
    if (open != null) {
        BackHandler { reader = null }
        ReaderScreen(open) { reader = null }
        return
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("●") }, label = { Text("Home") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("✉") }, label = { Text("Inbox") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("★") }, label = { Text("Saved") })
                NavigationBarItem(selected = tab == 3, onClick = { tab = 3 }, icon = { Text("⚙") }, label = { Text("Settings") })
            }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            when (tab) {
                // ================= HOME SCREEN =================
                0 -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("Dak Temp Mail", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    
                    // 🌟 OTP دکھانے کا کارڈ
                    if (extractedOtp.isNotBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SuccessBg),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text("OTP RECEIVED", fontSize = 12.sp, color = SuccessGreen, fontWeight = FontWeight.Bold)
                                Text(
                                    text = extractedOtp,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("AAPKA TEMPORARY ADDRESS", fontSize = 11.sp, color = Muted)
                            SelectionContainer {
                                Text(
                                    if (address.isBlank()) "Settings mein Gmail daalein" else address,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 18.sp,
                                    color = Ink
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { clip.setText(AnnotatedString(address)); info = "Copy ho gaya" },
                                    enabled = address.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Copy") }
                                OutlinedButton(onClick = newAddress, modifier = Modifier.weight(1f)) { Text("Naya") }
                                OutlinedButton(
                                    onClick = {
                                        if (address.isNotBlank() && !saved.contains(address)) {
                                            saved = saved + address
                                            store.saved = saved
                                            info = "Save ho gaya"
                                        } else if (saved.contains(address)) {
                                            info = "Ye pehle se saved hai"
                                        }
                                    },
                                    enabled = address.isNotBlank(),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Save") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Choice("Dot (a.b.c)", mode == 0, Modifier.weight(1f)) { mode = 0 }
                                Choice("Plus (+abc)", mode == 1, Modifier.weight(1f)) { mode = 1 }
                            }
                            if (info.isNotBlank()) Text(info, fontSize = 13.sp, color = Teal)
                            Text(
                                if (status.isBlank()) "Inbox check ho raha hai..." else status,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Muted
                            )
                        }
                    }
                    Text("Nayi mails", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    if (mails.isEmpty()) {
                        Text("Abhi is address par koi mail nahi aayi. Har 10 second mein check hota hai.", color = Muted)
                    } else {
                        mails.take(3).forEach { m -> MailRow(m) { reader = m } }
                    }
                }

                // ================= INBOX SCREEN =================
                1 -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("Inbox (${mails.size})", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Text(address, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
                    Text(status, fontSize = 12.sp, color = Muted)
                    if (mails.isEmpty()) {
                        Text("Abhi koi mail nahi aayi.", color = Muted, modifier = Modifier.padding(top = 16.dp))
                    } else {
                        LazyColumn(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(mails) { m -> MailRow(m) { reader = m } }
                        }
                    }
                }

                // ================= SAVED SCREEN =================
                2 -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("Saved addresses", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    if (saved.isEmpty()) {
                        Text(
                            "Koi address save nahi hai. Home par \"Save\" dabayein.",
                            color = Muted,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(saved) { s ->
                                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            s,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                            color = if (s == address) Teal else Ink
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    address = s
                                                    store.current = s
                                                    mails = emptyList()
                                                    extractedOtp = ""
                                                    info = "Address restore ho gaya"
                                                    tab = 0
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Restore karo") }
                                            OutlinedButton(
                                                onClick = {
                                                    saved = saved.filter { it != s }
                                                    store.saved = saved
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Delete", color = Alert) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ================= SETTINGS SCREEN =================
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Settings", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink)
                    Text(
                        "1. Google Account mein 2-Step Verification on karein.\n" +
                            "2. myaccount.google.com/apppasswords par jakar ek App Password banayein (16 letters).\n" +
                            "3. Neeche apna Gmail aur wo App Password daalein. Asli Gmail password mat daalein.",
                        fontSize = 13.sp,
                        color = Muted
                    )
                    OutlinedTextField(
                        value = gmailInput,
                        onValueChange = { gmailInput = it },
                        label = { Text("Aapka Gmail (name@gmail.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = passInput,
                        onValueChange = { passInput = it },
                        label = { Text("App Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val g = gmailInput.trim()
                            val p = passInput.replace(" ", "").trim()
                            if (AddressGen.base(g) == null) {
                                info = "Sahi @gmail.com address daalein"
                            } else if (p.length < 8) {
                                info = "App Password daalein"
                            } else {
                                store.gmail = g
                                store.appPassword = p
                                val baseChanged = AddressGen.base(gmail) != AddressGen.base(g)
                                gmail = g
                                pass = p
                                if (baseChanged || address.isBlank()) {
                                    val a = AddressGen.make(g, mode)
                                    if (a != null) {
                                        address = a
                                        store.current = a
                                        mails = emptyList()
                                        extractedOtp = ""
                                    }
                                }
                                info = "Save ho gaya. Home par jayein."
                                tab = 0
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save karo") }
                    if (info.isNotBlank()) Text(info, fontSize = 13.sp, color = Teal)

                    Text("Backup aur Restore", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                    Text(
                        "Backup mein aapka Gmail aur saved addresses hote hain. App Password shamil nahi hota.",
                        fontSize = 13.sp,
                        color = Muted
                    )
                    OutlinedButton(
                        onClick = { clip.setText(AnnotatedString(store.exportJson())); info = "Backup copy ho gaya. Kahin paste karke rakh lein." },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Backup copy karo") }
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("Backup yahan paste karein") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            if (store.importJson(importText)) {
                                gmail = store.gmail
                                gmailInput = store.gmail
                                address = store.current
                                saved = store.saved
                                mails = emptyList()
                                extractedOtp = ""
                                importText = ""
                                info = "Restore ho gaya. Ab App Password daal kar Save karein."
                            } else {
                                info = "Backup sahi nahi hai"
                            }
                        },
                        enabled = importText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore karo") }
                    Text(
                        "Koi zaroori ya private cheez temporary address par mat mangwana.",
                        fontSize = 12.sp,
                        color = Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label, fontSize = 13.sp) }
    }
}

@Composable
private fun MailRow(m: Mail, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(m.subject, fontWeight = FontWeight.SemiBold, color = Ink)
            // یہاں بھی body کر دیا گیا ہے
            Text(m.body, fontSize = 13.sp, color = Muted, maxLines = 2)
        }
    }
}

@Composable
private fun ReaderScreen(m: Mail, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Button(onClick = onBack) { Text("← Wapas") }
        Text(m.subject, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        // یہاں بھی body کر دیا گیا ہے
        Text(m.body, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp))
    }
}
