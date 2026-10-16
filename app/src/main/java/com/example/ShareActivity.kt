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
                // Copy while this activity still holds the sender's read grant; handing the
                // service only a content:// URI proved unreliable (WhatsApp's provider denied it).
                // The activity stays alive until the copy is done, then forwards a local file.
                Thread {
                    val local = copyToCache(audioUri)
                    runOnUiThread {
                        if (local != null) {
                            enqueueToTranscriptionService(local)
                        } else {
                            // Fall back to the grant-forwarding path as a last resort.
                            enqueueToTranscriptionService(audioUri)
                        }
                        finish()
                    }
                }.start()
                return
            } else {
                Toast.makeText(this, "No shared audio found", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun copyToCache(source: Uri): Uri? = try {
        val file = java.io.File(cacheDir, "shared_audio_${System.currentTimeMillis()}.cache")
        contentResolver.openInputStream(source)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: return null
        Uri.fromFile(file)
    } catch (e: Exception) {
        android.util.Log.e("ShareActivity", "Could not copy shared audio", e)
        null
    }

    private fun enqueueToTranscriptionService(audioUri: Uri) {
        val serviceIntent = Intent(this, TranscriptionOverlayService::class.java).apply {
            putExtra(TranscriptionOverlayService.EXTRA_AUDIO_URI, audioUri.toString())
            // The read grant WhatsApp gives us is tied to this activity and only transfers to the
            // service if the URI travels as intent data/ClipData together with the grant flag -
            // a plain string extra carries no permission, so the service would be denied.
            data = audioUri
            clipData = android.content.ClipData.newRawUri("shared_audio", audioUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
