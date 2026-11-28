package com.allerix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveDeviceProfileTest {
    @Test
    fun compactPhoneUsesMobileContentMetrics() {
        val profile = createAllervueDeviceProfile(
            widthDp = 393,
            heightDp = 873,
            smallestWidthDp = 393,
            isLandscape = false,
            isTv = false
        )

        assertEquals(AllervueDeviceType.PHONE, profile.deviceType)
        assertEquals(AllervueWindowClass.COMPACT, profile.widthClass)
        assertEquals(600, profile.contentMaxWidthDp)
        assertEquals(20, profile.horizontalGutterDp)
        assertTrue(!profile.isLandscape)
    }

    @Test
    fun tabletWindowUsesExpandedGridAndTabletMetrics() {
        val profile = createAllervueDeviceProfile(
            widthDp = 800,
            heightDp = 1280,
            smallestWidthDp = 600,
            isLandscape = false,
            isTv = false
        )

        assertEquals(AllervueDeviceType.TABLET, profile.deviceType)
        assertEquals(AllervueWindowClass.MEDIUM, profile.widthClass)
        assertEquals(1200, profile.contentMaxWidthDp)
        assertEquals(28, profile.horizontalGutterDp)
    }

    @Test
    fun resizableNarrowWindowCanRemainPhoneOnTabletHardware() {
        val profile = createAllervueDeviceProfile(
            widthDp = 560,
            heightDp = 900,
            smallestWidthDp = 600,
            isLandscape = true,
            isTv = false
        )

        assertEquals(AllervueDeviceType.TABLET, profile.deviceType)
        assertEquals(AllervueWindowClass.COMPACT, profile.widthClass)
        assertTrue(profile.isLandscape)
    }

    @Test
    fun televisionOverridesWindowWidthAndUsesTenFootMetrics() {
        val profile = createAllervueDeviceProfile(
            widthDp = 1920,
            heightDp = 1080,
            smallestWidthDp = 0,
            isLandscape = true,
            isTv = true
        )

        assertEquals(AllervueDeviceType.TV, profile.deviceType)
        assertEquals(1600, profile.contentMaxWidthDp)
        assertEquals(64, profile.horizontalGutterDp)
        assertTrue(profile.isLargeScreen)
    }

    @Test
    fun invalidDimensionsAreClampedToSafePositiveValues() {
        val profile = createAllervueDeviceProfile(
            widthDp = 0,
            heightDp = -20,
            smallestWidthDp = 0,
            isLandscape = false,
            isTv = false
        )

        assertEquals(1, profile.screenWidthDp)
        assertEquals(1, profile.screenHeightDp)
        assertEquals(AllervueDeviceType.PHONE, profile.deviceType)
    }
}
