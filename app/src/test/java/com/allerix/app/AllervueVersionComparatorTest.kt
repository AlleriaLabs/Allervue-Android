package com.allerix.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllervueVersionComparatorTest {
    @Test
    fun stableReleaseIsNewerThanInstalledPrerelease() {
        assertTrue(isNewerAllervueVersion("1.4.0", "1.4.0-beta01"))
    }

    @Test
    fun laterPrereleaseIsNewerThanEarlierPrerelease() {
        assertTrue(isNewerAllervueVersion("1.4.0-beta02", "1.4.0-beta01"))
    }

    @Test
    fun numericPrereleaseIdentifiersUseNumericOrdering() {
        assertTrue(isNewerAllervueVersion("1.4.0-beta.10", "1.4.0-beta.2"))
    }

    @Test
    fun higherCoreVersionOutranksPrereleaseOfLowerVersion() {
        assertTrue(isNewerAllervueVersion("1.5.0-alpha.1", "1.4.9"))
    }

    @Test
    fun olderOrEqualVersionsAreNotNewer() {
        assertFalse(isNewerAllervueVersion("1.3.9", "1.4.0"))
        assertFalse(isNewerAllervueVersion("1.4.0", "1.4.0"))
        assertFalse(isNewerAllervueVersion("1.4.0+build.2", "1.4.0"))
    }

    @Test
    fun optionalVPrefixIsAccepted() {
        assertTrue(isNewerAllervueVersion("v1.4.1", "1.4.0"))
    }
}
