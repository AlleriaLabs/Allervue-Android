package com.allerix.app

import android.app.DownloadManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AiSubtitleSettingsScreen(back: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            refresh++
        }
    }

    val models = AiSubtitleModels.all
    val installedBytes = remember(refresh) { AiSubtitleModelStore.usedBytes(context) }
    val stat = remember(context) {
        android.os.StatFs((context.getExternalFilesDir(null) ?: context.filesDir).absolutePath)
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("‹", color = MaterialTheme.colorScheme.onBackground, fontSize = 32.sp,
                modifier = Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text("AI Subtitles", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Offline speech recognition models",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                        .padding(17.dp)
                ) {
                    Text("ON-DEVICE AI", color = AllervueDesign.Cyan, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Your subtitles stay private.",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
                    Text(
                        "Whisper models run locally after the transcription engine is installed. Model downloads use your phone storage; no audio is uploaded.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                        lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Model storage", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        Text(formatAiBytes(installedBytes), color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Available on device: ${formatAiBytes(stat.availableBytes)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                        .padding(17.dp)
                ) {
                    Text("ENGINE STATUS", color = AllervueDesign.Cyan, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(
                        if (AiSubtitleEngineStatus.NATIVE_RUNTIME_READY) "Whisper engine ready" else "Engine setup · Phase 1",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "You can prepare and manage models now. Audio transcription becomes available after the native Whisper runtime is added in Phase 2.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                Text("AVAILABLE MODELS", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }

            items(models.size) { index ->
                val model = models[index]
                val downloadState = remember(refresh, model.id) {
                    AiSubtitleModelDownloads.state(context, model)
                }
                val installed = remember(refresh, model.id) {
                    AiSubtitleModelStore.isInstalled(context, model)
                }
                val progress = downloadState?.let {
                    if (it.totalBytes > 0L) (it.downloadedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) else 0f
                } ?: 0f
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(model.title, color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(model.description, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Text(if (installed) "DOWNLOADED" else formatAiBytes(model.expectedBytes),
                            color = if (installed) AllervueDesign.Success else AllervueDesign.Cyan,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    if (downloadState?.isActive == true) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            color = AllervueDesign.Cyan)
                        Text(
                            "${formatAiBytes(downloadState.downloadedBytes)} / ${formatAiBytes(downloadState.totalBytes)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                        if (installed) {
                            OutlinedButton(onClick = {
                                AiSubtitleModelDownloads.remove(context, model)
                                refresh++
                                message = "${model.title} removed"
                            }) { Text("Remove model") }
                        } else if (downloadState?.isActive == true) {
                            OutlinedButton(onClick = {
                                AiSubtitleModelDownloads.remove(context, model)
                                refresh++
                            }) { Text("Cancel") }
                        } else {
                            Button(onClick = {
                                runCatching { AiSubtitleModelDownloads.enqueue(context, model) }
                                    .onSuccess { message = "Download started for ${model.title}"; refresh++ }
                                    .onFailure { message = it.message ?: "Unable to start download" }
                            }) { Text(if (downloadState?.status == DownloadManager.STATUS_FAILED) "Retry" else "Download") }
                        }
                    }
                }
            }

            item {
                Text(
                    "Tiny is the recommended first download for phones. Base may improve recognition but needs more processing time and memory. Downloaded models remain in Allervue's private app storage.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    lineHeight = 16.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }

    message?.let { text ->
        LaunchedEffect(text) {
            delay(2600)
            if (message == text) message = null
        }
    }
}

private fun formatAiBytes(bytes: Long): String {
    if (bytes < 1024L) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
