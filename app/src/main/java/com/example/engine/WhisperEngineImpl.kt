package com.example.engine

import com.example.data.SettingsManager
import dev.ffmpegkit.whisper.Whisper
import dev.ffmpegkit.whisper.WhisperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WhisperEngineImpl : STTEngine {
    override suspend fun transcribe(
        context: android.content.Context,
        audioFile: File,
        modelPath: String,
        onProgress: (Float) -> Unit,
        onPartial: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        
        onProgress(0.05f)
        onPartial("...") 
        
        var fullText = ""
        var whisperModel: dev.ffmpegkit.whisper.WhisperModel? = null
        
        // Launch helper coroutine to smoothly transition progress while Whisper transcribes
        val progressJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            var currentProgress = 0.05f
            while (currentProgress < 0.95f) {
                kotlinx.coroutines.delay(500)
                val increment = when {
                    currentProgress < 0.3f -> 0.02f
                    currentProgress < 0.6f -> 0.015f
                    currentProgress < 0.85f -> 0.008f
                    else -> 0.002f
                }
                currentProgress = (currentProgress + increment).coerceAtMost(0.95f)
                onProgress(currentProgress)
            }
        }

        try {
            // Whisper.loadModel(context, modelPath, ...) is suspend
            whisperModel = Whisper.loadModel(context, modelPath)
            onProgress(0.15f)
            
            val settingsManager = SettingsManager(context)
            val langCode = settingsManager.getTargetLanguageCode()
            val threadsCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

            val config = WhisperConfig(
                language = langCode,
                translate = false,
                threads = threadsCount,
                maxSegmentLength = 0,
                printTimestamps = false
            )
            
            // Whisper.transcribe(model, audioPath, config) is suspend
            val result = Whisper.transcribe(whisperModel, audioFile.absolutePath, config)
            
            fullText = result.text ?: ""
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                throw e
            }
            android.util.Log.e("WhisperEngineImpl", "Whisper transcription failed", e)
            throw Exception("Whisper transcription failed: ${e.message}")
        } finally {
            progressJob.cancel()
            if (whisperModel != null) {
                Whisper.releaseModel(whisperModel)
            }
        }
        
        onProgress(1.0f)
        return@withContext fullText.trim()
    }
}
