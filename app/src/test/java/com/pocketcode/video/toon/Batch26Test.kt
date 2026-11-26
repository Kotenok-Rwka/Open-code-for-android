package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch26Test {

    @Test
    fun appearanceFromWordsAndColon() {
        val a = ToonAppearance.parse("волосы:рыжие, кофта:зелёная, очки, борода, рост:высокий")
        assertEquals(0xFFB5382B.toInt(), a.hairColor)
        assertEquals(0xFF3DB27A.toInt(), a.shirtColor)
        assertEquals(true, a.glasses)
        assertEquals(true, a.beard)
        assertEquals(1.12f, a.tall!!, 0.001f)
        // без двоеточий — те же цвета
        val b = ToonAppearance.parse("рыжие, тельняшка, кепка")
        assertEquals(0xFFB5382B.toInt(), b.hairColor)
        assertEquals(0xFF2B4A7A.toInt(), b.shirtColor)
        assertEquals(HairStyle.CAP, b.style)
        // рост числом
        assertEquals(0.9f, ToonAppearance.parse("рост:0.9").tall!!, 0.001f)
        // ребёнок
        assertEquals(0.75f, ToonAppearance.parse("рост:ребёнок").tall!!, 0.001f)
        // пусто и мусор — пустая внешность
        assertEquals(ToonAppearance(), ToonAppearance.parse(""))
        assertNull(ToonAppearance.parse("абракадабра").hairColor)
        assertFalse(ToonAppearance.parse("абракадабра").glasses == true)
    }

    @Test
    fun appearanceHexColors() {
        val a = ToonAppearance.parse("волосы:#A020F0, кофта:0x30A030")
        assertEquals(0xFFA020F0.toInt(), a.hairColor)
        assertEquals(0xFF30A030.toInt(), a.shirtColor)
    }

    @Test
    fun sceneFxTimeAndWeather() {
        val fx = ToonSceneFx.fromScript("Аня: привет\nВРЕМЯ: ночь\nБоря: пока\nПОГОДА: дождь\nПОГОДА: снег")
        assertEquals("ночь", fx.time)
        assertEquals("снег", fx.weather) // последняя погода wins
        assertEquals(0x66101830, ToonSceneFx.tintOf("ночь"))
        assertEquals(0, ToonSceneFx.tintOf("день"))
        assertEquals(0, ToonSceneFx.tintOf(""))
        // неизвестные значения не применяются
        val bad = ToonSceneFx.fromScript("ВРЕМЯ: обед\nПОГОДА: град")
        assertEquals("", bad.time); assertEquals("", bad.weather)
    }

    @Test
    fun appearanceIsDeterministic() {
        val a1 = ToonAppearance.parse("волосы:седые, очки")
        val a2 = ToonAppearance.parse("волосы:седые, очки")
        assertEquals(a1, a2)
    }

    @Test
    fun castAndMoodsFromScript() {
        val script = "КАСТ: Аня|ru|irina|0|волосы:рыжие, очки\n" +
            "Аня: привет\nЭМОЦИЯ Боря: грустит\nБоря: пока\nЭМОЦИЯ Аня: вау\nЭМОЦИЯ Аня: спрашивает"
        val cast = ToonSceneFx.castFromScript(script)
        assertEquals(1, cast.size)
        assertEquals("Аня", cast[0].name)
        assertEquals("irina", cast[0].voice)
        assertEquals(0xFFB5382B.toInt(), cast[0].look.hair)
        assertEquals(true, cast[0].look.glasses)
        assertEquals(0, ToonSceneFx.castFromScript("КАСТ:\nКАСТ:  абракадабра|||").size)
        // настроения: последняя директива wins, имя без регистра
        val moods = ToonSceneFx.moodsFromScript(script)
        assertEquals(Mood.ASK, moods["аня"])
        assertEquals(Mood.SAD, moods["боря"])
        assertEquals(Mood.SAD, ToonSceneFx.moodOf("плачет"))
        assertEquals(Mood.WOW, ToonSceneFx.moodOf("смеётся"))
        assertEquals(Mood.CALM, ToonSceneFx.moodOf("неизвестно"))
    }
}
