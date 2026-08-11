package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.service.TranscriptionOverlayService
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.TranscriptionDao
import com.example.data.TranscriptionEntity
import com.example.data.TranscriptionRepository
import com.example.engine.LocalTranscriptionEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TranscriptionDao
    private lateinit var repository: TranscriptionRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // In-memory database config for hermetic test execution
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.transcriptionDao()
        repository = TranscriptionRepository(dao)
        DependencyProvider.setTestInstances(db, repository, null)
    }

    @After
    fun closeDb() {
        DependencyProvider.setTestInstances(null, null, null)
        db.close()
    }

    @Test
    fun testInsertAndRetrieveTranscriptions() = runTest {
        val entry = TranscriptionEntity(
            audioUri = "content://media/external/audio/media/1",
            transcribedText = "Hallo, wie geht es dir?",
            language = "de"
        )

        val id = repository.insert(entry)
        assertTrue(id > 0)

        val allItems = repository.allTranscriptions.first()
        assertEquals(1, allItems.size)
        assertEquals("Hallo, wie geht es dir?", allItems[0].transcribedText)
        assertEquals("de", allItems[0].language)
    }

    @Test
    fun testSearchTranscriptionsFilter() = runTest {
        val entry1 = TranscriptionEntity(
            audioUri = "content://media/external/audio/media/1",
            transcribedText = "Meeting um achtzehn Uhr",
            language = "de"
        )
        val entry2 = TranscriptionEntity(
            audioUri = "content://media/external/audio/media/2",
            transcribedText = "Einkaufsliste mit Milch",
            language = "de"
        )

        repository.insert(entry1)
        repository.insert(entry2)

        // Search for "Milch"
        val searchResult1 = repository.search("Milch").first()
        assertEquals(1, searchResult1.size)
        assertEquals("Einkaufsliste mit Milch", searchResult1[0].transcribedText)

        // Search for "Uhr"
        val searchResult2 = repository.search("Uhr").first()
        assertEquals(1, searchResult2.size)
        assertEquals("Meeting um achtzehn Uhr", searchResult2[0].transcribedText)
    }

    @Test
    fun testDeleteTranscription() = runTest {
        val entry = TranscriptionEntity(
            audioUri = "content://media/external/audio/media/1",
            transcribedText = "Delete this message please",
            language = "en"
        )

        val id = repository.insert(entry).toInt()
        val beforeDelete = repository.allTranscriptions.first()
        assertEquals(1, beforeDelete.size)

        repository.deleteById(id)
        val afterDelete = repository.allTranscriptions.first()
        assertEquals(0, afterDelete.size)
    }

    @Test
    fun testLocalTranscriptionEngineSimulation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        val engine = LocalTranscriptionEngine(context, testDispatcher)
        val fakeUri = Uri.parse("mock://audio/whatsapp_voice_message_de")

        var startedCalled = false
        var progressReported = false
        var completedText = ""

        engine.transcribeAudio(fakeUri, object : LocalTranscriptionEngine.TranscriptionCallback {
            override fun onStart() {
                startedCalled = true
            }

            override fun onProgress(progress: Float) {
                progressReported = true
            }

            override fun onPartialResult(text: String) {
                // partial text streaming
            }

            override fun onComplete(fullText: String) {
                completedText = fullText
            }

            override fun onError(error: String) {
                // handle error
            }
        })

        // Advance time to execute all coroutine delays immediately in virtual time
        testScheduler.advanceUntilIdle()

        assertTrue("Should have started transcription", startedCalled)
        assertTrue("Should have reported progress", progressReported)
        assertTrue("Should have transcribed text", completedText.isNotEmpty())
    }

    @Test
    fun testLocalTranscriptionEngineCancellation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        val engine = LocalTranscriptionEngine(context, testDispatcher)
        val fakeUri = Uri.parse("mock://audio/whatsapp_voice_message_de")

        var errorCalled = false
        var completedCalled = false

        val job = engine.transcribeAudio(fakeUri, object : LocalTranscriptionEngine.TranscriptionCallback {
            override fun onStart() {}
            override fun onProgress(progress: Float) {}
            override fun onPartialResult(text: String) {}
            override fun onComplete(fullText: String) {
                completedCalled = true
            }
            override fun onError(error: String) {
                errorCalled = true
            }
        })

        // Cancel the job immediately before it finishes
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertTrue("Job should be cancelled", job.isCancelled)
        assertFalse("onError should not be called upon cancellation", errorCalled)
        assertFalse("onComplete should not be called upon cancellation", completedCalled)
    }

    @Test
    fun testTranscriptionOverlayServiceLifecycleAndActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/test")
        }
        
        val controller = org.robolectric.Robolectric.buildService(TranscriptionOverlayService::class.java, serviceIntent)
        controller.create()
        controller.startCommand(0, 1)
        val service = controller.get()
        
        assertNotNull(service)
        
        // Test Cancel Action
        val cancelIntent = Intent(context, TranscriptionOverlayService::class.java).apply {
            action = TranscriptionOverlayService.ACTION_CANCEL_TRANSCRIPTION
        }
        service.onStartCommand(cancelIntent, 0, 2)
        
        controller.destroy()
    }

    @Test
    fun testServiceQueueSequentialProcessing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent1 = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/whatsapp_de_1")
        }
        val controller = org.robolectric.Robolectric.buildService(TranscriptionOverlayService::class.java, serviceIntent1)
        controller.create()
        val service = controller.get()
        service.onStartCommand(serviceIntent1, 0, 1)

        val serviceIntent2 = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/whatsapp_de_2")
        }
        service.onStartCommand(serviceIntent2, 0, 2)

        val serviceIntent3 = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/whatsapp_de_3")
        }
        service.onStartCommand(serviceIntent3, 0, 3)

        // Filtered to completed rows: every queued item also gets a "running" placeholder row
        // right away (so the app can show it as a live card), which would otherwise satisfy this
        // wait before anything was actually transcribed.
        var allItems: List<TranscriptionEntity> = emptyList()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 6000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            allItems = repository.allTranscriptions.first().filter { it.status == TranscriptionEntity.STATUS_COMPLETED }
            if (allItems.size == 3) break
            kotlinx.coroutines.delay(100)
        }

        assertEquals(3, allItems.size)
        controller.destroy()
    }

    @Test
    fun testRunningItemAppearsInHistoryBeforeCompletion() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/whatsapp_de_1")
        }
        val controller = org.robolectric.Robolectric.buildService(TranscriptionOverlayService::class.java, serviceIntent)
        controller.create()
        controller.get().onStartCommand(serviceIntent, 0, 1)

        var sawRunning = false
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 6000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (repository.allTranscriptions.first().any { it.status == TranscriptionEntity.STATUS_RUNNING }) {
                sawRunning = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        assertTrue("A running transcription must show up in the history right away", sawRunning)

        var completed: List<TranscriptionEntity> = emptyList()
        val completionStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - completionStart < 6000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            completed = repository.allTranscriptions.first().filter { it.status == TranscriptionEntity.STATUS_COMPLETED }
            if (completed.isNotEmpty()) break
            kotlinx.coroutines.delay(100)
        }

        // The same row is updated in place - a finished transcription must not leave its running
        // placeholder behind as a second card.
        assertEquals(1, completed.size)
        assertEquals(1, repository.allTranscriptions.first().size)
        controller.destroy()
    }

    @Test
    fun testServiceQueueCancellation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceIntent1 = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/whatsapp_de_1")
        }
        val controller = org.robolectric.Robolectric.buildService(TranscriptionOverlayService::class.java, serviceIntent1)
        controller.create()
        val service = controller.get()
        service.onStartCommand(serviceIntent1, 0, 1)

        val serviceIntent2 = Intent(context, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, "mock://audio/whatsapp_de_2")
        }
        service.onStartCommand(serviceIntent2, 0, 2)

        val cancelIntent = Intent(context, TranscriptionOverlayService::class.java).apply {
            action = TranscriptionOverlayService.ACTION_CANCEL_TRANSCRIPTION
        }
        service.onStartCommand(cancelIntent, 0, 3)

        var allItems: List<TranscriptionEntity> = emptyList()
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < 6000) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            allItems = repository.allTranscriptions.first().filter { it.status == TranscriptionEntity.STATUS_COMPLETED }
            if (allItems.size == 1) break
            kotlinx.coroutines.delay(100)
        }

        assertEquals(1, allItems.size)
        controller.destroy()
    }
}
