package com.allerix.app

import android.app.DownloadManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSubtitleSettingsScreen(back: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var selectedModelId by remember {
        mutableStateOf(
            context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE)
                .getString("model", "base") ?: "base"
        )
    }
    var language by remember { mutableStateOf(context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE).getString("language", "auto") ?: "auto") }
    var autoGenerate by remember { mutableStateOf(context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE).getBoolean("auto_generate", true)) }
    var generating by remember { mutableStateOf(false) }
    var generationStatus by remember { mutableStateOf<String?>(null) }
    var generatedFile by remember { mutableStateOf<File?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { target: Uri? ->
        val source = generatedFile
        if (target != null && source != null) {
            runCatching {
                context.contentResolver.openOutputStream(target)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not open the selected save location.")
            }.onSuccess { generationStatus = "Subtitle file saved." }
                .onFailure { generationStatus = it.message ?: "Could not save the subtitle file." }
        }
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val model = AiSubtitleModels.get(selectedModelId)
            if (model == null || !AiSubtitleModelStore.isInstalled(context, model)) {
                generationStatus = "Download the selected Whisper model first."
            } else {
                scope.launch {
                    generating = true
                    generationStatus = "Extracting audio and preparing Whisper…"
                    runCatching {
                        AiSubtitleGenerator.generateFromMedia(context, uri, model, language.trim().ifBlank { "auto" })
                    }.onSuccess { result ->
                        generatedFile = result.file
                        generationStatus = "Generated ${result.segments.size} subtitle cues."
                        saveLauncher.launch(result.file.name)
                    }.onFailure { error ->
                        generationStatus = error.message ?: "Subtitle generation failed."
                    }
                    generating = false
                }
            }
        }
    }

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
                modifier = Modifier.padding(end = 14.dp).clickable(onClick = back))
            Column(Modifier.weight(1f)) {
                Text("AI Subtitles", color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Live streaming speech recognition",
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
                        "Live playback captions use Sherpa for English and Vosk for Filipino/Tagalog. Recognition runs on this phone; playback audio is not uploaded.",
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
                    Text(
                        "Filipino model download is about 320 MB and extracts on first use. Its CC-BY-NC-SA license is non-commercial; review the model terms before distributing Allervue.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                        lineHeight = 14.sp, modifier = Modifier.padding(top = 7.dp)
                    )
                }
            }

            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                        .padding(17.dp)
                ) {
                    Text("GENERATE FROM VIDEO", color = AllervueDesign.Cyan, fontSize = 10.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("Create an offline subtitle track", color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text("Optional batch SRT generation still uses the legacy Whisper model. It is separate from live playback captions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiSubtitleModels.all.forEach { model ->
                            FilterChip(
                                selected = selectedModelId == model.id,
                                onClick = {
                                    selectedModelId = model.id
                                    context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE)
                                        .edit().putString("model", model.id).apply()
                                },
                                label = {
                                    val installed = AiSubtitleModelStore.isInstalled(context, model)
                                    Text("${model.title.removePrefix("Whisper ")} · ${if (installed) "Ready" else "Not installed"}")
                                }
                            )
                        }
                    }
                    val activeModel = AiSubtitleModels.get(selectedModelId)
                    Text(
                        "Offline batch model: ${activeModel?.title ?: "Whisper Base"}. This choice affects manual SRT generation only, not live playback captions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 7.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic subtitles during playback",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Create live captions from the audio stream when a movie or episode has no subtitle tracks.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = autoGenerate,
                            onCheckedChange = { enabled ->
                                autoGenerate = enabled
                                context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE)
                                    .edit().putBoolean("auto_generate", enabled).apply()
                            }
                        )
                    }
                    val languageOptions = listOf(
                        "auto" to "Auto · Filipino-first",
                        "tl" to "Filipino / Tagalog",
                        "en" to "English"
                    )
                    var languageMenuExpanded by remember { mutableStateOf(false) }
                    val selectedLanguageLabel = languageOptions.firstOrNull { it.first == language }?.second ?: "Auto · Filipino-first"
                    ExposedDropdownMenuBox(
                        expanded = languageMenuExpanded,
                        onExpandedChange = { languageMenuExpanded = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = selectedLanguageLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Transcription language") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false },
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            languageOptions.forEach { (code, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        language = code
                                        context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE).edit().putString("language", code).apply()
                                        languageMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Auto is Filipino-first, not automatic language identification. Choose English for English audio. Taglish accuracy depends on the source model and still needs device testing.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    Button(
                        onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) },
                        enabled = !generating && AiSubtitleEngineStatus.NATIVE_RUNTIME_READY &&
                            AiSubtitleModels.get(selectedModelId)?.let { AiSubtitleModelStore.isInstalled(context, it) } == true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) {
                        if (generating) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(9.dp))
                        }
                        Text(if (generating) "Generating subtitles…" else "Choose media & generate SRT")
                    }
                    generationStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                            lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
                    }
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
                        "Sherpa streaming (English) · Vosk streaming (Filipino)",
                        color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "Live captions process decoded audio in short chunks while the video plays. The Filipino model is downloaded on demand; English uses the smaller Sherpa model pack.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                        lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item {
                Text("OPTIONAL LEGACY BATCH MODELS", color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (installed) "INSTALLED" else formatAiBytes(model.expectedBytes),
                                color = if (installed) AllervueDesign.Success else AllervueDesign.Cyan,
                                fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            if (selectedModelId == model.id) {
                                Text("PLAYBACK MODEL", color = AllervueDesign.Cyan,
                                    fontSize = 8.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                        }
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
                            }) { Text("Remove model") }
                        } else if (downloadState?.isActive == true) {
                            OutlinedButton(onClick = {
                                AiSubtitleModelDownloads.remove(context, model)
                                refresh++
                            }) { Text("Cancel") }
                        } else {
                            Button(onClick = {
                                runCatching { AiSubtitleModelDownloads.enqueue(context, model) }
                                    .onSuccess { refresh++ }
                                    .onFailure { refresh++ }
                            }) { Text(if (downloadState?.status == DownloadManager.STATUS_FAILED) "Retry" else "Download") }
                        }
                    }
                }
            }

            item {
                Text(
                    "These Whisper models are only for optional offline SRT generation. They are not downloaded automatically and are not used by live playback captions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    lineHeight = 16.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
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
