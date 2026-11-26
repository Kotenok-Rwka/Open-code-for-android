package com.pocketcode

import com.pocketcode.video.toon.HairStyle
import com.pocketcode.video.toon.Mood
import com.pocketcode.video.toon.ToonEntry
import com.pocketcode.video.toon.ToonFormat
import com.pocketcode.video.toon.ToonHistory
import com.pocketcode.video.toon.ToonLooks
import com.pocketcode.video.toon.ToonPrompt
import com.pocketcode.video.toon.ToonScene
import com.pocketcode.video.toon.ToonScript
import com.pocketcode.video.toon.ToonTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToonStoryTest {
    @Test fun lookIsStablePerName() {
        assertEquals(ToonLooks.of("Маша", 0), ToonLooks.of("Маша", 0))
        val set = listOf("Маша", "Петя", "Админ", "Лена", "Игорь", "Дима").map { ToonLooks.of(it, 0).shirt to ToonLooks.of(it, 0).style }.toSet()
        assertTrue(set.size >= 3)
    }

    @Test fun femaleNamesGetLongHairOrBun() {
        for (n in listOf("Маша", "Лена", "Катя")) {
            assertTrue(ToonLooks.isFemale(n))
            val st = ToonLooks.of(n, 0).style
            assertTrue(st == HairStyle.LONG || st == HairStyle.BUN)
        }
        assertFalse(ToonLooks.isFemale("Никита"))
        assertFalse(ToonLooks.isFemale("Игорь"))
    }

    @Test fun oldAndKidHeroes() {
        val d = ToonLooks.of("Дедушка", 0)
        assertEquals(HairStyle.BALD, d.style)
        assertTrue(d.glasses && d.beard)
        assertTrue(ToonLooks.of("Мальчик", 0).scale < 1f)
        assertFalse(ToonLooks.of("Девочка", 0).beard)
    }

    @Test fun moodFromText() {
        assertEquals(Mood.ASK, ToonLooks.mood("Ты кто?"))
        assertEquals(Mood.WOW, ToonLooks.mood("Беги!"))
        assertEquals(Mood.SAD, ToonLooks.mood("Ну ладно…"))
        assertEquals(Mood.CALM, ToonLooks.mood("Привет."))
    }

    @Test fun sceneChangesAlongTheStory() {
        val s = ToonScript.parse("Сцена: заправка\nМаша: Привет\nСцена: лагерь\nПетя: Пока")
        assertEquals(ToonScene.GAS, s.scene)
        assertEquals(ToonScene.GAS, s.lines[0].scene)
        assertEquals(ToonScene.TENT, s.lines[1].scene)
    }

    @Test fun historyKeepsNewestFirstAndCaps() {
        var l = emptyList<ToonEntry>()
        for (i in 1..40) l = ToonHistory.add(l, ToonEntry(i.toLong(), "t$i", "Маша: да", "video/$i.mp4"))
        assertEquals(ToonHistory.MAX, l.size)
        assertEquals(40L, l.first().id)
        val again = ToonHistory.add(l, l[5])
        assertEquals(l[5].id, again.first().id)
        assertEquals(ToonHistory.MAX, again.size)
    }

    @Test fun historyRoundTripAndBrokenJson() {
        val l = listOf(ToonEntry(1, "Мотель", "Маша: «да»\nВыбор: а | б | в", "video/a.mp4", 2, "s1"))
        assertEquals(l, ToonHistory.decode(ToonHistory.encode(l)))
        assertTrue(ToonHistory.decode("{не json").isEmpty())
        assertTrue(ToonHistory.decode(null).isEmpty())
    }

    @Test fun nextEpisodeCountsSameSeries() {
        val l = listOf(ToonEntry(1, "a", "x: y", "v", 1, "s"), ToonEntry(2, "a", "x: y", "v", 2, "s"), ToonEntry(3, "b", "x: y", "v", 5, "z"))
        assertEquals(3, ToonHistory.nextEpisode(l, "s"))
        assertEquals(1, ToonHistory.nextEpisode(l, "new"))
        assertEquals(1, ToonHistory.remove(l, 1).count { it.series == "s" })
    }

    @Test fun extractStripsFencesAndIntro() {
        val raw = "Вот сценарий:\n```\nНазвание: Мотель\nМаша: Привет\n```"
        val s = ToonPrompt.extract(raw)
        assertTrue(s.startsWith("Название: Мотель"))
        assertFalse(s.contains("```"))
        assertEquals(1, ToonScript.parse(s).lines.size)
    }

    @Test fun extractFailsOnEmpty() {
        var failed = false
        try { ToonPrompt.extract("   ") } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test fun seriesFormatDropsChoice() {
        val s = "Маша: Привет\nВыбор: Что? | а | б"
        val out = ToonPrompt.applyFormat(s, ToonFormat.SERIES)
        assertNull(ToonScript.parse(out).choice)
        assertEquals(2, ToonScript.parse(ToonPrompt.applyFormat(s, ToonFormat.CHOICE)).choice!!.options.size)
    }

    @Test fun longerSeriesNeedMoreLines() {
        assertTrue(ToonPrompt.linesFor(120) > ToonPrompt.linesFor(30))
        val short = ToonScript.parse("Маша: Привет")
        assertFalse(ToonPrompt.longEnough(short, 60))
        val text = (1..30).joinToString("\n") { "Маша: Это довольно длинная реплика номер $it про историю" }
        assertTrue(ToonPrompt.longEnough(ToonScript.parse(text), 60))
    }

    @Test fun promptMentionsLengthChoiceAndPrevious() {
        val a = ToonPrompt.system(90, ToonFormat.CHOICE, null, null, 1)
        assertTrue(a.contains("90"))
        assertTrue(a.contains("ровно два варианта"))
        val b = ToonPrompt.system(45, ToonFormat.SERIES, "Маша: старое", "Остаться", 3)
        assertTrue(b.contains("Маша: старое") && b.contains("Остаться") && b.contains("номер 3"))
        assertFalse(b.contains("ровно два варианта"))
    }

    @Test fun timelinePadsToMinimum() {
        val s = ToonScript.parse("Маша: Привет")
        val plan = ToonTimeline.build(s, listOf(null), listOf("Привет"), null, "", minTotal = 45.0)
        assertEquals(45.0, plan.total, 1e-9)
        assertTrue(plan.mix.size >= 45 * ToonTimeline.RATE)
    }

    @Test fun scriptPromptAsksForHookAndDialogue() {
        val p = ToonPrompt.system(45, ToonFormat.CHOICE, null, null, 1)
        assertTrue(p.contains("Крючок"))
        assertTrue(p.contains("70%"))
        assertTrue(p.contains("Выбор:"))
    }

    @Test fun topSpeakersKeepsMostTalkingHeroes() {
        val s = ToonScript.parse(
            "Аня: раз\nБоря: два\nАня: три\nАня: четыре\nВика: пять\nГриша: шесть\nБоря: семь",
        )
        assertEquals(listOf("Аня", "Боря", "Вика"), s.topSpeakers(3))
        assertEquals(s.speakers, s.topSpeakers(10))
    }
}
