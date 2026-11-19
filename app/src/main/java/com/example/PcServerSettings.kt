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
    var importing by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pastedJson by remember { mutableStateOf("") }
    fun tr(key: String) = Localization.getString(key, language)
    suspend fun importText(json: String): Boolean {
        var errorKey = "pc_import_invalid_json"
        importing = true
        status = tr("pc_importing")
        return try {
            withContext(Dispatchers.IO) {
                require(json.toByteArray(Charsets.UTF_8).size <= 32768)
                val parsed = PcProfile.parse(json)
                errorKey = "pc_import_invalid_certificate"
                PcTranscriptionEngine(parsed) // Validate the certificate before saving.
                errorKey = "pc_import_storage_failed"
                PcProfile.save(context, json)
            }
            profile = PcProfile.load(context)
            status = tr("pc_import_success")
            true
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) {
            // Never display exception messages: they can contain private profile data.
            status = tr(errorKey)
            false
        } finally { importing = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            status = tr("pc_importing")
            val json = try {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use {
                        val buffer = ByteArray(32769)
                        var total = 0
                        while (total < buffer.size) {
                            val n = it.read(buffer, total, buffer.size - total)
                            if (n < 0) break
                            total += n
                        }
                        require(total <= 32768)
                        buffer.copyOf(total)
                    } ?: error("No input stream")
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (e: kotlinx.coroutines.CancellationException) { importing = false; throw e }
            catch (_: Exception) {
                status = tr("pc_import_read_failed")
                importing = false
                return@launch
            }
            importText(json)
        }
    }
    if (showPaste) {
        AlertDialog(
            onDismissRequest = { if (!importing) { showPaste = false; pastedJson = "" } },
            title = { Text(tr("pc_paste")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("pc_paste_help"))
                    OutlinedTextField(
                        value = pastedJson,
                        onValueChange = { pastedJson = it },
                        enabled = !importing,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                        minLines = 4,
                        label = { Text("JSON") }
                    )
                    if (status.isNotEmpty()) Text(status)
                }
            },
            confirmButton = {
                TextButton(enabled = !importing && pastedJson.isNotBlank(), onClick = {
                    scope.launch {
                        if (importText(pastedJson)) { showPaste = false; pastedJson = "" }
                    }
                }) { Text(tr("pc_import")) }
            },
            dismissButton = {
                TextButton(enabled = !importing, onClick = { showPaste = false; pastedJson = "" }) {
                    Text(tr("pc_cancel"))
                }
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("pc_title"), color = SleekText)
        Text(tr("pc_help"), color = SleekText)
        profile?.let { Text(it.url, color = SleekText) }
        Button(enabled = !importing && !testing, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(tr("pc_import"))
        }
        OutlinedButton(enabled = !importing && !testing, onClick = { status = ""; showPaste = true }) {
            Text(tr("pc_paste"))
        }
        if (profile != null) {
            OutlinedButton(enabled = !testing && !importing, onClick = {
                testing = true
                scope.launch {
                    try {
                        val ready = PcTranscriptionEngine(profile!!).healthCheck()
                        status = tr(if (ready) "pc_ready" else "pc_unavailable")
                    } finally { testing = false }
                }
            }) { Text(tr("pc_test")) }
            TextButton(enabled = !testing && !importing, onClick = { PcProfile.clear(context); profile = null; status = "" }) {
                Text(tr("pc_remove"))
            }
        }
        if (status.isNotEmpty()) Text(status, color = SleekText)
    }
}
