package com.allerix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage

private val AAccent = AllervueDesign.Cyan

@Composable
internal fun AllervueHome(
    onOpen: (Item) -> Unit,
    onPlay: (Item) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var featured by remember { mutableStateOf<Item?>(null) }
    var resume by remember { mutableStateOf(emptyList<Item>()) }
    var recent by remember { mutableStateOf(emptyList<Item>()) }
    var picks by remember { mutableStateOf(emptyList<Item>()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val featureList = runCatching { Api.featured() }.getOrDefault(emptyList())
            featured = featureList.firstOrNull()
            resume = runCatching {
                Api.resume().filterNot { it.id in ContinueWatching.hiddenIds(context) }
            }.getOrDefault(emptyList())
            recent = runCatching { Api.trending() }.getOrDefault(emptyList())
            picks = runCatching {
                Api.personalizedPicks((resume + recent).map { it.id }.toSet())
            }.getOrDefault(emptyList())
        } catch (error: Exception) {
            if (error.message?.contains("401") == true) onLogout()
            else failure = true
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(27.dp).clip(RoundedCornerShape(8.dp)).background(AAccent),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("A", color = MaterialTheme.colorScheme.background, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.width(9.dp))
                        Text("ALLERVUE", color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp,
                            fontWeight = FontWeight.Black, letterSpacing = 2.2.sp)
                    }
                    Text("YOUR LIBRARY, IN FOCUS", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp,
                        letterSpacing = 1.6.sp, modifier = Modifier.padding(start = 36.dp, top = 3.dp))
                }
                Text("SEARCH", color = AAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp, modifier = Modifier.clickable(onClick = onSearch).padding(10.dp))
                Text("•••", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp,
                    modifier = Modifier.clickable(onClick = onSettings).padding(start = 8.dp, end = 2.dp))
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("01", color = AAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(9.dp))
                Text("THE SPOTLIGHT", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                Spacer(Modifier.weight(1f))
                Text("FEATURED FROM YOUR LIBRARY", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, letterSpacing = .7.sp)
            }
            Spacer(Modifier.height(10.dp))
            val item = featured
            if (item != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AsyncImage(
                        Api.poster(item), null,
                        Modifier.width(112.dp).height(164.dp).clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentScale = ContentScale.Crop
                    )
                    Column(Modifier.weight(1f).padding(start = 13.dp)) {
                        Text(if (item.type == "Series") "SERIES SELECTION" else "FILM SELECTION",
                            color = AAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, lineHeight = 23.sp,
                            fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(listOfNotNull(item.year?.toString(), item.officialRating)
                            .joinToString("  /  "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(item.overview.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                            lineHeight = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("PLAY  →", color = MaterialTheme.colorScheme.background, fontSize = 10.sp,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(AAccent)
                                    .clickable { onPlay(item) }.padding(horizontal = 11.dp, vertical = 8.dp))
                            Text("DETAILS", color = MaterialTheme.colorScheme.onBackground, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onOpen(item) }.padding(horizontal = 10.dp, vertical = 8.dp))
                        }
                    }
                }
            } else if (loading) {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AAccent, strokeWidth = 2.dp)
                }
            } else if (failure) {
                Text("Your library could not be reached. Check the server connection.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
            } else {
                Text("Your library is ready. New selections will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
            }
        }

        if (resume.isNotEmpty()) {
            item { AllervueSectionHeading("02", "IN PROGRESS", "PICK UP WHERE YOU LEFT OFF") }
            items(resume.take(8), key = { "resume-${it.id}" }) { entry ->
                AllervueProgressRow(entry, onClick = { onPlay(entry) })
            }
        }

        if (recent.isNotEmpty()) {
            item { AllervueSectionHeading("03", "RECENT ARRIVALS", "${recent.size} RECENT TITLES") }
            items(recent.take(10), key = { "recent-${it.id}" }) { entry ->
                AllervueIndexRow(entry, onClick = { onOpen(entry) })
            }
        }

        if (picks.isNotEmpty()) {
            item { AllervueSectionHeading("04", "A DIFFERENT DIRECTION", "SELECTED FOR YOUR LIBRARY") }
            items(picks.take(6), key = { "pick-${it.id}" }) { entry ->
                AllervueIndexRow(entry, onClick = { onOpen(entry) }, recommendation = true)
            }
        }
    }
}

@Composable
private fun AllervueSectionHeading(number: String, title: String, note: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(number, color = AAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                letterSpacing = .3.sp)
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
        Box(Modifier.width(34.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun AllervueProgressRow(item: Item, onClick: () -> Unit) {
    val progress = item.userData?.playedPercentage?.toFloat()
        ?: if ((item.runTimeTicks ?: 0L) > 0L)
            ((item.userData?.positionTicks ?: 0L).toFloat() / item.runTimeTicks!!.toFloat()) * 100f
        else 0f
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(11.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(Api.landscape(item), null,
            Modifier.width(100.dp).height(62.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text("CONTINUE", color = AAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp)
            Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${progress.toInt().coerceIn(0, 100)}% watched",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp))
            LinearProgressIndicator(
                progress = { (progress / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(2.dp),
                color = AAccent, trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Text("›", color = AAccent, fontSize = 23.sp, modifier = Modifier.padding(horizontal = 5.dp))
    }
}

@Composable
private fun AllervueIndexRow(item: Item, onClick: () -> Unit, recommendation: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(Api.poster(item), null,
            Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(if (recommendation) "ALLERVUE PICK" else item.type.uppercase(),
                color = AAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(item.year?.toString(), item.genres.orEmpty().take(2).joinToString(" · ").ifBlank { null })
                .joinToString("  /  "), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("OPEN  ↗", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 8.sp, fontWeight = FontWeight.Bold,
            letterSpacing = .6.sp)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
