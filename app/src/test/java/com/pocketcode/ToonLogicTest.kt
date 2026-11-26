package com.pocketcode

import com.pocketcode.video.toon.Lipsync
import com.pocketcode.video.toon.Pcm
import com.pocketcode.video.toon.Pronounce
import com.pocketcode.video.toon.ToonScene
import com.pocketcode.video.toon.ToonScript
import com.pocketcode.video.toon.ToonSubs
import com.pocketcode.video.toon.ToonTimeline
import com.pocketcode.video.toon.Wav
import com.pocketcode.video.toon.YuvConvert
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToonLogicTest {
    @Test fun scriptParsesLinesChoiceAndScene() {
        val s = ToonScript.parse(
            """
            # комментарий
            Название: Мотель
            Сцена: ночь
            Рассказчик: Всё началось ночью.
            Маша: Привет! || Прив+ет!
            Выбор: Что делать? | Ехать | Остаться
            """.trimIndent(),
        )
        assertEquals("Мотель", s.title)
        assertEquals(ToonScene.NIGHT, s.scene)
        assertEquals(2, s.lines.size)
        assertTrue(s.lines[0].narrator)
        assertEquals("Прив+ет!", s.lines[1].say)
        assertEquals(listOf("Маша"), s.speakers)
        assertEquals(2, s.choice!!.options.size)
        assertEquals(ToonScript.DEFAULT_CTA, s.cta)
    }

    @Test fun scriptWithoutLinesFails() {
        var failed = false
        try { ToonScript.parse("Название: пусто") } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun plainLineIsNarrator() {
        val s = ToonScript.parse("Просто текст без имени")
        assertTrue(s.lines[0].narrator)
    }

    @Test fun dictionaryApplies() {
        val d = Pronounce.parseDict("замок=з+амок\nбез знака\n=пусто")
        assertEquals(1, d.size)
        assertEquals("Это з+амок.", Pronounce.applyDict("Это замок.", d))
        assertEquals("замоки", Pronounce.applyDict("замоки", d))
    }

    @Test fun forVoicePrefersSayAndCleansMarkup() {
        assertEquals("как слышится", Pronounce.forVoice("x", "*как* слышится", emptyList(), true))
        assertEquals("привет", Pronounce.forVoice("привет", null, emptyList(), false))
    }

    @Test fun numbersBecomeWords() {
        val t = Pronounce.normalize("Нас 5 человек")
        assertFalse(t.any { it.isDigit() })
        assertTrue(t.contains("пять"))
        assertTrue(Pronounce.normalize("73%").contains("процент"))
        assertEquals("то есть так", Pronounce.normalize("т.е. так"))
    }

    @Test fun subsSplitIntoShortChunks() {
        val c = ToonSubs.chunks("Это очень длинная фраза про пирс и бочку, которая не влезает.")
        assertTrue(c.size >= 3)
        assertTrue(c.all { it.split(" ").size <= 5 })
        assertEquals("раз два три", ToonSubs.chunks("раз два три").joinToString(" "))
    }

    @Test fun subsTimingCoversWholeRange() {
        val t = ToonSubs.timed(listOf("раз два", "три"), 1.0, 3.0)
        assertEquals(1.0, t.first().start, 1e-9)
        assertEquals(3.0, t.last().end, 1e-9)
        assertEquals(t[0].end, t[1].start, 1e-9)
        assertTrue(ToonSubs.timed(emptyList(), 0.0, 1.0).isEmpty())
    }

    @Test fun wavRoundTrip() {
        val p = Pcm(16000, shortArrayOf(0, 1000, -1000, 32767, -32768))
        val q = Wav.parse(Wav.write(p))
        assertEquals(16000, q.rate)
        assertArrayEquals(p.samples, q.samples)
    }

    @Test fun wavRejectsGarbage() {
        var failed = false
        try { Wav.parse(ByteArray(100)) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun resampleChangesLength() {
        val p = Pcm(12000, ShortArray(1200) { 100 })
        assertEquals(2400, Wav.resample(p, 24000).samples.size)
        assertEquals(24000, Wav.resample(p, 24000).rate)
    }

    @Test fun trimCutsSilence() {
        val s = ShortArray(24000)
        for (i in 10000 until 11000) s[i] = 5000
        val t = Wav.trim(Pcm(24000, s))
        assertTrue(t.samples.size < 5000)
        assertTrue(t.samples.size >= 1000)
        assertEquals(0, Wav.trim(Pcm(24000, ShortArray(500))).samples.size)
    }

    @Test fun envelopeIsLoudWhereSoundIs() {
        val s = ShortArray(24000)
        for (i in 12000 until 24000) s[i] = (if (i % 2 == 0) 8000 else -8000).toShort()
        val e = Wav.envelope(Pcm(24000, s), 30, 30)
        assertEquals(0f, e[2], 0.001f)
        assertTrue(e[25] > 0.9f)
    }

    @Test fun mouthClosedInSilenceOpenOnSound() {
        val env = FloatArray(20) { if (it in 5..14) 0.9f else 0f }
        val m = Lipsync.track(env, "мама")
        assertEquals(0f, m.open[0], 0.001f)
        assertTrue(m.open[10] > 0.5f)
        assertEquals(0f, m.open[19], 0.2f)
        assertNull(Lipsync.vowelWidth('м'))
        assertNotNull(Lipsync.vowelWidth('у'))
    }

    @Test fun yuvSizeAndColors() {
        assertEquals(4 * 4 + 2 * 2 * 2, YuvConvert.size(4, 4))
        val white = IntArray(16) { 0xFFFFFFFF.toInt() }
        val out = ByteArray(YuvConvert.size(4, 4))
        YuvConvert.toI420(white, 4, 4, out)
        assertEquals(235, out[0].toInt() and 0xFF)
        assertEquals(128, out[16].toInt() and 0xFF)
        assertEquals(128, out[20].toInt() and 0xFF)
    }

    @Test fun timelineWithoutAudioUsesEstimate() {
        val s = ToonScript.parse("Маша: Привет\nПетя: Пока\nВыбор: Кто? | А | Б")
        val plan = ToonTimeline.build(s, listOf(null, null), listOf("Привет", "Пока"), null, "Кто?")
        assertEquals(2, plan.clips.size)
        assertTrue(plan.clips[1].start > plan.clips[0].end)
        assertTrue(plan.choiceStart >= plan.clips[1].end)
        assertTrue(plan.total > plan.choiceStart)
        assertTrue(plan.mix.size >= (plan.total * ToonTimeline.RATE).toInt())
    }

    @Test fun timelineWithoutChoiceHasNoChoiceTime() {
        val s = ToonScript.parse("Маша: Привет")
        val plan = ToonTimeline.build(s, listOf(null), listOf("Привет"), null, "")
        assertTrue(plan.choiceStart < 0)
    }

    @Test fun stressMarksStripped() {
        assertEquals("замок", com.pocketcode.video.toon.stripStressMarks("з+амок"))
        assertEquals("замок", com.pocketcode.video.toon.stripStressMarks("за\u0301мок"))
    }

    @Test fun normalizeEvendsVoiceVolume() {
        // Тихая и громкая реплики должны звучать одинаково громко
        val quiet = Wav.normalize(Pcm(24000, ShortArray(100) { 3000 }))
        val loud = Wav.normalize(Pcm(24000, ShortArray(100) { -3000 }))
        fun peak(p: Pcm) = p.samples.maxOf { if (it < 0) -it.toInt() else it.toInt() }
        assertTrue(peak(quiet) in 25_000..30_000)
        assertEquals(peak(quiet), peak(loud))
        assertEquals(0, peak(Wav.normalize(Pcm(24000, ShortArray(100)))))
    }

    @Test fun fadeRemovesClicksAtEdges() {
        val p = Pcm(24000, ShortArray(2400) { 10_000 })
        val f = Wav.fade(p, 10, 10)
        assertEquals(0, f.samples.first().toInt())
        assertEquals(0, f.samples.last().toInt())
        assertEquals(10_000, f.samples[1200].toInt())
    }

    @Test fun shortSeriesStretchesPausesInsteadOfFreezingLastFrame() {
        // Десять коротких реплик вместо заказанных 20 секунд: время уходит в паузы, финал не замирает
        val text = (1..10).joinToString("\n") { "Маша: Строка номер $it про кота и миску" }
        val s = ToonScript.parse(text)
        val plan = ToonTimeline.build(s, List(s.lines.size) { null }, s.lines.map { "x" }, null, "", minTotal = 20.0)
        assertEquals(20.0, plan.total, 1e-9)
        assertTrue(plan.clips[1].start - plan.clips[0].end > 0.3)
        assertTrue(plan.clips.last().end > plan.total - 3.0)
    }
}
