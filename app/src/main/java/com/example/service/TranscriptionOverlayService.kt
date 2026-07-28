package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.DependencyProvider
import com.example.data.TranscriptionEntity
import com.example.engine.LocalTranscriptionEngine
import com.example.engine.ModelDownloader
import com.example.engine.ServerTranscriptionEngine
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SleekBackground
import com.example.ui.theme.SleekInnerSurface
import com.example.ui.theme.SleekPrimary
import com.example.ui.theme.SleekSurface
import com.example.ui.theme.SleekText
import com.example.ui.theme.SleekButtonText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class TranscriptionOverlayService : Service() {

    companion object {
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        private const val NOTIFICATION_ID = 8888
        private const val CHANNEL_ID = "snapscripe_transcription_channel"
        const val ACTION_CANCEL_TRANSCRIPTION = "com.example.action.CANCEL_TRANSCRIPTION"
        const val ACTION_SWITCH_MODEL = "com.example.action.SWITCH_MODEL"
    }

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null

    // Dedicated Compose lifecycle owner
    private val lifecycleOwner = MyLifecycleOwner()

    data class QueueItem(
        val id: String,
        val originalUriString: String,
        var cachedUriString: String? = null,
        var status: String = "Waiting...",
        var progress: Float = 0f,
        var text: String = "",
        var isCompleted: Boolean = false,
        var isError: Boolean = false,
        var errorMsg: String = "",
        var currentModelType: ModelDownloader.ModelType? = null,
        var isServerAttempt: Boolean = false,
        var startedAtMs: Long = 0L,
        var durationLabel: String = "",
        var savedEntityId: Int? = null,
        var savedTimestamp: Long? = null,
        var activeJob: kotlinx.coroutines.Job? = null
    )

    // Queue state observed by Compose
    private val transcriptionQueue = mutableStateListOf<QueueItem>()
    private var isProcessingQueue = false

    private var installedModels by mutableStateOf<List<ModelDownloader.ModelType>>(emptyList())
    private var showModelMenu by mutableStateOf(false)
    private var cachedLocalAudioFile: File? = null
    private var currentSessionId = 0

    override fun onCreate() {
        super.onCreate()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureNotificationChannel()
    }

    // Channel importance is fixed the first time it's created, so it must only be
    // created once, and always with the same importance, or later changes are silently ignored.
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    // The content:// URI shared by another app (e.g. WhatsApp) only grants us read access
    // for a short-lived window tied to the sharing flow. Re-transcribing later (e.g. after
    // switching models from the overlay) would then fail with a permission denial, so the
    // source audio is copied into our own cache once, up front, and reused from there.
    private fun cacheAudioLocally(sourceUriString: String): String? {
        return try {
            val sourceUri = Uri.parse(sourceUriString)
            val cacheFile = File(cacheDir, "shared_audio_${System.currentTimeMillis()}.cache")
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            } ?: return null
            cachedLocalAudioFile = cacheFile
            Uri.fromFile(cacheFile).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_CANCEL_TRANSCRIPTION) {
            cancelOngoingTranscription()
            return START_NOT_STICKY
        } else if (action == ACTION_SWITCH_MODEL) {
            switchModelAndRestart()
            return START_NOT_STICKY
        }

        val audioUriString = intent?.getStringExtra(EXTRA_AUDIO_URI)
        if (audioUriString != null) {
            startForeground(NOTIFICATION_ID, createNotification())
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

            val settings = DependencyProvider.getSettingsManager(this)
            installedModels = ModelDownloader(this).installedModels()
            if (settings.showAsNotification) {
                val toastMsg = com.example.data.Localization.getString("toast_service_started", settings.uiLanguage)
                Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
            } else {
                showOverlay()
            }

            enqueueAudio(audioUriString)
        } else {
            if (transcriptionQueue.isEmpty()) {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun enqueueAudio(audioUriString: String) {
        val itemId = java.util.UUID.randomUUID().toString()
        val item = QueueItem(id = itemId, originalUriString = audioUriString)
        transcriptionQueue.add(item)
        
        CoroutineScope(Dispatchers.IO).launch {
            val cachedUri = cacheAudioLocally(audioUriString) ?: audioUriString
            CoroutineScope(Dispatchers.Main).launch {
                item.cachedUriString = cachedUri
                updateQueueItem(item)
                checkAndProcessQueue()
            }
        }
    }

    private fun checkAndProcessQueue() {
        // Safety net: `isProcessingQueue` is only ever meant to be true while some item's
        // coroutine is genuinely running. If a job dies in a way that skips both onComplete
        // and onError (e.g. an exception thrown by the callback itself, uncaught inside the
        // launch{} block), the flag could otherwise stay stuck true forever, wedging every
        // future item behind it at "Waiting in queue..." with nothing visibly happening. If
        // nothing in the queue actually has an active job, the flag is lying - fix it instead
        // of trusting it.
        if (isProcessingQueue && transcriptionQueue.none { it.activeJob != null }) {
            android.util.Log.w("TranscriptionOverlayService", "isProcessingQueue was true with no active job - resetting")
            isProcessingQueue = false
        }
        if (isProcessingQueue) return
        val nextItem = transcriptionQueue.firstOrNull { !it.isCompleted && !it.isError && it.activeJob == null }
        if (nextItem != null) {
            processQueueItem(nextItem)
        } else {
            val settings = DependencyProvider.getSettingsManager(this)
            if (settings.showAsNotification && transcriptionQueue.all { it.isCompleted || it.isError }) {
                stopSelf()
            }
        }
    }

    private fun processQueueItem(item: QueueItem) {
        isProcessingQueue = true
        val settings = DependencyProvider.getSettingsManager(this)
        item.startedAtMs = System.currentTimeMillis()
        item.status = com.example.data.Localization.getString("status_preparing", settings.uiLanguage)
        item.progress = 0.05f
        updateQueueItem(item)
        updateOverallProgressNotification()

        val uri = Uri.parse(item.cachedUriString ?: item.originalUriString)
        val engine = LocalTranscriptionEngine(this)
        val targetLanguage = settings.getTargetLanguageCode()

        val sessionId = ++currentSessionId
        val job = engine.transcribeAudio(uri, object : LocalTranscriptionEngine.TranscriptionCallback {
            override fun onStart() {
                if (sessionId != currentSessionId) return
                item.status = com.example.data.Localization.getString("status_preparing", settings.uiLanguage)
                item.progress = 0.05f
                updateQueueItem(item)
                updateOverallProgressNotification()
            }

            override fun onModelResolved(modelType: ModelDownloader.ModelType) {
                if (sessionId != currentSessionId) return
                item.currentModelType = modelType
                // Reached only when the local pipeline actually runs (either the server path was
                // never attempted, or it was attempted and failed and control fell through to the
                // local model resolution), so any earlier "server attempt" flag no longer reflects
                // what's actually happening.
                item.isServerAttempt = false
                updateQueueItem(item)
            }

            override fun onServerModelResolved(model: ServerTranscriptionEngine.ServerModel) {
                if (sessionId != currentSessionId) return
                item.isServerAttempt = true
                updateQueueItem(item)
            }

            override fun onProgress(progress: Float) {
                if (sessionId != currentSessionId) return
                val isWhisper = (item.currentModelType?.engine ?: settings.sttEngine) == "whisper"
                item.status = when {
                    item.isServerAttempt -> "Transcribing via server... (${(progress * 100).toInt()}%)"
                    isWhisper -> {
                        when {
                            progress < 0.15f -> "Loading Whisper model..."
                            progress < 0.4f -> "Analyzing audio waves..."
                            progress < 0.7f -> "Decoding speech patterns..."
                            progress < 0.9f -> "Synthesizing text..."
                            else -> "Finishing up..."
                        }
                    }
                    else -> "Transcribing... (${(progress * 100).toInt()}%)"
                }
                item.progress = progress
                updateQueueItem(item)
                updateOverallProgressNotification()
            }

            override fun onPartialResult(text: String) {
                if (sessionId != currentSessionId) return
                item.text = text
                updateQueueItem(item)
                updateOverallProgressNotification()
            }

            override fun onComplete(fullText: String) {
                if (sessionId != currentSessionId) return
                item.text = fullText
                item.progress = 1.0f
                item.status = "Completed"
                item.durationLabel = formatDuration(System.currentTimeMillis() - item.startedAtMs)
                item.isCompleted = true
                item.activeJob = null
                updateQueueItem(item)

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repository = DependencyProvider.getRepository(this@TranscriptionOverlayService)
                        val entity = TranscriptionEntity(
                            id = item.savedEntityId ?: 0,
                            audioUri = item.originalUriString,
                            transcribedText = fullText,
                            language = targetLanguage,
                            timestamp = item.savedTimestamp ?: System.currentTimeMillis()
                        )
                        val id = repository.insert(entity)
                        item.savedEntityId = id.toInt()
                        item.savedTimestamp = entity.timestamp
                        updateQueueItem(item)

                        CoroutineScope(Dispatchers.Main).launch {
                            showCompletedNotification(
                                this@TranscriptionOverlayService,
                                id.toInt(),
                                fullText,
                                item.durationLabel,
                                item.isServerAttempt,
                                item.currentModelType?.displayLabel
                            )
                            isProcessingQueue = false
                            checkAndProcessQueue()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        CoroutineScope(Dispatchers.Main).launch {
                            isProcessingQueue = false
                            checkAndProcessQueue()
                        }
                    }
                }
            }

            override fun onError(error: String) {
                if (sessionId != currentSessionId) return
                item.isError = true
                item.errorMsg = error
                item.status = "Error occurred"
                item.activeJob = null
                updateQueueItem(item)

                showErrorNotification(this@TranscriptionOverlayService, error)

                isProcessingQueue = false
                checkAndProcessQueue()
            }
        }, item.currentModelType)

        item.activeJob = job
        updateQueueItem(item)
    }

    private fun updateQueueItem(item: QueueItem) {
        val index = transcriptionQueue.indexOfFirst { it.id == item.id }
        if (index != -1) {
            transcriptionQueue[index] = item.copy()
        }
    }

    private fun cancelQueueItem(itemId: String) {
        val item = transcriptionQueue.find { it.id == itemId } ?: return
        item.activeJob?.cancel()
        transcriptionQueue.remove(item)

        if (item.activeJob != null) {
            isProcessingQueue = false
            checkAndProcessQueue()
        }

        if (transcriptionQueue.isEmpty()) {
            dismissOverlayAndStop()
        } else {
            updateOverallProgressNotification()
        }
    }

    private fun cancelOngoingTranscription() {
        val activeItem = transcriptionQueue.find { it.activeJob != null }
        if (activeItem != null) {
            cancelQueueItem(activeItem.id)
        } else {
            dismissOverlayAndStop()
        }
    }

    private fun switchModelAndRestart() {
        val activeItem = transcriptionQueue.find { it.activeJob != null } ?: return
        installedModels = ModelDownloader(this).installedModels()
        if (installedModels.size > 1) {
            val current = activeItem.currentModelType ?: resolveCurrentModelType()
            val currentIndex = installedModels.indexOf(current)
            val nextIndex = if (currentIndex != -1) {
                (currentIndex + 1) % installedModels.size
            } else {
                0
            }
            val nextModel = installedModels[nextIndex]

            activeItem.activeJob?.cancel()
            activeItem.activeJob = null
            activeItem.isCompleted = false
            activeItem.isError = false
            activeItem.text = ""
            activeItem.progress = 0f
            activeItem.currentModelType = nextModel
            // An explicit model switch always targets a specific local model (LocalTranscriptionEngine
            // skips the server path whenever a modelOverride is supplied), so the status text must
            // no longer claim the server is being used.
            activeItem.isServerAttempt = false
            updateQueueItem(activeItem)

            isProcessingQueue = false
            checkAndProcessQueue()
        } else {
            val settings = DependencyProvider.getSettingsManager(this)
            val toastMsg = if (settings.uiLanguage == "de") {
                "Keine weiteren Modelle installiert"
            } else {
                "No other models installed"
            }
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatDuration(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000.0
        return if (totalSeconds < 60) {
            String.format(java.util.Locale.US, "%.1fs", totalSeconds)
        } else {
            val minutes = clamped / 60000
            val seconds = (clamped % 60000) / 1000
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    private fun updateOverallProgressNotification() {
        val settings = DependencyProvider.getSettingsManager(this)
        if (!settings.showAsNotification) return

        val activeIndex = transcriptionQueue.indexOfFirst { it.activeJob != null }
        if (activeIndex == -1) return
        val activeItem = transcriptionQueue[activeIndex]

        val totalCount = transcriptionQueue.size
        val currentNum = activeIndex + 1

        val statusText = if (settings.uiLanguage == "de") {
            "Verarbeite $currentNum von $totalCount (${(activeItem.progress * 100).toInt()}%)"
        } else {
            "Processing $currentNum of $totalCount (${(activeItem.progress * 100).toInt()}%)"
        }

        updateForegroundNotification(statusText, activeItem.progress, activeItem.text)
    }

    private fun resolveCurrentModelType(): ModelDownloader.ModelType {
        val settings = DependencyProvider.getSettingsManager(this)
        val engineType = settings.sttEngine
        val langCode = settings.getTargetLanguageCode()
        return when {
            engineType == "whisper" -> {
                when (settings.whisperModelSize) {
                    "base" -> ModelDownloader.ModelType.WHISPER_BASE
                    "small" -> ModelDownloader.ModelType.WHISPER_SMALL
                    else -> ModelDownloader.ModelType.WHISPER_TINY
                }
            }
            langCode == "de" -> ModelDownloader.ModelType.VOSK_DE
            else -> ModelDownloader.ModelType.VOSK_EN
        }
    }

    private fun showOverlay() {
        if (composeView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 120 // Positioned nicely below status bar
        }

        composeView = ComposeView(this).apply {
            // Set up Jetpack Compose owners
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                MyApplicationTheme {
                    OverlayContent()
                }
            }
        }

        windowManager?.addView(composeView, params)
    }

    @Composable
    private fun OverlayContent() {
        val context = LocalContext.current
        val settings = remember { DependencyProvider.getSettingsManager(context) }
        val displayLang = remember {
            if (settings.getTargetLanguageCode() == "de") "Deutsch (DE)" else "English (EN)"
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = SleekSurface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = getString(R.string.app_name),
                            color = SleekPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        val queueCount = transcriptionQueue.size
                        val statusLabel = if (queueCount > 0) {
                            if (settings.uiLanguage == "de") {
                                "Warteschlange: $queueCount ${if (queueCount == 1) "Eintrag" else "Einträge"}"
                            } else {
                                "Queue: $queueCount ${if (queueCount == 1) "item" else "items"}"
                            }
                        } else {
                            if (settings.uiLanguage == "de") "Leer" else "Empty"
                        }
                        Text(
                            text = statusLabel,
                            color = SleekText.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(SleekBackground, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = displayLang,
                                color = SleekPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(
                            onClick = { 
                                dismissOverlayAndStop()
                            },
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SleekBackground)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close All",
                                tint = SleekText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    transcriptionQueue.forEachIndexed { index, item ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                        QueueItemRow(item, settings)
                    }
                }
            }
        }
    }

    @Composable
    private fun QueueItemRow(item: QueueItem, settings: com.example.data.SettingsManager) {
        val context = LocalContext.current
        var isExpanded by remember { mutableStateOf(false) }
        var canExpand by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val statusText = when {
                        item.isCompleted -> {
                            val base = if (settings.uiLanguage == "de") "Abgeschlossen" else "Completed"
                            if (item.durationLabel.isNotBlank()) "$base (${item.durationLabel})" else base
                        }
                        item.isError -> if (settings.uiLanguage == "de") "Fehler" else "Failed"
                        item.activeJob != null -> item.status
                        else -> if (settings.uiLanguage == "de") "In Warteschlange..." else "Waiting in queue..."
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusText,
                            color = if (item.isError) MaterialTheme.colorScheme.error else SleekPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        // Shown both while running and after completion (item.isServerAttempt /
                        // currentModelType aren't cleared on completion), so the source of a
                        // finished transcription stays visible, not just during processing.
                        val sourceBadge = when {
                            item.isServerAttempt -> com.example.data.Localization.getString("badge_server", settings.uiLanguage)
                            item.currentModelType != null -> item.currentModelType?.displayLabel
                            else -> null
                        }
                        if (sourceBadge != null && !item.isError) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SleekPrimary.copy(alpha = 0.18f))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = sourceBadge,
                                    color = SleekPrimary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { cancelQueueItem(item.id) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel item",
                        tint = SleekText.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (item.activeJob != null && !item.isCompleted && !item.isError) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = SleekPrimary,
                    trackColor = SleekInnerSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (item.isError) {
                Text(
                    text = "Error: ${item.errorMsg}",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val displayText = item.text.ifEmpty {
                    if (item.activeJob != null) {
                        if (settings.uiLanguage == "de") "Analysiere Sprachsegment..." else "Analyzing voice segment..."
                    } else {
                        if (settings.uiLanguage == "de") "Wartet auf Verarbeitung..." else "Waiting for processing..."
                    }
                }
                Text(
                    text = displayText,
                    color = SleekText.copy(alpha = if (item.text.isEmpty()) 0.4f else 0.95f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textLayoutResult ->
                        if (!isExpanded) {
                            canExpand = textLayoutResult.hasVisualOverflow
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (canExpand || isExpanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isExpanded) {
                            com.example.data.Localization.getString("btn_show_less", settings.uiLanguage)
                        } else {
                            com.example.data.Localization.getString("btn_show_more", settings.uiLanguage)
                        },
                        color = SleekPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { isExpanded = !isExpanded }
                            .padding(vertical = 2.dp)
                    )
                }
            }

            if (item.isCompleted && item.text.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Transcription", item.text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SleekPrimary,
                            contentColor = SleekButtonText
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, item.text)
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            val chooser = Intent.createChooser(intent, "Share Text").apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            context.startActivity(chooser)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SleekInnerSurface,
                            contentColor = SleekText
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = SleekText,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    private fun dismissOverlayAndStop() {
        if (composeView != null) {
            try {
                windowManager?.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }
        stopSelf()
    }

    private fun createNotification(): Notification {
        val channelId = CHANNEL_ID
        val settings = DependencyProvider.getSettingsManager(this)
        val uiLanguage = settings.uiLanguage
        val title = com.example.data.Localization.getString("notification_title_started", uiLanguage)
        val text = com.example.data.Localization.getString("notification_desc_started", uiLanguage)

        val cancelIntent = Intent(this, TranscriptionOverlayService::class.java).apply {
            action = ACTION_CANCEL_TRANSCRIPTION
        }
        val pendingCancelIntent = android.app.PendingIntent.getService(
            this,
            100,
            cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val switchIntent = Intent(this, TranscriptionOverlayService::class.java).apply {
            action = ACTION_SWITCH_MODEL
        }
        val pendingSwitchIntent = android.app.PendingIntent.getService(
            this,
            101,
            switchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val cancelLabel = com.example.data.Localization.getString("notification_action_cancel", uiLanguage)
        val switchLabel = com.example.data.Localization.getString("notification_action_switch_model", uiLanguage)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, pendingCancelIntent)
            .addAction(android.R.drawable.ic_menu_manage, switchLabel, pendingSwitchIntent)
            .build()
    }

    private fun updateForegroundNotification(status: String, progress: Float, partialText: String = "") {
        val settings = DependencyProvider.getSettingsManager(this)
        if (!settings.showAsNotification) return
        
        CoroutineScope(Dispatchers.Main).launch {
            val title = com.example.data.Localization.getString("notification_title_started", settings.uiLanguage)
            
            val cancelIntent = Intent(this@TranscriptionOverlayService, TranscriptionOverlayService::class.java).apply {
                action = ACTION_CANCEL_TRANSCRIPTION
            }
            val pendingCancelIntent = android.app.PendingIntent.getService(
                this@TranscriptionOverlayService,
                100,
                cancelIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val switchIntent = Intent(this@TranscriptionOverlayService, TranscriptionOverlayService::class.java).apply {
                action = ACTION_SWITCH_MODEL
            }
            val pendingSwitchIntent = android.app.PendingIntent.getService(
                this@TranscriptionOverlayService,
                101,
                switchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val cancelLabel = com.example.data.Localization.getString("notification_action_cancel", settings.uiLanguage)
            val switchLabel = com.example.data.Localization.getString("notification_action_switch_model", settings.uiLanguage)
            
            val activeItem = transcriptionQueue.find { it.activeJob != null }
            val activeModelName = if (activeItem?.isServerAttempt == true) {
                " (${com.example.data.Localization.getString("badge_server", settings.uiLanguage)})"
            } else {
                activeItem?.currentModelType?.let { " (${it.displayLabel})" } ?: ""
            }
            val fullTitle = title + activeModelName

            val builder = NotificationCompat.Builder(this@TranscriptionOverlayService, CHANNEL_ID)
                .setContentTitle(fullTitle)
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setProgress(100, (progress * 100).toInt(), false)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, pendingCancelIntent)
                .addAction(android.R.drawable.ic_menu_manage, switchLabel, pendingSwitchIntent)
                
            if (partialText.isNotEmpty()) {
                builder.setStyle(NotificationCompat.BigTextStyle().bigText("$status\n\n$partialText"))
            }
            
            try {
                startForeground(NOTIFICATION_ID, builder.build())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showCompletedNotification(
        context: Context,
        id: Int,
        fullText: String,
        durationLabel: String = "",
        isServerAttempt: Boolean = false,
        localModelLabel: String? = null
    ) {
        val channelId = CHANNEL_ID
        val settings = DependencyProvider.getSettingsManager(context)
        val uiLanguage = settings.uiLanguage

        val baseTitle = com.example.data.Localization.getString("notification_title_completed", uiLanguage)
        // Shown for both the server and local case, not just server, so a completed
        // notification always says where the transcription came from.
        val sourceLabel = if (isServerAttempt) {
            com.example.data.Localization.getString("badge_server", uiLanguage)
        } else {
            localModelLabel
        }
        val suffixParts = listOfNotNull(
            durationLabel.takeIf { it.isNotBlank() },
            sourceLabel
        )
        val title = if (suffixParts.isEmpty()) baseTitle else "$baseTitle (${suffixParts.joinToString(" · ")})"
        val btnCopyText = com.example.data.Localization.getString("notification_action_copy", uiLanguage)
        val btnShareText = com.example.data.Localization.getString("notification_action_share", uiLanguage)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val mainIntent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("scroll_to_id", id.toLong())
        }
        val pendingMainIntent = android.app.PendingIntent.getActivity(
            context,
            id,
            mainIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val copyIntent = Intent(context, CopyReceiver::class.java).apply {
            putExtra("text_to_copy", fullText)
        }
        val pendingCopyIntent = android.app.PendingIntent.getBroadcast(
            context,
            id + 10000,
            copyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, fullText)
            type = "text/plain"
        }
        val pendingShareIntent = android.app.PendingIntent.getActivity(
            context,
            id + 20000,
            Intent.createChooser(shareIntent, btnShareText),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(fullText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingMainIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullText))
            .addAction(android.R.drawable.ic_menu_save, btnCopyText, pendingCopyIntent)
            .addAction(android.R.drawable.ic_menu_share, btnShareText, pendingShareIntent)

        notificationManager.notify(id, builder.build())
    }

    private fun showErrorNotification(context: Context, error: String) {
        val channelId = CHANNEL_ID
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Transcription Failed")
            .setContentText(error)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
    }

    override fun onDestroy() {
        if (composeView != null) {
            try {
                windowManager?.removeView(composeView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }
        transcriptionQueue.forEach { it.activeJob?.cancel() }
        transcriptionQueue.clear()
        cachedLocalAudioFile?.delete()
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
