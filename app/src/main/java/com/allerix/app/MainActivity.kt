package com.allerix.app

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

private val detailTypes = setOf("Series", "Season", "Movie", "Episode", "Video", "MusicVideo")

enum class NavTab(val label: String, val icon: Int) {
    HOME("Home", R.drawable.ic_nav_home),
    MOVIES("Movies", R.drawable.ic_nav_movie),
    SHOWS("TV Shows", R.drawable.ic_nav_tv),
    ANIME("Anime", R.drawable.ic_nav_star),
    LIST("Collection", R.drawable.ic_nav_download)
}

sealed class Nav {
    data class Grid(val item: Item) : Nav()
    data class Detail(val item: Item) : Nav()
    object Search : Nav()
    object Settings : Nav()
    object RequestShow : Nav()
    object ClientSettings : Nav()
    object PlaybackSettings : Nav()
    object Diagnostics : Nav()
    data class Web(val url: String, val title: String) : Nav()
}

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One process-wide image loader keeps artwork cached when users switch tabs
        // or leave and return to a page. Ignore restrictive Jellyfin cache headers:
        // authenticated artwork is safe to cache in this app's private cache folder.
        Coil.setImageLoader(
            ImageLoader.Builder(applicationContext)
                .memoryCache {
                    MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.20)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(applicationContext.cacheDir.resolve("allervue_artwork"))
                        .maxSizeBytes(512L * 1024L * 1024L)
                        .build()
                }
                .respectCacheHeaders(false)
                .crossfade(false)
                .build()
        )
        DiagnosticLog.install(this)
        DiagnosticLog.record(this, "MainActivity onCreate")
        Api.load(this)
        SeerrApi.load(this)
        setContent {
            AllervueTheme { Surface(color = MaterialTheme.colorScheme.background) { App() } }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var splash by remember { mutableStateOf(true) }
    var loggedIn by remember { mutableStateOf(Api.token.isNotEmpty()) }
    var loginSettings by remember { mutableStateOf(false) }
    var forgotPassword by remember { mutableStateOf(false) }
    var pendingCrashReport by remember { mutableStateOf(DiagnosticLog.pendingFatalReport(ctx)) }
    LaunchedEffect(Unit) { delay(900); splash = false }

    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        delay(2800)
        availableUpdate = runCatching { UpdateChecker.checkForUpdate(ctx) }.getOrNull()
    }

    Box(Modifier.fillMaxSize()) {
        if (splash) {
            SplashScreen()
        } else if (pendingCrashReport != null) {
            CrashRecoveryScreen(
                report = pendingCrashReport.orEmpty(),
                onContinue = {
                    DiagnosticLog.acknowledgeCrashReport(ctx)
                    pendingCrashReport = null
                }
            )
        } else if (loggedIn) {
            RebuildMainShell(onLogout = { Api.logout(ctx); loggedIn = false })
        } else if (forgotPassword) {
            ForgotPasswordScreen(onBack = { forgotPassword = false })
        } else if (loginSettings) {
            BackHandler { loginSettings = false }
            ClientSettingsScreen(back = { loginSettings = false }, onServerChanged = { loginSettings = false }, loggedIn = false)
        } else {
            LoginScreen(
                onDone = { loggedIn = true }, onSettings = { loginSettings = true },
                onForgot = { forgotPassword = true }
            )
        }

        availableUpdate?.let { update ->
            UpdateAvailableDialog(
                update = update,
                onDismiss = {
                    UpdateChecker.dismissUpdate(ctx, update.tag)
                    availableUpdate = null
                }
            )
        }
    }
}

@Composable
fun SplashScreen() {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val logoScale by animateFloatAsState(
        if (started) 1f else 0.6f, tween(1000, easing = FastOutSlowInEasing), label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(if (started) 1f else 0f, tween(900), label = "logoAlpha")
    val tagAlpha by animateFloatAsState(if (started) 1f else 0f, tween(900, delayMillis = 800), label = "tagAlpha")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        0.15f, 0.40f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow"
    )
    val slide by pulse.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "slide"
    )
    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF65D6D0).copy(alpha = glow), BgDark), radius = 1100f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AllervueWordmark(
                emblemSize = 104.dp,
                textSize = 60.sp,
                modifier = Modifier.scale(logoScale).alpha(logoAlpha)
            )
            Spacer(Modifier.height(24.dp))
            Text(
                "SEE IT ALL", color = Color(0xFFB9B4C8), fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 5.sp,
                textAlign = TextAlign.Center, modifier = Modifier.alpha(tagAlpha)
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).width(120.dp).height(3.dp)
                .clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))
        ) {
            Box(Modifier.offset(x = 80.dp * slide).width(40.dp).fillMaxHeight().background(Color(0xFF65D6D0)))
        }
    }
}

@Composable
fun MainShell(onLogout: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(NavTab.HOME) }
    val stack = remember { mutableStateListOf<Nav>() }

    val onPlay: (Item) -> Unit = { target ->
        scope.launch {
            val ep = if (target.type == "Series" || target.type == "Season")
                runCatching { Api.nextEpisode(target.id, target.type) }.getOrNull() else target
            if (ep != null) ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java).putExtra("id", ep.id).putExtra("title", ep.name)
            )
        }
    }
    val onOpen: (Item) -> Unit = { target ->
        stack.add(if (target.type in detailTypes || !target.isFolder) Nav.Detail(target) else Nav.Grid(target))
    }
    val back: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val signOut: () -> Unit = { stack.clear(); onLogout() }
    val openSettings: () -> Unit = { stack.add(Nav.Settings) }

    BackHandler(enabled = stack.isNotEmpty()) { back() }
    BackHandler(enabled = stack.isEmpty() && tab != NavTab.HOME) { tab = NavTab.HOME }

    val top = stack.lastOrNull()
    val showBar = top == null || top is Nav.Detail || top is Nav.Grid

    Column(Modifier.fillMaxSize().background(BgDark)) {
        Box(Modifier.weight(1f)) {
            when (top) {
                null -> when (tab) {
                    NavTab.HOME -> HomeScreen(
                        onOpen, onPlay, onSeeAll = { _, items ->
                            stack.clear()
                            tab = if (items.firstOrNull()?.type == "Movie") NavTab.MOVIES else NavTab.SHOWS
                        },
                        onSearch = { stack.add(Nav.Search) }, onSettings = openSettings, onLogout = signOut
                    )
                    NavTab.MOVIES -> LibraryTab("Movies", "Movie", onOpen, onSearch = { stack.add(Nav.Search) }, onSettings = openSettings, libraryNames = listOf("Movies", "Kid's Movies"))
                    NavTab.SHOWS -> LibraryTab("TV Shows", "Series", onOpen, onSearch = { stack.add(Nav.Search) }, onSettings = openSettings, isShows = true, libraryNames = listOf("Series"))
                    NavTab.ANIME -> LibraryTab("Anime", "Series", onOpen, onSearch = { stack.add(Nav.Search) }, onSettings = openSettings, isShows = true, animeOnly = true, libraryNames = listOf("Anime"))
                    NavTab.LIST -> MyListTab(onOpen, onSettings = openSettings)
                }
                is Nav.Grid -> key(top) { GridScreen(top.item, onOpen, back) }
                is Nav.Detail -> key(top) { DetailScreen(top.item, onOpen, onPlay, back) }
                Nav.Search -> SearchScreen(onOpen, back)
                Nav.Settings -> SettingsMenuScreen(
                    back, onRequest = { stack.add(Nav.RequestShow) }, onClient = { stack.add(Nav.ClientSettings) },
                    onPlayback = { stack.add(Nav.PlaybackSettings) }, onDiagnostics = { stack.add(Nav.Diagnostics) }, onSignOut = signOut,
                    onWeb = { u, t -> stack.add(Nav.Web(u, t)) }
                )
                Nav.RequestShow -> RequestShowScreen(back)
                Nav.ClientSettings -> ClientSettingsScreen(
                    back, onServerChanged = signOut, loggedIn = true,
                    onWeb = { u, t -> stack.add(Nav.Web(u, t)) }
                )
                is Nav.Web -> key(top) { WebScreen(top.url, top.title, back) }
                Nav.PlaybackSettings -> PlaybackSettingsScreen(back)
                Nav.Diagnostics -> DiagnosticScreen(back)
            }
        }
        if (showBar) BottomBar(tab) { selected -> stack.clear(); tab = selected }
    }
}

@Composable
fun BottomBar(selected: NavTab, onTab: (NavTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xF2101014)).padding(top = 8.dp, bottom = 6.dp)
    ) {
        NavTab.values().forEach { t ->
            val on = t == selected
            Column(
                Modifier.weight(1f).clickable { onTab(t) }.padding(vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SmallIcon(t.icon, if (on) Red else Dim, 24)
                Text(t.label, color = if (on) Red else Dim, fontSize = 10.sp)
            }
        }
    }
}


@Composable
fun CrashRecoveryScreen(report: String, onContinue: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(BgDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "Allervue recovered from a crash",
            color = Color.White,
            fontSize = 23.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "A crash report was saved locally. Share it so we can identify why the app closed. The report does not include your password or Jellyfin token.",
            color = Dim,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = Color(0xFF17171D),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                report,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
                color = Color(0xFFD8D8DE),
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Allervue crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "Share crash report"))
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Red)
        ) {
            Text("Share crash report", color = Color.White)
        }
        TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Continue to Allervue", color = Color.White)
        }
    }
}
