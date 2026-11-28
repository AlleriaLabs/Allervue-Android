package com.allerix.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SubParserTest {
    @Test
    fun parsesSrtAndNormalizesCommaMilliseconds() {
        val cues = SubParser.parse(
            "1\n00:00:01,250 --> 00:00:02,500\nHello\nworld\n"
        )
        assertNotNull(cues)
        assertEquals(1_250L, cues!![0].startMs)
        assertEquals(2_500L, cues[0].endMs)
        assertEquals("Hello\nworld", cues[0].text)
    }

    @Test
    fun parsesWebVttWithCueSettings() {
        val cues = SubParser.parse(
            "WEBVTT\n\n00:00:03.000 --> 00:00:04.250 align:start position:20%\nCaption\n"
        )
        assertNotNull(cues)
        assertEquals(3_000L, cues!![0].startMs)
        assertEquals(4_250L, cues[0].endMs)
        assertEquals("Caption", cues[0].text)
    }

    @Test
    fun parsesAssDialogueAndRemovesFormattingTags() {
        val cues = SubParser.parse(
            "[Script Info]\n[V4+ Styles]\n[Events]\n" +
                "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
                "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,{\\i1}Hello\\Nworld{\\i0}\n"
        )
        assertNotNull(cues)
        assertEquals(1_000L, cues!![0].startMs)
        assertEquals(2_500L, cues[0].endMs)
        assertEquals("Hello\nworld", cues[0].text)
    }

    @Test
    fun parsesMicroDvdFrameTiming() {
        val cues = SubParser.parse("{1}{1}25.0\n{25}{50}Hello|there\n")
        assertNotNull(cues)
        assertEquals(1_000L, cues!![0].startMs)
        assertEquals(2_000L, cues[0].endMs)
        assertEquals("Hello\nthere", cues[0].text)
    }

    @Test
    fun rejectsUnsupportedAndInvalidTimedFiles() {
        assertNull(SubParser.parse("This is not a subtitle file"))
        assertNull(SubParser.parse("00:00:03,000 --> 00:00:02,000\nInvalid duration"))
    }

    @Test
    fun activeTextKeepsOverlappingCuesAndHonorsCueEnd() {
        val cues = listOf(
            SubCue(1_000, 3_000, "First"),
            SubCue(2_000, 4_000, "Second")
        )
        assertEquals("First\nSecond", SubParser.activeText(cues, 2_500))
        assertEquals("Second", SubParser.activeText(cues, 3_500))
        assertEquals("", SubParser.activeText(cues, 4_001))
    }
}
