package com.allerix.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
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
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.random.Random

private val AAccent = AllervueDesign.Cyan
private val AHot = Color(0xFFFF4D86)
private val AInk = Color(0xFF100E20)
private val APanel = Color(0xFF17152C)

private data class HomeMeteor(val startX: Float, val startY: Float)

private fun sampleArtworkGlow(drawable: android.graphics.drawable.Drawable): Color? = runCatching {
    // Draw any Coil drawable (BitmapDrawable, wrapped/crossfade drawable, etc.)
    // into a small software bitmap. This avoids HARDWARE bitmap pixel access
    // and does not depend on Coil's concrete drawable type.
    val sampleSize = 48
    val bitmap = android.graphics.Bitmap.createBitmap(
        sampleSize, sampleSize, android.graphics.Bitmap.Config.ARGB_8888
    )
    val oldBounds = android.graphics.Rect(drawable.bounds)
    try {
        drawable.setBounds(0, 0, sampleSize, sampleSize)
        drawable.draw(android.graphics.Canvas(bitmap))
    } finally {
        drawable.bounds = oldBounds
    }

    var red = 0L
    var green = 0L
    var blue = 0L
    var samples = 0L
    val step = 6
    for (y in step until sampleSize step step) {
        for (x in step until sampleSize step step) {
            val pixel = bitmap.getPixel(x, y)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            samples++
        }
    }
    bitmap.recycle()
    if (samples == 0L) return@runCatching null
    Color(android.graphics.Color.rgb(
        (red / samples).toInt(), (green / samples).toInt(), (blue / samples).toInt()
    ))
}.getOrNull()


private data class HomeSnapshot(
    val userId: String,
    val featured: Item?,
    val resume: List<Item>,
    val recent: List<Item>,
    val picks: List<Item>
)

private object HomeSnapshotCache {
    @Volatile var snapshot: HomeSnapshot? = null
}

@Composable
private fun HomeArtwork(
    model: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackModel: String? = null,
    onModelLoaded: (() -> Unit)? = null,
    onArtworkColor: ((Color) -> Unit)? = null
) {
    // Keep the last successfully decoded artwork underneath the next request.
    // The new image is drawn over it and becomes the displayed model only after
    // Coil reports success, avoiding a blank/black frame during movie changes.
    var displayedModel by remember { mutableStateOf(model) }
    var pendingModel by remember { mutableStateOf<String?>(null) }
    var pendingUsesFallback by remember { mutableStateOf(false) }

    LaunchedEffect(model) {
        if (model != displayedModel) {
            pendingModel = model
            pendingUsesFallback = false
        }
    }

    Box(
        modifier.background(
            Brush.radialGradient(
                listOf(Color(0xFF59435F), Color(0xFF263E50), Color(0xFF17152C))
            )
        )
    ) {
        AsyncImage(
            model = displayedModel,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale,
            onSuccess = { state ->
                sampleArtworkGlow(state.result.drawable)?.let { onArtworkColor?.invoke(it) }
            }
        )

        val requestModel = pendingModel
        if (requestModel != null) {
            val requestFallback = pendingUsesFallback
            AsyncImage(
                model = if (requestFallback) fallbackModel ?: requestModel else requestModel,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = { state ->
                    sampleArtworkGlow(state.result.drawable)?.let { onArtworkColor?.invoke(it) }
                    if (pendingModel == requestModel) {
                        displayedModel = requestModel
                        pendingModel = null
                        pendingUsesFallback = false
                        onModelLoaded?.invoke()
                    }
                },
                onError = {
                    if (pendingModel == requestModel) {
                        if (!requestFallback && !fallbackModel.isNullOrBlank()) {
                            pendingUsesFallback = true
                        } else {
                            // Keep the previous successful artwork if both
                            // the primary and fallback artwork fail.
                            pendingModel = null
                            pendingUsesFallback = false
                        }
                    }
                }
            )
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
    var weatherRefresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
    LaunchedEffect(hasLocationPermission, weatherRefresh) {
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
    val cachedHome = remember(Api.userId) {
        HomeSnapshotCache.snapshot?.takeIf { it.userId == Api.userId }
    }
    var featured by remember(Api.userId) { mutableStateOf(cachedHome?.featured) }
    var resume by remember(Api.userId) { mutableStateOf(cachedHome?.resume ?: emptyList()) }
    var recent by remember(Api.userId) { mutableStateOf(cachedHome?.recent ?: emptyList()) }
    var picks by remember(Api.userId) { mutableStateOf(cachedHome?.picks ?: emptyList()) }
    var selected by remember(Api.userId) {
        mutableStateOf(cachedHome?.picks?.firstOrNull() ?: cachedHome?.featured
            ?: cachedHome?.resume?.firstOrNull() ?: cachedHome?.recent?.firstOrNull())
    }
    var loading by remember(Api.userId) { mutableStateOf(cachedHome == null) }
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
        (homeWeather?.kind?.let { it in setOf("clear", "partly", "fog") } == true)
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

    LaunchedEffect(Api.userId) {
        val accountId = Api.userId
        try {
            // Fetch the first three Home sections in parallel. Render the hero as
            // soon as the first result arrives instead of serially waiting for all.
            coroutineScope {
                val featuredTask = async { runCatching { Api.featured().firstOrNull() }.getOrNull() }
                val resumeTask = async {
                    runCatching {
                        Api.resume().filterNot { it.id in ContinueWatching.hiddenIds(context) }
                    }.getOrDefault(emptyList())
                }
                val recentTask = async { runCatching { Api.trending() }.getOrDefault(emptyList()) }

                featured = featuredTask.await()
                if (selected == null) selected = featured
                resume = resumeTask.await()
                if (selected == null) selected = resume.firstOrNull()
                recent = recentTask.await()
                if (selected == null) selected = recent.firstOrNull()

                // The primary experience is now usable. Personalized ranking is
                // secondary work and must not hold the Home hero behind it.
                loading = false
                failure = featured == null && resume.isEmpty() && recent.isEmpty()
                if (failure) return@coroutineScope

                // Persist the usable first-paint data immediately; do not make
                // the next Home visit wait for the slower taste-ranking request.
                if (Api.userId == accountId) {
                    HomeSnapshotCache.snapshot = HomeSnapshot(
                        userId = accountId, featured = featured, resume = resume,
                        recent = recent, picks = picks
                    )
                }

                val loadedPicks = runCatching {
                    Api.personalizedPicks((resume + recent).map { it.id }.toSet())
                }.getOrDefault(emptyList())
                picks = loadedPicks
                selected = loadedPicks.firstOrNull() ?: selected ?: featured
                if (Api.userId == accountId) {
                    HomeSnapshotCache.snapshot = HomeSnapshot(
                        userId = accountId, featured = featured, resume = resume,
                        recent = recent, picks = picks
                    )
                }
            }
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
    // Keep the center artwork and its title/metadata as one atomic selection.
    // The text panel changes only after the matching image has decoded.
    var displayedCenterItem by remember(Api.userId) {
        mutableStateOf(cachedHome?.picks?.firstOrNull() ?: cachedHome?.featured
            ?: cachedHome?.resume?.firstOrNull() ?: cachedHome?.recent?.firstOrNull())
    }
    val shownCenterItem = displayedCenterItem
    // On a cold Home launch there may be no cached selection yet. Seed the
    // displayed item as soon as the first Jellyfin item arrives; otherwise the
    // text panel stays empty until an unrelated navigation causes recomposition.
    // After this initial seed, keep the artwork/text handoff atomic on rotations.
    LaunchedEffect(centerItem?.id) {
        if (displayedCenterItem == null && centerItem != null) {
            displayedCenterItem = centerItem
        }
    }
    // Keep the orbit's artwork stable across the 8-second center rotation.
    // Previously every center change reshuffled all six visible nodes, forcing
    // six new remote image downloads even though most titles were already shown.
    // Only replace the node that becomes the new center item.
    val orbitPool = remember(allItems) { allItems.shuffled() }
    val visibleOrbitItems = remember(orbitPool, centerItem?.id) {
        orbitPool.filter { it.id != centerItem?.id }.take(15)
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
            // Weather is the artwork: moving cloud banks, drifting fog, rain,
            // snow and storm light are painted into the full-screen sky itself.
            val weatherKind = homeWeather?.kind
            val weatherPhase = automaticRotation
            val isOvercast = weatherKind in setOf("cloudy", "fog", "rain", "storm", "snow")
            if (isOvercast) {
                val cloudAlpha = when (weatherKind) {
                    "rain", "storm" -> .43f
                    "fog" -> .28f
                    "snow" -> .34f
                    else -> .36f
                }
                // Broad, layered cloud masses travel at different speeds and heights.
                for (cloud in 0 until 9) {
                    val drift = weatherPhase * (if (cloud % 2 == 0) .19f else -.12f)
                    val rawX = cloud * size.width * .17f + drift * size.width / 360f
                    val cloudX = ((rawX % (size.width * 1.45f)) + size.width * 1.45f) % (size.width * 1.45f) - size.width * .20f
                    val cloudY = size.height * (.105f + (cloud % 4) * .062f)
                    val cloudRadius = size.width * (.19f + (cloud % 3) * .035f)
                    val cloudCenter = androidx.compose.ui.geometry.Offset(cloudX, cloudY)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFDCE7F0).copy(alpha = cloudAlpha),
                                Color(0xFFAABBCB).copy(alpha = cloudAlpha * .52f),
                                Color(0xFF71869A).copy(alpha = cloudAlpha * .12f),
                                Color.Transparent
                            ),
                            center = cloudCenter, radius = cloudRadius
                        ),
                        radius = cloudRadius, center = cloudCenter
                    )
                    // A soft underside gives the cloud bank depth instead of flat dots.
                    drawOval(
                        Brush.verticalGradient(
                            listOf(Color(0xFF25394C).copy(alpha = cloudAlpha * .08f), Color(0xFF101B2B).copy(alpha = cloudAlpha * .28f), Color.Transparent)
                        ),
                        topLeft = androidx.compose.ui.geometry.Offset(cloudX - cloudRadius, cloudY),
                        size = androidx.compose.ui.geometry.Size(cloudRadius * 2f, cloudRadius * .72f)
                    )
                }
            }
            if (weatherKind == "fog") {
                // Fog sheets slide slowly across the horizon and veil distant light.
                for (layer in 0 until 4) {
                    val drift = weatherPhase * (if (layer % 2 == 0) .11f else -.075f)
                    val x = size.width * (.5f + kotlin.math.sin(Math.toRadians((drift + layer * 83f).toDouble())).toFloat() * .22f)
                    val y = size.height * (.30f + layer * .105f)
                    val fogRadius = size.maxDimension * (.42f + layer * .055f)
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0xFFDCE6E8).copy(alpha = .16f), Color(0xFFC5D3D9).copy(alpha = .075f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(x, y), radius = fogRadius
                        ),
                        radius = fogRadius,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                }
            }
            if (weatherKind == "rain" || weatherKind == "storm") {
                // Slanted rain spans the sky and foreground, with varied lengths and speeds.
                for (drop in 0 until 105) {
                    val speed = 2.1f + (drop % 5) * .42f
                    val x = (drop * 47f + weatherPhase * speed) % size.width
                    val y = (drop * 31f + weatherPhase * (speed + .9f)) % (size.height * .92f)
                    val length = (9 + drop % 9).dp.toPx()
                    drawLine(
                        Color(0xFFD6E8F7).copy(alpha = if (weatherKind == "storm") .31f else .23f),
                        androidx.compose.ui.geometry.Offset(x, y),
                        androidx.compose.ui.geometry.Offset(x - length * .34f, y + length),
                        strokeWidth = if (drop % 4 == 0) 1.45.dp.toPx() else .85.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
            if (weatherKind == "snow") {
                // Snowflakes fall at different speeds and drift sideways in the wind.
                for (flake in 0 until 72) {
                    val speed = .55f + (flake % 6) * .16f
                    val x = (flake * 59f + kotlin.math.sin(Math.toRadians((weatherPhase * .8f + flake * 29f).toDouble())).toFloat() * 24f + weatherPhase * .22f) % size.width
                    val y = (flake * 37f + weatherPhase * speed) % (size.height * .88f)
                    val radius = (.8f + (flake % 4) * .42f).dp.toPx()
                    drawCircle(Color(0xFFEAF5FF).copy(alpha = .48f + (flake % 3) * .12f), radius, androidx.compose.ui.geometry.Offset(x, y))
                }
                drawRect(Color(0xFFDAEAF5).copy(alpha = .045f))
            }
            if (weatherKind == "storm") {
                // A restrained distant lightning glow illuminates the cloud deck periodically.
                val lightningPulse = (kotlin.math.sin(Math.toRadians((weatherPhase * 3.7f).toDouble())).toFloat() - .965f).coerceAtLeast(0f) / .035f
                if (lightningPulse > 0f) {
                    drawRect(Color(0xFFDCEBFF).copy(alpha = lightningPulse * .12f))
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color(0xFFBBD8FF).copy(alpha = lightningPulse * .22f), Color.Transparent),
                            center = androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .24f),
                            radius = size.width * .58f
                        ),
                        radius = size.width * .58f,
                        center = androidx.compose.ui.geometry.Offset(size.width * .72f, size.height * .24f)
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
                        // Use the rendered outer-node diameter for orbit clearance.
                        // The vertical orbit must reach at least the top tangent of
                        // the center artwork instead of stopping short above it.
                        val outerSize = planetSize + 40.dp
                        val radiusX = (w * (.25f + kotlin.random.Random.nextFloat() * .18f))
                            .coerceAtMost((w - outerSize) / 2 - 6.dp)
                        val minimumVerticalRadius = centerSize / 2 + outerSize / 2 + 8.dp
                        val maximumVerticalRadius = (h - outerSize) / 2 - 12.dp
                        val radiusY = (h * (.13f + kotlin.random.Random.nextFloat() * .20f))
                            .coerceAtLeast(minimumVerticalRadius)
                            .coerceAtMost(maximumVerticalRadius)
                        val speed = .65f + kotlin.random.Random.nextFloat() * .7f
                        Triple(baseAngle + phaseJitter, androidx.compose.ui.geometry.Offset(radiusX.value, radiusY.value), speed)
                    }
                }
                visibleOrbitItems.take(visiblePlanetCount).forEachIndexed { index, item ->
                    val lane = orbitSlots[index]
                    val nodeSize = planetSizes[index % planetSizes.size]
                    val nodeOuterSize = nodeSize + 40.dp
                    val angle = Math.toRadians(
                        (lane.first + automaticRotation * lane.third + orbitRotation.floatValue).toDouble()
                    )
                    val radiusX = lane.second.x.dp
                    val radiusY = lane.second.y.dp
                    val x = (orbitCenterX + radiusX * kotlin.math.cos(angle).toFloat() - nodeOuterSize / 2)
                        .coerceIn(6.dp, (w - nodeOuterSize - 6.dp).coerceAtLeast(6.dp))
                    val y = (orbitCenterY + radiusY * kotlin.math.sin(angle).toFloat() - nodeOuterSize / 2)
                        .coerceIn(8.dp, (h - nodeOuterSize - 10.dp).coerceAtLeast(8.dp))
                    val artworkGlow = AHot
                    Column(
                        Modifier.offset(x = x, y = y).width(nodeOuterSize)
                            .clickable { selected = item; synchronizedCycle++ },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(nodeOuterSize).drawBehind {
                                val glowRadius = size.minDimension * .50f
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            artworkGlow.copy(alpha = .76f),
                                            artworkGlow.copy(alpha = .48f),
                                            artworkGlow.copy(alpha = .22f),
                                            artworkGlow.copy(alpha = .08f),
                                            Color.Transparent
                                        ),
                                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
                                        radius = glowRadius
                                    ),
                                    radius = glowRadius,
                                    center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                )
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(Modifier.size(nodeSize).clip(CircleShape)
                                
                                ) {
                            HomeArtwork(
                                model = Api.orbitPoster(item),
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color(0xFF25213A)),
                                contentScale = ContentScale.Crop,
                                fallbackModel = Api.heroImage(item),
                                onArtworkColor = { }
                            )
                            }
                        }
                    }
                }

                if (centerItem != null) {
                    val centerArtworkGlow = AHot
                    // The central star/moon follows the device's local clock.
                    Box(
                        Modifier.offset(
                            x = orbitCenterX - (centerSize + 72.dp) / 2,
                            y = orbitCenterY - (centerSize + 72.dp) / 2
                        ).size(centerSize + 72.dp).drawBehind {
                            val glowCenter = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            val glowRadius = size.minDimension * .50f
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        centerArtworkGlow.copy(alpha = .78f),
                                        centerArtworkGlow.copy(alpha = .48f),
                                        centerArtworkGlow.copy(alpha = .22f),
                                        centerArtworkGlow.copy(alpha = .08f),
                                        Color.Transparent
                                    ),
                                    center = glowCenter,
                                    radius = glowRadius
                                ),
                                radius = glowRadius,
                                center = glowCenter
                            )
                        }
                    )
                    Box(
                        Modifier.offset(
                            x = orbitCenterX - centerSize / 2,
                            y = orbitCenterY - centerSize / 2
                        )
                            .size(centerSize).clip(CircleShape)
                    ) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape)
                                .clickable { shownCenterItem?.let(onOpen) }
                        ) {
                            HomeArtwork(
                                model = Api.homeCenterImage(centerItem),
                                contentDescription = centerItem.name,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                fallbackModel = Api.heroImage(centerItem),
                                onModelLoaded = {
                                    if (selected?.id == centerItem.id) displayedCenterItem = centerItem
                                },
                                onArtworkColor = { }
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
                .drawBehind {
                    drawRect(Brush.verticalGradient(if (isDaytime) listOf(Color(0xF22B293D), Color(0xE61B3040)) else listOf(Color(0xF2141930), Color(0xE60D1D2B))))
                    val phase = Math.toRadians((automaticRotation * .52f).toDouble())
                    val glowA = androidx.compose.ui.geometry.Offset(
                        size.width * (.22f + .58f * ((kotlin.math.sin(phase).toFloat() + 1f) / 2f)),
                        size.height * (.18f + .42f * ((kotlin.math.cos(phase * .7).toFloat() + 1f) / 2f))
                    )
                    val glowB = androidx.compose.ui.geometry.Offset(
                        size.width * (.78f - .48f * ((kotlin.math.sin(phase + 1.8).toFloat() + 1f) / 2f)),
                        size.height * (.82f - .35f * ((kotlin.math.cos(phase + 1.2).toFloat() + 1f) / 2f))
                    )
                    val radius = size.maxDimension * .72f
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF6E9D).copy(alpha = .22f), Color(0xFFB56CFF).copy(alpha = .08f), Color.Transparent), glowA, radius), radius, glowA)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF53D8D5).copy(alpha = .18f), Color(0xFF668BFF).copy(alpha = .07f), Color.Transparent), glowB, radius * .82f), radius * .82f, glowB)
                }
                .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().height(78.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(shownCenterItem?.name ?: if (loading) "Finding your next story" else "Your library",
                        Modifier.height(58.dp).fillMaxWidth(),
                        color = Color(0xFFF7F3FF), fontSize = 25.sp, lineHeight = 29.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-.8).sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = shownCenterItem?.let {
                        listOfNotNull(it.type, it.year?.toString(), it.officialRating).joinToString(" · ")
                    } ?: "Explore your Jellyfin library"
                    Text(meta, Modifier.height(20.dp).fillMaxWidth(),
                        color = Color(0xFFB7B2C7), fontSize = 12.sp, maxLines = 1)
                }
                Box(
                    Modifier.size(116.dp).drawBehind {
                        val glowCenter = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val glowRadius = size.minDimension * .50f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    AHot.copy(alpha = .52f),
                                    AHot.copy(alpha = .78f),
                                    AHot.copy(alpha = .26f),
                                    AHot.copy(alpha = .10f),
                                    Color.Transparent
                                ),
                                center = glowCenter,
                                radius = glowRadius
                            ),
                            radius = glowRadius,
                            center = glowCenter
                        )
                    },
                    contentAlignment = Alignment.Center
                ) {
                Box(
                    Modifier.size(62.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFFF7654), AHot)))
                        .clickable { shownCenterItem?.let(onPlay) },
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
            }
            // Reserve the synopsis slot even when an item has no description so
            // the panel geometry never shifts between featured titles.
            Text(shownCenterItem?.overview.orEmpty(), Modifier.fillMaxWidth().height(62.dp).padding(top = 6.dp),
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
                    (shownCenterItem?.genres.orEmpty().take(2)).forEach { genre ->
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF302A48)).padding(horizontal = 9.dp, vertical = 5.dp)) {
                            Text(genre, color = Color(0xFFE1DCEC), fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}