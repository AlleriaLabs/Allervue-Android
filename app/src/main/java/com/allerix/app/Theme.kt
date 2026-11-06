package com.allerix.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val Red = Color(0xFFD8B77E)
val BrandGold = Color(0xFFD8B77E)
val BrandGoldBright = Color(0xFFE8CCA0)
val BrandInk = Color(0xFF101013)
val BrandSurface = Color(0xFF19191E)
val BrandIvory = Color(0xFFF4EFE5)
val BgDark = Color(0xFF0A0A0C)
val Grey = Color(0xFFB3B3B3)
val Dim = Color(0xFF8A8A90)
val GlassColor = Color(0xE619191E)
val GlassBorder = Color(0x14D8B77E)

fun Modifier.glass(radius: Dp = 16.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this.clip(shape).background(GlassColor).border(1.dp, GlassBorder, shape)
}

@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(0f to Color(0xFF211C16), 0.28f to Color(0xFF121216), 0.72f to BgDark, 1f to BgDark)
        ),
        content = content
    )
}

@Composable
fun SmallIcon(res: Int, tint: Color = Color.White, size: Int = 24, modifier: Modifier = Modifier) {
    Icon(painterResource(res), null, tint = tint, modifier = modifier.size(size.dp))
}

@Composable
fun Badge(text: String) {
    Text(
        text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
fun TabHeader(title: String, onSearch: (() -> Unit)?, onSettings: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = BrandIvory, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onSearch != null) IconButton(onClick = onSearch) { SmallIcon(R.drawable.ic_search) }
        if (onSettings != null) IconButton(onClick = onSettings) { SmallIcon(R.drawable.ic_settings) }
    }
}

/** "+ My List" toggle backed by Jellyfin favorites. */
@Composable
fun MyListButton(item: Item, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var fav by remember(item.id) { mutableStateOf(item.userData?.isFavorite == true) }
    Button(
        onClick = {
            val now = !fav
            fav = now
            scope.launch { runCatching { Api.setFavorite(item.id, now) } }
        },
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28231B), contentColor = BrandIvory),
        border = BorderStroke(1.dp, BrandGold.copy(alpha = 0.32f))
    ) {
        SmallIcon(if (fav) R.drawable.ic_check else R.drawable.ic_add, size = 18)
        Spacer(Modifier.width(6.dp))
        Text("My List", fontWeight = FontWeight.Bold)
    }
}

fun fmtRuntime(ticks: Long?): String {
    val m = ((ticks ?: 0L) / 600_000_000L).toInt()
    return if (m <= 0) "" else if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

fun leftText(item: Item): String {
    val rt = item.runTimeTicks ?: return ""
    val s = fmtRuntime(rt - (item.userData?.positionTicks ?: 0L))
    return if (s.isEmpty()) "" else "$s left"
}

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

fun fmtClock(ms: Long): String {
    val s = ms.coerceAtLeast(0L) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
