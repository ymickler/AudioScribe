package com.example.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class LocalTranscriptionEngine(
    private val context: Context,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default
) {

    private val tag = "LocalTranscriptionEngine"
    private val downloader = ModelDownloader(context)
    private val converter = AudioConverter()
    private val settingsManager = SettingsManager(context)
    private val serverEngine = ServerTranscriptionEngine()

    interface TranscriptionCallback {
        fun onStart()
        fun onModelResolved(modelType: ModelDownloader.ModelType) {}
        fun onServerModelResolved(model: ServerTranscriptionEngine.ServerModel) {}
        fun onProgress(progress: Float)
        fun onPartialResult(text: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    fun transcribeAudio(
        audioUri: Uri,
        callback: TranscriptionCallback,
        modelOverride: ModelDownloader.ModelType? = null
    ): kotlinx.coroutines.Job {
        return CoroutineScope(dispatcher).launch {
            var convertedWavFile: File? = null
            var tempInputFile: File? = null
            try {
                callback.onStart()

                val engineType = settingsManager.sttEngine
                val langCode = settingsManager.getTargetLanguageCode()

                if (audioUri.toString().startsWith("mock://")) {
                    val isDe = audioUri.toString().contains("de") || langCode == "de"
                    val mockText = if (isDe) {
                        "Hallo, das ist eine simulierte Sprachnachricht für die App AudioScribe."
                    } else {
                        "Hello, this is a simulated voice message for the AudioScribe app."
                    }
                    callback.onProgress(0.15f)
                    kotlinx.coroutines.delay(50)
                    callback.onProgress(0.40f)
                    kotlinx.coroutines.delay(50)
                    callback.onProgress(0.70f)
                    callback.onPartialResult(if (isDe) "Hallo, das ist" else "Hello, this is")
                    kotlinx.coroutines.delay(50)
                    callback.onProgress(0.90f)
                    callback.onPartialResult(mockText)
                    kotlinx.coroutines.delay(50)
                    callback.onProgress(1.0f)
                    callback.onComplete(mockText)
                    return@launch
                }

                // 1. Copy URI to a temporary file. This happens up-front (before any local model
                // resolution) so that a server-only user - who may never have downloaded a local
                // model - can still have their audio transcribed via the server path below.
                val tempInput = File(context.cacheDir, "input_audio_${System.currentTimeMillis()}.tmp")
                context.contentResolver.openInputStream(audioUri)?.use { input ->
                    FileOutputStream(tempInput).use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    callback.onError("Failed to read audio file")
                    return@launch
                }
                tempInputFile = tempInput

                // 2. Optional server-side transcription path, with automatic fallback to the
                // local pipeline below on any failure. An explicit modelOverride (from the
                // overlay service's "switch model" action) always means "use this specific local
                // model", so the server is skipped entirely in that case.
                if (settingsManager.preferServerTranscription && modelOverride == null) {
                    val serverUrl = settingsManager.serverUrl
                    try {
                        if (serverEngine.healthCheck(serverUrl)) {
                            val serverModelId = settingsManager.serverModel
                            callback.onServerModelResolved(ServerTranscriptionEngine.ServerModel(serverModelId, serverModelId))

                            val serverText = serverEngine.transcribe(
                                baseUrl = serverUrl,
                                model = serverModelId,
                                audioFile = tempInput,
                                language = langCode,
                                onProgress = { progress -> callback.onProgress(progress) }
                            )

                            callback.onComplete(serverText)
                            return@launch
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            throw e
                        }
                        // Server path failed for any reason (unreachable, timeout, bad response,
                        // etc.) - silently fall back to the local whisper.cpp/Vosk pipeline below
                        // instead of surfacing a hard error to the user.
                        Log.w(tag, "Server transcription unavailable, falling back to local engine", e)
                    }
                }

                // 3. Local (offline) model resolution
                val modelType = modelOverride ?: when {
                    engineType == "whisper" -> {
                        when (settingsManager.whisperModelSize) {
                            "base" -> ModelDownloader.ModelType.WHISPER_BASE
                            "small" -> ModelDownloader.ModelType.WHISPER_SMALL
                            else -> ModelDownloader.ModelType.WHISPER_TINY
                        }
                    }
                    langCode == "de" -> ModelDownloader.ModelType.VOSK_DE
                    else -> ModelDownloader.ModelType.VOSK_EN
                }

                if (!downloader.isModelDownloaded(modelType)) {
                    callback.onError("Model not downloaded for ${modelType.engine} (${modelType.language}). Please download it in settings.")
                    return@launch
                }

                callback.onModelResolved(modelType)

                val modelPath = downloader.getModelPath(modelType)

                // 4. Convert to 16kHz WAV
                val wavPath = converter.convertToWav(tempInput.absolutePath, context.cacheDir)

                convertedWavFile = File(wavPath)

                // 5. Transcribe
                val engine: STTEngine = if (modelType.engine == "whisper") WhisperEngineImpl() else VoskEngineImpl()

                val fullText = engine.transcribe(
                    context = context,
                    audioFile = convertedWavFile,
                    modelPath = modelPath,
                    onProgress = { progress -> callback.onProgress(progress) },
                    onPartial = { partial -> callback.onPartialResult(partial) }
                )

                callback.onComplete(fullText)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    throw e
                }
                Log.e(tag, "Error transcribing audio file", e)
                callback.onError(e.message ?: "Unknown offline transcription error")
            } finally {
                tempInputFile?.delete()
                convertedWavFile?.delete()
            }
        }
    }
}
