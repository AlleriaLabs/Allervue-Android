@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.allerix.app

import android.app.DownloadManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RebuildTab(val label: String, val icon: Int) { HOME("Home", R.drawable.ic_nav_home), MOVIES("Movies", R.drawable.ic_nav_movie), SHOWS("TV Shows", R.drawable.ic_nav_tv), ANIME("Anime", R.drawable.ic_nav_star), COLLECTION("Collection", R.drawable.ic_nav_download) }
private val rebuildDetailTypes = setOf("Series", "Season", "Movie", "Episode", "Video", "MusicVideo")
private val VCyan = AllervueDesign.Cyan
private val VGold = AllervueDesign.Gold

@Composable
fun RebuildMainShell(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(RebuildTab.HOME) }
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
    BackHandler(enabled = stack.isEmpty() && tab != RebuildTab.HOME) { tab = RebuildTab.HOME }

    val top = stack.lastOrNull()
    val showNav = top == null || top is Nav.Detail || top is Nav.Grid
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                null -> when (tab) {
                    RebuildTab.HOME -> RebuildHome(onOpen, onPlay, { stack.add(Nav.Search) }, settings, signOut)
                    RebuildTab.MOVIES -> RebuildCatalog("Movies", "Movie", onOpen, { stack.add(Nav.Search) })
                    RebuildTab.SHOWS -> RebuildCatalog("TV Shows", "Series", onOpen, { stack.add(Nav.Search) })
                    RebuildTab.ANIME -> RebuildCatalog("Anime", "Anime", onOpen, { stack.add(Nav.Search) })
                    RebuildTab.COLLECTION -> RebuildCollection(onOpen, onPlay)
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
private fun RebuildBottomNav(selected: RebuildTab, onSelect: (RebuildTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        RebuildTab.values().forEach { destination ->
            val active = destination == selected
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) VCyan.copy(alpha = 0.13f) else Color.Transparent)
                    .clickable { onSelect(destination) }
                    .padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallIcon(destination.icon, if (active) VCyan else MaterialTheme.colorScheme.onSurfaceVariant, 21)
                Spacer(Modifier.height(3.dp))
                Text(
                    destination.label,
                    color = if (active) VCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun RebuildHeader(title: String, subtitle: String? = null, action: (() -> Unit)? = null, actionText: String = "Search") {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 26.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-.4).sp)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        if (action != null) {
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = action),
                contentAlignment = Alignment.Center
            ) {
                if (actionText.equals("Search", true)) Text("⌕", color = VCyan, fontSize = 27.sp)
                else Text("⋯", color = VCyan, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun RebuildHome(
    onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, onSearch: () -> Unit,
    onSettings: () -> Unit, onLogout: () -> Unit
) {
    AllervueHome(
        onOpen = onOpen,
        onPlay = onPlay,
        onSearch = onSearch,
        onSettings = onSettings,
        onLogout = onLogout
    )
}

@Composable
private fun RebuildShelf(title: String, entries: List<Item>, onItem: (Item) -> Unit, kicker: String) {
    Column(Modifier.fillMaxWidth().padding(top = 23.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kicker.uppercase(), color = VCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
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
        Box(Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(Api.poster(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val progress = item.userData?.playedPercentage?.toFloat()
                ?: if ((item.runTimeTicks ?: 0L) > 0L) ((item.userData?.positionTicks ?: 0L).toFloat() / item.runTimeTicks!!.toFloat()) * 100f else 0f
            if (progress > 0f && progress < 100f) {
                LinearProgressIndicator(progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = VCyan, trackColor = Color(0x66000000))
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, lineHeight = 15.sp,
            overflow = TextOverflow.Ellipsis)
        Text(item.year?.toString() ?: item.type, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}


@Composable
private fun RebuildDiscover(onOpen: (Item) -> Unit, onSearch: () -> Unit) {
    var type by remember { mutableStateOf("Movie") }
    Column(Modifier.fillMaxSize()) {
        RebuildHeader("Discover", "Explore your Jellyfin library", onSearch, "Search")
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Movie" to "Films", "Series" to "TV Shows", "Anime" to "Anime").forEach { (value, label) ->
                val active = type == value
                Text(label, color = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(if (active) VCyan else MaterialTheme.colorScheme.surface)
                        .clickable { type = value }.padding(horizontal = 15.dp, vertical = 9.dp))
            }
        }
        RebuildCatalog(if (type == "Movie") "Films" else if (type == "Series") "TV Shows" else "Anime", type, onOpen, onSearch, Modifier.weight(1f))
    }
}

@Composable
private fun RebuildCatalog(
    title: String, type: String, onOpen: (Item) -> Unit, onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var entries by remember(type) { mutableStateOf(emptyList<Item>()) }
    var loading by remember(type) { mutableStateOf(true) }
    var query by remember(type) { mutableStateOf("") }
    var view by remember(type) { mutableStateOf("Grid") }
    var filter by remember(type) { mutableStateOf("All") }
    var sortExpanded by remember { mutableStateOf(false) }
    var sort by remember(type) { mutableStateOf("A–Z") }
    var genre by remember(type) { mutableStateOf("All") }
    var genreExpanded by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    LaunchedEffect(type) {
        loading = true
        entries = when (type) {
            "Movie" -> runCatching {
                Api.libraryItems(
                    listOf("Jellyfin Movies Library", "Jellyfin Kid's Movies Library"),
                    listOf("Movie")
                )
            }.getOrDefault(emptyList())
            "Series" -> runCatching {
                Api.libraryItems(listOf("Jellyfin Series Library"), listOf("Series"))
            }.getOrDefault(emptyList())
            "Anime" -> runCatching {
                Api.libraryItems(listOf("Jellyfin Anime Library"), listOf("Movie", "Series"))
            }.getOrDefault(emptyList())
            else -> emptyList()
        }
        loading = false
    }

    val genres = remember(entries) {
        entries.flatMap { it.genres.orEmpty() }.filter { it.isNotBlank() }
            .distinct().sorted()
    }
    val filtered = remember(entries, query, filter, genre, sort, type) {
        val base = entries.filter { item ->
            val matchesQuery = query.isBlank() || item.name.contains(query.trim(), ignoreCase = true)
            val matchesWatched = when (filter) {
                "Unwatched" -> item.userData?.isPlayed != true
                "Watched" -> item.userData?.isPlayed == true
                else -> true
            }
            val matchesKind = when (filter) {
                "Series" -> item.type == "Series"
                "Films" -> item.type == "Movie"
                else -> true
            }
            val matchesGenre = genre == "All" || item.genres.orEmpty().any { it.equals(genre, true) }
            matchesQuery && matchesWatched && matchesKind && matchesGenre
        }
        when (sort) {
            "Z–A" -> base.sortedByDescending { it.name.lowercase() }
            "Year" -> base.sortedByDescending { it.year ?: 0 }
            "Recently added" -> base.sortedByDescending { it.year ?: 0 }
            else -> base.sortedBy { it.name.lowercase() }
        }
    }
    val groups = remember(filtered) {
        filtered.groupBy { item -> item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
            .toSortedMap()
    }
    val letters = groups.keys.toList()
    val gridIndex = { letter: String ->
        groups.keys.takeWhile { it != letter }.sumOf { 1 + (groups[it]?.size ?: 0) }
    }
    val listIndex = gridIndex

    Box(
        modifier.fillMaxSize().background(
            Brush.linearGradient(
                listOf(Color(0xFF351329), Color(0xFF24143A), Color(0xFF071C25), Color(0xFF080A10))
            )
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.weight(1f).height(54.dp),
                    singleLine = true, placeholder = { Text("Search ${entries.size} titles", color = Color(0xFF9B91A2)) },
                    leadingIcon = { Text("⌕", color = Color.White, fontSize = 25.sp) },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFB58AB8), unfocusedBorderColor = Color(0x66D6BBD6),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        cursorColor = Color(0xFFFF657D)
                    )
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color(0x44251B31))
                        .clickable { onSearch() }, contentAlignment = Alignment.Center
                ) { Text("☷", color = Color.White, fontSize = 25.sp) }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(78.dp).clip(CircleShape)
                        .background(Brush.sweepGradient(listOf(Color(0xFFFE4F83), Color(0xFF8B5CFF), Color(0xFFFFB24A), Color(0xFFFE4F83)))),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFFFFE8C6)))
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${entries.size} titles", color = Color(0xFFBEB3C6), fontSize = 14.sp)
                }
                Box {
                    Row(
                        Modifier.clip(CircleShape).background(Color(0x332E253A))
                            .clickable { sortExpanded = true }
                            .padding(horizontal = 17.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sort, color = Color.White, fontSize = 14.sp)
                        Text(" ⌄", color = Color.White, fontSize = 18.sp)
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        listOf("A–Z", "Z–A", "Year", "Recently added").forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = { sort = option; sortExpanded = false })
                        }
                    }
                }
            }

            val filters = when (type) {
                "Anime" -> listOf("Unwatched", "Series", "Films", "Mood")
                "Movie" -> listOf("Unwatched", "Films", "Mood")
                else -> listOf("Unwatched", "Series", "Mood")
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { label ->
                    val selected = when (label) {
                        "Mood" -> genre != "All"
                        else -> filter == label
                    }
                    Box {
                        Box(
                            Modifier.clip(CircleShape)
                                .background(if (selected) Brush.horizontalGradient(listOf(Color(0xFFFF794E), Color(0xFFFF397C))) else Brush.horizontalGradient(listOf(Color(0x332D243B), Color(0x332D243B))))
                                .clickable {
                                    if (label == "Mood") genreExpanded = true
                                    else filter = if (filter == label) "All" else label
                                }
                                .padding(horizontal = 19.dp, vertical = 12.dp)
                        ) {
                            Text(if (label == "Mood" && genre != "All") genre else label,
                                color = if (selected) Color(0xFF170C16) else Color.White, fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                        }
                        DropdownMenu(expanded = genreExpanded && label == "Mood", onDismissRequest = { genreExpanded = false }) {
                            (listOf("All") + genres).forEach { g ->
                                DropdownMenuItem(text = { Text(g) }, onClick = { genre = g; genreExpanded = false })
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Showing ${filtered.size} of ${entries.size}", color = Color(0xFFBEB3C6), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("Clear", color = Color(0xFFFF9A9C), fontSize = 13.sp,
                    modifier = Modifier.clickable { query = ""; filter = "All"; genre = "All"; sort = "A–Z" })
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFFFF5A79))
                } else if (filtered.isEmpty()) {
                    Text("No titles match these filters", color = Color(0xFFBEB3C6),
                        modifier = Modifier.align(Alignment.Center))
                } else if (view == "Grid") {
                    LazyVerticalGrid(
                        state = gridState, columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 48.dp, top = 8.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        groups.forEach { (letter, itemsForLetter) ->
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }, key = "header-$letter") {
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(34.dp).clip(CircleShape)
                                        .background(Brush.horizontalGradient(listOf(Color(0xFFFF754D), Color(0xFFFF3E80)))),
                                        contentAlignment = Alignment.Center) {
                                        Text(letter, color = Color(0xFF210D19), fontWeight = FontWeight.Bold)
                                    }
                                    Text("  ${itemsForLetter.size} titles", color = Color(0xFFBEB3C6), fontSize = 12.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Box(Modifier.weight(1f).height(1.dp).background(Color(0x338F7A98)))
                                }
                            }
                            items(itemsForLetter, key = { it.id }) { item ->
                                Column(Modifier.fillMaxWidth().clickable { onOpen(item) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape)
                                        .background(Color(0xFF152B3A))) {
                                        AsyncImage(Api.poster(item), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                        val progress = item.userData?.playedPercentage?.toFloat() ?: 0f
                                        if (progress in 0.1f..99.9f) {
                                            CircularProgressIndicator(progress = { progress / 100f },
                                                modifier = Modifier.fillMaxSize().padding(2.dp), color = Color(0xFFFF5A83),
                                                trackColor = Color.Transparent, strokeWidth = 3.dp)
                                        }
                                    }
                                    Text(item.name, color = Color(0xFFF5F0F8), fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 18.dp, end = 48.dp, top = 6.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        groups.forEach { (letter, itemsForLetter) ->
                            item(key = "header-$letter") {
                                Row(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(34.dp).clip(CircleShape)
                                        .background(Brush.horizontalGradient(listOf(Color(0xFFFF754D), Color(0xFFFF3E80)))),
                                        contentAlignment = Alignment.Center) {
                                        Text(letter, color = Color(0xFF210D19), fontWeight = FontWeight.Bold)
                                    }
                                    Text("  ${itemsForLetter.size} titles", color = Color(0xFFBEB3C6), fontSize = 12.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Box(Modifier.weight(1f).height(1.dp).background(Color(0x338F7A98)))
                                }
                            }
                            items(itemsForLetter, key = { it.id }) { item ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                                        .clickable { onOpen(item) }.padding(vertical = 7.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.size(82.dp).clip(CircleShape).background(Color(0xFF152B3A))) {
                                        AsyncImage(Api.poster(item), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                                    }
                                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                        Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(if (item.type == "Series") "Series" else "Film",
                                            item.year?.toString(), if ((item.runTimeTicks ?: 0L) > 0) fmtRuntime(item.runTimeTicks) else null)
                                            .joinToString(" · "), color = Color(0xFFBEB3C6), fontSize = 12.sp)
                                    }
                                    Box(Modifier.size(46.dp).clip(CircleShape)
                                        .background(Color(0x332E2944)), contentAlignment = Alignment.Center) {
                                        Text("▶", color = Color.White, fontSize = 17.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                if (letters.isNotEmpty()) {
                    Column(
                        Modifier.align(Alignment.CenterEnd).padding(end = 7.dp)
                            .clip(CircleShape).background(Color(0x33221D32)).padding(vertical = 7.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        letters.take(26).forEach { letter ->
                            Text(letter, color = Color(0xFFBDB1C7), fontSize = 9.sp,
                                modifier = Modifier.clickable {
                                    scope.launch {
                                        val index = if (view == "Grid") gridIndex(letter) else listIndex(letter)
                                        if (view == "Grid") gridState.animateScrollToItem(index)
                                        else listState.animateScrollToItem(index)
                                    }
                                }.padding(vertical = 2.dp, horizontal = 3.dp))
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(32.dp)).background(Color(0xE61A1429))
                    .padding(7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                listOf("List", "Grid").forEach { mode ->
                    val active = view == mode
                    Box(
                        Modifier.weight(1f).clip(CircleShape)
                            .background(if (active) Brush.horizontalGradient(listOf(Color(0xFFFF784F), Color(0xFFFF3D7E))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                            .clickable { view = mode }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (mode == "List") "☰  List" else "▦  Grid",
                            color = if (active) Color(0xFF1C0C19) else Color.White,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp)
                    }
                }
                Box(
                    Modifier.weight(1.5f).clip(CircleShape).background(Color(0x332E2944))
                        .clickable { filtered.randomOrNull()?.let(onOpen) }.padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("↗  Surprise me", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun AllervueCatalogCard(item: Item, number: Int, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.68f)
                .clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(Api.poster(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            val progress = item.userData?.playedPercentage?.toFloat()
                ?: if ((item.runTimeTicks ?: 0L) > 0L)
                    ((item.userData?.positionTicks ?: 0L).toFloat() / item.runTimeTicks!!.toFloat()) * 100f
                else 0f
            if (progress > 0f && progress < 100f) {
                LinearProgressIndicator(
                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = VCyan, trackColor = Color(0x66000000)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, lineHeight = 16.sp,
            overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(item.year?.toString(), item.type.takeIf { it != "Movie" })
            .joinToString(" · ").ifBlank { item.year?.toString() ?: " " },
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun RebuildCollection(onOpen: (Item) -> Unit, onPlay: (Item) -> Unit) {
    var section by remember { mutableStateOf("Saved") }
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF351329), Color(0xFF24143A), Color(0xFF0B2630), Color(0xFF080A11))))) {
        Canvas(Modifier.fillMaxSize()) {
            listOf(Offset(size.width*.08f,size.height*.13f), Offset(size.width*.82f,size.height*.09f),
                Offset(size.width*.92f,size.height*.28f), Offset(size.width*.17f,size.height*.42f),
                Offset(size.width*.71f,size.height*.57f), Offset(size.width*.11f,size.height*.76f),
                Offset(size.width*.88f,size.height*.88f)).forEachIndexed { i, p ->
                drawCircle(Color.White.copy(alpha = if (i % 3 == 0) .32f else .15f), if (i % 3 == 0) 2.1f else 1.2f, p)
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start=22.dp,end=18.dp,top=20.dp,bottom=14.dp), verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MY COLLECTION", color=Color(0xFFFF9C9C), fontSize=10.sp, fontWeight=FontWeight.Bold, letterSpacing=2.sp)
                    Text("Your library", color=Color.White, fontSize=29.sp, fontWeight=FontWeight.ExtraBold)
                    Text("Saved favorites and offline downloads", color=Color(0xFFBEB2C8), fontSize=12.sp)
                }
                Text("✦", color=Color(0xFFFFB36B), fontSize=25.sp, modifier=Modifier.padding(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=4.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0x55251D35)).border(1.dp,Color(0x33D8C2E8),RoundedCornerShape(18.dp)).padding(4.dp),
                horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("Saved","Downloads").forEach { label ->
                    val active = section == label
                    Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(if(active) Brush.horizontalGradient(listOf(Color(0xFFE95D83),Color(0xFF8B5CF6))) else Brush.horizontalGradient(listOf(Color.Transparent,Color.Transparent)))
                        .clickable { section=label }.padding(vertical=12.dp),
                        horizontalArrangement=Arrangement.Center, verticalAlignment=Alignment.CenterVertically) {
                        Text(if(label=="Saved") "♡" else "↓", color=Color.White, fontSize=15.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(label,color=Color.White,fontSize=13.sp,fontWeight=if(active) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                if(section=="Saved") RebuildSaved(onOpen,onPlay,showHeader=false) else RebuildDownloads(showHeader=false)
            }
        }
    }
}

@Composable
private fun RebuildSaved(onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, showHeader: Boolean = true) {
    var savedItems by remember { mutableStateOf(emptyList<Item>()) }
    var animeLibraryIds by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("All") }
    LaunchedEffect(Unit) {
        savedItems = runCatching { Api.favorites() }.getOrDefault(emptyList())
        animeLibraryIds = runCatching { Api.views().filter { it.name.equals("Jellyfin Anime Library", true) }.map { it.id }.toSet() }.getOrDefault(emptySet())
        loading = false
    }
    val visible = when(filter) {
        "Films" -> savedItems.filter { it.type=="Movie" }
        "Series" -> savedItems.filter { it.type=="Series" }
        "Anime" -> savedItems.filter { it.parentId in animeLibraryIds || (it.genres.orEmpty()+it.tags.orEmpty()).any { tag -> tag.contains("anime",true) } }
        else -> savedItems
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if(showHeader) "My Collection" else "Saved for later",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold)
                Text(savedItems.size.toString()+" saved titles",color=Color(0xFFBEB2C8),fontSize=11.sp)
            }
            Text("✦  FAVORITES",color=Color(0xFFFFB36B),fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.2.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start=18.dp,end=18.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("All","Films","Series","Anime").forEach { label ->
                val active=filter==label
                Text(label,color=if(active) Color.White else Color(0xFFBEB2C8),fontSize=11.sp,fontWeight=FontWeight.SemiBold,
                    modifier=Modifier.clip(CircleShape).background(if(active) Brush.horizontalGradient(listOf(Color(0xFFE95D83),Color(0xFF8B5CF6))) else Brush.horizontalGradient(listOf(Color(0x332F253D),Color(0x332F253D))))
                        .border(1.dp,if(active) Color.Transparent else Color(0x33D8C2E8),CircleShape)
                        .clickable { filter=label }.padding(horizontal=15.dp,vertical=9.dp))
            }
        }
        if(loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator(color=Color(0xFFFF7895)) }
        else if(visible.isEmpty()) Box(Modifier.fillMaxSize().padding(28.dp),contentAlignment=Alignment.Center) {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(if(savedItems.isEmpty()) "♡" else "✦",color=Color(0xFFFF9C9C),fontSize=42.sp)
                Text(if(savedItems.isEmpty()) "Your saved collection is waiting." else "No "+filter.lowercase()+" saved yet.",
                    color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                Text("Save titles from their details page to keep them here.",color=Color(0xFFBEB2C8),fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
            }
        } else LazyVerticalGrid(GridCells.Fixed(2),Modifier.fillMaxSize(),
            contentPadding=PaddingValues(start=16.dp,end=16.dp,top=8.dp,bottom=24.dp),
            horizontalArrangement=Arrangement.spacedBy(13.dp),verticalArrangement=Arrangement.spacedBy(17.dp)) {
            items(visible.size,key={visible[it].id}) { index ->
                val item=visible[index]
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(Color(0x33231B31))
                    .clickable { onOpen(item) }.padding(7.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(11.dp)).background(Color(0xFF30263C))) {
                        AsyncImage(Api.poster(item),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(25.dp).clip(CircleShape).background(Color(0xB51A1425)),contentAlignment=Alignment.Center) {
                            Text("♡",color=Color(0xFFFF91A9),fontSize=15.sp)
                        }
                        val progress=item.userData?.playedPercentage?.toFloat()
                            ?: if((item.runTimeTicks?:0L)>0L) ((item.userData?.positionTicks?:0L).toFloat()/item.runTimeTicks!!.toFloat())*100f else 0f
                        if(progress>0f && progress<100f) LinearProgressIndicator(progress={ (progress/100f).coerceIn(0f,1f) },
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),color=Color(0xFFFF7895),trackColor=Color(0x66000000))
                    }
                    Text(item.name,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=2,lineHeight=15.sp,
                        overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(start=3.dp,top=8.dp))
                    Text(listOfNotNull(item.year?.toString(),item.type.takeIf { it!="Movie" }).joinToString(" · "),
                        color=Color(0xFFBEB2C8),fontSize=10.sp,modifier=Modifier.padding(start=3.dp,top=3.dp))
                }
            }
        }
    }
}

@Composable
private fun RebuildDownloads(showHeader: Boolean = true) {
    val ctx=LocalContext.current
    var records by remember { mutableStateOf(Downloads.list(ctx)) }
    var states by remember { mutableStateOf(emptyMap<String,DlState>()) }
    LaunchedEffect(Unit) { while(true) { records=Downloads.list(ctx); states=records.associate { it.id to Downloads.state(ctx,it) }; delay(1000) } }
    val active=records.filter { states[it.id]?.status in listOf(DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED) }
    val complete=records.filter { states[it.id]?.status==DownloadManager.STATUS_SUCCESSFUL }
    val failed=records.filter { states[it.id]?.status==DownloadManager.STATUS_FAILED }
    val mediaBytes=records.sumOf { rec ->
        val media=java.io.File(rec.file).takeIf { it.exists() }?.length() ?: states[rec.id]?.done ?: 0L
        media+rec.subtitles.sumOf { sub -> java.io.File(sub.file).takeIf { it.exists() }?.length() ?: 0L }
    }
    val stat=remember(ctx) { android.os.StatFs((ctx.getExternalFilesDir(null)?:ctx.filesDir).absolutePath) }
    val totalBytes=stat.totalBytes.coerceAtLeast(1L)
    val freeBytes=stat.availableBytes.coerceAtLeast(0L)
    val usedBytes=(totalBytes-freeBytes).coerceAtLeast(0L)
    val pctUsed=(usedBytes.toFloat()/totalBytes.toFloat()).coerceIn(0f,1f)
    Column(Modifier.fillMaxSize()) {
        if(showHeader) Text("Downloads",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(start=21.dp,top=18.dp))
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=18.dp,end=18.dp,top=8.dp,bottom=26.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF48213F),Color(0xFF30244D),Color(0xFF12333A))))
                    .border(1.dp,Color(0x33E4C3E8),RoundedCornerShape(23.dp)).padding(18.dp)) {
                    Text("READY TO WATCH OFFLINE",color=Color(0xFFFFB36B),fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.6.sp)
                    Text("Your downloads",color=Color.White,fontSize=23.sp,fontWeight=FontWeight.ExtraBold,modifier=Modifier.padding(top=4.dp))
                    Text(complete.size.toString()+" ready · "+active.size+" in progress",color=Color(0xFFD0C3D8),fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
                    Row(Modifier.fillMaxWidth().padding(top=17.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(142.dp),contentAlignment=Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val stroke=13.dp.toPx(); val diameter=size.minDimension-stroke
                                val rect=Offset((size.width-diameter)/2f,(size.height-diameter)/2f)
                                drawArc(Color(0xFF8B5CF6),-90f,360f,false,rect,Size(diameter,diameter),style=Stroke(stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                                drawArc(Color(0xFFFFA45B),-90f,pctUsed*360f,false,rect,Size(diameter,diameter),style=Stroke(stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                                val downloadSweep=(mediaBytes.toFloat()/totalBytes.toFloat()*360f).coerceIn(0f,360f)
                                if(mediaBytes>0L) drawArc(Color(0xFF39D6C5),-90f,downloadSweep,false,rect,Size(diameter,diameter),style=Stroke(stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                                drawArc(Color(0xFFFF6F91),135f,9f,false,rect,Size(diameter,diameter),style=Stroke(stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                            }
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                Text(formatCollectionBytes(usedBytes),color=Color.White,fontSize=19.sp,fontWeight=FontWeight.ExtraBold)
                                Text("USED",color=Color(0xFFD0C3D8),fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.3.sp)
                            }
                        }
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            StorageLegend(Color(0xFFFFA45B),"Phone used",formatCollectionBytes(usedBytes))
                            StorageLegend(Color(0xFF8B5CF6),"Available",formatCollectionBytes(freeBytes))
                            StorageLegend(Color(0xFF39D6C5),"Offline media",formatCollectionBytes(mediaBytes))
                        }
                    }
                    Text(formatCollectionBytes(totalBytes)+" total device storage",color=Color(0xFFBEB2C8),fontSize=10.sp,modifier=Modifier.padding(top=13.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top=7.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("On this phone",color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold)
                        Text(records.size.toString()+" offline titles · "+formatCollectionBytes(mediaBytes)+" downloaded",color=Color(0xFFBEB2C8),fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
                    }
                    Text("LOCAL STORAGE",color=Color(0xFFFFB36B),fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                }
            }
            if(records.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical=34.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("↓",color=Color(0xFF39D6C5),fontSize=42.sp)
                    Text("Nothing downloaded yet",color=Color.White,fontSize=14.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
                    Text("Use the download action on a title to watch it offline.",color=Color(0xFFBEB2C8),fontSize=11.sp,modifier=Modifier.padding(top=4.dp))
                }
            }
            if(active.isNotEmpty()) { item { CollectionDownloadLabel("DOWNLOADING") }; items(active,key={it.id}) { rec -> CollectionDownloadCard(rec,states[rec.id],onDelete={Downloads.remove(ctx,rec)}) } }
            if(complete.isNotEmpty()) { item { CollectionDownloadLabel("AVAILABLE OFFLINE") }; items(complete,key={it.id}) { rec ->
                CollectionDownloadCard(rec,states[rec.id],onDelete={Downloads.remove(ctx,rec)},onPlay={
                    ctx.startActivity(Intent(ctx,PlayerActivity::class.java).putExtra("id",rec.id).putExtra("title",rec.title).putExtra("file",rec.file))
                })
            } }
            if(failed.isNotEmpty()) { item { CollectionDownloadLabel("NEEDS ATTENTION") }; items(failed,key={it.id}) { rec -> CollectionDownloadCard(rec,states[rec.id],onDelete={Downloads.remove(ctx,rec)}) } }
        }
    }
}

@Composable
private fun StorageLegend(color: Color,label: String,value: String) {
    Row(verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start=8.dp)) {
            Text(label,color=Color(0xFFD0C3D8),fontSize=10.sp)
            Text(value,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.Bold)
        }
    }
}

@Composable
private fun CollectionDownloadLabel(text: String) {
    Text(text,color=Color(0xFFFFB36B),fontSize=9.sp,fontWeight=FontWeight.Bold,letterSpacing=1.5.sp,
        modifier=Modifier.padding(start=2.dp,top=7.dp,bottom=1.dp))
}

@Composable
private fun CollectionDownloadCard(rec: DownloadRec,state: DlState?,onDelete: () -> Unit,onPlay: (() -> Unit)?=null) {
    val pct=if((state?.total?:0L)>0L) ((state!!.done*100L)/state.total).toInt() else 0
    val status=when(state?.status) {
        DownloadManager.STATUS_SUCCESSFUL -> "Ready to watch offline"
        DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PENDING -> "Downloading · "+pct+"%"
        DownloadManager.STATUS_PAUSED -> "Paused"
        DownloadManager.STATUS_FAILED -> "Failed · "+Downloads.reasonText(state.reason)
        else -> "Checking download"
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color(0x44251D35))
        .border(1.dp,Color(0x22D8C2E8),RoundedCornerShape(18.dp))
        .clickable(enabled=onPlay!=null,onClick={onPlay?.invoke()}).padding(horizontal=12.dp,vertical=11.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFF34253F))) {
            AsyncImage(rec.cover.ifBlank { Api.base+"/Items/"+rec.id+"/Images/Primary" },null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            if(state?.status==DownloadManager.STATUS_SUCCESSFUL) Box(Modifier.align(Alignment.BottomEnd).size(21.dp)
                .clip(CircleShape).background(Color(0xFF39D6C5)).border(2.dp,Color(0xFF251C35),CircleShape),contentAlignment=Alignment.Center) {
                Text("✓",color=Color(0xFF071C20),fontSize=12.sp,fontWeight=FontWeight.ExtraBold)
            } else if(state?.status==DownloadManager.STATUS_RUNNING || state?.status==DownloadManager.STATUS_PENDING) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Color(0xFFFFA45B),-90f,pct*3.6f,false,Offset(2.dp.toPx(),2.dp.toPx()),
                        Size(size.width-4.dp.toPx(),size.height-4.dp.toPx()),style=Stroke(3.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
        }
        Column(Modifier.weight(1f).padding(start=13.dp,end=8.dp)) {
            Text(rec.title,color=Color.White,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(status,color=if(state?.status==DownloadManager.STATUS_FAILED) Color(0xFFFF9C9C) else Color(0xFFBEB2C8),
                fontSize=10.sp,modifier=Modifier.padding(top=4.dp),maxLines=2)
            if(state?.status==DownloadManager.STATUS_RUNNING || state?.status==DownloadManager.STATUS_PENDING)
                LinearProgressIndicator(progress={if(state.total>0L) state.done.toFloat()/state.total else 0f},
                    Modifier.fillMaxWidth().padding(top=7.dp).height(3.dp),color=Color(0xFF39D6C5),trackColor=Color(0xFF57465E))
        }
        if(onPlay!=null) Text("PLAY",color=Color(0xFF39D6C5),fontSize=9.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=7.dp,vertical=10.dp))
        Text("⋯",color=Color(0xFFBEB2C8),fontSize=20.sp,modifier=Modifier.clickable(onClick=onDelete).padding(start=5.dp,end=2.dp))
    }
}

private fun formatCollectionBytes(bytes: Long): String {
    val gb=bytes/(1024.0*1024.0*1024.0)
    return if(gb>=1.0) String.format(java.util.Locale.US,"%.1f GB",gb)
    else String.format(java.util.Locale.US,"%.0f MB",bytes/(1024.0*1024.0))
}
@Composable
private fun RebuildSearch(onOpen: (Item) -> Unit, back: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<Item>()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = MaterialTheme.colorScheme.onBackground, fontSize = 30.sp, modifier = Modifier.clickable(onClick = back).padding(end = 12.dp))
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("Titles, people, genres…") },
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MaterialTheme.colorScheme.onBackground, unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedBorderColor = VCyan, unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant, cursorColor = VCyan))
            Text("Go", color = VCyan, modifier = Modifier.clickable {
                scope.launch { busy = true; results = runCatching { Api.search(query.trim()) }.getOrDefault(emptyList()); busy = false }
            }.padding(start = 12.dp))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = VCyan)
        if (results.isEmpty() && query.isBlank()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Find something worth watching.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(results.size, key = { results[it].id }) { index ->
                val item = results[index]
                AllervueCatalogCard(item, index + 1, onClick = { onOpen(item) })
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
    var tab by remember(base.id) { mutableStateOf(if (base.type == "Series" || base.type == "Season") "Episodes" else "About") }
    val isShow = base.type == "Series" || base.type == "Season"
    val isEpisode = base.type == "Episode"

    LaunchedEffect(base.id) {
        item = runCatching { Api.item(base.id) }.getOrDefault(base)
        if (base.type == "Series") {
            seasons = runCatching { Api.children(base.id) }.getOrDefault(emptyList()).filter { it.type == "Season" }
            if (seasons.size == 1) episodes = runCatching { Api.children(seasons.first().id) }.getOrDefault(emptyList())
        } else if (base.type == "Season") {
            episodes = runCatching { Api.children(base.id) }.getOrDefault(emptyList())
        }
        similar = runCatching { Api.similar(base.id) }.getOrDefault(emptyList())
    }

    val video = item.mediaSources?.firstOrNull()?.streams?.firstOrNull { it.type == "Video" }
    val h = video?.height ?: 0
    val resolution = when { h >= 4000 -> "8K"; h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; h > 0 -> "SD"; else -> "" }
    val hdr = video?.videoRange?.contains("HDR", ignoreCase = true) == true
    val metadata = listOfNotNull(if (isShow) "SERIES" else if (isEpisode) "EPISODE" else "MOVIE",
        item.year?.toString(), item.officialRating, resolution.takeIf { it.isNotBlank() }, "HDR".takeIf { hdr }).joinToString("  ·  ")
    val total = item.runTimeTicks ?: 0L
    val position = item.userData?.positionTicks ?: 0L
    val progress = if (total > 0L) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    val remaining = if (total > 0L && position > 0L && position < total) fmtRuntime(total - position) else ""
    // Orbit nodes represent actual seasons or episodes, never decorative placeholders.
    // A multi-season series shows seasons; a single-season series shows its episodes.
    val orbitItems = when {
        base.type == "Series" && seasons.size > 1 -> seasons
        base.type == "Series" -> episodes
        base.type == "Season" -> episodes
        else -> emptyList()
    }
    val showOrbit = orbitItems.size > 1

    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF35151D), Color(0xFF28143A), Color(0xFF0B2028), Color(0xFF080A11))))) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(Api.backdrop(item), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xAA160A18), .38f to Color(0x66301938), 1f to Color(0xFF24152E))))
                    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0x55FF754E), Color(0x333F45B8), Color.Transparent), radius = 900f)))
                    Canvas(Modifier.fillMaxSize()) {
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val r = size.minDimension * .405f
                        if (showOrbit) {
                            drawCircle(Color(0x557F8CA9), r, Offset(cx, cy), style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 13f))))
                            drawCircle(Color(0x227F8CA9), r * .76f, Offset(cx, cy), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 15f))))
                        }
                        drawArc(Color(0xFFFF477F), -82f, if (progress > 0f) 42f + progress * 30f else 58f, false,
                            Offset(cx-r, cy-r), Size(r*2f, r*2f), style = Stroke(4.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        listOf(Offset(size.width*.12f,size.height*.20f),Offset(size.width*.84f,size.height*.16f),
                            Offset(size.width*.91f,size.height*.72f),Offset(size.width*.16f,size.height*.82f),Offset(size.width*.51f,size.height*.07f))
                            .forEach { drawCircle(Color(0x997F9AB4),1.7f,it) }
                    }
                    if (showOrbit) {
                        orbitItems.forEachIndexed { index, orbitItem ->
                            val angle = (index.toDouble() / orbitItems.size.toDouble()) * 2.0 * PI - PI / 2.0
                            val x = (cos(angle) * 142.0).toFloat().dp
                            val y = (sin(angle) * 142.0).toFloat().dp
                            val isCurrent = orbitItem.userData?.isPlayed == true ||
                                (orbitItem.userData?.positionTicks ?: 0L) > 0L
                            Box(
                                Modifier.offset(x, y).size(54.dp).clip(CircleShape)
                                    .background(Color(0xFF171325))
                                    .border(if (isCurrent) 2.5.dp else 1.5.dp,
                                        if (isCurrent) Color(0xFFFF3982) else Color(0x889A91AA), CircleShape)
                                    .clickable { onOpen(orbitItem) },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    Api.poster(orbitItem), null,
                                    Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                                Box(
                                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                        .background(Color(0xCC100D1B)).padding(vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        if (orbitItem.type == "Season") "S${orbitItem.index ?: index + 1}"
                                        else "E${orbitItem.index ?: index + 1}",
                                        color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    Box(Modifier.size(204.dp).clip(CircleShape).background(Color(0xFF171325)).border(3.dp,Color(0xAA9A688E),CircleShape)) {
                        AsyncImage(Api.poster(item),null,Modifier.fillMaxSize().clip(CircleShape),contentScale=ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x11000000),.58f to Color(0x22000000),1f to Color(0xAA090713))))
                        CircularProgressIndicator(progress={progress},modifier=Modifier.fillMaxSize().padding(2.dp),color=Color(0xFFFF557E),trackColor=Color(0x668C7A9B),strokeWidth=3.dp)
                    }
                    if(remaining.isNotEmpty()) Text("${if(isEpisode) "EPISODE" else "CONTINUE"} · $remaining LEFT",color=Color.White,fontSize=11.sp,fontWeight=FontWeight.SemiBold,
                        modifier=Modifier.align(Alignment.CenterEnd).offset(x=18.dp,y=(-45).dp).clip(CircleShape).background(Color(0x884A3B5B)).border(1.dp,Color(0x55FFFFFF),CircleShape).padding(horizontal=12.dp,vertical=8.dp))
                    IconButton(onClick=back,modifier=Modifier.align(Alignment.TopStart).padding(14.dp).size(46.dp).clip(CircleShape).background(Color(0x5520182C))) { SmallIcon(R.drawable.ic_back,Color.White,22) }
                    IconButton(onClick={
                        val send=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,"${item.name} (${item.year ?: ""})") }
                        ctx.startActivity(Intent.createChooser(send,"Share title"))
                    },modifier=Modifier.align(Alignment.TopEnd).padding(14.dp).size(46.dp).clip(CircleShape).background(Color(0x5520182C))) { Text("↥",color=Color.White,fontSize=24.sp) }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
                    Text(metadata,color=Color(0xFFBDB3C7),fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.6.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(item.name,color=Color(0xFFF7F2FA),fontSize=34.sp,lineHeight=38.sp,fontWeight=FontWeight.ExtraBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Text(item.overview.orEmpty().ifBlank { "Discover more about this title in your Allervue library." },color=Color(0xFFD0C7D5),fontSize=15.sp,lineHeight=23.sp)
                    Spacer(Modifier.height(17.dp))
                    val cast=item.people.orEmpty().filter { it.type=="Actor" }.take(3)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        cast.forEachIndexed { i,p ->
                            Box(Modifier.size(42.dp).offset(x=(-i*7).dp).clip(CircleShape).background(listOf(Color(0xFFFF875A),Color(0xFFEA62A8),Color(0xFF4654A8))[i%3]).border(1.dp,Color(0xFF20172C),CircleShape),contentAlignment=Alignment.Center) {
                                Text(p.name.firstOrNull()?.uppercase() ?: "•",color=Color.White,fontWeight=FontWeight.Bold)
                            }
                        }
                        if(cast.isNotEmpty()) Text(cast.joinToString(" · ") { it.name },color=Color(0xFFB9B0C5),fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(top=20.dp,bottom=8.dp).clip(RoundedCornerShape(topStart=34.dp,topEnd=34.dp)).background(Color(0xEE171329)).padding(horizontal=20.dp,vertical=22.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if(remaining.isNotEmpty()) "Continue watching" else "Ready when you are",color=Color.White,fontSize=22.sp,fontWeight=FontWeight.ExtraBold)
                            Text(if(remaining.isNotEmpty()) "$remaining remaining" else fmtRuntime(item.runTimeTicks).ifEmpty { "Full title" },color=Color(0xFFB8AEC4),fontSize=13.sp,modifier=Modifier.padding(top=3.dp))
                        }
                        Button(onClick={onPlay(item)},modifier=Modifier.height(58.dp).weight(1.05f),shape=CircleShape,colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFFF4D70),contentColor=Color(0xFF1A0A17))) {
                            Text(if(progress>.02f) "▶  Resume" else "▶  Play",fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick={scope.launch { runCatching { Api.setFavorite(item.id,!saved) }; saved=!saved }},modifier=Modifier.weight(1f).height(48.dp),shape=CircleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text(if(saved) "✓  Saved" else "＋  Save",maxLines=1) }
                        OutlinedButton(onClick={startDownload(item)},modifier=Modifier.weight(1f).height(48.dp),shape=CircleShape,colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) { Text("↓  Download",maxLines=1) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=18.dp),horizontalArrangement=Arrangement.spacedBy(26.dp)) {
                    (if(isShow) listOf("Episodes","About","Similar") else listOf("About","Similar")).forEach { label ->
                        Column(Modifier.clickable { tab=label }) {
                            Text(label,color=if(tab==label)Color.White else Color(0xFF9F95AC),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape).background(if(tab==label)Color(0xFFFF587B) else Color.Transparent))
                        }
                    }
                }
            }
            when(tab) {
                "Episodes" -> {
                    if(seasons.size>1) item {
                        LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(seasons.size) { i ->
                                val active=i==seasonIndex
                                Text(seasons[i].name,color=if(active)Color(0xFF1B0D1A) else Color.White,modifier=Modifier.clip(CircleShape).background(if(active)Color(0xFFFF647C) else Color(0x44352D47)).clickable {
                                    seasonIndex=i
                                    scope.launch { episodes=runCatching { Api.children(seasons[i].id) }.getOrDefault(emptyList()) }
                                }.padding(horizontal=14.dp,vertical=9.dp),fontSize=12.sp)
                            }
                        }
                    }
                    items(episodes,key={it.id}) { ep ->
                        Row(Modifier.fillMaxWidth().clickable { onPlay(ep) }.padding(horizontal=20.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically) {
                            AsyncImage(Api.landscape(ep),null,Modifier.size(124.dp,70.dp).clip(RoundedCornerShape(10.dp)),contentScale=ContentScale.Crop)
                            Column(Modifier.weight(1f).padding(start=12.dp)) {
                                Text("${ep.index ?: ""}. ${ep.name}",color=Color.White,fontWeight=FontWeight.SemiBold,fontSize=13.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(fmtRuntime(ep.runTimeTicks),color=Color(0xFFB9B0C5),fontSize=11.sp)
                            }
                            Text("▶",color=Color(0xFFFF6681))
                        }
                    }
                }
                "Similar" -> item { RebuildShelf("More like this",similar,onOpen,"Similar") }
                else -> item {
                    Column(Modifier.padding(horizontal=20.dp,vertical=12.dp)) {
                        item.rating?.let { Text("★ %.1f".format(it),color=VGold,fontSize=13.sp) }
                        val genres=item.genres.orEmpty().joinToString("  ·  ")
                        if(genres.isNotBlank()) Text(genres,color=Color(0xFFB9B0C5),fontSize=12.sp)
                        val people=item.people.orEmpty().filter { it.type=="Actor" }.take(10).joinToString("  · ") { it.name }
                        if(people.isNotBlank()) Text(people,color=Color(0xFFB9B0C5),fontSize=12.sp,modifier=Modifier.padding(top=10.dp))
                    }
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
            Text("‹", color = MaterialTheme.colorScheme.onBackground, fontSize = 29.sp, modifier = Modifier.clickable(onClick = back).padding(end = 12.dp))
            Column {
                Text(folder.name, color = MaterialTheme.colorScheme.onBackground, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("LIBRARY COLLECTION", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp, letterSpacing = 1.5.sp)
            }
        }
        if (loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VCyan) }
        else LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(children.size, key = { children[it].id }) { index ->
                val item = children[index]
                AllervueCatalogCard(item, index + 1, onClick = { onOpen(item) })
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
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = MaterialTheme.colorScheme.onBackground, fontSize = 28.sp, modifier = Modifier.clickable(onClick = back).padding(end = 12.dp))
            Column { Text("Settings", color = MaterialTheme.colorScheme.onBackground, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("Your Allervue experience", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            item { RebuildSectionLabel("ACCOUNT & SERVER") }
            item { RebuildSettingRow("Server and account", "Connection, profile and sign-in", onClient) }
            item { RebuildSettingRow("Request a title", "Ask your library administrator to add media", onRequest) }
            item { RebuildSectionLabel("PLAYBACK") }
            item { RebuildSettingRow("Playback preferences", "Quality, audio, subtitles and behavior", onPlayback) }
            item { RebuildSettingRow("Automatic playback", "The player chooses Media3 or libmpv when needed", onPlayback) }
            item { RebuildSectionLabel("SUPPORT") }
            item { RebuildSettingRow("Diagnostics", "Connection checks and playback logs", onDiagnostics) }
            item { RebuildSettingRow("Allervue website", "Product information and updates") {
                onWeb("https://www.alleriafilmotheque.fyi", "Allervue")
            } }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                Text("Sign out", color = Color(0xFFFF8D8D), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onSignOut).padding(17.dp))
            }
            item {
                Text("ALLERVUE  ·  1.4.0", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 22.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RebuildSettingRow(title: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(13.dp))
        .background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Text("›", color = VCyan, fontSize = 24.sp)
    }
}

