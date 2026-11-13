// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokePlanTest {
    @Test fun planStartsAtOriginEndsAtTargetAndIsMonotonic() {
        val p = StrokePlan.of(100, 1000, 100, 200, 300)
        assertEquals(p.xs.size, p.ys.size)
        assertEquals(p.xs.size, p.times.size)
        assertEquals(100, p.xs.first()); assertEquals(1000, p.ys.first())
        assertEquals(100, p.xs.last()); assertEquals(200, p.ys.last())
        assertEquals(0L, p.times.first())
        assertEquals(300L, p.times.last())
        for (i in 1 until p.times.size) assertTrue(p.times[i] >= p.times[i - 1])
    }

    @Test fun planUsesAtLeastTwoPointsAndAtMostLimit() {
        assertTrue(StrokePlan.of(0, 0, 10, 10, 1).xs.size >= 3)
        assertTrue(StrokePlan.of(0, 0, 10, 10, 1_000_000).xs.size <= 61)
    }

    @Test fun durationIsClamped() {
        assertEquals(StrokePlan.MIN_MS, StrokePlan.clampDuration(-5))
        assertEquals(StrokePlan.MAX_MS, StrokePlan.clampDuration(99_999))
        assertEquals(400L, StrokePlan.clampDuration(400))
        assertEquals(StrokePlan.MAX_MS, StrokePlan.of(0, 0, 1, 1, 99_999).times.last())
    }

    @Test fun diagonalPointsAreEvenlySpread() {
        val p = StrokePlan.of(0, 0, 160, 320, 160)
        val steps = p.xs.size - 1
        assertEquals(160, p.xs[steps]); assertEquals(320, p.ys[steps])
        assertEquals(p.xs[steps / 2] * 2, p.ys[steps / 2])
    }
}
