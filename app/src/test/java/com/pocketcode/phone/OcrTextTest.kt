// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextTest {
    @Test fun normalizeAndBack() {
        assertEquals(0, OcrText.normalize(0, 1080))
        assertEquals(999, OcrText.normalize(1079, 1080))
        assertEquals(999, OcrText.normalize(5000, 1080))
        assertEquals(0, OcrText.normalize(-5, 1080))
        assertEquals(0, OcrText.normalize(10, 1))
        assertEquals(540, OcrText.toPixel(500, 1080))
        assertEquals(1079, OcrText.toPixel(999, 1080))
    }

    @Test fun ocrIsNeededOnlyForSparseTrees() {
        assertTrue(OcrText.needsOcr(0))
        assertTrue(OcrText.needsOcr(2))
        assertFalse(OcrText.needsOcr(3))
        assertFalse(OcrText.needsOcr(40))
    }

    @Test fun formatListsLinesTopToBottomWithPixelCenters() {
        val lines = listOf(
            OcrLine("Bottom", listOf(400, 900, 600, 920)),
            OcrLine("Top", listOf(400, 100, 600, 120)),
        )
        val text = OcrText.format(lines, 1000, 2000)
        assertTrue(text.contains("Текст на экране"))
        assertTrue(text.indexOf("«Top»") in 0 until text.indexOf("«Bottom»"))
        // центр: x = (400+600)/2 = 500 → 500, y = 110 → 220
        assertTrue(text, text.contains("«Top» (500, 220)"))
    }

    @Test fun duplicatesAndBlankLinesAreDropped() {
        val a = OcrLine("OK", listOf(100, 100, 200, 130))
        val text = OcrText.format(listOf(a, a.copy(), OcrLine("  ", listOf(0, 0, 10, 10)), OcrLine("bad", listOf(1, 2))), 1000, 1000)
        assertEquals(1, Regex("«OK»").findAll(text).count())
        assertFalse(text.contains("bad"))
    }

    @Test fun noLinesGiveEmptyText() {
        assertEquals("", OcrText.format(emptyList(), 1000, 1000))
    }

    @Test fun longListsAreCut() {
        val lines = (0 until 100).map { OcrLine("w$it", listOf(100, it * 9, 200, it * 9 + 5)) }
        val text = OcrText.format(lines, 1000, 1000, limit = 10)
        assertTrue(text.contains("и ещё 90"))
    }
}
