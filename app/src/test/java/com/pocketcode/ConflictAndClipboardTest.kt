package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictAndClipboardTest {
    private fun r(base: String, mine: String, theirs: String) =
        com.pocketcode.sync.resolveConflict(base, mine, theirs)

    @Test
    fun same() {
        assertEquals("same", r("a", "b", "b").verdict)
        assertEquals("b", r("a", "b", "b").merged)
    }

    @Test
    fun mineOnly() {
        assertEquals("mine", r("a", "b", "a").verdict)
        assertEquals("b", r("a", "b", "a").merged)
    }

    @Test
    fun theirsOnly() {
        assertEquals("theirs", r("a", "a", "b").verdict)
        assertEquals("b", r("a", "a", "b").merged)
    }

    @Test
    fun bothMarksConflict() {
        val res = r("a", "mine-text", "theirs-text")
        assertEquals("both", res.verdict)
        assertTrue(res.merged.contains("<<<<<<< mine"))
        assertTrue(res.merged.contains("mine-text"))
        assertTrue(res.merged.contains("======="))
        assertTrue(res.merged.contains("theirs-text"))
        assertTrue(res.merged.contains(">>>>>>> theirs"))
    }

    @Test
    fun emptyStrings() {
        assertEquals("same", r("", "", "").verdict)
        assertEquals("theirs", r("", "", "x").verdict)
    }

    // ---------- буфер обмена ----------

    @Test
    fun clipboardRoundtrip() {
        val packet = ClipboardSync.buildPacket("привет", "Pixel", 7)
        assertEquals("привет", ClipboardSync.parsePacket(packet))
    }

    @Test
    fun clipboardRejectsOtherKind() {
        val packet = ClipboardSync.buildPacket("x", "Pixel", 1)
            .replace("\"clipboard\"", "\"chat\"")
        assertNull(ClipboardSync.parsePacket(packet))
    }

    @Test
    fun clipboardRejectsGarbage() {
        assertNull(ClipboardSync.parsePacket("не json"))
    }

    // ---------- статус устройств ----------

    @Test
    fun freshDeviceOnline() {
        val now = System.currentTimeMillis()
        assertTrue(deviceOnline(now - 30_000, now))
    }

    @Test
    fun staleDeviceOffline() {
        val now = System.currentTimeMillis()
        assertFalse(deviceOnline(now - 300_000, now))
    }

    @Test
    fun futureTimestampOffline() {
        val now = System.currentTimeMillis()
        assertFalse(deviceOnline(now + 60_000, now))
    }
}
