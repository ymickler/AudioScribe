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
        // Fired when server transcription was preferred but we're about to fall back to
        // local: "unreachable" if the health check itself failed (server never attempted),
        // "error" if the health check passed but transcription failed even after a retry.
        fun onServerFallback(reason: String) {}
        fun onProgress(progress: Float)
        fun onPartialResult(text: String)
        fun onComplete(fullText: String)
        fun onError(error: String)
    }

    fun transcribeAudio(
        audioUri: Uri,
        callback: TranscriptionCallback,
        modelOverride: ModelDownloader.ModelType? = null,
        // Set by callers that already attempted the server themselves ahead of this call (see
        // TranscriptionOverlayService's eager per-item server dispatch) so it isn't retried a
        // second time here - this call becomes purely the local fallback in that case.
        skipServerAttempt: Boolean = false
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
                // local pipeline below. An explicit modelOverride (from the overlay service's
                // "switch model" action) always means "use this specific local model", so the
                // server is skipped entirely in that case.
                //
                // Two distinct failure modes are surfaced differently rather than treating
                // every failure identically:
                // - The server was never reachable at all (health check failed) -> fall back
                //   immediately, no point retrying a connection that isn't there.
                // - The health check passed but transcription itself failed (e.g. the
                //   connection drops mid-request) -> retry once before giving up, since a
                //   reachable server failing mid-flight is more likely a transient blip than a
                //   real outage.
                // Either way, onServerFallback tells the UI *why* local ended up being used
                // despite the user preferring the server, instead of looking identical to a
                // plain "server not preferred" local run.
                if (settingsManager.preferServerTranscription && modelOverride == null && !skipServerAttempt) {
                    val serverUrl = settingsManager.serverUrl
                    if (serverEngine.healthCheck(serverUrl)) {
                        var serverSucceeded = false
                        for (attempt in 1..2) {
                            try {
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
                                serverSucceeded = true
                                break
                            } catch (e: Exception) {
                                if (e is kotlinx.coroutines.CancellationException) {
                                    throw e
                                }
                                Log.w(tag, "Server transcription attempt $attempt failed", e)
                            }
                        }
                        if (serverSucceeded) return@launch
                        callback.onServerFallback("error")
                    } else {
                        callback.onServerFallback("unreachable")
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
