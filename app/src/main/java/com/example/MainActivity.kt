package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.TranscriptionEntity
import com.example.data.TranscriptionRepository
import com.example.data.Localization
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.SleekBackground
import com.example.ui.theme.SleekHeader
import com.example.ui.theme.SleekInnerSurface
import com.example.ui.theme.SleekPrimary
import com.example.ui.theme.SleekSurface
import com.example.ui.theme.SleekText
import com.example.ui.theme.SleekButtonText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

class MainViewModel(private val repository: TranscriptionRepository) : ViewModel() {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Combined stream filtering by search query only when executed
    private val _executedQuery = MutableStateFlow("")

    val transcriptions: StateFlow<List<TranscriptionEntity>> = _executedQuery
        .flatMapLatest { query ->
            if (query.isEmpty()) {
                repository.allTranscriptions
            } else {
                repository.search(query)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun executeSearch(query: String) {
        _executedQuery.value = query
    }

    fun deleteTranscription(id: Int) {
        viewModelScope.launch {
            repository.deleteById(id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repository = remember { DependencyProvider.getRepository(context) }
    val viewModel: MainViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(repository) as T
            }
        }
    )

    val transcriptions by viewModel.transcriptions.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    // Dynamic, reactive permission check on ON_RESUME
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    hasOverlayPermission = Settings.canDrawOverlays(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val settingsManager = remember { DependencyProvider.getSettingsManager(context) }
    val modelDownloader = remember { com.example.engine.ModelDownloader(context) }
    var modelUpdateTrigger by remember { mutableIntStateOf(0) }
    var showOnboarding by remember {
        mutableStateOf(
            !modelDownloader.isModelDownloaded(com.example.engine.ModelDownloader.ModelType.VOSK_DE) &&
            !modelDownloader.isModelDownloaded(com.example.engine.ModelDownloader.ModelType.VOSK_EN) &&
            !modelDownloader.isModelDownloaded(com.example.engine.ModelDownloader.ModelType.WHISPER_TINY) &&
            !modelDownloader.isModelDownloaded(com.example.engine.ModelDownloader.ModelType.WHISPER_BASE) &&
            !modelDownloader.isModelDownloaded(com.example.engine.ModelDownloader.ModelType.WHISPER_SMALL)
        )
    }
    var uiLanguage by remember { mutableStateOf(settingsManager.uiLanguage) }
    var currentTab by remember { mutableIntStateOf(0) }
    var preferServerTranscription by remember { mutableStateOf(settingsManager.preferServerTranscription) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Notification permission is required for Notification Mode.", Toast.LENGTH_LONG).show()
        }
    }
    
    LaunchedEffect(settingsManager.showAsNotification) {
        if (settingsManager.showAsNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val appVersion = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${packageInfo.versionName}"
        } catch (e: Exception) {
            "v1.0.0"
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = SleekSurface,
                tonalElevation = 0.dp,
                modifier = Modifier.height(80.dp)
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = Localization.getString("tab_history", uiLanguage)
                        )
                    },
                    label = { Text(Localization.getString("tab_history", uiLanguage), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SleekButtonText,
                        selectedTextColor = SleekText,
                        indicatorColor = SleekPrimary,
                        unselectedIconColor = SleekText.copy(alpha = 0.6f),
                        unselectedTextColor = SleekText.copy(alpha = 0.6f)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = Localization.getString("tab_settings", uiLanguage)
                        )
                    },
                    label = { Text(Localization.getString("tab_settings", uiLanguage), fontSize = 10.sp) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = SleekButtonText,
                        selectedTextColor = SleekText,
                        indicatorColor = SleekPrimary,
                        unselectedIconColor = SleekText.copy(alpha = 0.6f),
                        unselectedTextColor = SleekText.copy(alpha = 0.6f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SleekBackground)
                .padding(innerPadding)
        ) {
            // Top App Bar/Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Localization.getString("app_name", uiLanguage),
                        color = SleekText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SleekPrimary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = appVersion,
                            color = SleekPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (currentTab == 0) {
                // History Tab View
                var localSearchText by remember { mutableStateOf(searchQuery) }
                OutlinedTextField(
                    value = localSearchText,
                    onValueChange = {
                        localSearchText = it
                        viewModel.updateSearchQuery(it)
                    },
                    placeholder = {
                        Text(
                            text = Localization.getString("search_hint", uiLanguage),
                            color = SleekText.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search icon",
                            tint = SleekText.copy(alpha = 0.5f)
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("search_bar_input"),
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SleekPrimary,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = SleekSurface,
                        unfocusedContainerColor = SleekSurface,
                        focusedTextColor = SleekText,
                        unfocusedTextColor = SleekText
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            viewModel.executeSearch(localSearchText)
                        }
                    ),
                    singleLine = true
                )

                // Alert banner if system overlay permission is missing (Hides instantly when granted!)
                if (!hasOverlayPermission) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SleekInnerSurface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = Localization.getString("overlay_permission_title", uiLanguage),
                                color = SleekPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = Localization.getString("overlay_permission_msg", uiLanguage),
                                color = SleekText.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SleekPrimary,
                                    contentColor = SleekButtonText
                                )
                            ) {
                                Text(Localization.getString("overlay_permission_btn", uiLanguage))
                            }
                        }
                    }
                }

                // A user who prefers server-side transcription doesn't need a local model at all,
                // so this "download a local model" gate only applies to local-engine users.
                if (!preferServerTranscription) {
                    val activeRequiredModel = when {
                        settingsManager.sttEngine == "whisper" -> {
                            when (settingsManager.whisperModelSize) {
                                "base" -> com.example.engine.ModelDownloader.ModelType.WHISPER_BASE
                                "small" -> com.example.engine.ModelDownloader.ModelType.WHISPER_SMALL
                                else -> com.example.engine.ModelDownloader.ModelType.WHISPER_TINY
                            }
                        }
                        settingsManager.getTargetLanguageCode() == "de" -> com.example.engine.ModelDownloader.ModelType.VOSK_DE
                        else -> com.example.engine.ModelDownloader.ModelType.VOSK_EN
                    }

                    var isActiveModelDownloaded by remember(settingsManager.sttEngine, settingsManager.language, settingsManager.whisperModelSize, modelUpdateTrigger) {
                        mutableStateOf(modelDownloader.isModelDownloaded(activeRequiredModel))
                    }

                    if (!isActiveModelDownloaded) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            colors = CardDefaults.cardColors(containerColor = SleekInnerSurface),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = "Warning",
                                        tint = SleekPrimary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = Localization.getString("banner_model_required_title", uiLanguage),
                                        color = SleekPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = String.format(
                                        Localization.getString("banner_model_required_desc", uiLanguage),
                                        activeRequiredModel.folderName,
                                        activeRequiredModel.sizeLabel
                                    ),
                                    color = SleekText.copy(alpha = 0.8f),
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        currentTab = 1
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SleekPrimary,
                                        contentColor = SleekButtonText
                                    )
                                ) {
                                    Text(Localization.getString("banner_model_required_btn", uiLanguage))
                                }
                            }
                        }
                    }
                }

                // History List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("transcriptions_list"),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Item 1: Preview Sandbox (Debug/Preview Mode Only)
                    if (com.example.BuildConfig.DEBUG) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .border(1.dp, SleekPrimary.copy(alpha = 0.2f), RoundedCornerShape(20.dp)),
                                colors = CardDefaults.cardColors(containerColor = SleekSurface)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Mic,
                                                contentDescription = "Sandbox Icon",
                                                tint = SleekPrimary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Text(
                                                text = Localization.getString("sandbox_header", uiLanguage),
                                                color = SleekPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(SleekPrimary.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "SANDBOX",
                                                color = SleekPrimary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = Localization.getString("sandbox_desc", uiLanguage),
                                        color = SleekText.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = {
                                            val mockUri = "mock://audio/whatsapp_voice_message_de"
                                            val serviceIntent = Intent(context, com.example.service.TranscriptionOverlayService::class.java).apply {
                                                putExtra(com.example.service.TranscriptionOverlayService.EXTRA_AUDIO_URI, mockUri)
                                            }
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                context.startForegroundService(serviceIntent)
                                            } else {
                                                context.startService(serviceIntent)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SleekPrimary,
                                            contentColor = SleekButtonText
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = Localization.getString("sandbox_btn_simulate", uiLanguage),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Item 2: Empty placeholder or history list items
                    if (transcriptions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp, horizontal = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = Localization.getString("empty_history", uiLanguage),
                                    color = SleekText.copy(alpha = 0.5f),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    } else {
                        items(transcriptions, key = { it.id }) { item ->
                            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                                HistoryItemCard(
                                    item = item,
                                    onCopy = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("transcription", item.transcribedText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, Localization.getString("toast_copied", uiLanguage), Toast.LENGTH_SHORT).show()
                                    },
                                    onShare = {
                                        val shareIntent = Intent().apply {
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_TEXT, item.transcribedText)
                                            type = "text/plain"
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, null))
                                    },
                                    onDelete = {
                                        viewModel.deleteTranscription(item.id)
                                        Toast.makeText(context, Localization.getString("toast_deleted", uiLanguage), Toast.LENGTH_SHORT).show()
                                    },
                                    uiLanguage = uiLanguage
                                )
                            }
                        }
                    }
                }
            } else {
                // Settings Tab View (currentTab == 1)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // App UI Language Selection Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = Localization.getString("settings_header_ui_lang", uiLanguage),
                                color = SleekPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = Localization.getString("settings_desc_ui_lang", uiLanguage),
                                color = SleekText.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            val uiLangOptions = listOf(
                                "system" to Localization.getString("settings_lang_system", uiLanguage),
                                "de" to "Deutsch",
                                "en" to "English"
                            )

                            // Sleek, compact horizontally segmented button control
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                uiLangOptions.forEach { (code, label) ->
                                    val isSelected = uiLanguage == code
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) SleekPrimary else SleekInnerSurface)
                                            .clickable {
                                                uiLanguage = code
                                                settingsManager.uiLanguage = code
                                                val feedbackMsg = Localization.getString("toast_language_changed", code) + label
                                                Toast.makeText(context, feedbackMsg, Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) SleekButtonText else SleekText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Smart Language Selection Card (Transcription Language) - Space-efficient & polished!
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = Localization.getString("settings_header_transcription", uiLanguage),
                                color = SleekPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = Localization.getString("settings_desc_transcription", uiLanguage),
                                color = SleekText.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            var selectedLanguage by remember { mutableStateOf(settingsManager.language) }
                            val transLangOptions = listOf(
                                "system" to Localization.getString("settings_lang_system", uiLanguage),
                                "de" to "DE",
                                "en" to "EN"
                            )

                            // Custom Radio-styled segmented selector buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                transLangOptions.forEach { (code, label) ->
                                    val isSelected = selectedLanguage == code
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) SleekPrimary else SleekInnerSurface)
                                            .clickable {
                                                selectedLanguage = code
                                                settingsManager.language = code
                                                val feedbackMsg = Localization.getString("toast_language_changed", uiLanguage) + label
                                                Toast.makeText(context, feedbackMsg, Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) SleekButtonText else SleekText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // STT Engine & Model Management Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = "STT Engine & Models",
                                color = SleekPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Choose your transcription engine and manage local offline models to save storage space.",
                                color = SleekText.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            var selectedEngine by remember { mutableStateOf(settingsManager.sttEngine) }
                            val engineOptions = listOf(
                                "vosk" to "Vosk (Fast)",
                                "whisper" to "Whisper (Accurate)"
                            )

                            // Engine Selection
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                engineOptions.forEach { (code, label) ->
                                    val isSelected = selectedEngine == code
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) SleekPrimary else SleekInnerSurface)
                                            .clickable {
                                                selectedEngine = code
                                                settingsManager.sttEngine = code
                                                Toast.makeText(context, "Engine changed to $label", Toast.LENGTH_SHORT).show()
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (isSelected) SleekButtonText else SleekText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            
                            if (selectedEngine == "whisper") {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Whisper Model Size:",
                                    color = SleekText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                var selectedWhisperSize by remember { mutableStateOf(settingsManager.whisperModelSize) }
                                val whisperSizeOptions = remember {
                                    listOf("tiny", "base", "small").map { sizeCode ->
                                        val modelType = com.example.engine.ModelDownloader.ModelType.values()
                                            .find { it.engine == "whisper" && it.language == sizeCode }
                                        val sizeStr = modelType?.sizeLabel ?: ""
                                        val displayName = sizeCode.replaceFirstChar { it.uppercase() }
                                        sizeCode to "$displayName ($sizeStr)"
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    whisperSizeOptions.forEach { (sizeCode, sizeLabel) ->
                                        val isSizeSelected = selectedWhisperSize == sizeCode
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(if (isSizeSelected) SleekPrimary else SleekInnerSurface)
                                                .clickable {
                                                    selectedWhisperSize = sizeCode
                                                    settingsManager.whisperModelSize = sizeCode
                                                    modelUpdateTrigger++
                                                }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = sizeLabel.split(" ")[0] + "\n" + sizeLabel.substringAfter(" "),
                                                color = if (isSizeSelected) SleekButtonText else SleekText,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Model Download Status
                            val modelDownloader = remember { com.example.engine.ModelDownloader(context) }
                            val coroutineScope = rememberCoroutineScope()
                            
                            val requiredModel = when {
                                selectedEngine == "whisper" -> {
                                    when (settingsManager.whisperModelSize) {
                                        "base" -> com.example.engine.ModelDownloader.ModelType.WHISPER_BASE
                                        "small" -> com.example.engine.ModelDownloader.ModelType.WHISPER_SMALL
                                        else -> com.example.engine.ModelDownloader.ModelType.WHISPER_TINY
                                    }
                                }
                                settingsManager.getTargetLanguageCode() == "de" -> com.example.engine.ModelDownloader.ModelType.VOSK_DE
                                else -> com.example.engine.ModelDownloader.ModelType.VOSK_EN
                            }
                            
                            var isDownloaded by remember(requiredModel, modelUpdateTrigger) { mutableStateOf(modelDownloader.isModelDownloaded(requiredModel)) }
                            var downloadProgress by remember { mutableStateOf(-1f) }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Required Model: ${requiredModel.folderName} (${requiredModel.sizeLabel})",
                                        color = SleekText,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isDownloaded) "Status: Downloaded" else if (downloadProgress >= 0f) "Status: Downloading (${(downloadProgress * 100).toInt()}%)" else "Status: Not Downloaded",
                                        color = if (isDownloaded) Color(0xFF4CAF50) else if (downloadProgress >= 0f) SleekPrimary else SleekText.copy(alpha = 0.5f),
                                        fontSize = 12.sp
                                    )
                                }
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                if (isDownloaded) {
                                    OutlinedButton(
                                        onClick = { 
                                            modelDownloader.deleteModel(requiredModel)
                                            isDownloaded = false
                                            modelUpdateTrigger++
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF44336)),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.dp, brush = androidx.compose.ui.graphics.SolidColor(Color(0xFFF44336).copy(alpha = 0.5f))),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Delete", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            if (downloadProgress < 0f) {
                                                downloadProgress = 0f
                                                coroutineScope.launch {
                                                    try {
                                                        modelDownloader.downloadModel(requiredModel) { progress ->
                                                            downloadProgress = progress
                                                        }
                                                        isDownloaded = true
                                                        modelUpdateTrigger++
                                                    } catch (e: Exception) {
                                                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                                    } finally {
                                                        downloadProgress = -1f
                                                    }
                                                }
                                            }
                                        },
                                        enabled = downloadProgress < 0f,
                                        colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary, contentColor = SleekButtonText),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(if (downloadProgress >= 0f) "Downloading..." else "Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // Server Transcription Card (optional server-side path with automatic fallback
                    // to the local whisper.cpp/Vosk pipeline)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = Localization.getString("settings_header_server", uiLanguage),
                                color = SleekPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = Localization.getString("settings_desc_server", uiLanguage),
                                color = SleekText.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            var serverUrlText by remember { mutableStateOf(settingsManager.serverUrl) }

                            // Periodic live connection indicator, independent of the manual
                            // "Test Connection" button below - re-checks in the background
                            // every 20s so the user always knows the current state at a glance
                            // instead of only finding out when they press the button.
                            var liveConnectionStatus by remember { mutableStateOf<Boolean?>(null) }
                            LaunchedEffect(serverUrlText) {
                                if (serverUrlText.isBlank()) {
                                    liveConnectionStatus = null
                                    return@LaunchedEffect
                                }
                                val healthEngine = com.example.engine.ServerTranscriptionEngine()
                                while (true) {
                                    liveConnectionStatus = healthEngine.healthCheck(serverUrlText)
                                    delay(20_000)
                                }
                            }

                            if (serverUrlText.isNotBlank()) {
                                val (dotColor, statusLabel) = when (liveConnectionStatus) {
                                    true -> SleekPrimary to Localization.getString("status_server_connected", uiLanguage)
                                    false -> MaterialTheme.colorScheme.error to Localization.getString("status_server_disconnected", uiLanguage)
                                    null -> SleekText.copy(alpha = 0.35f) to Localization.getString("status_server_checking", uiLanguage)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(dotColor)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = statusLabel,
                                        color = SleekText.copy(alpha = 0.7f),
                                        fontSize = 11.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            OutlinedTextField(
                                value = serverUrlText,
                                onValueChange = {
                                    serverUrlText = it
                                    settingsManager.serverUrl = it
                                },
                                placeholder = {
                                    Text(
                                        text = Localization.getString("settings_server_url_hint", uiLanguage),
                                        color = SleekText.copy(alpha = 0.4f),
                                        fontSize = 14.sp
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("server_url_input"),
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = SleekPrimary,
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedContainerColor = SleekInnerSurface,
                                    unfocusedContainerColor = SleekInnerSurface,
                                    focusedTextColor = SleekText,
                                    unfocusedTextColor = SleekText
                                ),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            var isTestingConnection by remember { mutableStateOf(false) }
                            var serverModels by remember { mutableStateOf<List<com.example.engine.ServerTranscriptionEngine.ServerModel>>(emptyList()) }
                            var hasTestedConnection by remember { mutableStateOf(false) }
                            val serverCoroutineScope = rememberCoroutineScope()

                            Button(
                                onClick = {
                                    if (!isTestingConnection) {
                                        isTestingConnection = true
                                        serverCoroutineScope.launch {
                                            try {
                                                val models = com.example.engine.ServerTranscriptionEngine().fetchModels(serverUrlText)
                                                serverModels = models
                                                hasTestedConnection = true
                                                if (models.isEmpty()) {
                                                    Toast.makeText(context, Localization.getString("toast_server_no_models", uiLanguage), Toast.LENGTH_LONG).show()
                                                } else {
                                                    Toast.makeText(context, Localization.getString("toast_server_connected", uiLanguage), Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                hasTestedConnection = true
                                                val detail = e.message ?: e::class.simpleName ?: "unknown error"
                                                Toast.makeText(
                                                    context,
                                                    "${Localization.getString("toast_server_connection_failed", uiLanguage)}: $detail",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                isTestingConnection = false
                                            }
                                        }
                                    }
                                },
                                enabled = !isTestingConnection && serverUrlText.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary, contentColor = SleekButtonText),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = if (isTestingConnection) {
                                        Localization.getString("btn_testing_connection", uiLanguage)
                                    } else {
                                        Localization.getString("btn_test_connection", uiLanguage)
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (serverModels.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = Localization.getString("settings_server_model_label", uiLanguage),
                                    color = SleekText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = Localization.getString("settings_server_model_guidance", uiLanguage),
                                    color = SleekText.copy(alpha = 0.55f),
                                    fontSize = 11.sp,
                                    lineHeight = 14.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                var selectedServerModel by remember { mutableStateOf(settingsManager.serverModel) }
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    serverModels.forEach { serverModel ->
                                        val isSelected = selectedServerModel == serverModel.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(if (isSelected) SleekPrimary.copy(alpha = 0.15f) else SleekInnerSurface)
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isSelected) SleekPrimary else Color.Transparent,
                                                    shape = RoundedCornerShape(14.dp)
                                                )
                                                .clickable {
                                                    selectedServerModel = serverModel.id
                                                    settingsManager.serverModel = serverModel.id
                                                }
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(top = 2.dp)
                                                    .size(16.dp)
                                                    .clip(CircleShape)
                                                    .border(
                                                        width = 2.dp,
                                                        color = if (isSelected) SleekPrimary else SleekText.copy(alpha = 0.35f),
                                                        shape = CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(8.dp)
                                                            .clip(CircleShape)
                                                            .background(SleekPrimary)
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = serverModel.label,
                                                        color = SleekText,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    if (serverModel.sizeMb > 0) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = "~${serverModel.sizeMb} MB",
                                                            color = SleekText.copy(alpha = 0.45f),
                                                            fontSize = 10.sp
                                                        )
                                                    }
                                                }
                                                if (serverModel.speedNote.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Text(
                                                        text = serverModel.speedNote,
                                                        color = SleekText.copy(alpha = 0.65f),
                                                        fontSize = 11.sp,
                                                        lineHeight = 14.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (hasTestedConnection) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = Localization.getString("settings_server_no_models_hint", uiLanguage),
                                    color = SleekText.copy(alpha = 0.5f),
                                    fontSize = 12.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = Localization.getString("settings_server_prefer_toggle_label", uiLanguage),
                                        color = SleekPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = Localization.getString("settings_server_prefer_toggle_desc", uiLanguage),
                                        color = SleekText.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Switch(
                                    checked = preferServerTranscription,
                                    onCheckedChange = { checked ->
                                        preferServerTranscription = checked
                                        settingsManager.preferServerTranscription = checked
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SleekButtonText,
                                        checkedTrackColor = SleekPrimary,
                                        uncheckedThumbColor = SleekText.copy(alpha = 0.4f),
                                        uncheckedTrackColor = SleekInnerSurface
                                    )
                                )
                            }
                        }
                    }

                    // Notification Mode Toggle Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = Localization.getString("settings_header_notification", uiLanguage),
                                        color = SleekPrimary,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = Localization.getString("settings_desc_notification", uiLanguage),
                                        color = SleekText.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                var showAsNotification by remember { mutableStateOf(settingsManager.showAsNotification) }
                                Switch(
                                    checked = showAsNotification,
                                    onCheckedChange = { checked ->
                                        if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            val hasPermission = ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.POST_NOTIFICATIONS
                                            ) == PackageManager.PERMISSION_GRANTED
                                            if (!hasPermission) {
                                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            }
                                        }
                                        showAsNotification = checked
                                        settingsManager.showAsNotification = checked
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SleekButtonText,
                                        checkedTrackColor = SleekPrimary,
                                        uncheckedThumbColor = SleekText.copy(alpha = 0.4f),
                                        uncheckedTrackColor = SleekInnerSurface
                                    )
                                )
                            }
                        }
                    }

                    // About App / Privacy info Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = Localization.getString("about_header", uiLanguage),
                                color = SleekPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = Localization.getString("about_desc", uiLanguage),
                                color = SleekText.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    // App Version Information Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)),
                        colors = CardDefaults.cardColors(containerColor = SleekSurface)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = Localization.getString("system_info_title", uiLanguage),
                                color = SleekPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(Localization.getString("system_info_version", uiLanguage), color = SleekText.copy(alpha = 0.6f), fontSize = 12.sp)
                                Text(appVersion, color = SleekText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(Localization.getString("system_info_license", uiLanguage), color = SleekText.copy(alpha = 0.6f), fontSize = 12.sp)
                                Text(Localization.getString("system_info_license_type", uiLanguage), color = SleekText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(Localization.getString("system_info_security", uiLanguage), color = SleekText.copy(alpha = 0.6f), fontSize = 12.sp)
                                Text(
                                    text = if (preferServerTranscription) {
                                        Localization.getString("system_info_cloud", uiLanguage)
                                    } else {
                                        Localization.getString("system_info_offline", uiLanguage)
                                    },
                                    color = SleekPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    // A server-only user shouldn't be forced through the local-model download wizard.
    if (showOnboarding && !preferServerTranscription) {
        var downloadProgress by remember { mutableStateOf(-1f) }
        val scope = rememberCoroutineScope()
        
        AlertDialog(
            onDismissRequest = { /* Prevent dismiss on outside click */ },
            title = {
                Text(
                    text = Localization.getString("onboarding_title", uiLanguage),
                    color = SleekPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = Localization.getString("onboarding_desc", uiLanguage),
                        color = SleekText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    
                    var wizardEngine by remember { mutableStateOf(settingsManager.sttEngine) }
                    var wizardLang by remember { mutableStateOf(settingsManager.language) }
                    var wizardWhisperSize by remember { mutableStateOf(settingsManager.whisperModelSize) }
                    
                    Text(Localization.getString("onboarding_engine_title", uiLanguage), fontWeight = FontWeight.Bold, color = SleekText, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("vosk" to "Vosk (Fast)", "whisper" to "Whisper (Accurate)").forEach { (code, label) ->
                            val isSelected = wizardEngine == code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) SleekPrimary else SleekInnerSurface)
                                    .clickable {
                                        wizardEngine = code
                                        settingsManager.sttEngine = code
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (isSelected) SleekButtonText else SleekText, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    
                    if (wizardEngine == "vosk") {
                        Text(Localization.getString("onboarding_lang_title", uiLanguage), fontWeight = FontWeight.Bold, color = SleekText, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("de" to "Deutsch", "en" to "English").forEach { (code, label) ->
                                val isSelected = wizardLang == code
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) SleekPrimary else SleekInnerSurface)
                                        .clickable {
                                            wizardLang = code
                                            settingsManager.language = code
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label, color = if (isSelected) SleekButtonText else SleekText, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    } else {
                        Text(Localization.getString("onboarding_whisper_size_title", uiLanguage), fontWeight = FontWeight.Bold, color = SleekText, fontSize = 12.sp)
                        val whisperWizardOptions = remember {
                            listOf("tiny", "base", "small").map { sizeCode ->
                                val modelType = com.example.engine.ModelDownloader.ModelType.values()
                                    .find { it.engine == "whisper" && it.language == sizeCode }
                                val sizeStr = modelType?.sizeLabel ?: ""
                                val displayName = sizeCode.replaceFirstChar { it.uppercase() }
                                sizeCode to "$displayName ($sizeStr)"
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            whisperWizardOptions.forEach { (code, label) ->
                                val isSelected = wizardWhisperSize == code
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) SleekPrimary else SleekInnerSurface)
                                        .clickable {
                                            wizardWhisperSize = code
                                            settingsManager.whisperModelSize = code
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label.split(" ")[0] + "\n" + label.substringAfter(" "), color = if (isSelected) SleekButtonText else SleekText, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    
                    val selectedModel = when {
                        wizardEngine == "whisper" -> {
                            when (wizardWhisperSize) {
                                "base" -> com.example.engine.ModelDownloader.ModelType.WHISPER_BASE
                                "small" -> com.example.engine.ModelDownloader.ModelType.WHISPER_SMALL
                                else -> com.example.engine.ModelDownloader.ModelType.WHISPER_TINY
                            }
                        }
                        wizardLang == "de" || (wizardLang == "system" && Locale.getDefault().language.startsWith("de")) -> com.example.engine.ModelDownloader.ModelType.VOSK_DE
                        else -> com.example.engine.ModelDownloader.ModelType.VOSK_EN
                    }
                    
                    val isSelectedModelDownloaded = remember(selectedModel, modelUpdateTrigger) {
                        modelDownloader.isModelDownloaded(selectedModel)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (downloadProgress >= 0f) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                color = SleekPrimary,
                                trackColor = SleekInnerSurface,
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format(
                                    Localization.getString("onboarding_downloading", uiLanguage),
                                    (downloadProgress * 100).toInt()
                                ),
                                fontSize = 12.sp,
                                color = SleekPrimary
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (isSelectedModelDownloaded) {
                                    showOnboarding = false
                                } else {
                                    downloadProgress = 0f
                                    scope.launch {
                                        try {
                                            modelDownloader.downloadModel(selectedModel) { progress ->
                                                downloadProgress = progress
                                            }
                                            modelUpdateTrigger++
                                            showOnboarding = false
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                                        } finally {
                                            downloadProgress = -1f
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = SleekPrimary, contentColor = SleekButtonText)
                        ) {
                            Text(
                                text = if (isSelectedModelDownloaded) {
                                    Localization.getString("onboarding_start_btn", uiLanguage)
                                } else {
                                    String.format(
                                        Localization.getString("onboarding_download_btn", uiLanguage),
                                        selectedModel.sizeLabel
                                    )
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (downloadProgress < 0f) {
                    TextButton(onClick = { showOnboarding = false }) {
                        Text(Localization.getString("onboarding_skip_btn", uiLanguage), color = SleekText.copy(alpha = 0.6f))
                    }
                }
            },
            containerColor = SleekSurface
        )
    }
}

@Composable
fun HistoryItemCard(
    item: TranscriptionEntity,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    uiLanguage: String
) {
    val formattedDate = remember(item.timestamp) {
        val sdf = SimpleDateFormat("dd. MMM, HH:mm", Locale.getDefault())
        sdf.format(Date(item.timestamp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp))
            .testTag("transcription_item_card"),
        colors = CardDefaults.cardColors(containerColor = SleekSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedDate,
                    color = SleekPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (item.language.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(SleekHeader, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = item.language.uppercase(Locale.ROOT),
                            color = SleekText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            var isExpanded by remember { mutableStateOf(false) }
            var canExpand by remember { mutableStateOf(false) }

            Column {
                Text(
                    text = item.transcribedText,
                    color = SleekText.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { textLayoutResult ->
                        if (!isExpanded) {
                            canExpand = textLayoutResult.hasVisualOverflow
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                if (canExpand || isExpanded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isExpanded) {
                            Localization.getString("btn_show_less", uiLanguage)
                        } else {
                            Localization.getString("btn_show_more", uiLanguage)
                        },
                        color = SleekPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { isExpanded = !isExpanded }
                            .padding(vertical = 4.dp)
                            .testTag("read_more_btn")
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SleekPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Localization.getString("btn_copy", uiLanguage), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SleekText),
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 0.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Localization.getString("btn_share", uiLanguage), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(SleekHeader)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
