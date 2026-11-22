package com.allerix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val AAccent = AllervueDesign.Cyan
private val AGold = AllervueDesign.Gold

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
            featured = runCatching { Api.featured().firstOrNull() }.getOrNull()
            resume = runCatching {
                Api.resume().filterNot { it.id in ContinueWatching.hiddenIds(context) }
            }.getOrDefault(emptyList())
            recent = runCatching { Api.trending() }.getOrDefault(emptyList())
            picks = runCatching {
                Api.personalizedPicks((resume + recent).map { it.id }.toSet())
            }.getOrDefault(emptyList())
            if (featured == null && resume.isEmpty() && recent.isEmpty() && picks.isEmpty()) failure = true
        } catch (error: Exception) {
            if (error.message?.contains("401") == true) onLogout() else failure = true
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                            .background(Brush.linearGradient(listOf(AAccent, AllervueDesign.Violet))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("A", color = Color(0xFF071014), fontSize = 21.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Allervue", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
                        Text("Great stories. Anytime.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp)
                    }
                }
                AHomeHeaderAction("⌕", "Search", onSearch)
                Spacer(Modifier.width(8.dp))
                AHomeHeaderAction("⋯", "Settings", onSettings)
            }
        }

        item {
            val item = featured
            if (item != null) {
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                        .height(430.dp).clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    AsyncImage(
                        Api.backdrop(item), null, Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color(0x1106090D),
                                .28f to Color(0x2206090D),
                                .58f to Color(0xB906090D),
                                1f to Color(0xF506090D)
                            )
                        )
                    )
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .padding(start = 22.dp, end = 22.dp, bottom = 22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(25.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                                .background(Brush.horizontalGradient(listOf(AAccent, AllervueDesign.Violet))))
                            Spacer(Modifier.width(8.dp))
                            Text("FEATURED FOR YOU", color = Color.White, fontSize = 10.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(item.name, color = Color.White, fontSize = 30.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            listOfNotNull(item.year?.toString(), item.officialRating, item.type).joinToString("  •  "),
                            color = Color(0xFFE1E4E8), fontSize = 12.sp
                        )
                        if (!item.overview.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(item.overview.orEmpty(), color = Color(0xFFD0D5DC), fontSize = 12.sp,
                                lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(17.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onPlay(item) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White, contentColor = Color(0xFF10151B)
                                ),
                                contentPadding = PaddingValues(horizontal = 21.dp, vertical = 11.dp)
                            ) {
                                Text("▶  Play", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            OutlinedButton(
                                onClick = { onOpen(item) },
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x99FFFFFF)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp)
                            ) {
                                Text("More info", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            } else if (loading) {
                Box(Modifier.fillMaxWidth().height(330.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AAccent, strokeWidth = 2.dp)
                }
            } else if (failure) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 44.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your library isn't available", color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("Check your server connection and try again.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (resume.isNotEmpty()) {
            item { AHomeSectionTitle("Continue watching", "Pick up where you left off") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume.take(10), key = { "continue-${it.id}" }) { entry ->
                        AContinueCard(entry) { onPlay(entry) }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { AHomeSectionTitle("Recently added", "Fresh from your library") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(recent.take(14), key = { "recent-${it.id}" }) { entry ->
                        APosterCard(entry) { onOpen(entry) }
                    }
                }
            }
        }

        if (picks.isNotEmpty()) {
            item { AHomeSectionTitle("Picked for you", "More stories worth discovering") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(picks.take(14), key = { "pick-${it.id}" }) { entry ->
                        APosterCard(entry) { onOpen(entry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AHomeHeaderAction(symbol: String, description: String, onClick: () -> Unit) {
    Column(
        Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text(symbol, color = MaterialTheme.colorScheme.onBackground, fontSize = 23.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun AHomeSectionTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 25.dp, bottom = 13.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp,
            fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun AContinueCard(item: Item, onClick: () -> Unit) {
    val progress = (item.userData?.playedPercentage?.toFloat()
        ?: if ((item.runTimeTicks ?: 0L) > 0L)
            ((item.userData?.positionTicks ?: 0L).toFloat() / item.runTimeTicks!!.toFloat()) * 100f
        else 0f).coerceIn(0f, 100f)
    Column(
        Modifier.width(252.dp).clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(142.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(Api.landscape(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.Center).size(42.dp).clip(RoundedCornerShape(50))
                .background(Color(0xCC111820)), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, fontSize = 17.sp)
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(progress / 100f).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(AAccent, AllervueDesign.Violet))))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(item.name, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${progress.toInt()}% watched", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun APosterCard(item: Item, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        AsyncImage(
            Api.poster(item), null,
            Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentScale = ContentScale.Crop
        )
        Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp))
        Text(listOfNotNull(item.year?.toString(), item.type).joinToString("  •  "),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
    }
}
