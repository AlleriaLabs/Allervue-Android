package com.allerix.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.random.Random

private val AAccent = AllervueDesign.Cyan
private val AHot = Color(0xFFFF4D86)
private val AInk = Color(0xFF100E20)
private val APanel = Color(0xFF17152C)

private data class HomeMeteor(val startX: Float, val startY: Float)

@Composable
private fun HomeArtwork(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackModel: String? = null
) {
    val context = LocalContext.current
    val imageRequest = remember(model, context) {
        ImageRequest.Builder(context).data(model).crossfade(650).build()
    }
    val fallbackRequest = remember(fallbackModel, context) {
        fallbackModel?.let { ImageRequest.Builder(context).data(it).crossfade(450).build() }
    }
    val painter = rememberAsyncImagePainter(model = imageRequest)
    val fallbackPainter = rememberAsyncImagePainter(model = fallbackRequest)
    val primaryFailed = painter.state is AsyncImagePainter.State.Error
    val fallbackFailed = fallbackPainter.state is AsyncImagePainter.State.Error
    Box(modifier, contentAlignment = Alignment.Center) {
        if (primaryFailed && fallbackRequest != null && !fallbackFailed) {
            Image(painter = fallbackPainter, contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        } else {
            Image(painter = painter, contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        }
        if (primaryFailed && (fallbackRequest == null || fallbackFailed)) {
            Box(Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(Color(0xFF392746), Color(0xFF172D3B), Color(0xFF202039)))
            ), contentAlignment = Alignment.Center) {
                Text("✦", color = Color.White.copy(alpha = .72f), fontSize = 25.sp)
            }
        }
    }
}

@Composable
internal fun AllervueHome(
    onOpen: (Item) -> Unit,
    onPlay: (Item) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }
    var homeWeather by remember { mutableStateOf<HomeWeather?>(null) }
    var weatherLoading by remember { mutableStateOf(false) }
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            while (true) {
                weatherLoading = homeWeather == null
                homeWeather = HomeWeatherRepository.load(context)
                weatherLoading = false
                delay(30 * 60 * 1000L)
            }
        } else {
            homeWeather = null
            weatherLoading = false
        }
    }
    var featured by remember { mutableStateOf<Item?>(null) }
    var resume by remember { mutableStateOf(emptyList<Item>()) }
    var recent by remember { mutableStateOf(emptyList<Item>()) }
    var picks by remember { mutableStateOf(emptyList<Item>()) }
    var selected by remember { mutableStateOf<Item?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(false) }
    val orbitRotation = remember { mutableFloatStateOf(0f) }
    val orbitZoom = remember { mutableFloatStateOf(1f) }
    // Start with the original six planets. Pinch in to reveal up to fifteen;
    // pinch out returns smoothly to the six-planet base system.
    val visiblePlanetCount = (
        6 + (((orbitZoom.floatValue - .72f) / (1.28f - .72f)) * 9f).toInt()
    ).coerceIn(6, 15)
    // Track local device time to the second so the celestial bodies move
    // continuously along their daily arcs and resume at the correct position.
    var deviceMinuteOfDay by remember {
        mutableFloatStateOf(
            Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60f +
                    it.get(Calendar.MINUTE) + it.get(Calendar.SECOND) / 60f
            }
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            deviceMinuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60f +
                now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f
            delay(1_000)
        }
    }
    val isDaytime = deviceMinuteOfDay >= 360f && deviceMinuteOfDay < 1080f
    val celestialProgress = if (isDaytime) {
        ((deviceMinuteOfDay - 360f) / 720f).coerceIn(0f, 1f)
    } else {
        val nightMinutes = if (deviceMinuteOfDay >= 1080f) {
            deviceMinuteOfDay - 1080f
        } else {
            deviceMinuteOfDay + 360f
        }
        (nightMinutes / 720f).coerceIn(0f, 1f)
    }

    // A fixed star map twinkles at different rates. Meteors are occasional,
    // independently randomized events and only run while the local sky is dark.
    val starField = remember {
        List(115) { Triple(Random.nextFloat(), Random.nextFloat() * .72f, Random.nextFloat()) }
    }
    var meteor by remember { mutableStateOf<HomeMeteor?>(null) }
    val meteorProgress = remember { Animatable(0f) }
    val meteorWeatherAllowsSky = homeWeather?.kind == null ||
        homeWeather?.kind in setOf("clear", "partly", "fog")
    LaunchedEffect(isDaytime, meteorWeatherAllowsSky) {
        if (isDaytime || !meteorWeatherAllowsSky) {
            meteor = null
            meteorProgress.snapTo(0f)
        } else {
            while (true) {
                delay(Random.nextLong(18_000L, 55_001L))
                meteor = HomeMeteor(
                    startX = Random.nextFloat() * .72f + .12f,
                    startY = Random.nextFloat() * .30f + .06f
                )
                meteorProgress.snapTo(0f)
                meteorProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = Random.nextInt(750, 1_201), easing = LinearEasing)
                )
                meteor = null
            }
        }
    }

    val orbitMotion = rememberInfiniteTransition(label = "home-orbit-motion")
    val automaticRotation by orbitMotion.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 52000, easing = LinearEasing)),
        label = "home-orbit-rotation"
    )

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
            selected = picks.firstOrNull() ?: featured ?: resume.firstOrNull() ?: recent.firstOrNull()
            if (featured == null && resume.isEmpty() && recent.isEmpty() && picks.isEmpty()) failure = true
        } catch (error: Exception) {
            if (error.message?.contains("401") == true) onLogout() else failure = true
        } finally {
            loading = false
        }
    }

    val allItems = remember(featured, resume, recent, picks) {
        (listOfNotNull(featured) + resume + recent + picks).distinctBy { it.id }
    }
    // PersonalizedPicks are ranked locally from this Jellyfin user's watched titles
    // and favorites. Fall back to Jellyfin's random featured results when history
    // is unavailable or too sparse.
    val centerCandidates = remember(picks, featured, recent, resume) {
        (picks + listOfNotNull(featured) + recent + resume).distinctBy { it.id }
    }
    var synchronizedCycle by remember { mutableIntStateOf(0) }
    LaunchedEffect(centerCandidates.map { it.id }) {
        if (centerCandidates.size > 1) {
            while (true) {
                delay(8_000)
                val currentId = selected?.id
                val next = centerCandidates
                    .filterNot { it.id == currentId }
                    .shuffled()
                    .firstOrNull()
                if (next != null) {
                    selected = next
                    synchronizedCycle++
                }
            }
        }
    }
    val centerItem = selected ?: centerCandidates.firstOrNull() ?: allItems.firstOrNull()
    // Every 8-second center change also reshuffles six outer artwork nodes in the same
    // cycle. Their six slot positions stay anchored while only the titles change.
    val visibleOrbitItems = remember(allItems, centerItem?.id, synchronizedCycle) {
        allItems.filter { it.id != centerItem?.id }.shuffled().take(15)
    }
    val related = allItems.filter { it.id != centerItem?.id }.take(3)

    Column(
        Modifier.fillMaxSize().drawBehind {
            // Day and night use deliberately distinct full-screen palettes.
            // Daylight is bright and airy rather than a slightly warmer version of night.
            drawRect(
                Brush.verticalGradient(
                    when (homeWeather?.kind) {
                        "rain", "storm" -> if (isDaytime) listOf(Color(0xFF71818C), Color(0xFF465B70), Color(0xFF24384E)) else listOf(Color(0xFF111B2B), Color(0xFF172236), Color(0xFF091421))
                        "cloudy", "fog" -> if (isDaytime) listOf(Color(0xFF9AA9AC), Color(0xFF647C89), Color(0xFF344B5D)) else listOf(Color(0xFF182234), Color(0xFF1B2235), Color(0xFF0C1826))
                        "snow" -> if (isDaytime) listOf(Color(0xFFB8C9D0), Color(0xFF829BAA), Color(0xFF4A6579)) else listOf(Color(0xFF202B40), Color(0xFF1B263A), Color(0xFF101C2B))
                        else -> if (isDaytime) listOf(Color(0xFFB9A17F), Color(0xFF668E9A), Color(0xFF294B60)) else listOf(Color(0xFF10182F), Color(0xFF17152D), Color(0xFF071522))
                    }
                )
            )
            // A visible sun or moon is part of the ambient background, not a UI icon.
            // Day arc runs east-to-west from 06:00 to 18:00. The moon
            // follows the same sky arc in reverse from sunset to sunrise.
            val arc = kotlin.math.sin(Math.PI * celestialProgress).toFloat()
            val skyX = if (isDaytime) {
                .08f + .84f * celestialProgress
            } else {
                .92f - .84f * celestialProgress
            }
            val skyBody = androidx.compose.ui.geometry.Offset(
                size.width * skyX,
                size.height * (.43f - .29f * arc)
            )
            val skyRadius = size.minDimension * .105f
            if (isDaytime) {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFE6A6).copy(alpha = .42f), Color(0xFFFFD28A).copy(alpha = .13f), Color.Transparent),
                        center = skyBody, radius = skyRadius * 3.2f
                    ),
                    radius = skyRadius * 3.2f, center = skyBody
                )
                drawCircle(Color(0xFFFFE7B0).copy(alpha = .88f), skyRadius, skyBody)
                drawCircle(Color.White.copy(alpha = .18f), skyRadius * .72f,
                    androidx.compose.ui.geometry.Offset(skyBody.x - skyRadius * .22f, skyBody.y - skyRadius * .25f))
            } else {
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0xFFB9D4FF).copy(alpha = .24f), Color(0xFF8D9DDA).copy(alpha = .07f), Color.Transparent),
                        center = skyBody, radius = skyRadius * 3.0f
                    ),
                    radius = skyRadius * 3.0f, center = skyBody
                )
                drawCircle(Color(0xFFE1E9FF).copy(alpha = .88f), skyRadius, skyBody)
                drawCircle(Color(0xFF17203A), skyRadius * .88f,
                    androidx.compose.ui.geometry.Offset(skyBody.x + skyRadius * .42f, skyBody.y - skyRadius * .24f))
            }
            // Weather changes the actual sky atmosphere, not just the label.
            val weatherKind = homeWeather?.kind
            if (weatherKind in setOf("cloudy", "fog", "rain", "storm", "snow")) {
                val cloudAlpha = when (weatherKind) {
                    "rain", "storm" -> .34f
                    "fog" -> .42f
                    else -> .27f
                }
                for (cloud in 0 until 7) {
                    val cloudX = size.width * ((cloud * .19f + .08f) % 1f)
                    val cloudY = size.height * (.12f + (cloud % 3) * .075f)
                    val cloudRadius = size.width * (.15f + (cloud % 2) * .035f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0xFFDCE5EF).copy(alpha = cloudAlpha), Color(0xFFB9C8D8).copy(alpha = cloudAlpha * .34f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(cloudX, cloudY),
                            radius = cloudRadius
                        ),
                        radius = cloudRadius,
                        center = androidx.compose.ui.geometry.Offset(cloudX, cloudY)
                    )
                }
            }
            if (weatherKind == "rain" || weatherKind == "storm") {
                for (drop in 0 until 58) {
                    val x = (drop * 83f + automaticRotation * 2.2f) % size.width
                    val y = (drop * 59f + automaticRotation * 3.4f) % (size.height * .76f)
                    drawLine(
                        Color(0xFFBBD8EF).copy(alpha = if (weatherKind == "storm") .28f else .20f),
                        androidx.compose.ui.geometry.Offset(x, y),
                        androidx.compose.ui.geometry.Offset(x - 5.dp.toPx(), y + 13.dp.toPx()),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
            if (!isDaytime) {
                // Stars have individually phased, restrained twinkle.
                starField.forEachIndexed { index, star ->
                    val twinkle = (.42f + .34f * kotlin.math.sin(
                        Math.toRadians((automaticRotation * (1.1f + star.third * 1.8f) + index * 47f).toDouble())
                    ).toFloat()).coerceIn(.12f, .82f)
                    drawCircle(
                        Color(0xFFEAF2FF).copy(alpha = twinkle * when (weatherKind) {
                            "cloudy", "fog" -> .58f
                            "rain", "storm", "snow" -> .18f
                            else -> 1f
                        }),
                        radius = .65f + star.third * 1.15f,
                        center = androidx.compose.ui.geometry.Offset(size.width * star.first, size.height * star.second)
                    )
                }
                meteor?.let { shootingStar ->
                    val progress = meteorProgress.value
                    val travel = androidx.compose.ui.geometry.Offset(size.width * .34f, size.height * .25f)
                    val tip = androidx.compose.ui.geometry.Offset(
                        size.width * shootingStar.startX + travel.x * progress,
                        size.height * shootingStar.startY + travel.y * progress
                    )
                    val tail = androidx.compose.ui.geometry.Offset(tip.x - travel.x * .24f, tip.y - travel.y * .24f)
                    drawLine(Color(0xFFB9D8FF).copy(alpha = .24f), tail, tip, 7.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(Color.White, tail, tip, 2.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(Color.White, radius = 2.1.dp.toPx(), center = tip)
                }
            }

            // Ambient lighting drifts across the entire Home surface, including
            // the lower information area. Motion is slow and continuous, never flashing.
            val phase = Math.toRadians((automaticRotation * .38f).toDouble())
            val ambientA = androidx.compose.ui.geometry.Offset(
                size.width * (.5f + kotlin.math.cos(phase).toFloat() * .22f),
                size.height * (.48f + kotlin.math.sin(phase).toFloat() * .20f)
            )
            val ambientB = androidx.compose.ui.geometry.Offset(
                size.width * (.5f + kotlin.math.cos(phase + 2.5).toFloat() * .30f),
                size.height * (.55f + kotlin.math.sin(phase + 2.5).toFloat() * .24f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    (if (isDaytime) listOf(Color(0xFFFFC66A).copy(alpha = .25f), Color(0xFFFF9B68).copy(alpha = .06f), Color.Transparent) else listOf(Color(0xFF9BBFFF).copy(alpha = .22f), Color(0xFF8C8FE0).copy(alpha = .07f), Color.Transparent)),
                    center = ambientA, radius = size.maxDimension * .72f
                ),
                radius = size.maxDimension * .72f, center = ambientA
            )
            drawCircle(
                brush = Brush.radialGradient(
                    (if (isDaytime) listOf(Color(0xFFFFDDA0).copy(alpha = .16f), Color(0xFF75C6D1).copy(alpha = .06f), Color.Transparent) else listOf(Color(0xFF8D78C7).copy(alpha = .19f), Color(0xFF4B8EAA).copy(alpha = .06f), Color.Transparent)),
                    center = ambientB, radius = size.maxDimension * .66f
                ),
                radius = size.maxDimension * .66f, center = ambientB
            )
        }
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * .5f
                val cy = size.height * .55f
                val radius = size.width * .43f
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 8.dp.toPx()), 0f)
                val center = androidx.compose.ui.geometry.Offset(cx, cy)
                val drift = Math.toRadians((automaticRotation * .42f).toDouble())
                val glowCenterA = androidx.compose.ui.geometry.Offset(
                    cx + kotlin.math.cos(drift).toFloat() * radius * .24f,
                    cy + kotlin.math.sin(drift).toFloat() * radius * .18f
                )
                val glowCenterB = androidx.compose.ui.geometry.Offset(
                    cx + kotlin.math.cos(drift + 2.4).toFloat() * radius * .34f,
                    cy + kotlin.math.sin(drift + 2.4).toFloat() * radius * .24f
                )
                // Slow-moving ambient color fields add depth without flashing or
                // changing the orbit geometry when featured titles shuffle.
                drawRect(
                    brush = Brush.radialGradient(
                        colors = (if (isDaytime) listOf(Color(0xFFFFC56C).copy(alpha = .19f), Color(0xFFFFA16B).copy(alpha = .05f), Color.Transparent) else listOf(Color(0xFF7DAFFF).copy(alpha = .17f), Color(0xFF9A83D0).copy(alpha = .05f), Color.Transparent)),
                        center = glowCenterA, radius = radius * .95f
                    )
                )
                drawRect(
                    brush = Brush.radialGradient(
                        colors = (if (isDaytime) listOf(Color(0xFFFFDDA0).copy(alpha = .12f), Color(0xFF73BFCB).copy(alpha = .04f), Color.Transparent) else listOf(Color(0xFF9A78D0).copy(alpha = .15f), Color(0xFF527DA8).copy(alpha = .04f), Color.Transparent)),
                        center = glowCenterB, radius = radius * .82f
                    )
                )
                drawCircle(Color.White.copy(alpha = .11f), radius, center, style = Stroke(1.dp.toPx(), pathEffect = dash))
                drawCircle(Color.White.copy(alpha = .13f), radius * .78f, center, style = Stroke(1.dp.toPx(), pathEffect = dash))
                drawCircle(Color.White.copy(alpha = .15f), radius * .66f, center, style = Stroke(1.dp.toPx(), pathEffect = dash))
                drawCircle(Color(0xFFEF6C8B).copy(alpha = .10f), radius * .98f, center, style = Stroke(18.dp.toPx()))
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF49BFD0).copy(alpha = .16f), Color(0xFF8B4D9A).copy(alpha = .07f), Color.Transparent),
                        center = center, radius = radius * .78f
                    ),
                    radius = radius * .78f, center = center
                )
                // A restrained particle field drifts continuously around the scene.
                // Particles are decorative only; they never affect movie-node placement.
                for (i in 0 until 12) {
                    val angle = Math.toRadians((automaticRotation * (if (i % 2 == 0) .34f else -.22f) + i * 30f).toDouble())
                    val orbitRadius = radius * (.72f + (i % 3) * .105f)
                    val particle = androidx.compose.ui.geometry.Offset(
                        cx + kotlin.math.cos(angle).toFloat() * orbitRadius,
                        cy + kotlin.math.sin(angle).toFloat() * orbitRadius * .58f
                    )
                    val alpha = .28f + (i % 4) * .12f
                    drawCircle(
                        if (i % 2 == 0) Color(0xFF8CE6E8).copy(alpha = alpha)
                        else Color(0xFFFFB4C5).copy(alpha = alpha),
                        (1.2f + (i % 3) * .45f).dp.toPx(), particle
                    )
                }
                val sweepAngle = Math.toRadians((automaticRotation * .72f - 90f).toDouble())
                val sweepCenter = androidx.compose.ui.geometry.Offset(
                    cx + kotlin.math.cos(sweepAngle).toFloat() * radius,
                    cy + kotlin.math.sin(sweepAngle).toFloat() * radius
                )
                drawCircle(Color(0xFFFFB4C5).copy(alpha = .82f), 3.2.dp.toPx(), sweepCenter)
            }

            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp).zIndex(2f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AllervueWordmark(
                    emblemSize = 38.dp,
                    textSize = 25.sp,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0x33201D31))
                        .clickable(onClick = onSearch),
                    contentAlignment = Alignment.Center
                ) { Text("⌕", color = Color.White, fontSize = 28.sp) }
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Color(0x33201D31))
                        .clickable(onClick = onSettings),
                    contentAlignment = Alignment.Center
                ) { Text("♙", color = Color.White, fontSize = 23.sp) }
            }

            Text("DISCOVER", Modifier.align(Alignment.TopCenter).padding(top = 86.dp), color = Color.White.copy(alpha = .48f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("GENTLE", Modifier.align(Alignment.CenterStart).padding(start = 8.dp), color = Color.White.copy(alpha = .50f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("INTENSE", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), color = Color.White.copy(alpha = .50f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Text("LATE NIGHT", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), color = Color.White.copy(alpha = .42f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)

            BoxWithConstraints(
                // Keep the orbit gesture surface below the top action row so it
                // cannot intercept taps on Search or Settings.
                Modifier.fillMaxSize().padding(top = 82.dp).pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        // Pinch inward (zoom out) reveals more planets; pinch
                        // outward (zoom in) reduces the visible orbit to its core.
                        orbitZoom.floatValue =
                            (orbitZoom.floatValue * zoomChange).coerceIn(.72f, 1.28f)
                        if (kotlin.math.abs(pan.x) > 0.5f) {
                            orbitRotation.floatValue =
                                (orbitRotation.floatValue + pan.x / 3.2f) % 360f
                        }
                    }
                }
            ) {
                val w = maxWidth
                val h = maxHeight
                // Planet diameters vary like a miniature solar system; each slot
                // keeps its own size when artwork is shuffled every eight seconds.
                val planetSizes = listOf(42.dp, 58.dp, 36.dp, 64.dp, 48.dp, 32.dp)
                val centerSize = (w * .48f).coerceIn(172.dp, 216.dp)
                val orbitCenterX = w * .5f
                val orbitCenterY = h * .50f
                // Every artwork node follows the same fixed elliptical orbit.
                // No alternating radii or per-node clamping: those caused circles
                // to jump between tracks and bunch up at the screen edges.
                // Each node gets its own randomized orbital lane and phase.
                // Keep angular slots separated to prevent circles colliding, while
                // varying radius and rotation speed so the field never reads as one ellipse.
                // Slot geometry is tied only to layout dimensions and node count—not movie IDs.
                // Replacing/shuffling artwork therefore never resets a circle's path or position.
                val orbitSlots = remember(visibleOrbitItems.size, w, h, planetSizes) {
                    val count = visibleOrbitItems.size.coerceAtLeast(1)
                    List(visibleOrbitItems.size) { slot ->
                        val baseAngle = -90f + slot * (360f / count)
                        val phaseJitter = kotlin.random.Random.nextFloat() * 34f - 17f
                        val planetSize = planetSizes[slot % planetSizes.size]
                        val outerSize = planetSize + 8.dp
                        val radiusX = (w * (.25f + kotlin.random.Random.nextFloat() * .18f))
                            .coerceAtMost((w - outerSize) / 2 - 6.dp)
                        val radiusY = (h * (.13f + kotlin.random.Random.nextFloat() * .20f))
                            .coerceAtMost((h - outerSize) / 2 - 12.dp)
                        val speed = .65f + kotlin.random.Random.nextFloat() * .7f
                        Triple(baseAngle + phaseJitter, androidx.compose.ui.geometry.Offset(radiusX.value, radiusY.value), speed)
                    }
                }
                visibleOrbitItems.take(visiblePlanetCount).forEachIndexed { index, item ->
                    val lane = orbitSlots[index]
                    val nodeSize = planetSizes[index % planetSizes.size]
                    val nodeOuterSize = nodeSize + 8.dp
                    val angle = Math.toRadians(
                        (lane.first + automaticRotation * lane.third + orbitRotation.floatValue).toDouble()
                    )
                    val radiusX = lane.second.x.dp
                    val radiusY = lane.second.y.dp
                    val x = (orbitCenterX + radiusX * kotlin.math.cos(angle).toFloat() - nodeOuterSize / 2)
                        .coerceIn(6.dp, (w - nodeOuterSize - 6.dp).coerceAtLeast(6.dp))
                    val y = (orbitCenterY + radiusY * kotlin.math.sin(angle).toFloat() - nodeOuterSize / 2)
                        .coerceIn(112.dp, (h - nodeOuterSize - 10.dp).coerceAtLeast(112.dp))
                    Column(
                        Modifier.offset(x = x, y = y).width(nodeOuterSize)
                            .clickable { selected = item; synchronizedCycle++ },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(nodeOuterSize).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF63D8DE), Color(0xFF9B6AB5), Color(0xFFFF7B86))))
                                .padding(2.dp)
                        ) {
                            HomeArtwork(
                                model = Api.poster(item),
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF25213A)),
                                contentScale = ContentScale.Crop,
                                fallbackModel = Api.heroImage(item)
                            )
                        }
                    }
                }

                if (centerItem != null) {
                    // The central star/moon follows the device's local clock.
                    Box(
                        Modifier.offset(
                            x = orbitCenterX - (centerSize + 42.dp) / 2,
                            y = orbitCenterY - (centerSize + 42.dp) / 2
                        ).size(centerSize + 42.dp).clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    if (isDaytime) listOf(Color(0xFFFFC65C).copy(alpha = .36f), Color(0xFFFF8A4C).copy(alpha = .12f), Color.Transparent)
                                    else listOf(Color(0xFFB9D8FF).copy(alpha = .30f), Color(0xFF8795D8).copy(alpha = .12f), Color.Transparent)
                                )
                            )
                    )
                    Box(
                        Modifier.offset(
                            x = orbitCenterX - (centerSize + 14.dp) / 2,
                            y = orbitCenterY - (centerSize + 14.dp) / 2
                        )
                            .size(centerSize + 14.dp).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF74E0E1), Color(0xFFAB75C4), Color(0xFFFF8A8A))))
                            .padding(3.dp)
                    ) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape)
                                .background(Color(0xFF100E20))
                                .border(1.5.dp, Color.White.copy(alpha = .78f), CircleShape)
                                .clickable { onOpen(centerItem) }
                        ) {
                            HomeArtwork(
                                model = Api.backdrop(centerItem),
                                contentDescription = centerItem.name,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                fallbackModel = Api.heroImage(centerItem)
                            )
                            Box(Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color(0x220B0915), Color(0x550B0915)))
                            ))
                        }
                    }

                } else if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = AAccent)
                } else if (failure) {
                    Text("Your library isn't available", Modifier.align(Alignment.Center), color = Color.White)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().height(254.dp)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(Brush.verticalGradient(if (isDaytime) listOf(Color(0xF22B293D), Color(0xE61B3040)) else listOf(Color(0xF2141930), Color(0xE60D1D2B))))
                .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(78.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(centerItem?.name ?: if (loading) "Finding your next story" else "Your library",
                        Modifier.height(58.dp).fillMaxWidth(),
                        color = Color(0xFFF7F3FF), fontSize = 25.sp, lineHeight = 29.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-.8).sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = centerItem?.let {
                        listOfNotNull(it.type, it.year?.toString(), it.officialRating).joinToString(" · ")
                    } ?: "Explore your Jellyfin library"
                    Text(meta, Modifier.height(20.dp).fillMaxWidth(),
                        color = Color(0xFFB7B2C7), fontSize = 12.sp, maxLines = 1)
                }
                Box(
                    Modifier.size(62.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFFF7654), AHot)))
                        .clickable { centerItem?.let(onPlay) },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(25.dp)) {
                        val play = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * .29f, size.height * .12f)
                            lineTo(size.width * .92f, size.height * .50f)
                            lineTo(size.width * .29f, size.height * .88f)
                            close()
                        }
                        drawPath(play, Color(0xFF170D1B))
                    }
                }
            }
            // Reserve the synopsis slot even when an item has no description so
            // the panel geometry never shifts between featured titles.
            Text(centerItem?.overview.orEmpty(), Modifier.fillMaxWidth().height(62.dp).padding(top = 6.dp),
                color = Color(0xFFC7C2D3), fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(
                Modifier.fillMaxWidth().height(32.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("MORE IN THIS MOOD", color = Color(0xFF8CE0E2), fontSize = 9.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                    (centerItem?.genres.orEmpty().take(2)).forEach { genre ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF302A48)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                            Text(genre, color = Color(0xFFE1DCEC), fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(32.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        if (!hasLocationPermission) {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        } else {
                            weatherLoading = true
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (homeWeather != null) {
                    val weather = homeWeather!!
                    Text(
                        when (weather.kind) {
                            "rain", "storm" -> "🌧"
                            "cloudy", "fog" -> "☁"
                            "snow" -> "❄"
                            "partly" -> "⛅"
                            else -> if (weather.isDay) "☀" else "☾"
                        },
                        fontSize = 17.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${weather.city}  ·  ${weather.condition}",
                            color = Color(0xFFE5E5F0), fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("Feels like ${weather.apparentTemperature}°C",
                            color = Color(0xFFB7B2C7), fontSize = 9.sp, maxLines = 1)
                    }
                    Text("${weather.temperature}°",
                        color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        when {
                            weatherLoading -> "Finding local weather…"
                            hasLocationPermission -> "Weather unavailable · Tap to retry"
                            else -> "Tap to enable local weather"
                        },
                        color = Color(0xFFB7DDE0), fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
