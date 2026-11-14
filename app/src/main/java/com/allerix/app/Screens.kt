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
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

// ------------------------------------------------------------------ my list
@Composable
fun MyListTab(onOpen: (Item) -> Unit, onSettings: () -> Unit) {
    var favs by remember { mutableStateOf<List<Item>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        favs = runCatching { Api.favorites() }.getOrDefault(emptyList()); loading = false
    }
    AppBackground {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("My List", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onSettings) { SmallIcon(R.drawable.ic_settings) }
                Box(Modifier.size(36.dp).clip(CircleShape).background(Red), contentAlignment = Alignment.Center) {
                    Text(Api.username.take(1).uppercase().ifEmpty { "A" }, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Red)
                else if (favs.isEmpty()) Text(
                    "Your list is empty. Tap “+ My List” on any title to save it here.",
                    color = Grey, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) else LazyVerticalGrid(
                    GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favs, key = { it.id }) { MediaCard(it, Modifier.fillMaxWidth()) { onOpen(it) } }
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
    var cur by remember { mutableStateOf(base) }
    var seasons by remember { mutableStateOf<List<Item>>(emptyList()) }
    var episodes by remember { mutableStateOf<List<Item>>(emptyList()) }
    var seasonIdx by remember { mutableStateOf(0) }
    var similar by remember { mutableStateOf<List<Item>>(emptyList()) }
    var tab by remember { mutableStateOf(0) }
    var downloadRecords by remember { mutableStateOf(Downloads.list(ctx)) }
    var downloadStates by remember { mutableStateOf<Map<String, DlState>>(emptyMap()) }
    LaunchedEffect(base.id, episodes) {
        while (true) {
            downloadRecords = Downloads.list(ctx)
            downloadStates = downloadRecords.associate { it.id to Downloads.state(ctx, it) }
            delay(1000)
        }
    }
    val isSeries = base.type == "Series"
    val isSeason = base.type == "Season"
    val tabs = if (isSeries || isSeason) listOf("Episodes", "Details", "More Like This") else listOf("Details", "More Like This")

    LaunchedEffect(base.id) {
        runCatching { cur = Api.item(base.id) }
        if (isSeries) seasons = runCatching { Api.children(base.id) }.getOrDefault(emptyList()).filter { it.type == "Season" }
        else if (isSeason) episodes = runCatching { Api.children(base.id) }.getOrDefault(emptyList())
        similar = runCatching { Api.similar(base.id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(seasons, seasonIdx) {
        if (isSeries && seasons.isNotEmpty()) {
            episodes = runCatching { Api.children(seasons[seasonIdx.coerceIn(0, seasons.size - 1)].id) }
                .getOrDefault(emptyList())
        }
    }

    val video = cur.mediaSources?.firstOrNull()?.streams?.firstOrNull { it.type == "Video" }
    val h = video?.height ?: 0
    val resBadge = when { h >= 4000 -> "8K"; h >= 2000 -> "4K"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; h > 0 -> "SD"; else -> "" }
    val hdr = video?.videoRange?.contains("HDR", ignoreCase = true) == true
    val meta = listOfNotNull(cur.year?.toString(), fmtRuntime(cur.runTimeTicks).ifEmpty { null }, cur.officialRating)
        .joinToString("   •   ")
    val canDownload = !isSeries && !isSeason
    val currentDownload = downloadRecords.firstOrNull { it.id == cur.id }
    val currentDownloadState = currentDownload?.let { downloadStates[it.id] }
    val downloadedEpisodes = episodes.count { ep ->
        downloadRecords.firstOrNull { it.id == ep.id }?.let { downloadStates[it.id]?.status == android.app.DownloadManager.STATUS_SUCCESSFUL } == true
    }
    val activeEpisode = episodes.firstNotNullOfOrNull { ep ->
        val rec = downloadRecords.firstOrNull { it.id == ep.id } ?: return@firstNotNullOfOrNull null
        val state = downloadStates[ep.id] ?: return@firstNotNullOfOrNull null
        if (state.status == android.app.DownloadManager.STATUS_RUNNING || state.status == android.app.DownloadManager.STATUS_PENDING) rec to state else null
    }

    AppBackground {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp).background(Color(0xFF111111))) {
                    AsyncImage(Api.backdrop(cur), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x88000000), 0.4f to Color.Transparent, 1f to BgDark)
                        )
                    )
                    IconButton(onClick = back, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                        SmallIcon(R.drawable.ic_back)
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(cur.seriesName ?: cur.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    if (cur.seriesName != null) Text(cur.name, color = Grey, fontSize = 15.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (meta.isNotEmpty()) Text(meta, color = Grey, fontSize = 13.sp)
                        if (resBadge.isNotEmpty()) Badge(resBadge)
                        if (hdr) Badge("HDR")
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(cur.overview.orEmpty(), color = Color(0xFFD2D2D2), fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onPlay(cur) }, modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)
                        ) {
                            SmallIcon(R.drawable.ic_play, Color.White, 20)
                            Spacer(Modifier.width(6.dp))
                            Text("Play", fontWeight = FontWeight.Bold)
                        }
                        MyListButton(cur, Modifier.weight(1f))
                        if (canDownload) IconButton(
                            onClick = {
                                if (currentDownloadState?.status != android.app.DownloadManager.STATUS_SUCCESSFUL &&
                                    currentDownloadState?.status != android.app.DownloadManager.STATUS_RUNNING &&
                                    currentDownloadState?.status != android.app.DownloadManager.STATUS_PENDING
                                ) startDownload(cur)
                            },
                            enabled = currentDownloadState?.status != android.app.DownloadManager.STATUS_SUCCESSFUL &&
                                currentDownloadState?.status != android.app.DownloadManager.STATUS_RUNNING &&
                                currentDownloadState?.status != android.app.DownloadManager.STATUS_PENDING
                        ) {
                            when (currentDownloadState?.status) {
                                android.app.DownloadManager.STATUS_SUCCESSFUL ->
                                    SmallIcon(R.drawable.ic_check, Color(0xFF8FC7A0), 24)
                                android.app.DownloadManager.STATUS_RUNNING,
                                android.app.DownloadManager.STATUS_PENDING -> {
                                    val progress = currentDownloadState.let {
                                        if (it.total > 0L) (it.done.toFloat() / it.total).coerceIn(0f, 1f) else 0f
                                    }
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.size(24.dp),
                                        color = Red,
                                        trackColor = Color(0x44FFFFFF),
                                        strokeWidth = 2.5.dp
                                    )
                                }
                                else -> SmallIcon(R.drawable.ic_download, Color.White, 24)
                            }
                        }
                    }
                    if (canDownload && currentDownloadState != null) {
                        val state = currentDownloadState
                        val label = when (state.status) {
                            android.app.DownloadManager.STATUS_SUCCESSFUL -> "Downloaded"
                            android.app.DownloadManager.STATUS_RUNNING, android.app.DownloadManager.STATUS_PENDING -> {
                                val pct = if (state.total > 0) (state.done * 100 / state.total).toInt() else 0
                                "Downloading · $pct%"
                            }
                            android.app.DownloadManager.STATUS_PAUSED -> "Download paused"
                            android.app.DownloadManager.STATUS_FAILED -> "Download failed · tap to retry"
                            else -> ""
                        }
                        if (label.isNotEmpty()) Text(label, color = if (state.status == android.app.DownloadManager.STATUS_SUCCESSFUL) Color(0xFF8FC7A0) else Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    if ((isSeries || isSeason) && episodes.isNotEmpty()) {
                        Text(
                            "Downloads · $downloadedEpisodes/${episodes.size} episodes downloaded" +
                                (activeEpisode?.let { pair ->
                                    val pct = if (pair.second.total > 0) (pair.second.done * 100 / pair.second.total).toInt() else 0
                                    " · ${pair.first.title}: $pct%"
                                } ?: ""),
                            color = Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, start = 8.dp)) {
                    tabs.forEachIndexed { i, label ->
                        val on = i == tab
                        Column(
                            Modifier.clickable { tab = i }.padding(horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(label, color = if (on) Color.White else Dim, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.height(2.dp).width(28.dp).background(if (on) Red else Color.Transparent))
                        }
                    }
                }
            }
            when (tabs[tab.coerceIn(0, tabs.size - 1)]) {
                "Episodes" -> {
                    if (isSeries && seasons.size > 1) item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons.size) { i ->
                                val on = i == seasonIdx
                                Text(
                                    seasons[i].name, color = Color.White, fontSize = 13.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                        .background(if (on) Red else Color(0xFF2A2A2E))
                                        .clickable { seasonIdx = i }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    items(episodes, key = { it.id }) { ep ->
                        EpisodeRow(
                            ep, onPlay = { onPlay(ep) },
                            onDownload = { startDownload(ep) },
                            downloadState = downloadStates[ep.id]
                        )
                    }
                }
                "Details" -> item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        val genres = cur.genres.orEmpty().joinToString(", ")
                        val studios = cur.studios.orEmpty().joinToString(", ") { it.name }
                        val cast = cur.people.orEmpty().filter { it.type == "Actor" }.take(10).joinToString(", ") { it.name }
                        cur.rating?.let { DetailLine("Rating", "★ %.1f".format(it)) }
                        if (genres.isNotEmpty()) DetailLine("Genres", genres)
                        if (studios.isNotEmpty()) DetailLine("Studio", studios)
                        if (cast.isNotEmpty()) DetailLine("Cast", cast)
                        if (genres.isEmpty() && studios.isEmpty() && cast.isEmpty() && cur.rating == null) {
                            Text("No extra details available.", color = Grey, fontSize = 14.sp)
                        }
                    }
                }
                else -> {
                    if (similar.isEmpty()) item {
                        Text("Nothing similar found.", color = Grey, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
                    }
                    similar.chunked(3).forEach { chunk ->
                        item {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                chunk.forEach { MediaCard(it, Modifier.weight(1f)) { onOpen(it) } }
                                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
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
        IconButton(onClick = onDownload, enabled = downloadState?.status != android.app.DownloadManager.STATUS_SUCCESSFUL) {
            SmallIcon(if (downloadState?.status == android.app.DownloadManager.STATUS_SUCCESSFUL) R.drawable.ic_check else R.drawable.ic_download)
        }
    }
}
