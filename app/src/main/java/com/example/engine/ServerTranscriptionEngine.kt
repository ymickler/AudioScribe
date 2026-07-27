package com.example.engine

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional server-side transcription path. Talks to a self-hosted transcription server
 * (see README of the companion server project) over a small REST API:
 *   GET  /health          -> 200 when the server is up and ready to accept work
 *   GET  /v1/models       -> {"models": [{"id": "...", "label": "..."}, ...]}
 *   POST /v1/transcribe   -> multipart form (audio, model, language) -> {"text": "..."}
 *
 * This engine deliberately does NOT implement [STTEngine]: that interface assumes an
 * already-converted local WAV file plus a local model path, whereas the server accepts the
 * original (unconverted) audio bytes and resolves the model itself. It is driven directly by
 * [LocalTranscriptionEngine] as an optional first attempt, with automatic fallback to the local
 * whisper.cpp/Vosk pipeline on any failure.
 */
// [client] defaults to a brand-new OkHttpClient instance - deliberately separate from
// ModelDownloader's - since health checks need a short timeout so a stale/unreachable server
// fails fast and falls back to the local engine, while transcription uploads of larger audio
// files need a much longer timeout. The parameter exists (rather than a hardcoded private val)
// purely so unit tests can inject a client with a fake/interceptor-based Call.Factory instead of
// hitting the network.
class ServerTranscriptionEngine(private val client: OkHttpClient = OkHttpClient()) {

    data class ServerModel(val id: String, val label: String)

    private val tag = "ServerTranscriptionEngine"

    suspend fun healthCheck(baseUrl: String, timeoutMs: Long = 2000): Boolean {
        if (baseUrl.isBlank()) return false
        return try {
            val healthClient = client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            val request = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/health")
                .get()
                .build()
            val response = executeAsync(healthClient, request)
            response.use { it.code == 200 }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(tag, "Server health check failed: ${e.message}")
            false
        }
    }

    suspend fun fetchModels(baseUrl: String): List<ServerModel> {
        if (baseUrl.isBlank()) return emptyList()
        return try {
            val request = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/v1/models")
                .get()
                .build()
            val response = executeAsync(client, request)
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "fetchModels failed with HTTP ${resp.code}")
                    return emptyList()
                }
                val bodyString = resp.body?.string()
                if (bodyString.isNullOrEmpty()) return emptyList()

                val json = JSONObject(bodyString)
                val modelsArray = json.optJSONArray("models") ?: return emptyList()

                (0 until modelsArray.length()).mapNotNull { index ->
                    val obj = modelsArray.optJSONObject(index) ?: return@mapNotNull null
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) return@mapNotNull null
                    val label = obj.optString("label", id)
                    ServerModel(id, label)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(tag, "fetchModels failed", e)
            emptyList()
        }
    }

    suspend fun transcribe(
        baseUrl: String,
        model: String,
        audioFile: File,
        language: String,
        onProgress: (Float) -> Unit
    ): String {
        onProgress(0.1f)

        val audioMediaType = "audio/*".toMediaTypeOrNull()
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, audioFile.asRequestBody(audioMediaType))
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .build()

        val request = Request.Builder()
            .url("${normalizeBaseUrl(baseUrl)}/v1/transcribe")
            .post(requestBody)
            .build()

        onProgress(0.3f)
        val response = executeAsync(client, request)
        onProgress(0.85f)

        return response.use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = try { resp.body?.string() } catch (e: Exception) { null }
                throw Exception("Server transcription failed with HTTP ${resp.code}${if (!errorBody.isNullOrBlank()) ": $errorBody" else ""}")
            }
            val bodyString = resp.body?.string()
                ?: throw Exception("Server transcription returned an empty response body")

            val text = try {
                JSONObject(bodyString).optString("text", "")
            } catch (e: Exception) {
                throw Exception("Failed to parse server transcription response: ${e.message}")
            }
            onProgress(1.0f)
            text
        }
    }

    private fun normalizeBaseUrl(baseUrl: String): String = baseUrl.trimEnd('/')

    /**
     * Bridges OkHttp's callback-based [Call.enqueue] to coroutines. A blocking `.execute()` call
     * inside `withContext(Dispatchers.IO)` would NOT be cancelled when the parent coroutine Job is
     * cancelled (e.g. the user cancels the queue item or switches models from the overlay
     * service) - the underlying HTTP call would keep running in the background. Using
     * suspendCancellableCoroutine + invokeOnCancellation ensures the call is actually aborted.
     */
    private suspend fun executeAsync(okHttpClient: OkHttpClient, request: Request): Response {
        return suspendCancellableCoroutine { continuation ->
            val call = okHttpClient.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }
    }
}
