package com.allerix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSubtitleSettingsScreen(back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("ai_subtitle_settings", android.content.Context.MODE_PRIVATE)
    }
    var language by remember { mutableStateOf(prefs.getString("language", "auto") ?: "auto") }
    var autoGenerate by remember { mutableStateOf(prefs.getBoolean("auto_generate", true)) }
    var introPreRoll by remember { mutableStateOf(prefs.getBoolean("intro_preroll", true)) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    val languageOptions = listOf(
        "auto" to "Auto · Filipino / Tagalog",
        "tl" to "Filipino / Tagalog",
        "en" to "English"
    )
    val selectedLanguageLabel =
        languageOptions.firstOrNull { it.first == language }?.second ?: "Auto · Filipino / Tagalog"

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("ON-DEVICE LIVE CAPTIONS", color = AllervueDesign.Cyan,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Sherpa + Vosk", color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Sherpa provides streaming English recognition. Vosk provides streaming Filipino / Tagalog recognition. Audio is processed on this phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            ExposedDropdownMenuBox(
                expanded = languageMenuExpanded,
                onExpandedChange = { languageMenuExpanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = selectedLanguageLabel, onValueChange = {}, readOnly = true,
                    label = { Text("Recognition language") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageMenuExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false }) {
                    languageOptions.forEach { (code, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            language = code
                            prefs.edit().putString("language", code).apply()
                            languageMenuExpanded = false
                        })
                    }
                }
            }
            Text(
                "Auto currently selects the Filipino-first Vosk model; it is not automatic language identification. Choose English for English audio.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 15.sp)
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Start live captions automatically", color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Start recognition when playback begins.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Switch(checked = autoGenerate, onCheckedChange = {
                    autoGenerate = it
                    prefs.edit().putBoolean("auto_generate", it).apply()
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Subtitle intro pre-roll", color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Allow captions to initialize during the opening playback segment.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
                }
                Switch(checked = introPreRoll, onCheckedChange = {
                    introPreRoll = it
                    prefs.edit().putBoolean("intro_preroll", it).apply()
                })
            }
        }
    }
}
