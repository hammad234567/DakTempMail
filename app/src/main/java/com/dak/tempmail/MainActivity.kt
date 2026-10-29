package com.dak.tempmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import org.json.JSONObject

private const val RAPID_API_KEY = "31ad4a2a42mshde492d6a579a4dep1b77e9jsn743a3a025918"
private const val API_HOST = "gmailnator.p.rapidapi.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                DakApp()
            }
        }
    }
}

@Composable
fun DakApp() {
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val client = remember { OkHttpClient() }
    val mediaType = "application/json; charset=utf-8".toMediaType()

    var address by remember { mutableStateOf("") }
    var extractedOtp by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Naya Email banane ke liye Generate dabayein") }
    var loading by remember { mutableStateOf(false) }

    fun generateAndCheckOtp() {
        if (loading) return
        loading = true
        address = ""
        extractedOtp = ""
        status = "Naya Email generate ho raha hai..."

        scope.launch(Dispatchers.IO) {
            try {
                val genReq = Request.Builder()
                    .url("https://$API_HOST/api/generateSingleEmail")
                    .post("{}".toRequestBody(mediaType))
                    .addHeader("content-type", "application/json")
                    .addHeader("X-RapidAPI-Key", RAPID_API_KEY)
                    .addHeader("X-RapidAPI-Host", API_HOST)
                    .build()

                val genRes = client.newCall(genReq).execute()
                val genJson = JSONObject(genRes.body?.string() ?: "")
                
                if (!genJson.has("email")) {
                    withContext(Dispatchers.Main) {
                        status = "Error: Email generate nahi hui."
                        loading = false
                    }
                    return@launch
                }

                val newEmail = genJson.getString("email")
                
                withContext(Dispatchers.Main) {
                    address = newEmail
                    status = "Email ban gayi! OTP ka wait kar rahe hain..."
                }

                var otpFound = false
                var attempts = 0
                while (!otpFound && attempts < 40) {
                    delay(5000)
                    attempts++

                    val listReq = Request.Builder()
                        .url("https://$API_HOST/api/listInboxMessages")
                        .post("{\"email\":\"$newEmail\"}".toRequestBody(mediaType))
                        .addHeader("content-type", "application/json")
                        .addHeader("X-RapidAPI-Key", RAPID_API_KEY)
                        .addHeader("X-RapidAPI-Host", API_HOST)
                        .build()

                    val listRes = client.newCall(listReq).execute()
                    val listBody = listRes.body?.string() ?: ""

                    if (listBody.contains("id")) {
                        val msgIdRegex = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
                        val msgId = msgIdRegex.find(listBody)?.groupValues?.get(1)

                        if (msgId != null) {
                            val contentReq = Request.Builder()
                                .url("https://$API_HOST/api/getFullMessageContent?messageId=$msgId")
                                .get()
                                .addHeader("X-RapidAPI-Key", RAPID_API_KEY)
                                .addHeader("X-RapidAPI-Host", API_HOST)
                                .build()

                            val contentRes = client.newCall(contentReq).execute()
                            val contentBody = contentRes.body?.string() ?: ""

                            val otpRegex = Regex("\\b(\\d{4,8})\\b")
                            val match = otpRegex.find(contentBody)

                            if (match != null) {
                                otpFound = true
                                withContext(Dispatchers.Main) {
                                    extractedOtp = match.value
                                    status = "OTP mil gaya!"
                                    loading = false
                                }
                            }
                        }
                    }
                }

                if (!otpFound) {
                    withContext(Dispatchers.Main) {
                        status = "OTP nahi mila. Dobara koshish karein."
                        loading = false
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "Error: ${e.message}"
                    loading = false
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Emailnator Style", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        
        if (extractedOtp.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(16.dp)) {
                    Text("OTP RECEIVED", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                    Text(extractedOtp, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AAPKA GMAIL ADDRESS", fontSize = 11.sp, color = Color.Gray)
                SelectionContainer {
                    Text(
                        if (address.isBlank()) "Generate par click karein" else address,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 18.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { clip.setText(AnnotatedString(address)); status = "Copy ho gaya" },
                        enabled = address.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Copy") }
                    
                    Button(
                        onClick = { generateAndCheckOtp() },
                        enabled = !loading,
                        modifier = Modifier.weight(1f)
                    ) { Text(if (loading) "Wait..." else "Generate") }
                }
                Text(status, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}
