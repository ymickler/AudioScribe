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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
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
class ServerTranscriptionEngine(private val client: OkHttpClient = sharedClient) {

    companion object {
        // One process-wide client instead of a fresh one per engine instance (and the service
        // creates an engine per attempt): every OkHttpClient brings its own connection pool and
        // thread pool, so the old default threw away keep-alive connections and re-paid the
        // TCP/TLS handshake on every single call. Per-call timeouts still differ - newBuilder()
        // below returns a client that *shares* this pool, which is exactly the intended usage.
        private val sharedClient: OkHttpClient by lazy { OkHttpClient() }

        // The upload is deliberately mapped to a narrow band rather than the full bar: on a
        // typical voice note it's a small fraction of the total wall time (the server-side
        // transcription dominates), so letting it run to 90% would just move the "frozen bar"
        // problem to a higher number.
        //
        // Public because the UI has to map a raw progress value back onto "x% of the upload" to
        // label the upload phase - keeping the band private would mean duplicating these two
        // numbers in the service, where they could silently drift out of sync with the mapping
        // actually being applied here.
        const val UPLOAD_PROGRESS_START = 0.05f
        const val UPLOAD_PROGRESS_END = 0.35f
    }

    /**
     * Wraps a [RequestBody] and reports how many bytes have actually been handed to the socket.
     * OkHttp has no built-in upload-progress hook; this is the standard way to get one.
     *
     * The callback fires per write (~8 KB chunks), i.e. potentially hundreds of times per second
     * on a fast connection - throttling is the *consumer's* job, and the service already funnels
     * every status change through one throttled path.
     */
    private class CountingRequestBody(
        private val delegate: RequestBody,
        private val onBytesWritten: (written: Long, total: Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()

        override fun contentLength() = delegate.contentLength()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            val countingSink = object : ForwardingSink(sink) {
                private var written = 0L

                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onBytesWritten(written, total)
                }
            }
            // The delegate must write into a *buffered* sink - MultipartBody writes headers and
            // boundaries via BufferedSink methods that a raw Sink doesn't offer. flush() rather
            // than close(): closing here would also close the underlying socket sink that OkHttp
            // still owns and intends to read the response from.
            val bufferedSink = countingSink.buffer()
            delegate.writeTo(bufferedSink)
            bufferedSink.flush()
        }
    }

    /**
     * Which part of a server transcription is currently happening. Exists because the two parts
     * have fundamentally different progress characteristics and must be presented differently:
     * [UPLOADING] has a known total size and therefore a real, moving percentage, while
     * [PROCESSING] is a single opaque wait on the server with no progress signal at all - the
     * ASR webservice reports nothing until it's completely done.
     *
     * Showing a *static* percentage during [PROCESSING] was the actual user-visible bug: the
     * display sat at "30%" for minutes on end, which is indistinguishable from a crash. The UI
     * uses this phase to switch to an elapsed-time display plus an indeterminate progress bar
     * instead of a frozen number.
     */
    enum class Phase { UPLOADING, PROCESSING }

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

    // The ASR webservice has no way to cancel an in-flight transcription - it's a blocking CPU
    // loop, and closing the HTTP connection to /v1/transcribe does not stop it server-side. This
    // asks the gateway to restart that model's container instead, which actually frees the CPU.
    // Deliberately best-effort: called from cancelQueueItem right as the local transcribe() call
    // is itself being cancelled, so failures here are logged and swallowed rather than surfaced -
    // there's no user-facing action to take on a failed cancel, the item is already gone from the
    // queue either way.
    suspend fun cancel(baseUrl: String, model: String, timeoutMs: Long = 10_000): Unit = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("model", model).toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("${normalizeBaseUrl(baseUrl)}/v1/cancel")
                .post(body)
                .build()
            val cancelClient = client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
            executeAsync(cancelClient, request).use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(tag, "Server-side cancel for model \"$model\" returned HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(tag, "Server-side cancel for model \"$model\" failed: ${e.message}")
        }
    }

    // Real transcription can legitimately take well beyond OkHttp's default 10s read timeout
    // (a cold model load alone measured 25-30s against our own reference server) - using the
    // plain default client here was the actual bug behind a user-visible symptom: the server
    // attempt would show progress, then silently time out and fall back to local a few seconds
    // in, even though the server would have finished successfully given more time.
    suspend fun transcribe(
        baseUrl: String,
        model: String,
        audioFile: File,
        language: String,
        // Kept in sync with the gateway's own DEFAULT_TIMEOUT_MS (audioscribe-server
        // src/lib/whisperClient.mjs) - if this is shorter than the server's timeout, the app
        // gives up and falls back to local while the server keeps burning CPU on a transcription
        // nobody is waiting for anymore.
        timeoutMs: Long = 240_000,
        onProgress: (Float) -> Unit,
        // Defaulted so the other call site (LocalTranscriptionEngine's sequential fallback path,
        // which drives a plain local-style progress bar) doesn't have to care about phases.
        onPhase: (Phase) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        onProgress(UPLOAD_PROGRESS_START)
        onPhase(Phase.UPLOADING)

        val audioMediaType = "audio/*".toMediaTypeOrNull()
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", audioFile.name, audioFile.asRequestBody(audioMediaType))
            .addFormDataPart("model", model)
            .addFormDataPart("language", language)
            .build()

        // Everything from here to the server's reply used to be one opaque blocking call that
        // reported nothing. Counting the bytes as they're written turns the upload half into a
        // real, moving progress bar - and, more importantly, makes the *transition* to "server
        // is now computing" observable at all, which is the single most useful thing to tell a
        // user waiting on a transcription: the audio definitely arrived.
        var phaseSwitched = false
        val requestBody = CountingRequestBody(multipartBody) { written, total ->
            if (total > 0) {
                val fraction = (written.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                onProgress(
                    (UPLOAD_PROGRESS_START + fraction * (UPLOAD_PROGRESS_END - UPLOAD_PROGRESS_START)).toFloat()
                )
            }
            // Fires once, when the last byte has gone out. OkHttp can legitimately call writeTo()
            // more than once (retry after an early failure, redirect), so this is guarded rather
            // than assumed to happen exactly once per transcribe() call.
            if (written >= total && total > 0 && !phaseSwitched) {
                phaseSwitched = true
                onPhase(Phase.PROCESSING)
            }
        }

        val request = Request.Builder()
            .url("${normalizeBaseUrl(baseUrl)}/v1/transcribe")
            .post(requestBody)
            .build()

        val transcribeClient = client.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()

        val response = executeAsync(transcribeClient, request)
        // Belt and braces: a body small enough to be buffered whole, or an upload whose final
        // write didn't reach the branch above, must still not leave the UI stuck in the upload
        // phase once the request is demonstrably out the door.
        if (!phaseSwitched) {
            phaseSwitched = true
            onPhase(Phase.PROCESSING)
        }
        onProgress(0.9f)

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
