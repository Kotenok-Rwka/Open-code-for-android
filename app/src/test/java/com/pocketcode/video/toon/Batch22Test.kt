package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch22Test {
    // ToonEnginePool
    @Test
    fun poolAssignsAllFourEngines() {
        val m = ToonEnginePool.assign(listOf("Аня", "Боря", "Вера", "Гоша"))
        assertEquals(ToonEnginePool.SHERPA.toSet(), m.values.toSet())
        assertEquals(4, m.size)
        // пожелание уважается
        val w = ToonEnginePool.assign(listOf("Аня", "Боря"), mapOf("Аня" to "ruslan"))
        assertEquals("ruslan", w["Аня"])
        // больше персонажей, чем движков — все получают голос
        val many = ToonEnginePool.assign(listOf("a", "b", "c", "d", "e", "f"))
        assertEquals(6, many.size)
        assertTrue(many.values.all { it in ToonEnginePool.SHERPA })
        assertEquals("irina|dmitri", ToonEnginePool.enginesCsv(mapOf("a" to "irina", "b" to "dmitri")))
        assertEquals(0.85f, ToonEnginePool.pitchFor("ruslan"), 0.001f)
    }

    // SayText
    @Test
    fun sayTextStressAndDict() {
        val s = SayText.say("5 замков у Мши", "ru",
            stressDict = mapOf("мши" to "м+ши"), dict = listOf("Мши" to "Мши"))
        assertTrue(s.startsWith("пять"))
        assertEquals("м+ши", SayText.forPiper("мши", mapOf("мши" to "м+ши")))
        assertEquals("М+ши", SayText.forPiper("Мши", mapOf("мши" to "м+ши")))
        assertTrue(SayText.needsStressMark("ёлка"))
        assertTrue(!SayText.needsStressMark("hello"))
        val en = SayText.say("25%", "en")
        assertEquals("twenty five percent", en)
    }

    // ToonBackdrop
    @Test
    fun backdropSerializeParseRoundTrip() {
        val b = ToonBackdrop("мой_фон", ToonScene.SEA,
            skyTop = 0xFF112233.toInt(), sun = 0xFFFFD84D.toInt(),
            props = listOf("облако|0xFFFFFFFF|30|10|20"))
        val parsed = ToonBackdropLib.parse(ToonBackdropLib.serialize(listOf(b)))
        assertEquals(1, parsed.size)
        assertEquals(b.id, parsed[0].id)
        assertEquals(ToonScene.SEA, parsed[0].scene)
        assertEquals(0xFF112233.toInt(), parsed[0].skyTop)
        assertEquals(0xFFFFD84D.toInt(), parsed[0].sun)
        assertEquals(1, parsed[0].props.size)
        val (skyTop, skyBottom, sunDetail) = ToonBackdropLib.override(parsed[0])
        assertEquals(0xFF112233.toInt(), skyTop)
        assertEquals(null, skyBottom)
        assertEquals(0xFFFFD84D.toInt(), sunDetail!!.first)
    }
}
