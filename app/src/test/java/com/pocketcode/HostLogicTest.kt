package com.pocketcode

import com.pocketcode.host.HostApp
import com.pocketcode.host.HostLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLogicTest {
    private fun app(
        background: Boolean = true, useWindow: Boolean = false, start: Int = 8 * 60, end: Int = 23 * 60, enabled: Boolean = true,
    ) = HostApp("1", "Бот", "bot", "python3 bot.py", background, useWindow, start, end, true, enabled)

    @Test
    fun windowInsideTheDay() {
        assertTrue(HostLogic.inWindow(9 * 60, 8 * 60, 23 * 60))
        assertTrue(HostLogic.inWindow(8 * 60, 8 * 60, 23 * 60))
        assertFalse(HostLogic.inWindow(23 * 60, 8 * 60, 23 * 60))
        assertFalse(HostLogic.inWindow(3 * 60, 8 * 60, 23 * 60))
    }

    @Test
    fun windowAcrossMidnight() {
        assertTrue(HostLogic.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(HostLogic.inWindow(2 * 60, 22 * 60, 6 * 60))
        assertFalse(HostLogic.inWindow(6 * 60, 22 * 60, 6 * 60))
        assertFalse(HostLogic.inWindow(12 * 60, 22 * 60, 6 * 60))
    }

    @Test
    fun equalStartAndEndMeansWholeDay() {
        assertTrue(HostLogic.inWindow(0, 600, 600))
        assertTrue(HostLogic.inWindow(900, 600, 600))
    }

    @Test
    fun wantedFollowsScheduleAndMode() {
        assertTrue(HostLogic.wanted(app(), 12 * 60, appOpen = false, manual = false))
        assertFalse(HostLogic.wanted(app(enabled = false), 12 * 60, appOpen = true, manual = false))
        assertTrue(HostLogic.wanted(app(enabled = false), 12 * 60, appOpen = true, manual = true))
        // окно времени
        assertTrue(HostLogic.wanted(app(useWindow = true), 10 * 60, appOpen = false, manual = false))
        assertFalse(HostLogic.wanted(app(useWindow = true), 3 * 60, appOpen = false, manual = false))
        // ручной запуск не зависит от окна
        assertTrue(HostLogic.wanted(app(useWindow = true), 3 * 60, appOpen = false, manual = true))
        // «только пока открыто»
        assertFalse(HostLogic.wanted(app(background = false), 12 * 60, appOpen = false, manual = false))
        assertFalse(HostLogic.wanted(app(background = false), 12 * 60, appOpen = false, manual = true))
        assertTrue(HostLogic.wanted(app(background = false), 12 * 60, appOpen = true, manual = false))
    }

    @Test
    fun restartDelayGrowsAndIsCapped() {
        assertEquals(0, HostLogic.restartDelaySec(0))
        assertEquals(5, HostLogic.restartDelaySec(1))
        assertEquals(10, HostLogic.restartDelaySec(2))
        assertEquals(20, HostLogic.restartDelaySec(3))
        assertEquals(160, HostLogic.restartDelaySec(6))
        assertEquals(300, HostLogic.restartDelaySec(7))
        assertEquals(300, HostLogic.restartDelaySec(50))
    }

    @Test
    fun timeParsingAndFormatting() {
        assertEquals(22 * 60 + 30, HostLogic.parseTime("22:30"))
        assertEquals(7 * 60 + 5, HostLogic.parseTime(" 7:05 "))
        assertEquals(7 * 60 + 5, HostLogic.parseTime("7.05"))
        assertNull(HostLogic.parseTime("24:00"))
        assertNull(HostLogic.parseTime("12:60"))
        assertNull(HostLogic.parseTime("abc"))
        assertEquals("07:05", HostLogic.fmtTime(7 * 60 + 5))
        assertEquals("23:59", HostLogic.fmtTime(99999))
    }

    @Test
    fun durationFormatting() {
        assertEquals("45s", HostLogic.fmtDuration(45_000))
        assertEquals("5m", HostLogic.fmtDuration(5 * 60_000))
        assertEquals("2h 3m", HostLogic.fmtDuration((2 * 3600 + 3 * 60) * 1000L))
        assertEquals("1d 4h", HostLogic.fmtDuration((86400 + 4 * 3600) * 1000L))
    }

    @Test
    fun saveAndLoadKeepsEverything() {
        val list = listOf(app(useWindow = true, start = 22 * 60, end = 6 * 60), app(background = false, enabled = false).copy(id = "2"))
        assertEquals(list, HostLogic.decode(HostLogic.encode(list)))
        assertTrue(HostLogic.decode(null).isEmpty())
        assertTrue(HostLogic.decode("не json").isEmpty())
    }

    @Test
    fun guessCommandForPythonAndNode() {
        assertEquals("python3 bot.py", HostLogic.guessCommand(setOf("bot.py", "README.md"), false, false))
        assertEquals(
            "pip install -q --break-system-packages -r requirements.txt && python3 main.py",
            HostLogic.guessCommand(setOf("main.py", "requirements.txt"), false, true),
        )
        assertEquals("pip install -q -r requirements.txt && python3 main.py", HostLogic.guessCommand(setOf("main.py", "requirements.txt"), false, false))
        assertEquals("npm install && npm start", HostLogic.guessCommand(setOf("package.json", "index.js"), true, false))
        assertEquals("npm install && node index.js", HostLogic.guessCommand(setOf("package.json", "index.js"), false, false))
        assertNull(HostLogic.guessCommand(setOf("README.md"), false, false))
    }

    @Test
    fun npmStartDetection() {
        assertTrue(HostLogic.hasNpmStart("""{"name":"x","scripts":{"test":"jest","start":"node ."}}"""))
        assertFalse(HostLogic.hasNpmStart("""{"name":"x","scripts":{"test":"jest"}}"""))
        assertFalse(HostLogic.hasNpmStart("""{"name":"x"}"""))
    }
}
