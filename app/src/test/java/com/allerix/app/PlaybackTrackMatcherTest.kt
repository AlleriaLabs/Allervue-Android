package com.allerix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTrackMatcherTest {
    @Test
    fun exactStreamIdWinsOverLanguageFallback() {
        val candidates = listOf(
            PlaybackTrackCandidate(0, 0, "11", "eng", "English"),
            PlaybackTrackCandidate(1, 1, "42", "jpn", "Japanese")
        )
        assertEquals(
            PlaybackTrackMatch(1, 1),
            PlaybackTrackMatcher.find(candidates, PlaybackTrackSelection(42, language = "en"))
        )
    }

    @Test
    fun languageFallbackPreservesMatchedTrackIndex() {
        val candidates = listOf(
            PlaybackTrackCandidate(0, 0, "1", "eng", "English Commentary"),
            PlaybackTrackCandidate(0, 1, "2", "jpn", "Japanese")
        )
        assertEquals(
            PlaybackTrackMatch(0, 1),
            PlaybackTrackMatcher.find(candidates, PlaybackTrackSelection(99, language = "ja"))
        )
    }

    @Test
    fun threeLetterLanguageCodesAreNormalized() {
        val candidates = listOf(PlaybackTrackCandidate(2, 3, null, "eng-US", "English"))
        assertEquals(
            PlaybackTrackMatch(2, 3),
            PlaybackTrackMatcher.find(candidates, PlaybackTrackSelection(99, language = "en"))
        )
    }

    @Test
    fun titleIsUsedWhenIdAndLanguageDoNotMatch() {
        val candidates = listOf(PlaybackTrackCandidate(1, 2, "7", "fra", "English Commentary"))
        assertEquals(
            PlaybackTrackMatch(1, 2),
            PlaybackTrackMatcher.find(candidates, PlaybackTrackSelection(99, title = "commentary"))
        )
    }

    @Test
    fun noMatchingTrackReturnsNull() {
        val candidates = listOf(PlaybackTrackCandidate(0, 0, "1", "fra", "French"))
        assertNull(
            PlaybackTrackMatcher.find(candidates, PlaybackTrackSelection(99, language = "ja", title = "Japanese"))
        )
    }

    @Test
    fun emptyLanguageDoesNotAccidentallyMatchUnknownTracks() {
        val candidates = listOf(PlaybackTrackCandidate(0, 0, null, null, null))
        assertNull(PlaybackTrackMatcher.find(candidates, PlaybackTrackSelection(99)))
    }
}
