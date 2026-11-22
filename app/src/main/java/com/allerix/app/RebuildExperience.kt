@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.allerix.app

import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val VBg = Color(0xFF090B10)
private val rebuildDetailTypes = setOf("Series", "Season", "Movie", "Episode", "Video", "MusicVideo")
private val VSurface = Color(0xFF141923)
private val VSurface2 = Color(0xFF1C2430)
private val VText = Color(0xFFF4F5F7)
private val VMuted = Color(0xFF929BA9)
private val VCyan = Color(0xFF65D6D0)
private val VGold = Color(0xFFD9B77D)

@Composable
fun RebuildMainShell(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(NavTab.HOME) }
    val stack = remember { mutableStateListOf<Nav>() }
    val onPlay: (Item) -> Unit = { target ->
        scope.launch {
            val playable = if (target.type == "Series" || target.type == "Season")
                runCatching { Api.nextEpisode(target.id, target.type) }.getOrNull() else target
            if (playable != null) ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).putExtra("id", playable.id).putExtra("title", playable.name)
            )
        }
    }
    val onOpen: (Item) -> Unit = { target ->
        stack.add(if (target.type in rebuildDetailTypes || !target.isFolder) Nav.Detail(target) else Nav.Grid(target))
    }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val signOut: () -> Unit = { stack.clear(); onLogout() }
    val settings: () -> Unit = { stack.add(Nav.Settings) }
    BackHandler(enabled = stack.isNotEmpty()) { back() }
    BackHandler(enabled = stack.isEmpty() && tab != NavTab.HOME) { tab = NavTab.HOME }

    val top = stack.lastOrNull()
    val showNav = top == null || top is Nav.Detail || top is Nav.Grid
    Column(Modifier.fillMaxSize().background(VBg)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                null -> when (tab) {
                    NavTab.HOME -> RebuildHome(onOpen, onPlay, { stack.add(Nav.Search) }, settings, signOut)
                    NavTab.MOVIES -> RebuildCatalog("Movies", "Movie", onOpen, { stack.add(Nav.Search) })
                    NavTab.SHOWS -> RebuildCatalog("TV Shows", "Series", onOpen, { stack.add(Nav.Search) })
                    NavTab.LIST -> RebuildSaved(onOpen, onPlay)
                    NavTab.DOWNLOADS -> RebuildDownloads(onPlay)
                }
                is Nav.Grid -> RebuildFolderScreen(top.item, onOpen, back)
                is Nav.Detail -> RebuildDetail(top.item, onOpen, onPlay, back)
                Nav.Search -> RebuildSearch(onOpen, back)
                Nav.Settings -> RebuildSettingsScreen(
                    back, onRequest = { stack.add(Nav.RequestShow) },
                    onClient = { stack.add(Nav.ClientSettings) },
                    onPlayback = { stack.add(Nav.PlaybackSettings) },
                    onDiagnostics = { stack.add(Nav.Diagnostics) },
                    onSignOut = signOut,
                    onWeb = { u, t -> stack.add(Nav.Web(u, t)) }
                )
                Nav.RequestShow -> RequestShowScreen(back)
                Nav.ClientSettings -> ClientSettingsScreen(
                    back, onServerChanged = signOut, loggedIn = true,
                    onWeb = { u, t -> stack.add(Nav.Web(u, t)) }
                )
                Nav.PlaybackSettings -> PlaybackSettingsScreen(back)
                Nav.Diagnostics -> DiagnosticScreen(back)
                is Nav.Web -> WebScreen(top.url, top.title, back)
            }
        }
        if (showNav) RebuildBottomNav(tab) { selected -> stack.clear(); tab = selected }
    }
}

@Composable
private fun RebuildBottomNav(selected: NavTab, onSelect: (NavTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF10151D))
            .padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        NavTab.values().forEach { destination ->
            val active = destination == selected
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(13.dp))
                    .background(if (active) Color(0xFF26343C) else Color.Transparent)
                    .clickable { onSelect(destination) }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallIcon(destination.icon, if (active) VCyan else VMuted, 21)
                Spacer(Modifier.height(4.dp))
                Text(destination.label, color = if (active) VText else VMuted, fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun RebuildHeader(title: String, subtitle: String? = null, action: (() -> Unit)? = null, actionText: String = "Search") {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = VText, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = VMuted, fontSize = 12.sp)
        }
        if (action != null) Text(actionText, color = VCyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = action).padding(10.dp))
    }
}

@Composable
private fun RebuildHome(
    onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, onSearch: () -> Unit,
    onSettings: () -> Unit, onLogout: () -> Unit
) {
    val ctx = LocalContext.current
    var featured by remember { mutableStateOf<Item?>(null) }
    var resume by remember { mutableStateOf(emptyList<Item>()) }
    var latest by remember { mutableStateOf(emptyList<Item>()) }
    var picks by remember { mutableStateOf(emptyList<Item>()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val hero = runCatching { Api.featured() }.getOrDefault(emptyList())
            featured = hero.firstOrNull()
            resume = Api.resume().filterNot { it.id in ContinueWatching.hiddenIds(ctx) }
            latest = Api.trending()
            busy = false
            picks = runCatching { Api.personalizedPicks((resume + latest).map { it.id }.toSet()) }.getOrDefault(emptyList())
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) onLogout()
            else error = "Unable to load your library. Check your server connection."
            busy = false
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 22.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ALLERVUE", color = VText, fontSize = 17.sp, fontWeight = FontWeight.Black, letterSpacing = 2.5.sp)
                    Text("Your next story starts here", color = VMuted, fontSize = 11.sp)
                }
                Text("Search", color = VCyan, modifier = Modifier.clickable(onClick = onSearch).padding(10.dp))
                Text("Settings", color = VMuted, modifier = Modifier.clickable(onClick = onSettings).padding(start = 8.dp, end = 2.dp))
            }
        }
        item {
            val hero = featured
            if (hero != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(390.dp).clip(RoundedCornerShape(22.dp))) {
                    AsyncImage(Api.backdrop(hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to Color(0x22000000), 0.35f to Color(0x33000000), 1f to Color(0xF0090B10)
                    )))
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        Text(if (hero.type == "Series") "FEATURED SERIES" else "FEATURED FILM",
                            color = VCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(hero.name, color = VText, fontSize = 29.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(7.dp))
                        Text(listOfNotNull(hero.year?.toString(), hero.officialRating, fmtRuntime(hero.runTimeTicks).ifEmpty { null })
                            .joinToString("  ·  "), color = VMuted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(hero.overview.orEmpty(), color = Color(0xFFD5D9DE), fontSize = 12.sp, lineHeight = 17.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(15.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Button(onClick = { onPlay(hero) }, colors = ButtonDefaults.buttonColors(containerColor = VCyan, contentColor = VBg),
                                shape = RoundedCornerShape(11.dp)) { Text("▶  Play", fontWeight = FontWeight.Bold) }
                            OutlinedButton(onClick = { onOpen(hero) }, shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = VText)) { Text("View details") }
                        }
                    }
                }
            } else if (busy) {
                Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VCyan)
                }
            } else if (error.isNotBlank()) Text(error, color = VMuted, modifier = Modifier.padding(24.dp))
        }
        if (resume.isNotEmpty()) item { RebuildShelf("Pick up where you left off", resume, onPlay, "Resume") }
        if (picks.isNotEmpty()) item { RebuildShelf("Picked for you", picks, onOpen, "Recommended") }
        val movies = latest.filter { it.type == "Movie" }
        val shows = latest.filter { it.type == "Series" }
        if (movies.isNotEmpty()) item { RebuildShelf("Recently added films", movies, onOpen, "Movies") }
        if (shows.isNotEmpty()) item { RebuildShelf("New series", shows, onOpen, "Series") }
    }
}

@Composable
private fun RebuildShelf(title: String, entries: List<Item>, onItem: (Item) -> Unit, kicker: String) {
    Column(Modifier.fillMaxWidth().padding(top = 23.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kicker.uppercase(), color = VCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Text(title, color = VText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text("See all", color = VMuted, fontSize = 11.sp)
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) { items(entries, key = { it.id }) { item -> RebuildPoster(item, onClick = { onItem(item) }) } }
    }
}

@Composable
private fun RebuildPoster(item: Item, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(116.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp)).background(VSurface2)) {
            AsyncImage(Api.poster(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val progress = item.userData?.playedPercentage?.toFloat()
                ?: if ((item.runTimeTicks ?: 0L) > 0L) ((item.userData?.positionTicks ?: 0L).toFloat() / item.runTimeTicks!!.toFloat()) * 100f else 0f
            if (progress > 0f && progress < 100f) {
                LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = VCyan, trackColor = Color(0x66000000))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.name, color = VText, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 15.sp,
            overflow = TextOverflow.Ellipsis)
        Text(item.year?.toString() ?: item.type, color = VMuted, fontSize = 10.sp)
    }
}

@Composable
private fun RebuildCatalog(title: String, type: String, onOpen: (Item) -> Unit, onSearch: () -> Unit) {
    var entries by remember(type) { mutableStateOf(emptyList<Item>()) }
    var loading by remember(type) { mutableStateOf(true) }
    var filter by remember(type) { mutableStateOf("All") }
    LaunchedEffect(type) {
        loading = true
        entries = runCatching { Api.byType(type) }.getOrDefault(emptyList())
        loading = false
    }
    val filtered = when (filter) {
        "Unwatched" -> entries.filter { it.userData?.isPlayed != true }
        "Watched" -> entries.filter { it.userData?.isPlayed == true }
        "Recently added" -> entries.sortedByDescending { it.year ?: 0 }
        else -> entries
    }
    Column(Modifier.fillMaxSize()) {
        RebuildHeader(title, "${filtered.size} titles in your library", onSearch)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Unwatched", "Watched", "Recently added").forEach { label ->
                val selected = filter == label
                Text(label, color = if (selected) VBg else VMuted, fontSize = 11.sp,
                    modifier = Modifier.clip(CircleShape).background(if (selected) VCyan else VSurface)
                        .clickable { filter = label }.padding(horizontal = 13.dp, vertical = 8.dp))
            }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VCyan) }
        else if (filtered.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nothing here yet", color = VMuted)
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(filtered.size, key = { filtered[it].id }) { index ->
                val item = filtered[index]
                RebuildPoster(item, onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RebuildSaved(onOpen: (Item) -> Unit, onPlay: (Item) -> Unit) {
    var savedItems by remember { mutableStateOf(emptyList<Item>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { savedItems = runCatching { Api.favorites() }.getOrDefault(emptyList()); loading = false }
    Column(Modifier.fillMaxSize()) {
        RebuildHeader("My List", "Your saved films and series")
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VCyan) }
        else if (savedItems.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Save a title to find it here.", color = VMuted)
        } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(savedItems.size, key = { savedItems[it].id }) { index ->
                val item = savedItems[index]
                RebuildPoster(item, onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RebuildDownloads(onPlay: (Item) -> Unit) {
    val ctx = LocalContext.current
    var records by remember { mutableStateOf(Downloads.list(ctx)) }
    var states by remember { mutableStateOf(emptyMap<String, DlState>()) }
    LaunchedEffect(Unit) {
        while (true) {
            records = Downloads.list(ctx)
            states = records.associate { it.id to Downloads.state(ctx, it) }
            delay(1000)
        }
    }
    val active = records.filter { states[it.id]?.status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED) }
    val complete = records.filter { states[it.id]?.status == DownloadManager.STATUS_SUCCESSFUL }
    Column(Modifier.fillMaxSize()) {
        RebuildHeader("Downloads", "${records.size} offline titles")
        if (records.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Your offline library is empty.", color = VMuted)
        } else LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            if (active.isNotEmpty()) item { RebuildSectionLabel("IN PROGRESS") }
            items(active, key = { it.id }) { rec -> RebuildDownloadRow(rec, states[rec.id], onDelete = { Downloads.remove(ctx, rec) }) }
            if (complete.isNotEmpty()) item { RebuildSectionLabel("AVAILABLE OFFLINE") }
            items(complete, key = { it.id }) { rec ->
                RebuildDownloadRow(rec, states[rec.id], onDelete = { Downloads.remove(ctx, rec) },
                    onPlay = { ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("id", rec.id).putExtra("title", rec.title).putExtra("offlinePath", rec.file)) })
            }
            val failed = records.filter { states[it.id]?.status == DownloadManager.STATUS_FAILED }
            if (failed.isNotEmpty()) item { RebuildSectionLabel("NEEDS ATTENTION") }
            items(failed, key = { it.id }) { rec -> RebuildDownloadRow(rec, states[rec.id], onDelete = { Downloads.remove(ctx, rec) }) }
        }
    }
}

@Composable
private fun RebuildSectionLabel(text: String) {
    Text(text, color = VCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun RebuildDownloadRow(rec: DownloadRec, state: DlState?, onDelete: () -> Unit, onPlay: (() -> Unit)? = null) {
    val pct = if ((state?.total ?: 0L) > 0L) ((state!!.done * 100L) / state.total).toInt() else 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(rec.cover.ifBlank { Api.base + "/Items/${rec.id}/Images/Primary" }, null,
            Modifier.size(58.dp, 78.dp).clip(RoundedCornerShape(8.dp)).background(VSurface2), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(rec.title, color = VText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(when (state?.status) {
                DownloadManager.STATUS_SUCCESSFUL -> "Ready offline"
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> "Downloading · $pct%"
                DownloadManager.STATUS_PAUSED -> "Paused"
                DownloadManager.STATUS_FAILED -> "Failed · ${Downloads.reasonText(state.reason)}"
                else -> "Checking file"
            }, color = VMuted, fontSize = 11.sp)
            if (state?.status == DownloadManager.STATUS_RUNNING || state?.status == DownloadManager.STATUS_PENDING) {
                LinearProgressIndicator(progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(3.dp), color = VCyan, trackColor = VSurface2)
            }
        }
        if (onPlay != null) Text("Play", color = VCyan, modifier = Modifier.clickable(onClick = onPlay).padding(8.dp))
        Text("Remove", color = VMuted, fontSize = 10.sp, modifier = Modifier.clickable(onClick = onDelete).padding(7.dp))
    }
}

@Composable
private fun RebuildSearch(onOpen: (Item) -> Unit, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Item>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = VText, fontSize = 30.sp, modifier = Modifier.clickable(onClick = back).padding(end = 12.dp))
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Titles, people, genres…") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = VText, unfocusedTextColor = VText,
                    focusedBorderColor = VCyan, unfocusedBorderColor = VSurface2, cursorColor = VCyan))
            Text("Go", color = VCyan, modifier = Modifier.clickable {
                scope.launch { busy = true; results = runCatching { Api.search(query.trim()) }.getOrDefault(emptyList()); busy = false }
            }.padding(start = 12.dp))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VCyan)
        if (results.isEmpty() && query.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Find something worth watching.", color = VMuted)
        } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(results.size, key = { results[it].id }) { index ->
                val item = results[index]
                RebuildPoster(item, onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RebuildDetail(base: Item, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val startDownload = rememberDownloadStarter()
    var item by remember(base.id) { mutableStateOf(base) }
    var seasons by remember(base.id) { mutableStateOf(emptyList<Item>()) }
    var episodes by remember(base.id) { mutableStateOf(emptyList<Item>()) }
    var similar by remember(base.id) { mutableStateOf(emptyList<Item>()) }
    var seasonIndex by remember(base.id) { mutableIntStateOf(0) }
    var saved by remember(base.id) { mutableStateOf(base.userData?.isFavorite == true) }
    var tab by remember { mutableStateOf(if (base.type == "Series" || base.type == "Season") "Episodes" else "About") }
    LaunchedEffect(base.id) {
        item = runCatching { Api.item(base.id) }.getOrDefault(base)
        if (base.type == "Series") {
            seasons = runCatching { Api.children(base.id) }.getOrDefault(emptyList()).filter { it.type == "Season" }
            if (seasons.isNotEmpty()) episodes = runCatching { Api.children(seasons.first().id) }.getOrDefault(emptyList())
        } else if (base.type == "Season") episodes = runCatching { Api.children(base.id) }.getOrDefault(emptyList())
        similar = runCatching { Api.similar(base.id) }.getOrDefault(emptyList())
    }
    val isShow = base.type == "Series" || base.type == "Season"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(340.dp).background(VSurface)) {
                AsyncImage(Api.backdrop(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22000000), VBg))))
                Text("‹", color = VText, fontSize = 30.sp,
                    modifier = Modifier.align(Alignment.TopStart).clickable(onClick = back).padding(18.dp))
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(item.type.uppercase(), color = VCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(item.name, color = VText, fontSize = 29.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(item.year?.toString(), item.officialRating, fmtRuntime(item.runTimeTicks).ifEmpty { null }).joinToString("  ·  "),
                        color = VMuted, fontSize = 12.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(item.overview.orEmpty(), color = Color(0xFFD3D8DF), fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = { onPlay(item) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VCyan, contentColor = VBg)) { Text("▶  Play", fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { Api.setFavorite(item.id, !saved) }; saved = !saved }
                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VText)) { Text(if (saved) "✓  Saved" else "+  My List") }
                    if (!isShow) OutlinedButton(onClick = { startDownload(item) }, shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VText)) { Text("↓") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                (if (isShow) listOf("Episodes", "About", "Similar") else listOf("About", "Similar")).forEach { label ->
                    Column(Modifier.clickable { tab = label }) {
                        Text(label, color = if (tab == label) VText else VMuted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.height(7.dp))
                        Box(Modifier.width(30.dp).height(2.dp).background(if (tab == label) VCyan else Color.Transparent))
                    }
                }
            }
        }
        when (tab) {
            "Episodes" -> {
                if (seasons.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        seasons.forEachIndexed { index, season ->
                            Text(season.name, color = if (index == seasonIndex) VBg else VMuted,
                                modifier = Modifier.clip(CircleShape).background(if (index == seasonIndex) VCyan else VSurface)
                                    .clickable {
                                        seasonIndex = index
                                        scope.launch { episodes = runCatching { Api.children(season.id) }.getOrDefault(emptyList()) }
                                    }.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 11.sp)
                        }
                    }
                }
                items(episodes, key = { it.id }) { ep ->
                    Row(Modifier.fillMaxWidth().clickable { onPlay(ep) }.padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(Api.landscape(ep), null, Modifier.size(124.dp, 70.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("${ep.index ?: ""}. ${ep.name}", color = VText, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2)
                            Text(fmtRuntime(ep.runTimeTicks), color = VMuted, fontSize = 11.sp)
                        }
                        Text("▶", color = VCyan)
                    }
                }
            }
            "Similar" -> item { RebuildShelf("More like this", similar, onOpen, "Similar") }
            else -> item {
                Column(Modifier.padding(18.dp)) {
                    item.rating?.let { Text("★ %.1f".format(it), color = VGold, fontSize = 13.sp) }
                    Text(item.genres.orEmpty().joinToString("  ·  "), color = VMuted, fontSize = 12.sp)
                    Text(item.people.orEmpty().filter { it.type == "Actor" }.take(8).joinToString("  ·  ") { it.name },
                        color = VMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}


@Composable
private fun RebuildFolderScreen(folder: Item, onOpen: (Item) -> Unit, back: () -> Unit) {
    var children by remember(folder.id) { mutableStateOf(emptyList<Item>()) }
    var loading by remember(folder.id) { mutableStateOf(true) }
    LaunchedEffect(folder.id) {
        children = runCatching { Api.children(folder.id) }.getOrDefault(emptyList())
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = VText, fontSize = 29.sp, modifier = Modifier.clickable(onClick = back).padding(end = 12.dp))
            Column {
                Text(folder.name, color = VText, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("LIBRARY COLLECTION", color = VMuted, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VCyan) }
        else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(children.size, key = { children[it].id }) { index ->
                val item = children[index]
                RebuildPoster(item, onClick = { onOpen(item) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RebuildSettingsScreen(
    back: () -> Unit, onRequest: () -> Unit, onClient: () -> Unit,
    onPlayback: () -> Unit, onDiagnostics: () -> Unit, onSignOut: () -> Unit,
    onWeb: (String, String) -> Unit
) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(VBg)) {
        RebuildHeader("Settings", "Your Allervue experience")
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            item { RebuildSectionLabel("ACCOUNT & SERVER") }
            item { RebuildSettingRow("Server and account", "Connection, profile and sign-in", onClient) }
            item { RebuildSettingRow("Request a title", "Ask your library administrator to add media", onRequest) }
            item { RebuildSectionLabel("PLAYBACK") }
            item { RebuildSettingRow("Playback preferences", "Quality, audio, subtitles and behavior", onPlayback) }
            item { RebuildSettingRow("Player engine", "Auto selection with Media3 and libmpv fallback") {
                ctx.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
                    .edit().putString("playback_engine", "AUTO").apply()
            } }
            item { RebuildSectionLabel("SUPPORT") }
            item { RebuildSettingRow("Diagnostics", "Connection checks and playback logs", onDiagnostics) }
            item { RebuildSettingRow("Allervue website", "Product information and updates") {
                onWeb("https://www.alleriafilmotheque.fyi", "Allervue")
            } }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                Text("Sign out", color = Color(0xFFFF8D8D), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(VSurface)
                        .clickable(onClick = onSignOut).padding(17.dp))
            }
            item {
                Text("ALLERVUE  ·  1.4.0", color = VMuted, fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RebuildSettingRow(title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(13.dp))
        .background(VSurface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = VText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(description, color = VMuted, fontSize = 11.sp)
        }
        Text("›", color = VCyan, fontSize = 24.sp)
    }
}
