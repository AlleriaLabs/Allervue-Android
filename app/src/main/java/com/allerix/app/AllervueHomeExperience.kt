package com.allerix.app

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

private val AAccent = AllervueDesign.Cyan
private val AHot = Color(0xFFFF4D86)
private val AInk = Color(0xFF100E20)
private val APanel = Color(0xFF17152C)

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
    var deviceHour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    LaunchedEffect(Unit) {
        while (true) {
            deviceHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            delay(60_000)
        }
    }
    val isDaytime = deviceHour in 6..17

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
            drawRect(Brush.verticalGradient(if (isDaytime) listOf(Color(0xFF563B38), Color(0xFF263B4A), Color(0xFF142C38)) else listOf(Color(0xFF10182F), Color(0xFF17152D), Color(0xFF071522))))
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
        }
    }
}
