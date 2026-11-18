package com.example.engine

import com.example.data.PcProfile
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A lost response after upload must not silently start a second transcription elsewhere. */
class PendingPcJobException : IOException("PC connection lost; the job may still be running. Retry when the PC is reachable.")

class PcTranscriptionEngine(private val profile: PcProfile, clientOverride: OkHttpClient? = null) {
    private val client: OkHttpClient = clientOverride ?: secureClient()
    private fun secureClient(): OkHttpClient {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(profile.caPem.byteInputStream())
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null); setCertificateEntry("pc", cert)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory, trust)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(2, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS).build()
    }
    private fun request(path: String) = Request.Builder().url(profile.url + path)
        .header("Authorization", "Bearer ${profile.token}")

    private suspend fun execute(request: Request, http: OkHttpClient = client): Response =
        suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }

    suspend fun healthCheck(): Boolean = withContext(Dispatchers.IO) {
        try {
            val short = client.newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
            execute(request("/health").get().build(), short).use {
                it.isSuccessful && JSONObject(it.body!!.string()).optBoolean("ready", false)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    suspend fun transcribe(file: File, language: String, onProgress: (Float) -> Unit,
                           onPhase: (ServerTranscriptionEngine.Phase) -> Unit): String = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().replace("-", "")
        val path = "/v1/jobs/$id"
        var submitted = false
        var finished = false
        try {
            onPhase(ServerTranscriptionEngine.Phase.UPLOADING)
            val body = object : RequestBody() {
                private val source = file.asRequestBody("application/octet-stream".toMediaType())
                override fun contentType() = source.contentType()
                override fun contentLength() = source.contentLength()
                override fun writeTo(sink: okio.BufferedSink) {
                    var written = 0L
                    file.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val size = input.read(buffer)
                            if (size < 0) break
                            sink.write(buffer, 0, size)
                            written += size
                            onProgress(0.05f + 0.30f * written.toFloat() / file.length().coerceAtLeast(1))
                        }
                    }
                }
            }
            // Set before sending: if the response is lost, query this exact ID instead of re-uploading.
            submitted = true
            try {
                execute(request(path).header("X-Language", language).put(body).build()).use {
                    if (!it.isSuccessful) {
                        if (it.code in listOf(400, 401, 403, 409, 413, 422, 503, 507)) {
                            submitted = false
                            throw IOException("PC upload rejected (${it.code})")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: IOException) { if (!submitted) throw e }
            onProgress(0.35f)
            onPhase(ServerTranscriptionEngine.Phase.PROCESSING)
            var failures = 0
            val deadline = android.os.SystemClock.elapsedRealtime() + 30 * 60 * 1000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                try {
                    val state = execute(request(path).get().build()).use {
                        if (it.code == 404) {
                            // Upload processing may still be in progress: a cancellation tombstone
                            // must be acknowledged before another server can receive the audio.
                            execute(request(path).delete().build()).use { cancelled ->
                                if (!cancelled.isSuccessful) throw PendingPcJobException()
                            }
                            finished = true
                            throw IOException("PC did not accept the upload")
                        }
                        if (!it.isSuccessful) throw IOException("PC status unavailable")
                        JSONObject(it.body!!.string())
                    }
                    failures = 0
                    when (state.getString("status")) {
                        "done" -> { finished = true; onProgress(1f); return@withContext state.getString("text") }
                        "error", "cancelled" -> { finished = true; throw IOException("PC job failed") }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (finished) throw e
                    if (++failures >= 3) throw PendingPcJobException()
                }
                delay(1000)
            }
            throw PendingPcJobException()
        } finally {
            if (submitted && !finished) withContext(NonCancellable) {
                runCatching { execute(request(path).delete().build()).close() }
            }
        }
    }
}
