package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val audioUri: String,
    val transcribedText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val language: String,
    val duration: Long = 0L,
    // A history row is created the moment a transcription starts (not only once it finishes), so
    // opening the app mid-run shows the running item as a live card that updates in place.
    val status: String = STATUS_COMPLETED,
    // Human-readable progress label ("Connecting to server...", "Transcribing... (42%)"), mirrored
    // from the overlay/notification so both surfaces show the same thing.
    val statusText: String = "",
    val progress: Float = 0f
) {
    val isRunning: Boolean get() = status == STATUS_RUNNING

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_ERROR = "error"
    }
}
