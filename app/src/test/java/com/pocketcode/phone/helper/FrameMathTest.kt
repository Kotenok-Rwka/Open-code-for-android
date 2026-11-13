// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameMathTest {
    private fun solid(rgb: Int): (Int, Int) -> Int = { _, _ -> 0xff000000.toInt() or rgb }

    @Test fun lumaGridHasFixedSizeAndUniformValues() {
        val grid = FrameMath.lumaGrid(240, 480, solid(0x808080))
        assertEquals(FrameMath.GRID_W * FrameMath.GRID_H, grid.size)
        assertTrue(grid.all { it == 128 })
    }

    @Test fun lumaGridTooSmallFrameGivesZeros() {
        assertTrue(FrameMath.lumaGrid(5, 5, solid(0xffffff)).all { it == 0 })
    }

    @Test fun differsNeedsEnoughCellsAndIgnoresSmallChanges() {
        val a = IntArray(FrameMath.GRID_W * FrameMath.GRID_H) { 100 }
        val one = a.copyOf().also { it[0] = 200 }
        assertFalse(FrameMath.differs(a, one))
        val many = a.copyOf().also { for (i in 0 until 5) it[i] = 200 }
        assertTrue(FrameMath.differs(a, many))
        assertFalse(FrameMath.differs(a, a.copyOf().also { it[0] = 110 }))
        assertFalse(FrameMath.differs(null, a))
        assertFalse(FrameMath.differs(a, IntArray(3)))
    }

    @Test fun pageChangedNeedsQuarterOfCells() {
        val n = FrameMath.GRID_W * FrameMath.GRID_H
        val a = IntArray(n) { 100 }
        val partial = a.copyOf().also { for (i in 0 until n / 10) it[i] = 220 }
        val whole = a.copyOf().also { for (i in 0 until n / 2) it[i] = 220 }
        assertFalse(FrameMath.pageChanged(a, partial))
        assertTrue(FrameMath.pageChanged(a, whole))
    }

    @Test fun isBlackDetectsBlackAndNotNearlyBlack() {
        assertTrue(FrameMath.isBlack(100, 100, solid(0x000000)))
        assertFalse(FrameMath.isBlack(100, 100, solid(0x101010)))
        assertFalse(FrameMath.isBlack(1, 100, solid(0x000000)))
    }
}
