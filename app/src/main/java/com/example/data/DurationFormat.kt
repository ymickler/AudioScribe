package com.example.data

import java.util.Locale

/**
 * How long a transcription took, rendered the same way everywhere it is shown (overlay card,
 * completion notification, history card).
 */
object DurationFormat {
    fun format(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000.0
        return if (totalSeconds < 60) {
            String.format(Locale.US, "%.1fs", totalSeconds)
        } else {
            val minutes = clamped / 60000
            val seconds = (clamped % 60000) / 1000
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
