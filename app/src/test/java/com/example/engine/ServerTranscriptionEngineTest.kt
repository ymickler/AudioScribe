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
    fun `fetchModels parses optional sizeMb, ramMb and speedNote metadata`() = runTest {
        val body = """{"models": [{"id": "german-small", "label": "German Small", "sizeMb": 950, "ramMb": 1200, "speedNote": "good balance"}]}"""
        val client = fakeClient { request -> jsonResponse(request, 200, body) }
        val engine = ServerTranscriptionEngine(client)

        val models = engine.fetchModels("http://fake-server:8000")

        assertEquals(1, models.size)
        assertEquals(
            ServerTranscriptionEngine.ServerModel("german-small", "German Small", sizeMb = 950, ramMb = 1200, speedNote = "good balance"),
            models[0]
        )
    }

    @Test
    fun `fetchModels defaults optional metadata when a server omits it`() = runTest {
        val body = """{"models": [{"id": "tiny", "label": "Tiny"}]}"""
        val client = fakeClient { request -> jsonResponse(request, 200, body) }
        val engine = ServerTranscriptionEngine(client)

        val model = engine.fetchModels("http://fake-server:8000").single()

        assertEquals(0, model.sizeMb)
        assertEquals(0, model.ramMb)
        assertTrue(model.speedNote.isEmpty())
    }

    @Test
    fun `fetchModels returns empty list when models array is missing`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 200, "{}") }
        val engine = ServerTranscriptionEngine(client)

        assertTrue(engine.fetchModels("http://fake-server:8000").isEmpty())
    }

    // Unlike healthCheck, fetchModels is driven by a user-visible "Test Connection" action whose
    // caller already distinguishes success from failure - so a non-2xx, malformed body, or
    // network error must throw rather than silently look like "connected, zero models" (that
    // ambiguity is exactly what caused a real cleartext-blocked connection to be misreported as
    // an empty model list instead of a connection failure).

    @Test(expected = Exception::class)
    fun `fetchModels throws on non-2xx response`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 500, "") }
        val engine = ServerTranscriptionEngine(client)

        engine.fetchModels("http://fake-server:8000")
    }

    @Test(expected = Exception::class)
    fun `fetchModels throws on malformed json`() = runTest {
        val client = fakeClient { request -> jsonResponse(request, 200, "not valid json") }
        val engine = ServerTranscriptionEngine(client)

        engine.fetchModels("http://fake-server:8000")
    }

    @Test(expected = Exception::class)
    fun `fetchModels throws on network failure`() = runTest {
        val client = fakeClient { throw IOException("simulated network error") }
        val engine = ServerTranscriptionEngine(client)

        engine.fetchModels("http://fake-server:8000")
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

    @Test
    fun `transcribe reports upload progress within the upload band`() = runTest {
        val body = """{"text": "ok"}"""
        // The interceptor short-circuits before OkHttp would write the request body, so the
        // upload is simulated here by writing it out explicitly - that is what drives the
        // CountingRequestBody's sink and therefore the upload progress callbacks.
        val client = fakeClient { request ->
            request.body?.writeTo(okio.Buffer())
            jsonResponse(request, 200, body)
        }
        val engine = ServerTranscriptionEngine(client)
        val audioFile = File.createTempFile("audio", ".ogg")
            .apply { writeBytes(ByteArray(64 * 1024) { it.toByte() }) }

        val progressValues = mutableListOf<Float>()
        engine.transcribe(
            baseUrl = "http://fake-server:8000",
            model = "tiny",
            audioFile = audioFile,
            language = "en",
            onProgress = { progressValues.add(it) }
        )

        val uploadValues = progressValues.filter {
            it > ServerTranscriptionEngine.UPLOAD_PROGRESS_START &&
                it <= ServerTranscriptionEngine.UPLOAD_PROGRESS_END
        }
        assertTrue(
            "expected several distinct upload progress values, got $progressValues",
            uploadValues.distinct().size > 1
        )
        assertEquals(
            "upload progress must be monotonically increasing",
            uploadValues.sorted(),
            uploadValues
        )
        assertEquals(1.0f, progressValues.last())
        audioFile.delete()
    }

    @Test
    fun `transcribe switches from upload phase to processing phase`() = runTest {
        val client = fakeClient { request ->
            request.body?.writeTo(okio.Buffer())
            jsonResponse(request, 200, """{"text": "ok"}""")
        }
        val engine = ServerTranscriptionEngine(client)
        val audioFile = File.createTempFile("audio", ".ogg")
            .apply { writeBytes(ByteArray(16 * 1024)) }

        val phases = mutableListOf<ServerTranscriptionEngine.Phase>()
        engine.transcribe(
            baseUrl = "http://fake-server:8000",
            model = "tiny",
            audioFile = audioFile,
            language = "en",
            onProgress = {},
            onPhase = { phases.add(it) }
        )

        assertEquals(
            listOf(
                ServerTranscriptionEngine.Phase.UPLOADING,
                ServerTranscriptionEngine.Phase.PROCESSING
            ),
            phases
        )
        audioFile.delete()
    }

    @Test
    fun `transcribe still reaches the processing phase when the body is never written`() = runTest {
        // A short-circuited call (or a buffered body OkHttp never streams) must not leave the UI
        // stuck in the upload phase - the fallback after the request completes covers that.
        val client = fakeClient { request -> jsonResponse(request, 200, """{"text": "ok"}""") }
        val engine = ServerTranscriptionEngine(client)
        val audioFile = File.createTempFile("audio", ".ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val phases = mutableListOf<ServerTranscriptionEngine.Phase>()
        engine.transcribe(
            baseUrl = "http://fake-server:8000",
            model = "tiny",
            audioFile = audioFile,
            language = "en",
            onProgress = {},
            onPhase = { phases.add(it) }
        )

        assertTrue(phases.contains(ServerTranscriptionEngine.Phase.PROCESSING))
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
