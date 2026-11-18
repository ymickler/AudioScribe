package com.example.engine

import android.content.Context
import com.example.data.PcProfile
import com.example.data.SettingsManager
import kotlinx.coroutines.CancellationException
import java.io.File

/** Both Android entry points use the same PC -> homelab -> on-device policy. */
class ServerRouter(private val context: Context) {
    suspend fun transcribe(file: File, language: String, onTarget: (String, String?) -> Unit,
                           onProgress: (Float) -> Unit,
                           onPhase: (ServerTranscriptionEngine.Phase) -> Unit = {}): String? {
        val settings = SettingsManager(context)
        val profile = PcProfile.load(context)
        if (profile != null) {
            val pc = runCatching { PcTranscriptionEngine(profile) }.getOrNull()
            if (pc != null && pc.healthCheck()) {
                onTarget("PC", null)
                try { return pc.transcribe(file, language, onProgress, onPhase) }
                catch (e: CancellationException) { throw e }
                catch (e: PendingPcJobException) { throw e }
                catch (_: Exception) { /* Definitive rejection or terminal job: safe to try homelab. */ }
            }
        }
        val server = ServerTranscriptionEngine()
        if (server.healthCheck(settings.serverUrl)) {
            onTarget("Homelab", settings.serverModel)
            try {
                return server.transcribe(settings.serverUrl, settings.serverModel, file, language,
                    onProgress = onProgress, onPhase = onPhase)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Existing companion server has no durable job API. */ }
        }
        return null
    }
}
