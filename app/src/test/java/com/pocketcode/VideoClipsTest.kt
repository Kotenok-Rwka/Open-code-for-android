package com.pocketcode

import com.pocketcode.video.Clips
import com.pocketcode.video.TimeRange
import com.pocketcode.video.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoClipsTest {
    @Test
    fun keepsAreClampedMergedAndFiltered() {
        val r = Clips.normalizeKeeps(listOf(TimeRange(50.0, 80.0), TimeRange(-5.0, 10.0), TimeRange(9.9, 20.0), TimeRange(95.0, 95.1), TimeRange(90.0, 200.0)), 100.0)
        assertEquals(listOf(TimeRange(0.0, 20.0), TimeRange(50.0, 80.0), TimeRange(90.0, 100.0)), r)
        assertTrue(Clips.normalizeKeeps(listOf(TimeRange(5.0, 3.0), TimeRange(200.0, 300.0)), 100.0).isEmpty())
    }

    @Test
    fun rangesUnderstandMinutesAndCommas() {
        val r = Clips.parseRanges("12,5-40, 1:05-1:30; 0:02:00 – 0:02:10,5 и мусор 7-3")
        assertEquals(listOf(TimeRange(12.5, 40.0), TimeRange(65.0, 90.0), TimeRange(120.0, 130.5)), r)
        assertTrue(Clips.parseRanges("ничего").isEmpty())
    }

    private fun clip(rel: String, audio: Boolean = true) = Clips.ClipInfo(rel, 1081, 1921, 5.0, audio)

    @Test
    fun concatCommandNormalizesEveryClip() {
        val c = Clips.concatCommand(listOf(clip("a.mp4"), clip("b.mp4", audio = false)), "out.mp4", VideoQuality.FAST) { "'$it'" }
        assertTrue(c.contains("-i 'a.mp4' -i 'b.mp4'"))
        assertTrue(c.contains("scale=1080:1920:force_original_aspect_ratio=decrease,pad=1080:1920"))
        assertTrue(c.contains("aevalsrc=0:s=44100:d=5.000"))
        assertTrue(c.contains("concat=n=2:v=1:a=1"))
        assertFalse(c.contains("[1:a]"))
        try { Clips.concatCommand(listOf(clip("a.mp4")), "o", VideoQuality.FAST) { it }; fail() } catch (e: IllegalArgumentException) { }
    }

    @Test
    fun estimateGrowsWithQualityAndUsesMeasuredSpeed() {
        val fast = Clips.estimate(60.0, VideoQuality.FAST, false, false, false, false, null)
        val high = Clips.estimate(60.0, VideoQuality.HIGH, false, false, false, false, null)
        assertTrue(high.seconds > fast.seconds)
        assertTrue(high.megabytes > fast.megabytes)
        val measured = Clips.estimate(60.0, VideoQuality.HIGH, false, false, true, true, 3.0)
        assertEquals(20, measured.seconds)
        assertTrue(Clips.estimate(0.0, VideoQuality.FAST, false, false, false, false, null).seconds >= 5)
        assertTrue(Clips.estimate(60.0, VideoQuality.BALANCED, true, true, false, false, null).megabytes < Clips.estimate(60.0, VideoQuality.BALANCED, true, false, false, false, null).megabytes)
    }

    @Test
    fun waitText() {
        assertEquals("около 45 с", Clips.fmtWait(45))
        assertEquals("около 3 мин", Clips.fmtWait(170))
    }
}
