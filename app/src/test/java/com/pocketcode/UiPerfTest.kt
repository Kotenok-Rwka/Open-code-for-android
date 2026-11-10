package com.pocketcode

import com.pocketcode.ui.isQuestionReply
import com.pocketcode.ui.revealStep
import com.pocketcode.ui.shouldJumpToItem
import com.pocketcode.ui.smoothStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPerfTest {
    @Test
    fun smoothStepIsTwoRevealSteps() {
        for (total in listOf(1, 2, 7, 80, 5000)) {
            var shown = 0
            while (shown < total) {
                assertEquals(revealStep(revealStep(shown, total), total), smoothStep(shown, total))
                shown = revealStep(shown, total)
            }
        }
    }

    @Test
    fun smoothStepMovesForwardAndNeverPassesTotal() {
        var shown = 0
        var ticks = 0
        while (shown < 5000) {
            val next = smoothStep(shown, 5000)
            assertTrue(next > shown)
            assertTrue(next <= 5000)
            shown = next
            ticks++
        }
        // Два шага за тик: догоняем не дольше, чем при одном шаге на каждый кадр
        var frames = 0
        var s = 0
        while (s < 5000) { s = revealStep(s, 5000); frames++ }
        assertTrue(ticks <= frames)
    }

    @Test
    fun smoothStepKeepsFinishedTextAsIs() {
        assertEquals(5, smoothStep(5, 5))
        assertEquals(5, smoothStep(9, 5))
        assertEquals(0, smoothStep(0, 0))
        assertEquals(1, smoothStep(0, 1))
    }

    @Test
    fun questionReplyDetection() {
        assertTrue(isQuestionReply("Запустить тесты?"))
        assertTrue(isQuestionReply("Продолжить?  \n"))
        assertFalse(isQuestionReply("Готово."))
        assertFalse(isQuestionReply(""))
        assertFalse(isQuestionReply("? уже не вопрос."))
        assertFalse(isQuestionReply("⏳ Думаю?"))
    }

    @Test
    fun jumpToItemOnlyWhenNotOnScreen() {
        assertTrue(shouldJumpToItem(emptyList(), 3))
        assertTrue(shouldJumpToItem(listOf(0, 1, 2), 3))
        assertFalse(shouldJumpToItem(listOf(1, 2, 3), 3))
        assertFalse(shouldJumpToItem(listOf(3), 3))
    }
}
