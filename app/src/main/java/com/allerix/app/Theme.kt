package com.allerix.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.random.Random

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

/**
 * One persistent ambient sky shared by every destination in the app.
 * Weather is rendered as atmosphere and particles, never as a text widget.
 */
@Composable
fun AllervueAmbientBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var weather by remember { mutableStateOf<HomeWeather?>(null) }
    // Home owns the permission prompt. The shared scene simply observes permission
    // so it never launches a duplicate system dialog while the user enters Home.
    LaunchedEffect(Unit) {
        while (true) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (granted) weather = HomeWeatherRepository.load(context)
            delay(if (granted) 30 * 60 * 1000L else 3_000L)
        }
    }
    var minute by remember {
        mutableFloatStateOf(Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60f + it.get(Calendar.MINUTE) })
    }
    LaunchedEffect(Unit) {
        while (true) {
            val now = Calendar.getInstance()
            minute = now.get(Calendar.HOUR_OF_DAY) * 60f + now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f
            delay(1_000L)
        }
    }
    val daytime = minute >= 360f && minute < 1080f
    val progress = if (daytime) (minute - 360f) / 720f else
        (if (minute >= 1080f) minute - 1080f else minute + 360f) / 720f
    val motion = rememberInfiniteTransition(label = "shared-ambient-sky")
    val drift by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(52000, easing = LinearEasing)), label = "weather-drift")
    val stars = remember { List(90) { Triple(Random.nextFloat(), Random.nextFloat() * .72f, Random.nextFloat()) } }
    Canvas(modifier.fillMaxSize()) {
        val kind = weather?.kind
        val palette = when (kind) {
            "rain", "storm" -> if (daytime) listOf(Color(0xFF71818C), Color(0xFF465B70), Color(0xFF24384E)) else listOf(Color(0xFF111B2B), Color(0xFF172236), Color(0xFF091421))
            "cloudy", "fog" -> if (daytime) listOf(Color(0xFF9AA9AC), Color(0xFF647C89), Color(0xFF344B5D)) else listOf(Color(0xFF182234), Color(0xFF1B2235), Color(0xFF0C1826))
            "snow" -> if (daytime) listOf(Color(0xFFB8C9D0), Color(0xFF829BAA), Color(0xFF4A6579)) else listOf(Color(0xFF202B40), Color(0xFF1B263A), Color(0xFF101C2B))
            else -> if (daytime) listOf(Color(0xFFB9A17F), Color(0xFF668E9A), Color(0xFF294B60)) else listOf(Color(0xFF10182F), Color(0xFF17152D), Color(0xFF071522))
        }
        drawRect(Brush.verticalGradient(palette))
        val arc = kotlin.math.sin(Math.PI * progress.coerceIn(0f, 1f)).toFloat()
        val body = Offset(size.width * (if (daytime) .08f + .84f * progress else .92f - .84f * progress), size.height * (.43f - .29f * arc))
        val radius = size.minDimension * .105f
        if (daytime) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE6A6).copy(alpha=.38f), Color.Transparent), body, radius*3.2f), radius*3.2f, body)
            drawCircle(Color(0xFFFFE7B0).copy(alpha=.88f), radius, body)
        } else {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFB9D4FF).copy(alpha=.24f), Color.Transparent), body, radius*3f), radius*3f, body)
            drawCircle(Color(0xFFE1E9FF).copy(alpha=.88f), radius, body)
            drawCircle(Color(0xFF17203A), radius*.88f, Offset(body.x+radius*.42f,body.y-radius*.24f))
        }
        if (kind in setOf("cloudy","fog","rain","storm","snow")) {
            val alpha = if (kind == "storm" || kind == "rain") .42f else .30f
            for (i in 0 until 9) {
                val x = ((i*size.width*.17f + drift*size.width/360f) % (size.width*1.4f)) - size.width*.18f
                val y = size.height*(.12f+(i%4)*.06f)
                val r = size.width*(.18f+(i%3)*.035f)
                drawCircle(Brush.radialGradient(listOf(Color(0xFFDCE7F0).copy(alpha=alpha),Color(0xFF8799AA).copy(alpha=alpha*.4f),Color.Transparent),Offset(x,y),r),r,Offset(x,y))
            }
        }
        if (kind == "fog") for (i in 0 until 4) {
            val x=size.width*(.5f+kotlin.math.sin(Math.toRadians((drift*.11f+i*83f).toDouble())).toFloat()*.22f)
            val y=size.height*(.32f+i*.10f); val r=size.maxDimension*.48f
            drawCircle(Brush.radialGradient(listOf(Color(0xFFDCE6E8).copy(alpha=.15f),Color.Transparent),Offset(x,y),r),r,Offset(x,y))
        }
        if (kind == "rain" || kind == "storm") for (i in 0 until 105) {
            val speed=2.1f+(i%5)*.42f
            val x=(i*47f+drift*speed)%size.width
            val y=(i*31f+drift*(speed+.9f))%(size.height*.92f)
            val length=(9+i%9).dp.toPx()
            drawLine(Color(0xFFD6E8F7).copy(alpha=.25f),Offset(x,y),Offset(x-length*.34f,y+length),1.dp.toPx(),StrokeCap.Round)
        }
        if (kind == "snow") for (i in 0 until 72) {
            val x=(i*59f+kotlin.math.sin(Math.toRadians((drift*.8f+i*29f).toDouble())).toFloat()*24f+drift*.22f)%size.width
            val y=(i*37f+drift*(.55f+(i%6)*.16f))%(size.height*.88f)
            drawCircle(Color(0xFFEAF5FF).copy(alpha=.62f),(.8f+(i%4)*.42f).dp.toPx(),Offset(x,y))
        }
        if (!daytime) stars.forEachIndexed { i, star ->
            val twinkle=.42f+.34f*kotlin.math.sin(Math.toRadians((drift*(1.1f+star.third*1.8f)+i*47f).toDouble())).toFloat()
            val dim=when(kind) { "cloudy","fog"->.55f; "rain","storm","snow"->.16f; else->1f }
            drawCircle(Color(0xFFEAF2FF).copy(alpha=twinkle*dim),.7f+star.third*1.1f,Offset(size.width*star.first,size.height*star.second))
        }
        val phase=Math.toRadians((drift*.38f).toDouble())
        val glow=Offset(size.width*(.5f+kotlin.math.cos(phase).toFloat()*.22f),size.height*(.48f+kotlin.math.sin(phase).toFloat()*.20f))
        drawCircle(Brush.radialGradient(listOf((if(daytime) Color(0xFFFFC66A) else Color(0xFF9BBFFF)).copy(alpha=.16f),Color.Transparent),glow,size.maxDimension*.72f),size.maxDimension*.72f,glow)
    }
}

@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) {
        AllervueAmbientBackground(Modifier.matchParentSize())
        content()
    }
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
