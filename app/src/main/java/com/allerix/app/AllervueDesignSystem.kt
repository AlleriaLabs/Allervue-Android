package com.allerix.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Allervue's shared visual language. Keep screen-level styling tied to these
 * semantic tokens so later phases can redesign individual screens consistently.
 */
internal object AllervueDesign {
    // Brand
    val Cyan = Color(0xFF65D6D0)
    val CyanStrong = Color(0xFF42C8C1)
    val Violet = Color(0xFF9B8AFB)
    val Gold = Color(0xFFD9B77D)

    // Dark surfaces
    val DarkBackground = Color(0xFF090B10)
    val DarkSurface = Color(0xFF141923)
    val DarkSurfaceElevated = Color(0xFF1C2430)
    val DarkSurfaceActive = Color(0xFF26343C)
    val DarkText = Color(0xFFF4F5F7)
    val DarkMuted = Color(0xFF929BA9)
    val DarkDivider = Color(0xFF29313D)

    // Light surfaces
    val LightBackground = Color(0xFFF5F7F8)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceElevated = Color(0xFFEAF0F2)
    val LightText = Color(0xFF172027)
    val LightMuted = Color(0xFF66737D)
    val LightDivider = Color(0xFFDCE4E7)

    val Danger = Color(0xFFE56F78)
    val Success = Color(0xFF55B98A)
}

@Composable
internal fun AllervueTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = context.getSharedPreferences("allervue_appearance", android.content.Context.MODE_PRIVATE)
        .getString("theme_mode", "SYSTEM") ?: "SYSTEM"
    val dark = when (mode) {
        "DARK" -> true
        "LIGHT" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) {
        darkColorScheme(
            primary = AllervueDesign.Cyan,
            secondary = AllervueDesign.Violet,
            tertiary = AllervueDesign.Gold,
            background = AllervueDesign.DarkBackground,
            surface = AllervueDesign.DarkSurface,
            surfaceVariant = AllervueDesign.DarkSurfaceElevated,
            onPrimary = Color(0xFF071014),
            onBackground = AllervueDesign.DarkText,
            onSurface = AllervueDesign.DarkText,
            onSurfaceVariant = AllervueDesign.DarkMuted,
            error = AllervueDesign.Danger
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF168F8A),
            secondary = Color(0xFF7061D8),
            tertiary = Color(0xFF9A7436),
            background = AllervueDesign.LightBackground,
            surface = AllervueDesign.LightSurface,
            surfaceVariant = AllervueDesign.LightSurfaceElevated,
            onPrimary = Color.White,
            onBackground = AllervueDesign.LightText,
            onSurface = AllervueDesign.LightText,
            onSurfaceVariant = AllervueDesign.LightMuted,
            error = AllervueDesign.Danger
        )
    }
    MaterialTheme(
        colorScheme = colors,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(26.dp)
        ),
        typography = Typography(),
        content = content
    )
}
