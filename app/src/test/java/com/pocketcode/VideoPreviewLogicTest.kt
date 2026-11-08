package com.pocketcode

import com.pocketcode.video.SubStyle
import com.pocketcode.video.Transition
import com.pocketcode.video.popScale
import com.pocketcode.video.previewFontFraction
import com.pocketcode.video.previewWordAge
import com.pocketcode.video.previewWordIndex
import com.pocketcode.video.previewWords
import com.pocketcode.video.transitionFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreviewLogicTest {
    @Test
    fun everyTransitionStartsOnAAndEndsOnB() {
        for (t in Transition.values()) for (c in 0..4) {
            val a = transitionFrame(t, 0f, c)
            val b = transitionFrame(t, 1f, c)
            assertFalse("$t: в начале виден только A", a.showB)
            assertTrue("$t: в конце виден B", b.showB)
            assertEquals("$t: в конце B непрозрачен", 1f, b.bAlpha, 0.001f)
            assertEquals(0f, b.white, 0.001f)
            assertEquals(0f, b.bShiftX, 0.001f)
            assertTrue(b.circle < 0f || b.circle >= 0.99f)
        }
    }

    @Test
    fun cutAndZoomSwitchInstantly() {
        for (t in listOf(Transition.CUT, Transition.ZOOM)) {
            assertFalse(transitionFrame(t, 0.49f).showB)
            assertTrue(transitionFrame(t, 0.5f).showB)
        }
        assertTrue(transitionFrame(Transition.ZOOM, 0.9f).bScale > 1f)
        assertEquals(1f, transitionFrame(Transition.CUT, 0.9f).bScale, 0.001f)
    }

    @Test
    fun midpointOfEffects() {
        assertEquals(0.5f, transitionFrame(Transition.FADE, 0.5f).bAlpha, 0.01f)
        assertEquals(1f, transitionFrame(Transition.FLASH, 0.5f).white, 0.01f)
        assertEquals(0.5f, transitionFrame(Transition.SLIDE, 0.5f).bShiftX, 0.01f)
        assertEquals(1f, transitionFrame(Transition.BLUR, 0.5f).smear, 0.01f)
        assertEquals(0.5f, transitionFrame(Transition.CIRCLE, 0.5f).circle, 0.01f)
    }

    @Test
    fun mixShowsDifferentEffectsInTurn() {
        val shown = (0..4).map { transitionFrame(Transition.MIX, 0.5f, it) }.toSet()
        assertTrue(shown.size >= 4)
        assertEquals(transitionFrame(Transition.MIX, 0.5f, 0), transitionFrame(Transition.MIX, 0.5f, 5))
    }

    @Test
    fun wordIndexWalksAndLoops() {
        assertEquals(0, previewWordIndex(0.0, 4))
        assertEquals(1, previewWordIndex(0.6, 4))
        assertEquals(3, previewWordIndex(4 * 0.55 + 0.5, 4))
        assertEquals(0, previewWordIndex(4 * 0.55 + 1.0 + 0.1, 4))
        assertEquals(0, previewWordIndex(5.0, 0))
        assertEquals(0.05, previewWordAge(0.6, 4), 0.001)
    }

    @Test
    fun popScaleJumpsThenSettles() {
        assertTrue(popScale(0.0) < 1f)
        assertEquals(1.18f, popScale(0.09), 0.001f)
        assertEquals(1f, popScale(0.5f.toDouble()), 0.001f)
    }

    @Test
    fun everyStyleHasFontSizeAndSampleSplits() {
        for (s in SubStyle.values()) assertTrue(previewFontFraction(s) in 0.04..0.14)
        assertEquals(listOf("Это", "пример"), previewWords("  Это   пример "))
        assertEquals(listOf("Subtitles"), previewWords(" "))
    }
}
