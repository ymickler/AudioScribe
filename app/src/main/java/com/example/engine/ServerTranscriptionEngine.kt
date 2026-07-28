package com.example.engine

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    // sizeMb/ramMb/speedNote are optional metadata a compatible server may provide (our own
    // audioscribe-server does); default to 0/"" so a third-party server that only returns
    // id+label still parses fine - the UI simply hides whatever isn't present.
    data class ServerModel(
        val id: String,
        val label: String,
        val sizeMb: Int = 0,
        val ramMb: Int = 0,
        val speedNote: String = ""
    )

    private val tag = "ServerTranscriptionEngine"

    // Every public function here wraps its body in withContext(Dispatchers.IO): OkHttp's
    // call.enqueue() itself never blocks the calling thread, but once the coroutine resumes
    // inside executeAsync() it continues on whatever dispatcher the *caller* happened to be
    // using (e.g. rememberCoroutineScope() defaults to Main) - and reading the response body
    // (response.body?.string()) is real blocking socket I/O, which throws
    // NetworkOnMainThreadException if that resumption lands back on the main thread. Forcing
    // Dispatchers.IO here makes every call in this class safe regardless of which dispatcher
    // the caller launched from, instead of relying on every call site to remember Dispatchers.IO.
    suspend fun healthCheck(baseUrl: String, timeoutMs: Long = 2000): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext false
        try {
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

    // Unlike healthCheck/transcribe, failures here are NOT swallowed: this is the one call
    // driven directly by a user-visible "Test Connection" action, and the caller already has
    // a catch block that surfaces the real error message. Silently returning emptyList() on a
    // network failure or HTTP error used to be indistinguishable from "connected fine, server
    // just has zero models" - a genuinely empty/missing `models` array in a 2xx response is
    // the only case that still returns emptyList() normally.
    suspend fun fetchModels(baseUrl: String): List<ServerModel> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyList()
        val request = Request.Builder()
            .url("${normalizeBaseUrl(baseUrl)}/v1/models")
            .get()
            .build()
        val response = executeAsync(client, request)
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Server responded with HTTP ${resp.code}")
            }
            val bodyString = resp.body?.string()
            if (bodyString.isNullOrEmpty()) return@use emptyList()

            val json = JSONObject(bodyString)
            val modelsArray = json.optJSONArray("models") ?: return@use emptyList()

            (0 until modelsArray.length()).mapNotNull { index ->
                val obj = modelsArray.optJSONObject(index) ?: return@mapNotNull null
                val id = obj.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                val label = obj.optString("label", id)
                ServerModel(
                    id = id,
                    label = label,
                    sizeMb = obj.optInt("sizeMb", 0),
                    ramMb = obj.optInt("ramMb", 0),
                    speedNote = obj.optString("speedNote", "")
                )
            }
        }
    }

    suspend fun transcribe(
        baseUrl: String,
        model: String,
        audioFile: File,
        language: String,
        onProgress: (Float) -> Unit
    ): String = withContext(Dispatchers.IO) {
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

        response.use { resp ->
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
