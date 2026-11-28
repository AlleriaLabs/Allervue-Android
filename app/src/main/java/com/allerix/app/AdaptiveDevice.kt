package com.allerix.app

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Shared device and window information for Allervue's adaptive UI.
 *
 * Device type describes the platform; window classes describe the space
 * currently available. Keeping these separate supports split-screen,
 * resizable windows and foldable devices without misclassifying them.
 */
enum class AllervueDeviceType {
    PHONE,
    TABLET,
    TV
}

enum class AllervueWindowClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

@Immutable
data class AllervueDeviceProfile(
    val deviceType: AllervueDeviceType,
    val widthClass: AllervueWindowClass,
    val heightClass: AllervueWindowClass,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestScreenWidthDp: Int,
    val isLandscape: Boolean,
    val contentMaxWidthDp: Int,
    val horizontalGutterDp: Int
) {
    val isPhone: Boolean get() = deviceType == AllervueDeviceType.PHONE
    val isTablet: Boolean get() = deviceType == AllervueDeviceType.TABLET
    val isTv: Boolean get() = deviceType == AllervueDeviceType.TV
    val isLargeScreen: Boolean get() = widthClass != AllervueWindowClass.COMPACT || isTv
}

val LocalAllervueDeviceProfile = staticCompositionLocalOf {
    AllervueDeviceProfile(
        deviceType = AllervueDeviceType.PHONE,
        widthClass = AllervueWindowClass.COMPACT,
        heightClass = AllervueWindowClass.MEDIUM,
        screenWidthDp = 0,
        screenHeightDp = 0,
        smallestScreenWidthDp = 0,
        isLandscape = false,
        contentMaxWidthDp = 600,
        horizontalGutterDp = 20
    )
}

/**
 * Pure classifier kept separate from Android configuration access so the
 * phone/tablet/TV breakpoints can be regression-tested on the CI JVM.
 */
internal fun createAllervueDeviceProfile(
    widthDp: Int,
    heightDp: Int,
    smallestWidthDp: Int,
    isLandscape: Boolean,
    isTv: Boolean
): AllervueDeviceProfile {
    val width = widthDp.coerceAtLeast(1)
    val height = heightDp.coerceAtLeast(1)
    val widthClass = when {
        width < 600 -> AllervueWindowClass.COMPACT
        width < 840 -> AllervueWindowClass.MEDIUM
        else -> AllervueWindowClass.EXPANDED
    }
    val heightClass = when {
        height < 480 -> AllervueWindowClass.COMPACT
        height < 900 -> AllervueWindowClass.MEDIUM
        else -> AllervueWindowClass.EXPANDED
    }
    val deviceType = when {
        isTv -> AllervueDeviceType.TV
        smallestWidthDp >= 600 || width >= 600 -> AllervueDeviceType.TABLET
        else -> AllervueDeviceType.PHONE
    }
    val maxContentWidth = when {
        isTv -> 1600
        widthClass == AllervueWindowClass.EXPANDED -> 1440
        widthClass == AllervueWindowClass.MEDIUM -> 1200
        else -> 600
    }
    val gutter = when {
        isTv -> 64
        widthClass == AllervueWindowClass.EXPANDED -> 40
        widthClass == AllervueWindowClass.MEDIUM -> 28
        else -> 20
    }
    return AllervueDeviceProfile(
        deviceType = deviceType,
        widthClass = widthClass,
        heightClass = heightClass,
        screenWidthDp = width,
        screenHeightDp = height,
        smallestScreenWidthDp = smallestWidthDp,
        isLandscape = isLandscape,
        contentMaxWidthDp = maxContentWidth,
        horizontalGutterDp = gutter
    )
}

/**
 * Re-evaluates automatically when Android reports a configuration/window
 * size change. TV mode is detected from the platform UI mode, while tablet
 * classification also considers the current window and smallest width.
 */
@Composable
fun rememberAllervueDeviceProfile(): AllervueDeviceProfile {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val isTv = (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
    return createAllervueDeviceProfile(
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
        smallestWidthDp = configuration.smallestScreenWidthDp,
        isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
        isTv = isTv
    )
}

/** Installs the device profile once at the application UI root. */
@Composable
fun AllervueAdaptiveRoot(content: @Composable () -> Unit) {
    val profile = rememberAllervueDeviceProfile()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAllervueDeviceProfile provides profile,
        content = content
    )
}

/**
 * Reusable centered content constraint for pages and sections. Phase 2/3
 * layouts can use this instead of hard-coded screen-width calculations.
 */
fun Modifier.allervueContentWidth(profile: AllervueDeviceProfile): Modifier =
    fillMaxWidth().widthIn(max = profile.contentMaxWidthDp.dp)

/** Standard adaptive horizontal page gutter in dp. */
fun AllervueDeviceProfile.horizontalGutter() = horizontalGutterDp.dp
