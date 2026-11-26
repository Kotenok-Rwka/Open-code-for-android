package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch20Test {
    // ToonQuality
    @Test
    fun qualityPresets() {
        assertEquals(720, ToonQuality.TIKTOK.w)
        assertEquals(1280, ToonQuality.TIKTOK.h)
        assertEquals(30, ToonQuality.TIKTOK.fps)
        assertEquals(ToonQuality.TIKTOK, ToonQuality.byIdOrNull("tiktok"))
        assertEquals(ToonQuality.HD, ToonQuality.byIdOrNull("hd"))
        assertEquals(null, ToonQuality.byIdOrNull("нет"))
        assertTrue(ToonQuality.HD.bitrate > ToonQuality.FAST.bitrate)
    }

    // ToonCaption
    @Test
    fun captionHashtagsAndLimit() {
        val tags = ToonCaption.hashtags("Кот спас город", "city")
        assertEquals("#city", tags[0])
        assertTrue(tags.contains("#спас"))
        assertTrue(tags.size <= 6)
        val cap = ToonCaption.caption("Кот спас город.", "Кот прыгнул на крышу", "city")
        assertTrue(cap.startsWith("Кот прыгнул на крышу 👀"))
        assertTrue(cap.contains("#city"))
        val long = ToonCaption.caption("Т".repeat(3000), null, null, limit = 100)
        assertEquals(100, long.length)
        assertTrue(long.endsWith("…"))
    }

    // ToonSeries
    @Test
    fun seriesTitlesAndCliffhanger() {
        assertEquals("Часть 2: Кот спас город 😱", ToonSeries.title("Кот спас город.", 2))
        val cliff = ToonSeries.cliffhanger("Что дальше?", "Спасает", "Убегает")
        assertEquals("Выбор: Что дальше? | Спасает | Убегает", cliff)
        assertEquals("Часть 3: Месть пса 😱", ToonSeries.nextTitle("Часть 2: Кот спас город", "Месть пса"))
        assertEquals("Часть 2: Месть пса 😱", ToonSeries.nextTitle("без номера", "Месть пса"))
    }

    // ToonPlanner
    @Test
    fun plannerSlotsHookAndTotal() {
        val slots = ToonPlanner.plan(listOf("Кот" to "Смотри!", "Пёс" to "Не догонишь", "Кот" to "Уже бегу"))
        assertEquals(3, slots.size)
        assertEquals(1.2, slots[0].seconds, 0.001)
        assertEquals(2.2, slots[1].seconds, 0.001)
        assertEquals(1.2 + 2.2 * 2, ToonPlanner.totalSeconds(slots), 0.001)
        assertEquals("Кот прыгнул на крышу и…", ToonPlanner.hook("Кот прыгнул на крышу и  полетел", 22))
        assertTrue(ToonPlanner.plan(emptyList()).isEmpty())
    }
}
