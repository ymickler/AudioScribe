package com.example

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.data.Localization
import com.example.data.PcProfile
import com.example.engine.PcTranscriptionEngine
import com.example.ui.theme.SleekText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PcServerSettings(language: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf(PcProfile.load(context)) }
    var status by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    fun tr(key: String) = Localization.getString(key, language)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use {
                        val buffer = ByteArray(32769)
                        var total = 0
                        while (total < buffer.size) {
                            val n = it.read(buffer, total, buffer.size - total)
                            if (n < 0) break
                            total += n
                        }
                        require(total <= 32768)
                        buffer.copyOf(total)
                    }
                    val json = bytes.toString(Charsets.UTF_8)
                    val parsed = PcProfile.parse(json)
                    PcTranscriptionEngine(parsed) // Validate the certificate before saving.
                    PcProfile.save(context, json)
                }
                profile = PcProfile.load(context)
                status = ""
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { status = tr("pc_import_failed") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("pc_title"), color = SleekText)
        Text(tr("pc_help"), color = SleekText)
        profile?.let { Text(it.url, color = SleekText) }
        Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
            Text(tr("pc_import"))
        }
        if (profile != null) {
            OutlinedButton(enabled = !testing, onClick = {
                testing = true
                scope.launch {
                    try {
                        val ready = PcTranscriptionEngine(profile!!).healthCheck()
                        status = tr(if (ready) "pc_ready" else "pc_unavailable")
                    } finally { testing = false }
                }
            }) { Text(tr("pc_test")) }
            TextButton(onClick = { PcProfile.clear(context); profile = null; status = "" }) {
                Text(tr("pc_remove"))
            }
        }
        if (status.isNotEmpty()) Text(status, color = SleekText)
    }
}
