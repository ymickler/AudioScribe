package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionDao {

    @Query("SELECT * FROM transcriptions ORDER BY timestamp DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions WHERE transcribedText LIKE :query ORDER BY timestamp DESC")
    fun searchTranscriptions(query: String): Flow<List<TranscriptionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscription(transcription: TranscriptionEntity): Long

    // Live progress updates for a still-running row. Deliberately not touching transcribedText so
    // partial results and progress can be written independently of each other.
    @Query("UPDATE transcriptions SET status = :status, statusText = :statusText, progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Int, status: String, statusText: String, progress: Float)

    @Query("UPDATE transcriptions SET transcribedText = :text WHERE id = :id")
    suspend fun updateText(id: Int, text: String)

    // Safety net for a service that was killed mid-run (crash, swipe-away, low memory): without
    // this, its row would stay "running" forever and show a progress bar nothing is driving.
    @Query("UPDATE transcriptions SET status = '${TranscriptionEntity.STATUS_ERROR}' WHERE status = '${TranscriptionEntity.STATUS_RUNNING}' AND timestamp < :olderThan")
    suspend fun failStaleRunning(olderThan: Long)

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteTranscriptionById(id: Int)

    @Query("DELETE FROM transcriptions")
    suspend fun clearAll()
}
