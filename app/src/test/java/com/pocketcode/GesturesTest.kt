package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    @Test
    fun horizontalSwipeRightReplies() {
        assertEquals("REPLY", Gestures.action(120f, 5f))
    }

    @Test
    fun horizontalSwipeLeftOpensDraft() {
        assertEquals("DRAFT", Gestures.action(-120f, -5f))
    }

    @Test
    fun smallOrVerticalSwipesDoNothing() {
        assertEquals("NONE", Gestures.action(50f, 0f))    // короче порога
        assertEquals("NONE", Gestures.action(120f, 300f)) // вертикальный
        assertEquals("NONE", Gestures.action(0f, 120f))
    }

    @Test
    fun doubleTapQuotes() {
        assertTrue(Gestures.doubleTap(2))
        assertFalse(Gestures.doubleTap(1))
        assertFalse(Gestures.doubleTap(3))
    }
}
