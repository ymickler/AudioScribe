package com.example.engine

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Unit tests for [ServerTranscriptionEngine]. There is no MockWebServer (or similar) dependency
 * wired up in this project yet, so instead of adding one, these tests fake HTTP responses with a
 * plain OkHttp application [okhttp3.Interceptor] that short-circuits the call and returns a
 * canned [Response] without ever touching the network - the engine's `client` constructor
 * parameter exists specifically to allow this kind of injection in tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerTranscriptionEngineTest {

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()
    }

    private fun jsonResponse(request: Request, code: Int, body: String): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    // ---- healthCheck ----

    @Test
    fun `healthCheck returns true on HTTP 200`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 200, "") }
        val engine = ServerTranscriptionEngine(client)

        assertTrue(engine.healthCheck("http://fake-server:8000"))
    }

    @Test
    fun `healthCheck returns false on non-200 response`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 503, "") }
        val engine = ServerTranscriptionEngine(client)

        assertFalse(engine.healthCheck("http://fake-server:8000"))
    }

    @Test
    fun `healthCheck returns false and does not throw on network failure`() = runTest {
        val client = fakeClient { throw IOException("simulated timeout") }
        val engine = ServerTranscriptionEngine(client)

        assertFalse(engine.healthCheck("http://fake-server:8000"))
    }

    @Test
    fun `healthCheck returns false for blank url without making a request`() = runTest {
        val client = fakeClient { throw AssertionError("should not perform a request for a blank url") }
        val engine = ServerTranscriptionEngine(client)

        assertFalse(engine.healthCheck(""))
    }

    // ---- fetchModels ----

    @Test
    fun `fetchModels parses id and label fields on success`() = runTest {
        val body = """{"models": [{"id": "tiny", "label": "Tiny"}, {"id": "german-small", "label": "German (Small)"}]}"""
        val client = fakeClient { request -> jsonResponse(request, 200, body) }
        val engine = ServerTranscriptionEngine(client)

        val models = engine.fetchModels("http://fake-server:8000")

        assertEquals(2, models.size)
        assertEquals(ServerTranscriptionEngine.ServerModel("tiny", "Tiny"), models[0])
        assertEquals(ServerTranscriptionEngine.ServerModel("german-small", "German (Small)"), models[1])
    }

    @Test
    fun `fetchModels returns empty list on non-2xx response`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 500, "") }
        val engine = ServerTranscriptionEngine(client)

        assertTrue(engine.fetchModels("http://fake-server:8000").isEmpty())
    }

    @Test
    fun `fetchModels returns empty list on malformed json instead of throwing`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 200, "not valid json") }
        val engine = ServerTranscriptionEngine(client)

        assertTrue(engine.fetchModels("http://fake-server:8000").isEmpty())
    }

    @Test
    fun `fetchModels returns empty list on network failure`() = runTest {
        val client = fakeClient { throw IOException("simulated network error") }
        val engine = ServerTranscriptionEngine(client)

        assertTrue(engine.fetchModels("http://fake-server:8000").isEmpty())
    }

    // ---- transcribe ----

    @Test
    fun `transcribe returns text field on success`() = runTest {
        val body = """{"text": "hello from the server"}"""
        val client = fakeClient { request -> jsonResponse(request, 200, body) }
        val engine = ServerTranscriptionEngine(client)
        val audioFile = File.createTempFile("audio", ".ogg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        var lastProgress = -1f
        val text = engine.transcribe(
            baseUrl = "http://fake-server:8000",
            model = "tiny",
            audioFile = audioFile,
            language = "en",
            onProgress = { lastProgress = it }
        )

        assertEquals("hello from the server", text)
        assertEquals(1.0f, lastProgress)
        audioFile.delete()
    }

    @Test(expected = Exception::class)
    fun `transcribe throws on non-2xx response`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 500, "server error") }
        val engine = ServerTranscriptionEngine(client)
        val audioFile = File.createTempFile("audio", ".ogg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        try {
            engine.transcribe(
                baseUrl = "http://fake-server:8000",
                model = "tiny",
                audioFile = audioFile,
                language = "en",
                onProgress = {}
            )
        } finally {
            audioFile.delete()
        }
    }

    @Test(expected = Exception::class)
    fun `transcribe throws on network failure`() = runTest {
        val client = fakeClient { throw IOException("simulated network error") }
        val engine = ServerTranscriptionEngine(client)
        val audioFile = File.createTempFile("audio", ".ogg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        try {
            engine.transcribe(
                baseUrl = "http://fake-server:8000",
                model = "tiny",
                audioFile = audioFile,
                language = "en",
                onProgress = {}
            )
        } finally {
            audioFile.delete()
        }
    }
}
