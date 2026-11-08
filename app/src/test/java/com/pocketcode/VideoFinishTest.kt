package com.pocketcode

import com.pocketcode.video.Finish
import com.pocketcode.video.FinishOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFinishTest {
    private fun cmd(o: FinishOptions, audio: Boolean = true, ass: String? = "video/.work/title.ass") =
        Finish.command("video/a.mp4", "video/a_fx.mp4", 1080, 1920, 10.0, audio, 30.0, o, ass, "/fonts") { "'$it'" }

    @Test
    fun speedIsClampedAndRounded() {
        assertEquals(1.0, Finish.clampSpeed(0.5), 0.0001)
        assertEquals(2.0, Finish.clampSpeed(5.0), 0.0001)
        assertEquals(1.25, Finish.clampSpeed(1.26), 0.0001)
        assertEquals(8.0, Finish.outDuration(10.0, FinishOptions(speed = 1.25)), 0.0001)
        assertEquals(10.0, Finish.outDuration(10.0, FinishOptions(title = "x")), 0.0001)
    }

    @Test
    fun emptyOptionsAreDetected() {
        assertTrue(FinishOptions().isEmpty)
        assertTrue(FinishOptions(title = "   ").isEmpty)
        assertFalse(FinishOptions(progressBar = true).isEmpty)
        assertFalse(FinishOptions(speed = 1.1).isEmpty)
    }

    @Test
    fun titleIsSafeForAss() {
        assertEquals("a (b) c/d e", Finish.cleanTitle(" a {b}\n c\\d\r\n e "))
        assertEquals(120, Finish.cleanTitle("я".repeat(300)).length)
        val ass = Finish.titleAss("Это {тест}", 1080, 1920, 2.0)
        val line = ass.lines().first { it.startsWith("Dialogue:") }
        assertTrue(line.endsWith("Это (тест)"))
        assertTrue(line.contains("0:00:00.00,0:00:02.00"))
    }

    @Test
    fun longTitlesGetSmallerFont() {
        val short = Finish.titleFontSize("Привет", 1080, 1920)
        val long = Finish.titleFontSize("а".repeat(90), 1080, 1920)
        assertTrue(long < short)
        assertTrue(Finish.titleFontSize("а", 100, 100) >= 14)
    }

    @Test
    fun commandHasOnlyRequestedSteps() {
        val plain = cmd(FinishOptions(progressBar = false, speed = 1.0, title = ""), ass = null)
        assertFalse(plain.contains("overlay"))
        assertFalse(plain.contains("ass="))
        assertFalse(plain.contains("atempo"))
        assertFalse(plain.contains("lavfi"))

        val all = cmd(FinishOptions(title = "Хук", progressBar = true, speed = 1.5))
        assertTrue(all.contains("setpts=PTS/1.500"))
        assertTrue(all.contains("atempo=1.500"))
        assertTrue(all.contains("overlay=x='-w+w*t/6.667'"))
        assertTrue(all.contains("ass=filename=video/.work/title.ass:fontsdir=/fonts"))
        assertTrue(all.contains("-f lavfi -i color=c=0xFFD400:s=1080x"))
    }

    @Test
    fun noAudioStreamMeansNoAudioFilters() {
        val c = cmd(FinishOptions(speed = 2.0), audio = false)
        assertFalse(c.contains("atempo"))
        assertFalse(c.contains("-c:a"))
    }

    @Test
    fun oddSizesAreRoundedToEven() {
        val c = Finish.command("a.mp4", "b.mp4", 1081, 1921, 5.0, true, 30.0, FinishOptions(progressBar = true), null, "/f") { it }
        assertTrue(c.contains("s=1080x"))
    }
}
