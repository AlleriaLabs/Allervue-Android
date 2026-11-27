package com.allerix.app

import android.content.Intent
import android.app.DownloadManager
import android.os.Environment
import java.io.File
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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
    object Profile : Nav()
    object Appearance : Nav()
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
        // Cancel any model downloads queued by older app versions and remove their model files.
        runCatching {
            val legacyPrefs = getSharedPreferences("ai_subtitle_models", MODE_PRIVATE)
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            listOf("tiny", "base").forEach { modelId ->
                val downloadId = legacyPrefs.getLong("download_$modelId", -1L)
                if (downloadId >= 0L) manager.remove(downloadId)
            }
            legacyPrefs.edit().clear().apply()
            val downloads = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            val modelDir = File(downloads, "allervue_ai_models")
            listOf("ggml-tiny.bin", "ggml-base.bin").forEach { File(modelDir, it).delete() }
        }
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
    LaunchedEffect(Unit) { delay(1800); splash = false }

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
    val entrance by animateFloatAsState(if (started) 1f else 0f,
        tween(900, easing = FastOutSlowInEasing), label = "splashEntrance")
    val orbitPulse = rememberInfiniteTransition(label = "splashOrbit")
    val orbitRotation by orbitPulse.animateFloat(0f, 360f,
        infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "orbitRotation")
    val progress by orbitPulse.animateFloat(.43f, .49f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "splashProgress")

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF080A11))) {
        val w = maxWidth
        val h = maxHeight
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.maxDimension * .82f
            drawRect(Brush.radialGradient(listOf(Color(0xFF63251F).copy(alpha = .88f), Color.Transparent),
                Offset(size.width * .13f, size.height * .13f), radius))
            drawRect(Brush.radialGradient(listOf(Color(0xFF3D217D).copy(alpha = .82f), Color.Transparent),
                Offset(size.width * 1.02f, size.height * .49f), radius * .88f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF004C4B).copy(alpha = .72f), Color.Transparent),
                Offset(size.width * .13f, size.height * .91f), radius * .86f))
            val starPoints = listOf(
                .05f to .035f, .51f to .035f, .975f to .035f, .23f to .071f, .85f to .071f,
                .39f to .11f, .05f to .27f, .975f to .27f, .23f to .38f, .69f to .38f,
                .05f to .51f, .975f to .51f, .23f to .62f, .69f to .64f, .05f to .75f,
                .975f to .75f, .39f to .84f, .23f to .91f, .69f to .91f, .05f to .975f,
                .975f to .975f, .51f to .78f, .39f to .035f
            )
            starPoints.forEachIndexed { index, p ->
                drawCircle(Color.White.copy(alpha = if (index % 4 == 0) .52f else .30f),
                    if (index % 4 == 0) 1.45.dp.toPx() else 1.dp.toPx(),
                    Offset(size.width * p.first, size.height * p.second))
            }

            val cx = size.width * .5f
            val cy = size.height * .402f
            val orbitRadii = listOf(size.width * .385f, size.width * .585f, size.width * .755f)
            orbitRadii.forEachIndexed { index, r ->
                drawCircle(Color.White.copy(alpha = if (index == 0) .28f else .12f),
                    r, Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(2.dp.toPx(), 11.dp.toPx()), orbitRotation * (index + 1) / 3f)))
            }

            val mainR = size.width * .202f
            drawCircle(Color(0xFFFF8B62).copy(alpha = .17f), mainR * 1.23f, Offset(cx, cy))
            drawCircle(Brush.sweepGradient(listOf(Color(0xFF58D8CF), Color(0xFFFFA45E),
                Color(0xFFFF4D83), Color(0xFF7547E8), Color(0xFF58D8CF)), Offset(cx, cy)),
                mainR * 1.035f, Offset(cx, cy))
            drawCircle(Color(0xFF27132F), mainR * .985f, Offset(cx, cy))
            drawCircle(Brush.linearGradient(listOf(Color(0xFF3C176C), Color(0xFFB84283),
                Color(0xFFFF8750), Color(0xFF251039)), Offset(cx-mainR,cy-mainR), Offset(cx+mainR,cy+mainR)),
                mainR * .94f, Offset(cx, cy))
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF0BB).copy(alpha=.95f),
                Color(0xFFFFB36C).copy(alpha=.42f), Color.Transparent), Offset(cx+mainR*.40f,cy-mainR*.43f), mainR*.53f),
                mainR*.58f, Offset(cx+mainR*.12f,cy-mainR*.17f))
            val horizonY = cy + mainR * .48f
            val horizon = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx-mainR*.94f,horizonY)
                lineTo(cx+mainR*.94f,horizonY)
                lineTo(cx+mainR*.94f,cy+mainR*.94f)
                lineTo(cx-mainR*.94f,cy+mainR*.94f)
                close()
            }
            drawPath(horizon, Brush.verticalGradient(listOf(Color(0xFF310B39), Color(0xFF120B25)),
                horizonY, cy+mainR))
            val tower = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx+mainR*.28f,horizonY)
                lineTo(cx+mainR*.35f,cy-mainR*.23f)
                lineTo(cx+mainR*.47f,cy-mainR*.23f)
                lineTo(cx+mainR*.55f,horizonY)
                close()
            }
            drawPath(tower, Color(0xFF10091B))
            drawCircle(Color(0xFF10091B), mainR*.94f, Offset(cx,cy),
                style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.2.dp.toPx()))

            fun satellite(x: Float, y: Float, r: Float, colors: List<Color>) {
                drawCircle(colors.first().copy(alpha=.24f), r*1.1f, Offset(x,y))
                drawCircle(Brush.linearGradient(colors, Offset(x-r,y-r), Offset(x+r,y+r)), r, Offset(x,y))
                drawCircle(Color.White.copy(alpha=.22f), r, Offset(x,y),
                    style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.dp.toPx()))
            }
            satellite(cx-size.width*.36f, cy-size.width*.125f, size.width*.054f,
                listOf(Color(0xFFFFB15F),Color(0xFFE94D31),Color(0xFF3A1427)))
            satellite(cx+size.width*.295f, cy-size.width*.245f, size.width*.047f,
                listOf(Color(0xFF43C9C1),Color(0xFF144B56),Color(0xFF13243A)))
            drawCircle(Color(0xFFFF72B5), size.width*.027f, Offset(cx+size.width*.283f,cy-size.width*.25f))
            drawCircle(Color(0xFFFFD17A), size.width*.018f, Offset(cx+size.width*.332f,cy-size.width*.22f))
            satellite(cx+size.width*.36f, cy+size.width*.135f, size.width*.058f,
                listOf(Color(0xFF282A76),Color(0xFF121A48),Color(0xFF080D27)))
            drawCircle(Color(0xFFD6D9FF), size.width*.014f,
                Offset(cx+size.width*.385f,cy+size.width*.105f))
            satellite(cx-size.width*.245f, cy+size.width*.30f, size.width*.046f,
                listOf(Color(0xFF7EA8E8),Color(0xFF344E9A),Color(0xFF162E5C)))
            drawCircle(Color(0xFFB7C9F6), size.width*.029f,
                Offset(cx-size.width*.245f,cy+size.width*.30f),
                style=androidx.compose.ui.graphics.drawscope.Stroke(width=2.dp.toPx()))
            satellite(cx-size.width*.19f, cy-size.width*.53f, size.width*.038f,
                listOf(Color(0xFF8A6048),Color(0xFF342033),Color(0xFF171020)))
            drawCircle(Color(0xFFFFD59A).copy(alpha=.8f), size.width*.013f,
                Offset(cx-size.width*.20f,cy-size.width*.545f))
            satellite(cx+size.width*.30f, cy+size.width*.49f, size.width*.032f,
                listOf(Color(0xFF7544D5),Color(0xFF321A73)))
            satellite(cx-size.width*.32f, cy+size.width*.59f, size.width*.025f,
                listOf(Color(0xFFE84D91),Color(0xFF76285E)))
        }

        Column(
            Modifier.fillMaxWidth().offset(y = h * .60f).alpha(entrance),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text("Allervue", color = Color(0xFFF8F4FF), fontSize = 43.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-1.8).sp, lineHeight = 52.sp)
                Text(".", color = Color(0xFFFF4D78), fontSize = 48.sp,
                    fontWeight = FontWeight.Black, lineHeight = 52.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text("Find what fits tonight", color = Color(0xFFB9B4C8), fontSize = 16.sp,
                fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
        }

        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = h * .10f)
            .width(120.dp).height(4.dp).clip(RoundedCornerShape(4.dp))
            .background(Color(0x554D5360))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF824D), Color(0xFFFF3D85)))))
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
                    onWeb = { u, t -> stack.add(Nav.Web(u, t)) },
                    onProfile = { stack.add(Nav.Profile) }, onAppearance = { stack.add(Nav.Appearance) }
                )
                Nav.Profile -> ProfileScreen(
                    back = back,
                    onServer = { stack.add(Nav.ClientSettings) },
                    onPortal = { stack.add(Nav.Web(Api.ACCOUNT_URL, "My account")) },
                    onSignOut = signOut
                )
                Nav.Appearance -> AppearanceSettingsScreen(back)
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
