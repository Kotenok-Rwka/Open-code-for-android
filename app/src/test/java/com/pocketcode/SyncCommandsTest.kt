package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCommandsTest {

    @Test
    fun stopPacketRoundtrip() {
        val p = SyncCommands.buildStop("Pixel", 5, "*")
        assertEquals("stop", SyncCommands.kind(p))
        assertEquals("*", SyncCommands.data(p))
    }

    @Test
    fun stopPacketTargetSession() {
        val p = SyncCommands.buildStop("Pixel", 6, "sess-1")
        assertEquals("sess-1", SyncCommands.data(p))
    }

    @Test
    fun handoffPacket() {
        val p = SyncCommands.buildHandoff("Pixel", 7, "myapp", "исправь баг")
        assertEquals("handoff", SyncCommands.kind(p))
        assertTrue(SyncCommands.data(p)!!.startsWith("myapp|"))
        assertTrue(SyncCommands.data(p)!!.contains("исправь баг"))
    }

    @Test
    fun taskPacket() {
        val done = SyncCommands.buildTask("Pixel", 8, "купить молоко", true)
        val open = SyncCommands.buildTask("Pixel", 9, "написать тест")
        assertEquals("x купить молоко", SyncCommands.data(done))
        assertEquals("написать тест", SyncCommands.data(open))
    }

    @Test
    fun garbageIsNull() {
        assertNull(SyncCommands.kind("мусор"))
        assertNull(SyncCommands.data("{}"))
    }

    // ---------- прогресс ----------

    @Test
    fun progressEmpty() = assertEquals(0, progressPercent(emptyList()))

    @Test
    fun progressHalf() {
        val pct = progressPercent(listOf("[x] раз", "[x] два", "[ ] три", "[ ] четыре"))
        assertEquals(50, pct)
    }

    @Test
    fun progressAllDone() = assertEquals(100, progressPercent(listOf("[x] а", "x б")))

    @Test
    fun progressNone() = assertEquals(0, progressPercent(listOf("[ ] а", "[ ] б")))

    // ---------- оценка ----------

    @Test
    fun estimateShortTask() {
        val (steps, tokens) = estimateTask("поправь заголовок")
        assertEquals(1, steps)
        assertEquals(2300, tokens)
    }

    @Test
    fun estimateLongTask() {
        val (steps, tokens) = estimateTask("слово ".repeat(60))
        assertTrue(steps > 1)
        assertTrue(tokens > 3000)
    }

    // ---------- политика сети ----------

    @Test
    fun wifiOnlyBlocksMetered() {
        assertFalse(syncAllowedNow(wifiOnly = true, enabled = true, onMetered = true))
        assertTrue(syncAllowedNow(wifiOnly = true, enabled = true, onMetered = false))
        assertFalse(syncAllowedNow(wifiOnly = false, enabled = false, onMetered = false))
        assertTrue(syncAllowedNow(wifiOnly = false, enabled = true, onMetered = true))
    }
}
