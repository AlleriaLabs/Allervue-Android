@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.allerix.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.core.content.ContextCompat
import android.widget.Toast
import java.io.File
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ home
@Composable
fun HomeScreen(
    onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, onSeeAll: (Item, List<Item>) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, onLogout: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var featured by remember { mutableStateOf<List<Item>>(emptyList()) }
    var resume by remember { mutableStateOf<List<Item>>(emptyList()) }
    var trending by remember { mutableStateOf<List<Item>>(emptyList()) }
    var recommended by remember { mutableStateOf<List<Item>>(emptyList()) }
    var rows by remember { mutableStateOf<List<Pair<Item, List<Item>>>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            featured = runCatching { Api.featured() }.getOrDefault(emptyList())
            resume = Api.resume().filterNot { it.id in ContinueWatching.hiddenIds(ctx) }
            trending = runCatching { Api.trending() }.getOrDefault(emptyList())
            rows = Api.views().map { it to Api.latest(it.id) }.filter { it.second.isNotEmpty() }
            loading = false
            // Recommendation analysis runs after the core shelves render, so it never delays first paint.
            launch {
                recommended = runCatching {
                    Api.personalizedPicks((resume + trending).map { it.id }.toSet())
                }.getOrDefault(emptyList())
            }
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) onLogout() else error = "Can't reach the server. Check your connection."
        }
        loading = false
    }
    val heroes = featured.ifEmpty { trending.take(6) }
    val pager = rememberPagerState(pageCount = { heroes.size })

    // Keyed only on the number of slides: keying on currentPage cancelled the slide animation halfway.
    LaunchedEffect(heroes.size) {
        if (heroes.size > 1) {
            while (true) {
                delay(5000)
                if (!pager.isScrollInProgress) {
                    pager.animateScrollToPage((pager.currentPage + 1) % heroes.size, animationSpec = tween(700))
                }
            }
        }
    }
    val bgItem = heroes.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(BgDark)) {
        Crossfade(targetState = bgItem, animationSpec = tween(900), label = "bg") { scene ->
            if (scene != null) AsyncImage(
                Api.blurBg(scene), null, Modifier.fillMaxSize().blur(20.dp), contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(Color(0xE60A0A0C)))

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { HeroCarousel(heroes, pager, onPlay, onOpen) }
            if (resume.isNotEmpty()) item {
                MediaRow(
                    "Continue Watching", resume, onClick = onPlay,
                    onMore = { item, action ->
                        scope.launch {
                            when (action) {
                                "hide" -> {
                                    ContinueWatching.hide(ctx, item.id)
                                    resume = resume.filterNot { it.id == item.id }
                                    Toast.makeText(ctx, "Removed from Continue Watching", Toast.LENGTH_SHORT).show()
                                }
                                "watched" -> runCatching { Api.markPlayed(item.id) }
                                    .onSuccess {
                                        resume = resume.filterNot { it.id == item.id }
                                        Toast.makeText(ctx, "Marked as watched", Toast.LENGTH_SHORT).show()
                                    }
                                    .onFailure {
                                        Toast.makeText(ctx, "Couldn't update watched status: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                            }
                        }
                    }
                )
            }
            val popularMovies = trending.filter { it.type == "Movie" }
            val popularShows = trending.filter { it.type == "Series" }
            if (popularMovies.isNotEmpty()) item { MediaRow("Recently Added Movies", popularMovies, onClick = onOpen) }
            if (popularShows.isNotEmpty()) item { MediaRow("Recently Added Series", popularShows, onClick = onOpen) }
            val picks = recommended.take(12)
            if (picks.isNotEmpty()) item { MediaRow("Picked for You", picks, onClick = onOpen) }
            items(rows, key = { it.first.id }) { (lib, list) ->
                MediaRow(
                    when {
                        list.firstOrNull()?.type == "Movie" -> "Latest Movies"
                        list.firstOrNull()?.type == "Series" && list.all { it.isAnimeSeries() } -> "Latest Anime"
                        list.firstOrNull()?.type == "Series" -> "Latest Series"
                        else -> "Latest in ${lib.name}"
                    }, list, onClick = onOpen, onSeeAll = { onSeeAll(lib, list) }
                )
            }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
        if (error.isNotEmpty()) Text(error, Modifier.align(Alignment.Center).padding(24.dp), color = Grey)

        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.ic_allervue_emblem), "Allervue emblem", Modifier.size(34.dp))
            Text("ALLERVUE", color = Color(0xFFD9B77D), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.2.sp, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSearch) { SmallIcon(R.drawable.ic_search) }
            IconButton(onClick = onSettings) { SmallIcon(R.drawable.ic_settings) }
        }
    }
}

@Composable
fun HeroCarousel(heroes: List<Item>, pager: PagerState, onPlay: (Item) -> Unit, onOpen: (Item) -> Unit) {
    Box(Modifier.fillMaxWidth().height(500.dp)) {
        if (heroes.isNotEmpty()) HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            HeroPage(heroes[page], onPlay, onOpen)
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
            repeat(heroes.size) { i ->
                val on = i == pager.currentPage
                Box(
                    Modifier.padding(3.dp).size(if (on) 7.dp else 5.dp).clip(CircleShape)
                        .background(if (on) Color.White else Color(0x66FFFFFF))
                )
            }
        }
    }
}

@Composable
fun HeroPage(hero: Item, onPlay: (Item) -> Unit, onOpen: (Item) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF111111))) {
        AsyncImage(Api.heroImage(hero), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent, 0.75f to Color(0xDD000000), 1f to Color(0xE60A0A0C))
            )
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 26.dp)) {
            Text(
                "FEATURED", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                modifier = Modifier.background(Color(0x44FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                (hero.seriesName ?: hero.name).uppercase(), color = Color.White, fontSize = 30.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 4.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            val genres = hero.genres?.take(3)?.joinToString("   •   ").orEmpty()
            if (genres.isNotEmpty()) Text(genres, color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            Text(
                hero.overview.orEmpty(), color = Color(0xFFD2D2D2), fontSize = 13.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPlay(hero) }, modifier = Modifier.height(46.dp), shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) {
                    SmallIcon(R.drawable.ic_play, Color.Black, 20)
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.Bold)
                }
                MyListButton(hero)
            }
        }
    }
}

@Composable
fun MediaRow(
    title: String, list: List<Item>, landscape: Boolean = false,
    onClick: (Item) -> Unit, onSeeAll: (() -> Unit)? = null,
    onMore: ((Item, String) -> Unit)? = null
) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (onSeeAll != null) Text("See all ›", color = Grey, fontSize = 13.sp, modifier = Modifier.clickable { onSeeAll() })
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) {
                MediaCard(it, Modifier.width(if (landscape) 220.dp else 118.dp), landscape, onMore = onMore) { onClick(it) }
            }
        }
    }
}

@Composable
fun MediaCard(
    item: Item, modifier: Modifier, landscape: Boolean = false,
    onMore: ((Item, String) -> Unit)? = null, onClick: () -> Unit
) {
    var menuExpanded by remember(item.id) { mutableStateOf(false) }
    Column(modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1F1F23))
        ) {
            AsyncImage(
                if (landscape) Api.landscape(item) else Api.poster(item), item.name,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            if (onMore != null) {
                Box(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(34.dp)) {
                        SmallIcon(R.drawable.ic_more, Color.White, 19)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove from Continue Watching") },
                            onClick = { menuExpanded = false; onMore(item, "hide") }
                        )
                        DropdownMenuItem(
                            text = { Text("Mark as watched") },
                            onClick = { menuExpanded = false; onMore(item, "watched") }
                        )
                    }
                }
            }
            val rt = item.runTimeTicks ?: 0L
            val pos = item.userData?.positionTicks ?: 0L
            if (rt > 0 && pos > 0) LinearProgressIndicator(
                progress = { (pos.toFloat() / rt).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Red, trackColor = Color(0x66FFFFFF)
            )
        }
        val caption = if (item.type == "Episode" && item.seriesName != null)
            "${item.seriesName} · S${item.parentIndex ?: 0}:E${item.index ?: 0}" else item.name
        Text(
            caption, color = Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// ------------------------------------------------------------------ movies / tv shows tabs
private fun Item.isAnimeSeries(): Boolean =
    (tags.orEmpty() + genres.orEmpty()).any { it.equals("Anime", ignoreCase = true) }

/** Derive movie origin from Jellyfin ProductionLocations metadata; never infer from a title. */
private fun Item.movieOriginCategory(): String {
    val countries = productionLocations.orEmpty().map { it.trim().lowercase() }
    if (countries.isEmpty()) return "Unclassified"
    return when {
        countries.any { it in setOf("philippines", "the philippines", "ph") } -> "Filipino"
        countries.any { it in setOf("united states", "united states of america", "usa", "us") } -> "Hollywood"
        countries.any { it.contains("south korea") || it == "korea" || it == "republic of korea" } -> "Korean"
        countries.any { it == "japan" } -> "Japanese"
        countries.any { it == "china" || it == "mainland china" } -> "Chinese"
        countries.any { it == "india" } -> "Indian"
        countries.any { it in setOf("united kingdom", "uk", "great britain") } -> "British"
        else -> "Other International"
    }
}

private val movieOriginOrder = listOf(
    "Filipino", "Hollywood", "Korean", "Japanese", "Chinese", "Indian",
    "British", "Other International", "Unclassified"
)

@Composable
private fun LibraryFilterDropdown(
    title: String, selected: String, options: List<String>, onSelected: (String) -> Unit
) {
    var expanded by remember(title) { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != options.firstOrNull(),
            onClick = { expanded = true },
            label = { Text(if (selected == options.firstOrNull()) title else "$title: $selected") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Red.copy(alpha = 0.22f),
                selectedLabelColor = Color.White, labelColor = Grey
            )
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == options.firstOrNull()) "Any $title" else option) },
                    onClick = { onSelected(option); expanded = false }
                )
            }
        }
    }
}

@Composable
fun LibraryTab(
    title: String, type: String, onOpen: (Item) -> Unit,
    onSearch: () -> Unit, onSettings: () -> Unit, isShows: Boolean = false
) {
    var list by remember(type) { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember(type) { mutableStateOf(true) }
    var allItems by remember(type) { mutableStateOf<List<Item>>(emptyList()) }
    var selectedGenre by remember(type) { mutableStateOf("All") }
    var selectedYear by remember(type) { mutableStateOf("Any") }
    var selectedRating by remember(type) { mutableStateOf("Any") }
    var selectedResolution by remember(type) { mutableStateOf("Any") }
    var watchedFilter by remember(type) { mutableStateOf("Any") }
    var showFilter by remember(type) { mutableStateOf("All") }

    LaunchedEffect(type) {
        loading = true
        allItems = runCatching { Api.byType(type) }.getOrDefault(emptyList())
        list = allItems
        loading = false
    }
    LaunchedEffect(type, allItems, selectedGenre, selectedYear, selectedRating, selectedResolution, watchedFilter) {
        if (allItems.isNotEmpty()) {
            loading = true
            list = runCatching {
                Api.byType(
                    type,
                    genre = selectedGenre.takeUnless { it == "All" },
                    year = selectedYear.toIntOrNull(),
                    minRating = selectedRating.removeSuffix("+").toDoubleOrNull(),
                    resolution = selectedResolution.takeUnless { it == "Any" },
                    watched = watchedFilter.takeUnless { it == "Any" }
                )
            }.getOrDefault(emptyList())
            loading = false
        }
    }

    val genres = remember(allItems) {
        allItems.flatMap { it.genres.orEmpty() }
            .filter { it.isNotBlank() }.distinct().sorted()
    }
    val years = remember(allItems) { allItems.mapNotNull { it.year }.distinct().sortedDescending() }
    val visible = remember(list, selectedGenre, showFilter, isShows) {
        list.filter { item ->
            val categoryMatch = !isShows || when (showFilter) {
                "Anime" -> item.isAnimeSeries()
                "TV Series" -> !item.isAnimeSeries()
                else -> true
            }
            val genreMatch = selectedGenre == "All" || item.genres.orEmpty().any {
                it.equals(selectedGenre, ignoreCase = true)
            }
            categoryMatch && genreMatch
        }
    }
    val originRows = remember(visible, isShows) {
        if (isShows) emptyList() else movieOriginOrder.mapNotNull { origin ->
            val titles = visible.filter { it.movieOriginCategory() == origin }
            if (titles.isEmpty()) null else origin to titles
        }
    }
    val showRows = remember(visible, isShows) {
        if (!isShows) emptyList() else listOf(
            "Anime" to visible.filter { it.isAnimeSeries() },
            "TV Series" to visible.filterNot { it.isAnimeSeries() }
        ).filter { it.second.isNotEmpty() }
    }

    AppBackground {
        Column(Modifier.fillMaxSize()) {
            TabHeader(title, onSearch, onSettings)
            if (isShows) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Anime", "TV Series")) { filter ->
                        FilterChip(selected = showFilter == filter, onClick = {
                            showFilter = filter
                            selectedGenre = "All"
                        }, label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Red.copy(alpha = 0.22f),
                                selectedLabelColor = Color.White, labelColor = Grey))
                    }
                }
            }
            if (genres.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All") + genres) { genre ->
                        FilterChip(selected = selectedGenre == genre, onClick = { selectedGenre = genre },
                            label = { Text(genre) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Red.copy(alpha = 0.22f),
                                selectedLabelColor = Color.White, labelColor = Grey))
                    }
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    LibraryFilterDropdown(
                        "Year", selectedYear, listOf("Any") + years.map { it.toString() },
                        onSelected = { selectedYear = it }
                    )
                }
                item {
                    LibraryFilterDropdown(
                        "Rating", selectedRating, listOf("Any", "6+", "7+", "8+"),
                        onSelected = { selectedRating = it }
                    )
                }
                item {
                    LibraryFilterDropdown(
                        "Resolution", selectedResolution, listOf("Any", "HD", "4K"),
                        onSelected = { selectedResolution = it }
                    )
                }
                item {
                    LibraryFilterDropdown(
                        "Watched", watchedFilter, listOf("Any", "Watched", "Unwatched"),
                        onSelected = { watchedFilter = it }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                    isShows && showFilter != "All" -> showFilter
                    !isShows && selectedGenre != "All" -> "$selectedGenre Movies"
                    else -> title
                }, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("${visible.size} titles", color = Dim, fontSize = 12.sp)
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
                else if (visible.isEmpty()) {
                    Text(if (isShows && showFilter == "Anime")
                        "No Anime titles found. Add an Anime genre or tag in Jellyfin to classify shows here."
                    else if (isShows && showFilter == "TV Series")
                        "No TV Series found in this category."
                    else "No titles in this category yet.",
                        color = Grey, modifier = Modifier.align(Alignment.Center).padding(28.dp))
                } else if (isShows) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(showRows, key = { it.first }) { (category, shows) ->
                            MediaRow(category, shows, onClick = onOpen)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(originRows, key = { it.first }) { (origin, movies) ->
                            val shelfTitle = when (origin) {
                                "Filipino" -> "Filipino Movies"
                                "Hollywood" -> "Hollywood Movies"
                                "Other International" -> "Other International Movies"
                                "Unclassified" -> "Other Movies"
                                else -> "$origin Movies"
                            }
                            MediaRow(shelfTitle, movies, onClick = onOpen)
                        }
                    }
                }
            }
        }
    }
}

/** Folder-style browsing (collections, box sets, library folders). */
@Composable
fun GridScreen(parent: Item, onOpen: (Item) -> Unit, back: () -> Unit) {
    var list by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(parent.id) {
        list = runCatching { Api.children(parent.id) }.getOrDefault(emptyList()); loading = false
    }
    AppBackground {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { SmallIcon(R.drawable.ic_back) }
                Text(parent.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
                LazyVerticalGrid(
                    GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(list, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ search
private fun highlight(s: String, term: String): AnnotatedString = buildAnnotatedString {
    val i = if (term.isEmpty()) -1 else s.indexOf(term, ignoreCase = true)
    if (i < 0) append(s) else {
        append(s.substring(0, i))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Red)) { append(s.substring(i, i + term.length)) }
        append(s.substring(i + term.length))
    }
}

@Composable
fun SearchScreen(onOpen: (Item) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val prefs = remember { ctx.getSharedPreferences("search", Context.MODE_PRIVATE) }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var showSuggest by remember { mutableStateOf(true) }
    var recent by remember {
        mutableStateOf(prefs.getString("recent", "").orEmpty().split("\n").filter { it.isNotBlank() })
    }
    var suggested by remember { mutableStateOf<List<Item>>(emptyList()) }

    fun saveRecent(term: String) {
        val t = term.trim()
        if (t.length < 2) return
        recent = (listOf(t) + recent.filter { !it.equals(t, ignoreCase = true) }).take(8)
        prefs.edit().putString("recent", recent.joinToString("\n")).apply()
    }

    LaunchedEffect(Unit) {
        runCatching { delay(150); focusRequester.requestFocus() }
        suggested = runCatching { Api.suggestions() }.getOrDefault(emptyList())
    }
    LaunchedEffect(q) {
        if (q.trim().isEmpty()) {
            results = emptyList(); searched = false
        } else {
            delay(250)
            results = runCatching { Api.search(q.trim()) }.getOrDefault(emptyList())
            searched = true
        }
    }
    val term = q.trim()
    val suggestions = remember(results, term) {
        results.map { it.seriesName ?: it.name }.distinct()
            .sortedBy { !it.startsWith(term, ignoreCase = true) }.take(6)
    }

    AppBackground {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { SmallIcon(R.drawable.ic_back) }
                OutlinedTextField(
                    q, { q = it; showSuggest = true }, singleLine = true,
                    placeholder = { Text("Search movies, shows…", color = Dim) },
                    trailingIcon = {
                        if (q.isNotEmpty()) IconButton(onClick = { q = ""; showSuggest = true }) {
                            SmallIcon(R.drawable.ic_close, Grey, 18)
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { showSuggest = false; saveRecent(q); focus.clearFocus() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Red, unfocusedBorderColor = Color(0xFF444444), cursorColor = Red,
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focusRequester)
                )
            }
            LazyVerticalGrid(
                GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (term.isEmpty()) {
                    if (recent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Recent searches", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Text("Clear", color = Grey, fontSize = 13.sp, modifier = Modifier.clickable {
                                    recent = emptyList(); prefs.edit().remove("recent").apply()
                                })
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(recent) { r ->
                                    Text(
                                        r, color = Color.White, fontSize = 13.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF2A2A2E))
                                            .clickable { q = r; showSuggest = false; focus.clearFocus() }
                                            .padding(horizontal = 14.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Suggested for you", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    }
                    items(suggested, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
                } else {
                    if (showSuggest && suggestions.isNotEmpty()) {
                        items(suggestions, span = { GridItemSpan(maxLineSpan) }) { s ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { q = s; showSuggest = false; saveRecent(s); focus.clearFocus() }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SmallIcon(R.drawable.ic_search, Grey, 18)
                                Spacer(Modifier.width(14.dp))
                                Text(highlight(s, term), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (searched && results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("No results", color = Grey, modifier = Modifier.padding(12.dp))
                    }
                    items(results, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { saveRecent(term); onOpen(it) } }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ my collection
@Composable
fun MyListTab(onOpen: (Item) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by remember { mutableIntStateOf(0) } // Saved / Downloads
    var filter by remember { mutableStateOf("All") }
    var favs by remember { mutableStateOf<List<Item>>(emptyList()) }
    var recs by remember { mutableStateOf(Downloads.list(ctx)) }
    var states by remember { mutableStateOf<Map<String, DlState>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }

    val storage = remember {
        runCatching {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
            stat.totalBytes to stat.availableBytes
        }.getOrDefault(0L to 0L)
    }
    val usedBytes = (storage.first - storage.second).coerceAtLeast(0L)
    val usedGb = usedBytes / 1_000_000_000.0
    val totalGb = storage.first / 1_000_000_000.0
    val downloadedBytes = states.values.sumOf { it.done.coerceAtLeast(0L) }
    val downloadedGb = downloadedBytes / 1_000_000_000.0

    LaunchedEffect(Unit) {
        favs = runCatching { Api.favorites() }.getOrDefault(emptyList())
        Downloads.ensureCovers(ctx)
        recs = Downloads.list(ctx)
        loading = false
    }
    LaunchedEffect(recs) {
        while (true) {
            states = recs.associate { it.id to Downloads.state(ctx, it) }
            delay(1000)
        }
    }

    val filtered = when (filter) {
        "Films" -> favs.filter { it.type == "Movie" }
        "Series" -> favs.filter { it.type == "Series" && !it.isAnimeSeries() }
        "Anime" -> favs.filter { it.type == "Series" && it.isAnimeSeries() }
        else -> favs.filter { it.type != "Episode" }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090A0E))) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 26.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("My Collection", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                    Text("Saved titles and offline downloads", color = Color(0xFF9296A5),
                        fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp, bottom = 24.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                            .background(Color(0xFF171B27)).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("Saved", "Downloads").forEachIndexed { index, label ->
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                                    .background(if (section == index) Color(0xFF214347) else Color.Transparent)
                                    .clickable { section = index }.padding(vertical = 15.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (section == index) Color(0xFF68D8D0) else Color(0xFF969BAA),
                                    fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }

            if (section == 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)) {
                        listOf("All", "Films", "Series", "Anime").forEach { value ->
                            Box(
                                Modifier.clip(CircleShape)
                                    .background(if (filter == value) Color(0xFF67D9D1) else Color(0xFF171B27))
                                    .clickable { filter = value }
                                    .padding(horizontal = 19.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(value, color = if (filter == value) Color(0xFF071416) else Color(0xFF9DA2B0),
                                    fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                if (loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().padding(34.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF67D9D1))
                        }
                    }
                } else if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(if (favs.isEmpty()) "Your collection is empty. Save a movie, show, or anime to see it here."
                            else "No saved titles in this category.",
                            color = Color(0xFF9296A5), fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 24.dp))
                    }
                } else {
                    items(filtered, key = { it.id }) { media ->
                        Column(Modifier.fillMaxWidth().clickable { onOpen(media) }) {
                            AsyncImage(
                                Api.poster(media), media.name,
                                Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Text(media.name, color = Color(0xFFF2F2F5), fontSize = 15.sp,
                                fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp))
                            Text(media.year?.toString() ?: media.type, color = Color(0xFF9296A5),
                                fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(218.dp), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val stroke = 13.dp.toPx()
                                drawArc(Color(0xFF252A38), -90f, 360f, false,
                                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                                val fraction = if (storage.first > 0L) (usedBytes.toFloat() / storage.first).coerceIn(0f, 1f) else 0f
                                drawArc(
                                    Brush.sweepGradient(listOf(Color(0xFFFF8050), Color(0xFF7847F5),
                                        Color(0xFF22D6D0), Color(0xFFFF3F83), Color(0xFFFF8050))),
                                    -90f, fraction * 360f, false,
                                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (storage.first > 0L) "%.1f GB".format(usedGb) else "—",
                                    color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                                Text(if (storage.first > 0L) "of %.0f GB used".format(totalGb) else "Storage unavailable",
                                    color = Color(0xFF9296A5), fontSize = 14.sp)
                            }
                        }
                        Text("On this phone", color = Color.White, fontSize = 21.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth()
                                .padding(top = 24.dp, bottom = 12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Offline downloads", color = Color(0xFF9296A5), fontSize = 13.sp,
                                modifier = Modifier.weight(1f))
                            Text("%.2f GB".format(downloadedGb), color = Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (recs.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Nothing downloaded yet. Open a title and tap Download to watch offline.",
                            color = Color(0xFF9296A5), fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 16.dp))
                    }
                }
                items(recs, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { rec ->
                    val st = states[rec.id] ?: DlState(-1, 0L, 0L)
                    val complete = st.status == android.app.DownloadManager.STATUS_SUCCESSFUL
                    val running = st.status == android.app.DownloadManager.STATUS_RUNNING ||
                        st.status == android.app.DownloadManager.STATUS_PENDING
                    val pct = if (st.total > 0L) (st.done * 100L / st.total).toInt().coerceIn(0, 100) else 0
                    val hasCover = rec.cover.isNotEmpty() && File(rec.cover).exists()
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = complete) {
                            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("id", rec.id)
                                .putExtra("file", rec.file).putExtra("title", rec.title))
                        }.padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            if (hasCover) File(rec.cover) else "${Api.base}/Items/${rec.id}/Images/Primary?maxWidth=400",
                            rec.title, Modifier.size(82.dp).clip(CircleShape)
                                .border(2.dp, Color(0x660B0714), CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                            Text(rec.title, color = Color(0xFFF2F2F5), fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                complete -> "Offline · ${fmtSize(st.done)}"
                                running -> "Downloading · $pct% · ${fmtSize(st.done)}"
                                st.status == android.app.DownloadManager.STATUS_FAILED -> "Download failed"
                                else -> "Waiting to download"
                            }
                            Text(subtitle, color = Color(0xFF9296A5), fontSize = 13.sp, maxLines = 1)
                        }
                        if (complete) {
                            Box(Modifier.size(44.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFFFF8050), Color(0xFFFF3F83)))),
                                contentAlignment = Alignment.Center) {
                                SmallIcon(R.drawable.ic_check, Color(0xFF190C18), 22)
                            }
                        } else {
                            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxSize(),
                                    color = Color(0xFFFF4F83), trackColor = Color(0x553F3452), strokeWidth = 3.dp)
                                Text(if (running) "$pct" else "!", color = Color.White, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ downloads
/** Returns a function that asks for the needed permissions (if any) and then starts the download. */
@Composable
fun rememberDownloadStarter(): (Item) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Item?>(null) }
    fun go(item: Item) {
        scope.launch { Toast.makeText(ctx, Downloads.start(ctx, item), Toast.LENGTH_SHORT).show() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val item = pending
        pending = null
        if (item != null) go(item)
    }
    return { item ->
        val missing = Downloads.requiredPermissions().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) go(item) else { pending = item; launcher.launch(missing.toTypedArray()) }
    }
}

@Composable
fun DownloadsTab(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    var recs by remember { mutableStateOf(Downloads.list(ctx)) }
    var states by remember { mutableStateOf<Map<String, DlState>>(emptyMap()) }
    LaunchedEffect(Unit) {
        if (Downloads.ensureCovers(ctx)) recs = Downloads.list(ctx)
    }
    LaunchedEffect(recs) {
        while (true) {
            states = recs.associate { it.id to Downloads.state(ctx, it) }
            delay(1000)
        }
    }
    AppBackground {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { TabHeader("Downloads", null, onSettings) }
            item {
                Text(
                    "Downloads", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 6.dp)
                )
                if (recs.isEmpty()) Text(
                    "Nothing downloaded yet. Open a movie or episode and tap the download icon.",
                    color = Grey, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(recs, key = { it.id }) { rec ->
                val st = states[rec.id] ?: DlState(-1, 0L, 0L)
                DownloadRow(
                    rec, st,
                    onPlay = {
                        ctx.startActivity(
                            Intent(ctx, PlayerActivity::class.java).putExtra("id", rec.id)
                                .putExtra("file", rec.file).putExtra("title", rec.title)
                        )
                    },
                    onDelete = { Downloads.remove(ctx, rec); recs = Downloads.list(ctx) }
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(rec: DownloadRec, st: DlState, onPlay: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    val complete = st.status == android.app.DownloadManager.STATUS_SUCCESSFUL
    val failed = st.status == android.app.DownloadManager.STATUS_FAILED
    val pct = if (st.total > 0) ((st.done * 100) / st.total).toInt() else 0
    val saved = Downloads.position(ctx, rec.id)
    val runMs = rec.runtimeTicks / 10_000
    val hasCover = rec.cover.isNotEmpty() && File(rec.cover).exists()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).glass(14.dp)
            .clickable(enabled = complete, onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(124.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F1F23))) {
            AsyncImage(
                if (hasCover) File(rec.cover) else "${Api.base}/Items/${rec.id}/Images/Primary?maxWidth=600",
                rec.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
            Box(
                Modifier.align(Alignment.Center).size(34.dp).clip(CircleShape).background(Color(0x88000000)),
                contentAlignment = Alignment.Center
            ) {
                SmallIcon(if (complete) R.drawable.ic_play else R.drawable.ic_download, Color.White, 20)
            }
            if (complete && runMs > 0 && saved > 0) LinearProgressIndicator(
                progress = { (saved.toFloat() / runMs).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Red, trackColor = Color(0x66FFFFFF)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(rec.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when {
                complete -> listOf(
                    fmtSize(st.done), fmtRuntime(rec.runtimeTicks),
                    if (saved > 5_000) "Resume " + fmtClock(saved) else ""
                ).filter { it.isNotEmpty() }.joinToString("  ·  ")
                failed -> "Failed: ${Downloads.reasonText(st.reason)}. Delete and try again"
                st.status == -1 -> "Unavailable"
                else -> "Downloading… $pct%"
            }
            Text(sub, color = Grey, fontSize = 12.sp)
            if (!complete && !failed && st.status != -1) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Red, trackColor = Color(0x33FFFFFF)
                )
            }
        }
        IconButton(onClick = onDelete) { SmallIcon(R.drawable.ic_close, Grey, 20) }
    }
}

// ------------------------------------------------------------------ detail
@Composable
fun DetailScreen(base: Item, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val startDownload = rememberDownloadStarter()
    var cur by remember(base.id) { mutableStateOf(base) }
    var seasons by remember(base.id) { mutableStateOf<List<Item>>(emptyList()) }
    var episodes by remember(base.id) { mutableStateOf<List<Item>>(emptyList()) }
    var seasonIdx by remember(base.id) { mutableIntStateOf(0) }
    var similar by remember(base.id) { mutableStateOf<List<Item>>(emptyList()) }
    var tab by remember(base.id) { mutableIntStateOf(0) }
    var saved by remember(base.id) { mutableStateOf(base.userData?.isFavorite == true) }
    var downloadRecords by remember { mutableStateOf(Downloads.list(ctx)) }
    var downloadStates by remember { mutableStateOf<Map<String, DlState>>(emptyMap()) }

    val isSeries = base.type == "Series"
    val isSeason = base.type == "Season"
    val isEpisode = base.type == "Episode"
    val isShow = isSeries || isSeason
    val tabs = if (isShow) listOf("Episodes", "About", "Similar") else listOf("About", "Similar")

    LaunchedEffect(base.id) {
        cur = runCatching { Api.item(base.id) }.getOrDefault(base)
        if (isSeries) seasons = runCatching { Api.children(base.id) }.getOrDefault(emptyList()).filter { it.type == "Season" }
        else if (isSeason) episodes = runCatching { Api.children(base.id) }.getOrDefault(emptyList())
        similar = runCatching { Api.similar(base.id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(seasons, seasonIdx) {
        if (isSeries && seasons.isNotEmpty()) {
            episodes = runCatching { Api.children(seasons[seasonIdx.coerceIn(0, seasons.lastIndex)].id) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(base.id) {
        while (true) {
            downloadRecords = Downloads.list(ctx)
            downloadStates = downloadRecords.associate { it.id to Downloads.state(ctx, it) }
            delay(1000)
        }
    }

    val video = cur.mediaSources?.firstOrNull()?.streams?.firstOrNull { it.type == "Video" }
    val height = video?.height ?: 0
    val resolution = when { height >= 4000 -> "8K"; height >= 2000 -> "4K"; height >= 1000 -> "1080p"; height >= 700 -> "720p"; height > 0 -> "SD"; else -> "" }
    val hdr = video?.videoRange?.contains("HDR", ignoreCase = true) == true
    val metadata = listOfNotNull(
        if (isShow) "SERIES" else if (isEpisode) "EPISODE" else "MOVIE",
        cur.year?.toString(), cur.officialRating, resolution.takeIf { it.isNotBlank() }, "HDR".takeIf { hdr }
    ).joinToString("  ·  ")
    val totalTicks = cur.runTimeTicks ?: 0L
    val positionTicks = cur.userData?.positionTicks ?: 0L
    val progress = if (totalTicks > 0L) (positionTicks.toFloat() / totalTicks).coerceIn(0f, 1f) else 0f
    val remaining = if (totalTicks > 0L && positionTicks > 0L && positionTicks < totalTicks) fmtRuntime(totalTicks - positionTicks) else ""
    val canDownload = !isShow
    val currentDownload = downloadRecords.firstOrNull { it.id == cur.id }
    val currentDownloadState = currentDownload?.let { downloadStates[it.id] }
    val downloading = currentDownloadState?.status == android.app.DownloadManager.STATUS_RUNNING ||
        currentDownloadState?.status == android.app.DownloadManager.STATUS_PENDING
    val downloaded = currentDownloadState?.status == android.app.DownloadManager.STATUS_SUCCESSFUL

    Box(Modifier.fillMaxSize().background(
        Brush.linearGradient(listOf(Color(0xFF35151D), Color(0xFF28143A), Color(0xFF0B2028), Color(0xFF080A11)))
    )) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(510.dp)) {
                    AsyncImage(Api.backdrop(cur), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color(0xAA100811), 0.28f to Color(0x55200D22), 0.72f to Color(0xAA101020), 1f to Color(0xFF24152E))
                    ))
                    Box(Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(Color(0x55FF754E), Color(0x223F45B8), Color.Transparent), radius = 850f)
                    ))

                    IconButton(onClick = back, modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp)
                        .size(48.dp).clip(CircleShape).background(Color(0x5520182C))) {
                        SmallIcon(R.drawable.ic_back, Color.White, 22)
                    }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "${cur.name} (${cur.year ?: ""})")
                        }
                        ctx.startActivity(Intent.createChooser(send, "Share title"))
                    }, modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp, top = 12.dp)
                        .size(48.dp).clip(CircleShape).background(Color(0x5520182C))) {
                        Text("↥", color = Color.White, fontSize = 25.sp)
                    }

                    // Orbital hero: dotted celestial tracks, a curved progress trail,
                    // eight numbered satellites and the circular artwork at the center.
                    Box(Modifier.align(Alignment.Center).size(340.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val radius = size.minDimension * 0.455f
                            val topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
                            val arcSize = Size(radius * 2f, radius * 2f)
                            drawCircle(
                                color = Color(0x557F8CA9), radius = radius,
                                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 13f)))
                            )
                            drawCircle(
                                color = Color(0x227F8CA9), radius = radius * 0.78f,
                                style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 15f)))
                            )
                            drawArc(
                                color = Color(0xFFFF477F), startAngle = -82f,
                                sweepAngle = if (progress > 0f) (35f + progress * 32f) else 58f,
                                useCenter = false, topLeft = topLeft, size = arcSize,
                                style = Stroke(width = 4.5f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            )
                            // Tiny stars scattered around the orbital field.
                            listOf(
                                Offset(size.width * .12f, size.height * .22f),
                                Offset(size.width * .82f, size.height * .17f),
                                Offset(size.width * .92f, size.height * .68f),
                                Offset(size.width * .19f, size.height * .83f),
                                Offset(size.width * .48f, size.height * .04f)
                            ).forEach { drawCircle(Color(0x887F9AB4), radius = 1.7f, center = it) }
                        }

                        val satellites = listOf(
                            Triple(0.dp, (-145).dp, 0),
                            Triple(103.dp, (-103).dp, 1),
                            Triple(145.dp, 0.dp, 2),
                            Triple(103.dp, 103.dp, 3),
                            Triple(0.dp, 145.dp, 4),
                            Triple((-103).dp, 103.dp, 5),
                            Triple((-145).dp, 0.dp, 6),
                            Triple((-103).dp, (-103).dp, 7)
                        )
                        satellites.forEach { (x, y, index) ->
                            val active = index == 0 && progress > 0f
                            val current = index == 1 && progress > 0f
                            Box(
                                Modifier.offset(x = x, y = y).size(if (active) 44.dp else 42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            active -> Brush.linearGradient(listOf(Color(0xFFFF8150), Color(0xFFFF347E)))
                                            else -> Brush.linearGradient(listOf(Color(0x662B2037), Color(0x55302748)))
                                        }
                                    )
                                    .border(
                                        width = if (current) 2.dp else 1.dp,
                                        color = if (current) Color(0xFFFF3982) else Color(0x667F829A),
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (current) {
                                    Box(Modifier.fillMaxSize().padding(4.dp).border(1.dp, Color(0xFFFF3982), CircleShape))
                                }
                                Text("${index + 1}", color = if (active) Color(0xFF190C18) else Color.White,
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Box(Modifier.size(204.dp).clip(CircleShape)
                            .background(Color(0xFF171325))
                            .border(3.dp, Color(0xAA9A688E), CircleShape)) {
                            AsyncImage(Api.poster(cur), null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                            Box(Modifier.fillMaxSize().background(
                                Brush.verticalGradient(0f to Color(0x11000000), 0.58f to Color(0x22000000), 1f to Color(0xAA090713))
                            ))
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                                color = Color(0xFFFF557E), trackColor = Color(0x668C7A9B), strokeWidth = 3.dp
                            )
                        }
                        if (remaining.isNotEmpty()) {
                            Text(
                                "${if (isEpisode) "EPISODE" else "CONTINUE"} · $remaining LEFT",
                                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 34.dp, y = (-48).dp)
                                    .clip(CircleShape).background(Color(0x884A3B5B))
                                    .border(1.dp, Color(0x55FFFFFF), CircleShape)
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = (-2).dp)) {
                    Text(metadata, color = Color(0xFFBDB3C7), fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Text(cur.seriesName ?: cur.name, color = Color(0xFFF7F2FA), fontSize = 34.sp,
                        lineHeight = 38.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (cur.seriesName != null) {
                        Text(cur.name, color = Color(0xFFC5B9CE), fontSize = 15.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(cur.overview.orEmpty().ifBlank { "Discover more about this title in your Allervue library." },
                        color = Color(0xFFD0C7D5), fontSize = 15.sp, lineHeight = 23.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cast = cur.people.orEmpty().filter { it.type == "Actor" }.take(3)
                        cast.forEachIndexed { index, person ->
                            Box(Modifier.size(42.dp).offset(x = (-index * 7).dp).clip(CircleShape)
                                .background(listOf(Color(0xFFFF875A), Color(0xFFEA62A8), Color(0xFF4654A8))[index % 3])
                                .border(1.dp, Color(0xFF20172C), CircleShape), contentAlignment = Alignment.Center) {
                                Text(person.name.firstOrNull()?.uppercase() ?: "•", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (cast.isNotEmpty()) Text(cast.joinToString(" · ") { it.name }, color = Color(0xFFB9B0C5),
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                    .background(Color(0xEE171329)).padding(horizontal = 20.dp, vertical = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (progress > 0f && progress < 1f) "Continue watching" else "Ready when you are",
                                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            Text(if (remaining.isNotEmpty()) "$remaining remaining" else fmtRuntime(cur.runTimeTicks).ifEmpty { "Full title" },
                                color = Color(0xFFB8AEC4), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        Button(onClick = { onPlay(cur) }, modifier = Modifier.height(58.dp).weight(1.05f),
                            shape = CircleShape, colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFF4D70), contentColor = Color(0xFF1A0A17)
                            )) {
                            Text(if (progress > 0.02f) "▶  Resume" else "▶  Play",
                                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch { runCatching { Api.setFavorite(cur.id, !saved) }; saved = !saved }
                        }, modifier = Modifier.weight(1f).height(48.dp), shape = CircleShape,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Text(if (saved) "✓  Saved" else "＋  Save", maxLines = 1)
                        }
                        if (canDownload) OutlinedButton(onClick = {
                            if (!downloaded && !downloading) startDownload(cur)
                        }, enabled = !downloaded && !downloading, modifier = Modifier.weight(1f).height(48.dp),
                            shape = CircleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                            Text(when { downloaded -> "✓  Downloaded"; downloading -> "Downloading"; else -> "↓  Download" }, maxLines = 1)
                        } else {
                            OutlinedButton(onClick = { tab = 0 }, modifier = Modifier.weight(1f).height(48.dp),
                                shape = CircleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                                Text("Episodes", maxLines = 1)
                            }
                        }
                    }
                    if (currentDownloadState != null && downloading) {
                        val pct = if (currentDownloadState.total > 0L) currentDownloadState.done.toFloat() / currentDownloadState.total else 0f
                        LinearProgressIndicator(progress = { pct.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            color = Color(0xFFFF5B79), trackColor = Color(0x44FFFFFF))
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    tabs.forEachIndexed { index, label ->
                        Column(Modifier.clickable { tab = index }) {
                            Text(label, color = if (tab == index) Color.White else Color(0xFF9F95AC),
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(7.dp))
                            Box(Modifier.width(28.dp).height(3.dp).clip(CircleShape)
                                .background(if (tab == index) Color(0xFFFF587B) else Color.Transparent))
                        }
                    }
                }
            }

            when (tabs[tab.coerceIn(0, tabs.lastIndex)]) {
                "Episodes" -> {
                    if (seasons.size > 1) item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons.size) { index ->
                                val active = index == seasonIdx
                                Text(seasons[index].name, color = if (active) Color(0xFF1B0D1A) else Color.White,
                                    modifier = Modifier.clip(CircleShape)
                                        .background(if (active) Color(0xFFFF647C) else Color(0x44352D47))
                                        .clickable { seasonIdx = index }.padding(horizontal = 14.dp, vertical = 9.dp),
                                    fontSize = 12.sp)
                            }
                        }
                    }
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(ep, onPlay = { onPlay(ep) }, onDownload = { startDownload(ep) },
                            downloadState = downloadStates[ep.id])
                    }
                }
                "About" -> item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        cur.rating?.let { DetailLine("Rating", "★ %.1f".format(it)) }
                        val genres = cur.genres.orEmpty().joinToString("  ·  ")
                        val studios = cur.studios.orEmpty().joinToString("  ·  ") { it.name }
                        val cast = cur.people.orEmpty().filter { it.type == "Actor" }.take(10).joinToString("  ·  ") { it.name }
                        if (genres.isNotBlank()) DetailLine("Genres", genres)
                        if (studios.isNotBlank()) DetailLine("Studio", studios)
                        if (cast.isNotBlank()) DetailLine("Cast", cast)
                        if (genres.isBlank() && studios.isBlank() && cast.isBlank() && cur.rating == null) {
                            Text("No additional details available.", color = Color(0xFFB9B0C5), fontSize = 14.sp)
                        }
                    }
                }
                else -> {
                    if (similar.isEmpty()) item {
                        Text("Nothing similar found yet.", color = Color(0xFFB9B0C5), fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                    } else item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(similar, key = { it.id }) { related ->
                                MediaCard(related, Modifier.width(118.dp)) { onOpen(related) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = Dim, fontSize = 13.sp, modifier = Modifier.width(72.dp))
        Text(value, color = Color(0xFFE0E0E0), fontSize = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EpisodeRow(ep: Item, onPlay: () -> Unit, onDownload: () -> Unit, downloadState: DlState? = null) {
    val rt = ep.runTimeTicks ?: 0L
    val pos = ep.userData?.positionTicks ?: 0L
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(130.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F1F23))) {
            AsyncImage(Api.landscape(ep), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (rt > 0 && pos > 0) LinearProgressIndicator(
                progress = { (pos.toFloat() / rt).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Red, trackColor = Color(0x66FFFFFF)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${ep.index ?: ""}. ${ep.name}", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(fmtRuntime(ep.runTimeTicks), color = Grey, fontSize = 12.sp)
            val dlLabel = when (downloadState?.status) {
                android.app.DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"
                android.app.DownloadManager.STATUS_RUNNING, android.app.DownloadManager.STATUS_PENDING -> {
                    val pct = if (downloadState.total > 0) (downloadState.done * 100 / downloadState.total).toInt() else 0
                    "Downloading · $pct%"
                }
                android.app.DownloadManager.STATUS_PAUSED -> "Paused"
                android.app.DownloadManager.STATUS_FAILED -> "Failed · retry"
                else -> ""
            }
            if (dlLabel.isNotEmpty()) Text(dlLabel, color = if (downloadState?.status == android.app.DownloadManager.STATUS_SUCCESSFUL) Color(0xFF8FC7A0) else Grey, fontSize = 11.sp)
        }
        val downloadRunning = downloadState?.status == android.app.DownloadManager.STATUS_RUNNING ||
            downloadState?.status == android.app.DownloadManager.STATUS_PENDING
        val downloadComplete = downloadState?.status == android.app.DownloadManager.STATUS_SUCCESSFUL
        IconButton(
            onClick = onDownload,
            enabled = !downloadRunning && !downloadComplete
        ) {
            when {
                downloadComplete -> SmallIcon(R.drawable.ic_check, Color(0xFF8FC7A0))
                downloadRunning -> {
                    val progress = if (downloadState.total > 0L) {
                        (downloadState.done.toFloat() / downloadState.total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFFD9B77E),
                        trackColor = Color(0x44FFFFFF),
                        strokeWidth = 2.5.dp
                    )
                }
                else -> SmallIcon(R.drawable.ic_download, Color.White, 24)
            }
        }
    }
}
