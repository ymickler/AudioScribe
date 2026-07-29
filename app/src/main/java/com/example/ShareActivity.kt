package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.example.service.TranscriptionOverlayService

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settingsManager = com.example.DependencyProvider.getSettingsManager(this)
        val showAsNotification = settingsManager.showAsNotification

        // Check overlay permission ONLY if using Overlay Mode (not notification mode)
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !showAsNotification) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        if (!hasOverlayPermission) {
            Toast.makeText(
                this,
                "Please grant overlay permission in " + getString(R.string.app_name) + " first",
                Toast.LENGTH_LONG
            ).show()
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(mainIntent)
            finish()
            return
        }

        // Handle the incoming share intent. WhatsApp only ever sends a single-file ACTION_SEND
        // for voice messages (it doesn't support multi-selecting several voice notes to share at
        // once - only forwarding them one at a time, which is also a single-file ACTION_SEND),
        // so ACTION_SEND_MULTIPLE isn't handled: it would be dead code for this app's actual use.
        if (intent?.action == Intent.ACTION_SEND) {
            val audioUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }

            if (audioUri != null) {
                enqueueToTranscriptionService(audioUri)
            } else {
                Toast.makeText(this, "No shared audio found", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun enqueueToTranscriptionService(audioUri: Uri) {
        val serviceIntent = Intent(this, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, audioUri.toString())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
