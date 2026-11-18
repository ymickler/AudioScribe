package com.example.engine

import com.example.data.PcProfile
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcTranscriptionEngineTest {
    private val profile = PcProfile("https://pc.example", "test-token", "test-only")
    private fun engine(handler: (Request) -> Pair<Int, String>) = PcTranscriptionEngine(profile,
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer test-token", request.header("Authorization"))
            val (code, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    private fun audio() = File.createTempFile("pc-audio", ".tmp").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun healthRequiresReadyWorker() = runBlocking {
        assertFalse(engine { 200 to "{\"ready\":false}" }.healthCheck())
        assertTrue(engine { 200 to "{\"ready\":true}" }.healthCheck())
        assertFalse(engine { 503 to "{}" }.healthCheck())
    }
    @Test fun lostUploadReplyPollsSameIdWithoutUploadingAgain() = runBlocking {
        val methods = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val pc = engine {
            methods.add(it.method); paths.add(it.url.encodedPath)
            if (it.method == "PUT") throw IOException("reply lost after acceptance")
            200 to "{\"status\":\"done\",\"text\":\"hello\"}"
        }
        val file = audio()
        try { assertEquals("hello", pc.transcribe(file, "en", {}, {})) }
        finally { file.delete() }
        assertEquals(listOf("PUT", "GET"), methods)
        assertEquals(1, paths.distinct().size)
    }
    @Test fun missingJobIsCancelledBeforeSafeFallback() = runBlocking {
        val methods = mutableListOf<String>()
        val pc = engine {
            methods.add(it.method)
            when (it.method) { "PUT" -> 200 to "{}"; "GET" -> 404 to "{}"; else -> 200 to "{\"status\":\"cancelled\"}" }
        }
        val file = audio()
        try {
            try { pc.transcribe(file, "de", {}, {}); fail("must fail") }
            catch (_: IOException) { }
        } finally { file.delete() }
        assertEquals(listOf("PUT", "GET", "DELETE"), methods)
    }
    @Test fun ambiguousJobNeverTurnsIntoOrdinaryFallbackError() = runBlocking {
        val pc = engine {
            if (it.method == "PUT") 200 to "{}" else throw IOException("offline")
        }
        val file = audio()
        try {
            try { pc.transcribe(file, "de", {}, {}); fail("must fail") }
            catch (_: PendingPcJobException) { }
        } finally { file.delete() }
    }
}
