package com.allerix.app

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private val AAccent = AllervueDesign.Cyan
private val AHot = Color(0xFFFF4D86)
private val AInk = Color(0xFF100E20)
private val APanel = Color(0xFF17152C)

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
            selected = featured ?: resume.firstOrNull() ?: recent.firstOrNull() ?: picks.firstOrNull()
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
    val centerItem = selected ?: featured ?: allItems.firstOrNull()
    val orbitItems = allItems.filter { it.id != centerItem?.id }.take(5)
    val related = allItems.filter { it.id != centerItem?.id }.take(3)

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF32191F), Color(0xFF17152D), Color(0xFF071F27))))
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width * .5f
                val cy = size.height * .55f
                val radius = size.width * .43f
                val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 8.dp.toPx()), 0f)
                drawCircle(Color.White.copy(alpha = .11f), radius, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(1.dp.toPx(), pathEffect = dash))
                drawCircle(Color.White.copy(alpha = .15f), radius * .66f, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(1.dp.toPx(), pathEffect = dash))
                drawCircle(Color(0xFFEF6C8B).copy(alpha = .10f), radius * .98f, androidx.compose.ui.geometry.Offset(cx, cy), style = Stroke(18.dp.toPx()))
            }

            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("allervue.", color = Color(0xFFF8F3FF), fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, modifier = Modifier.weight(1f))
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

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = maxWidth
                val h = maxHeight
                val nodeSize = (w * .22f).coerceIn(76.dp, 104.dp)
                val centerSize = (w * .48f).coerceIn(172.dp, 224.dp)
                val positions = listOf(
                    .10f to .30f, .69f to .22f, .73f to .62f, .08f to .75f, .50f to .83f
                )
                orbitItems.forEachIndexed { index, item ->
                    val (x, y) = positions[index]
                    Column(
                        Modifier.offset(x = w * x, y = h * y).width(nodeSize + 24.dp)
                            .clickable { selected = item },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AsyncImage(
                            model = Api.poster(item), contentDescription = item.name,
                            modifier = Modifier.size(nodeSize).clip(CircleShape)
                                .background(Color(0xFF25213A)),
                            contentScale = ContentScale.Crop
                        )
                        Text(item.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                    }
                }

                if (centerItem != null) {
                    Box(
                        Modifier.offset(x = (w - centerSize) / 2, y = h * .40f)
                            .size(centerSize).clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF7C3F88), Color(0xFFFF704D), Color(0xFF211238))))
                            .border(1.5.dp, Color.White.copy(alpha = .62f), CircleShape)
                            .clickable { onOpen(centerItem) }
                    ) {
                        AsyncImage(
                            model = Api.backdrop(centerItem), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                        )
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x220A0712), Color(0xC90A0712)))))
                        Box(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(centerItem.name.uppercase(), color = Color.White, fontSize = 19.sp, lineHeight = 22.sp,
                                fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(Color.Transparent).then(Modifier))
                    }

                } else if (loading) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = AAccent)
                } else if (failure) {
                    Text("Your library isn't available", Modifier.align(Alignment.Center), color = Color.White)
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF19162F), APanel)))
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(centerItem?.name ?: if (loading) "Finding your next story" else "Your library",
                        color = Color(0xFFF7F3FF), fontSize = 28.sp, lineHeight = 32.sp,
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-.8).sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = centerItem?.let {
                        listOfNotNull(it.type, it.year?.toString(), it.officialRating).joinToString(" · ")
                    } ?: "Explore your Jellyfin library"
                    Text(meta, color = Color(0xFFB7B2C7), fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp), maxLines = 1)
                }
                Box(
                    Modifier.size(66.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFFFF7654), AHot)))
                        .clickable { centerItem?.let(onPlay) },
                    contentAlignment = Alignment.Center
                ) { Text("▶", color = Color(0xFF170D1B), fontSize = 25.sp) }
            }
            if (!centerItem?.overview.isNullOrBlank()) {
                Text(centerItem?.overview.orEmpty(), color = Color(0xFFC7C2D3), fontSize = 12.sp,
                    lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp))
            }
            if (related.isNotEmpty()) {
                LazyRow(
                    Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(related, key = { it.id }) { item ->
                        Row(
                            Modifier.clip(CircleShape).background(Color(0xFF29263F))
                                .clickable { selected = item }
                                .padding(horizontal = 13.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(AHot))
                            Spacer(Modifier.width(8.dp))
                            Text(item.name, color = Color(0xFFF1EDFA), fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
