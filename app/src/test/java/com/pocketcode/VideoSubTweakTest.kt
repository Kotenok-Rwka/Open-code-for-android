package com.pocketcode

import com.pocketcode.video.Phrase
import com.pocketcode.video.PhraseWord
import com.pocketcode.video.SubPos
import com.pocketcode.video.SubStyle
import com.pocketcode.video.SubTweak
import com.pocketcode.video.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSubTweakTest {
    private fun phrase(text: String): Phrase {
        var t = 0.0
        val ws = text.split(" ").map { PhraseWord(it, t, t + 0.3).also { _ -> t += 0.32 } }
        return Phrase(ws.first().start, ws.last().end, ws)
    }

    private fun styleLine(ass: String) = ass.lines().first { it.startsWith("Style: Default") }.removePrefix("Style: ").split(",")

    @Test
    fun defaultTweakChangesNothing() {
        val a = Subtitles.ass(listOf(phrase("Привет всем")), SubStyle.KARAOKE, 1080, 1920, 0.0)
        val b = Subtitles.ass(listOf(phrase("Привет всем")), SubStyle.KARAOKE, 1080, 1920, 0.0, SubTweak())
        assertEquals(a, b)
    }

    @Test
    fun accentColorIsConvertedToAssOrder() {
        // RRGGBB 40C8FF → &HBBGGRR = FFC840
        val k = Subtitles.ass(listOf(phrase("Привет всем")), SubStyle.KARAOKE, 1080, 1920, 0.0, SubTweak(accent = "40C8FF"))
        assertEquals("&H00FFC840", styleLine(k)[3])
        val pop = Subtitles.ass(listOf(phrase("раз два три")), SubStyle.POP, 1080, 1920, 0.0, SubTweak(accent = "#50E850"))
        assertTrue(pop.contains("\\c&H50E850&"))
        val bad = Subtitles.ass(listOf(phrase("раз два три")), SubStyle.POP, 1080, 1920, 0.0, SubTweak(accent = "zzz"))
        assertTrue(bad.contains("\\c&H00F0FF&"))
    }

    @Test
    fun scaleChangesFontSizeWithinLimits() {
        val base = styleLine(Subtitles.ass(listOf(phrase("Привет")), SubStyle.CLASSIC, 1080, 1920, 0.0))[2].toInt()
        val big = styleLine(Subtitles.ass(listOf(phrase("Привет")), SubStyle.CLASSIC, 1080, 1920, 0.0, SubTweak(scale = 1.5)))[2].toInt()
        val huge = styleLine(Subtitles.ass(listOf(phrase("Привет")), SubStyle.CLASSIC, 1080, 1920, 0.0, SubTweak(scale = 9.0)))[2].toInt()
        assertTrue(big > base)
        assertEquals((base * 1.6), huge.toDouble(), 2.0)
    }

    @Test
    fun positionChangesAlignmentExceptForBigWords() {
        fun align(style: SubStyle, pos: SubPos) = styleLine(Subtitles.ass(listOf(phrase("Привет")), style, 1080, 1920, 0.0, SubTweak(position = pos)))[18]
        assertEquals("5", align(SubStyle.CLASSIC, SubPos.MIDDLE))
        assertEquals("8", align(SubStyle.CLASSIC, SubPos.TOP))
        assertEquals("2", align(SubStyle.CLASSIC, SubPos.BOTTOM))
        assertEquals("5", align(SubStyle.BIG, SubPos.TOP))
    }

    @Test
    fun colorsAreValidHex() {
        assertTrue(SubTweak.COLORS.all { Regex("[0-9A-F]{6}").matches(it.first) })
        assertTrue(SubTweak().isDefault)
    }
}
