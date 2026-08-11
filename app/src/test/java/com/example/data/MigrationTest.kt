package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The destructive migration fallback is deliberately gone, so a broken migration no longer fails
 * silently - it either crashes or loses the user's entire transcription history. This test walks
 * a real v1 database (the schema that shipped before live status columns existed) all the way up
 * and checks the old rows are still there and readable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val dbName = "migration_test.db"

    private fun createV1Database(context: Context) {
        context.deleteDatabase(dbName)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `transcriptions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`audioUri` TEXT NOT NULL, " +
                "`transcribedText` TEXT NOT NULL, " +
                "`timestamp` INTEGER NOT NULL, " +
                "`language` TEXT NOT NULL, " +
                "`duration` INTEGER NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO transcriptions (audioUri, transcribedText, timestamp, language, duration) " +
                "VALUES ('content://old/1', 'Alte Transkription', 1000, 'de', 0)"
        )
        db.version = 1
        db.close()
    }

    @Test
    fun oldHistorySurvivesMigrationToCurrentSchema() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        createV1Database(context)

        val db = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.migrations())
            .allowMainThreadQueries()
            .build()

        val rows = TranscriptionRepository(db.transcriptionDao()).allTranscriptions.first()
        assertEquals(1, rows.size)
        assertEquals("Alte Transkription", rows[0].transcribedText)
        // Rows written before the status columns existed must read as ordinary finished entries,
        // not as forever-running cards.
        assertEquals(TranscriptionEntity.STATUS_COMPLETED, rows[0].status)
        assertEquals(0f, rows[0].progress, 0.001f)

        // The v2 -> v3 index has to exist under exactly the name Room derives from @Index,
        // otherwise Room's schema validation rejects the database on open.
        val cursor = db.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_transcriptions_timestamp'"
        )
        assertTrue("timestamp index missing after migration", cursor.moveToFirst())
        cursor.close()

        db.close()
        context.deleteDatabase(dbName)
    }
}
