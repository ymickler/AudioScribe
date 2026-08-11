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
import com.example.data.DurationFormat
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
import com.example.ui.theme.SleekSuccess
import com.example.ui.theme.SleekText
import com.example.ui.theme.SleekButtonText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    val isRunning = item.status == TranscriptionEntity.STATUS_RUNNING
    val isFailed = item.status == TranscriptionEntity.STATUS_ERROR

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
                    // How long the transcription took, next to when it happened - the value was
                    // already being measured for the overlay, it just never reached the history.
                    text = if (!isRunning && item.duration > 0L) {
                        "$formattedDate · ${DurationFormat.format(item.duration)}"
                    } else {
                        formattedDate
                    },
                    color = SleekPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (item.language.isNotEmpty() && !isRunning) {
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
                if (isRunning || isFailed) {
                    Box(
                        modifier = Modifier
                            .background(SleekHeader, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (isRunning) {
                                Localization.getString("card_running_title", uiLanguage)
                            } else {
                                Localization.getString("card_failed_title", uiLanguage)
                            },
                            color = if (isFailed) MaterialTheme.colorScheme.error else SleekPrimary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(12.dp))
                // Indeterminate until the engine reports real progress, so the very first seconds
                // (caching the file, reaching the server) don't look like a stuck 0% bar.
                if (item.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { item.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SleekPrimary,
                        trackColor = SleekHeader
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp)),
                        color = SleekPrimary,
                        trackColor = SleekHeader
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.statusText.ifEmpty { Localization.getString("card_running_title", uiLanguage) },
                    color = SleekText.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            var isExpanded by remember { mutableStateOf(false) }
            var canExpand by remember { mutableStateOf(false) }

            Column {
                Text(
                    text = when {
                        item.transcribedText.isNotEmpty() -> item.transcribedText
                        isRunning -> Localization.getString("card_running_waiting_text", uiLanguage)
                        isFailed -> item.statusText
                        else -> item.transcribedText
                    },
                    color = if (isFailed && item.transcribedText.isEmpty()) {
                        MaterialTheme.colorScheme.error
                    } else if (isRunning && item.transcribedText.isEmpty()) {
                        SleekText.copy(alpha = 0.5f)
                    } else SleekText.copy(alpha = 0.9f),
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

            // A still-running item has nothing to copy or share yet, and deleting it here would
            // only get its row rewritten by the service on completion - it's cancelled from the
            // overlay/notification instead.
            if (!isRunning) {
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
}
