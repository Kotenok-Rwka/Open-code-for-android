package com.pocketcode.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch27Test {

    private fun w(text: String, s: Double, e: Double) = PhraseWord(text, s, e)

    @Test
    fun highlightPicksLongestMeaningfulWord() {
        val words = listOf(w("и", 0.0, 0.2), w("вот", 0.2, 0.4), w("невероятный", 0.4, 1.2), w("кадр", 1.2, 1.5))
        assertEquals(2, SubHighlight.keyIndex(words))
        // одно слово — всегда 0
        assertEquals(0, SubHighlight.keyIndex(listOf(w("стоп", 0.0, 0.5))))
        // пустая фраза
        assertEquals(-1, SubHighlight.keyIndex(emptyList()))
        // все слова стоповые — первое
        assertEquals(0, SubHighlight.keyIndex(listOf(w("и", 0.0, 0.3), w("на", 0.3, 0.6))))
        // равная длина — произносимое дольше
        val eq = listOf(w("кот", 0.0, 0.2), w("дом", 0.2, 1.0))
        assertEquals(1, SubHighlight.keyIndex(eq))
        // пунктуация и кавычки не мешают
        val q = listOf(w("«Мужа»", 0.0, 0.4), w("сына,", 0.4, 0.9))
        assertEquals(0, SubHighlight.keyIndex(q)) // оба не-стоп, «Мужа» длиннее
    }

    @Test
    fun accentColorHexAndDefault() {
        assertEquals("&H00F0FF&", SubHighlight.accentColor(SubTweak()))
        assertEquals("&H50F0FF&", SubHighlight.accentColor(SubTweak(accent = "FFF050")))
        assertEquals("&H28C840&", SubHighlight.accentColor(SubTweak(accent = "#40C828")))
        // мусор — дефолтный жёлтый
        assertEquals("&H00F0FF&", SubHighlight.accentColor(SubTweak(accent = "zzz")))
    }

    @Test
    fun platformPresetsSafeZones() {
        assertEquals(5, PlatformPreset.ALL.size)
        assertEquals(9.0 / 16.0, PlatformPreset.byId("tiktok")!!.aspect, 1e-9)
        assertEquals("TikTok", PlatformPreset.byId("TikTok")!!.label)
        assertEquals(null, PlatformPreset.byId("мусор"))
        // субтитры выталкиваются из зоны кнопок
        val tt = PlatformPreset.byId("tiktok")!!
        assertEquals(0.18, PlatformPreset.safeY(tt, 0.90), 1e-9) // снизу подняли
        assertEquals(0.10, PlatformPreset.safeY(tt, 0.02), 1e-9) // сверху опустили
        assertEquals(0.5, PlatformPreset.safeY(tt, 0.5), 1e-9)   // середина не тронута
        assertEquals(0.82, PlatformPreset.safeY(tt, 0.82), 1e-9) // граница
    }

    @Test
    fun sceneCutDetect() {
        // яркая смена на кадре 24 (1 c), затем шум ниже порога
        val fp = DoubleArray(48) { 100.0 } + DoubleArray(48) { 130.0 }
        for (i in 25..47) fp[i] = 130.0 // смена начинается ровно на кадре 25
        val cuts = SceneCuts.detect(fp, 24.0, threshold = 26.0, minGap = 0.8)
        assertEquals(1, cuts.size)
        assertEquals(25.0 / 24.0, cuts[0], 1e-9)
        assertEquals(0, SceneCuts.detect(doubleArrayOf(1.0), 24.0).size)
        assertEquals(0, SceneCuts.detect(fp, 0.0).size)
        // мелкие колебания не режут
        val noise = DoubleArray(96) { 100.0 + (it % 3) * 2.0 }
        assertEquals(0, SceneCuts.detect(noise, 24.0).size)
    }

    @Test
    fun sceneCutRanges() {
        val r = SceneCuts.ranges(listOf(5.0, 12.0), 20.0)
        assertEquals(3, r.size)
        assertEquals(0.0, r[0].start, 1e-9); assertEquals(5.0, r[0].end, 1e-9)
        assertEquals(5.0, r[1].start, 1e-9); assertEquals(12.0, r[1].end, 1e-9)
        assertEquals(12.0, r[2].start, 1e-9); assertEquals(20.0, r[2].end, 1e-9)
        // короткий хвост приклеивается к предыдущему
        val r2 = SceneCuts.ranges(listOf(5.0, 5.8), 20.0, minLen = 1.2)
        assertEquals(2, r2.size)
        assertEquals(5.8, r2[0].end, 1e-9) // короткий [5,5.8] приклеен к [0,5]
        assertEquals(20.0, r2[1].end, 1e-9) // покрытие видео непрерывно
        // длинный кусок режется по maxLen
        val r3 = SceneCuts.ranges(emptyList(), 30.0, maxLen = 10.0)
        assertEquals(3, r3.size)
        assertEquals(0, SceneCuts.ranges(emptyList(), 0.0).size)
    }

    @Test
    fun subtitlesAssContainsHighlight() {
        val p = Phrase(0.0, 1.5, listOf(w("и", 0.0, 0.2), w("вот", 0.2, 0.4), w("невероятный", 0.4, 1.2), w("кадр", 1.2, 1.5)))
        val ass = Subtitles.ass(listOf(p), SubStyle.CLASSIC, 720, 1280, 0.0)
        assertTrue(ass.contains("\\c&H00F0FF&")) // ключевое слово «невероятный» жёлтое
        assertTrue(ass.contains("невероятный"))
        // с пользовательским акцентом цвет подставляется
        val ass2 = Subtitles.ass(listOf(p), SubStyle.CLASSIC, 720, 1280, 0.0, SubTweak(accent = "50E850"))
        assertTrue(ass2.contains("\\c&H50E850&"))
    }
}
