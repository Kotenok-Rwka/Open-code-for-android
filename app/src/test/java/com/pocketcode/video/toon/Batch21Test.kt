package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch21Test {
    private fun look() = ToonLook(0, 0, 0, HairStyle.SHORT, false, false, 1f)

    // ToonCast
    @Test
    fun castResolveReusesLibraryAndAutofills() {
        val lib = mapOf("кот" to ToonCharacter("Кот", look(), "муж", "en", true))
        val cast = ToonCast.resolve(listOf("Кот", "Пёс"), lib) { ToonLook(1, 1, 1, HairStyle.CAP, true, false, 1f) }
        assertEquals("en", cast["Кот"]!!.lang)               // из библиотеки
        assertEquals(true, cast["Кот"]!!.custom)
        assertEquals("муж", cast["Пёс"]!!.voice)             // новый — автолук с голосом по порядку
        assertEquals("жен", ToonCast.resolve(listOf("Пёс", "Ёж"), emptyMap()) { look() }["Ёж"]!!.voice)
    }

    @Test
    fun castSaveMergesAndRecent() {
        var lib = ToonCast.save(emptyMap(), ToonCharacter("Кот", look(), custom = true))
        lib = ToonCast.save(lib, ToonCharacter("Кот", look(1)))   // автоген не затирает свой
        assertEquals(true, lib["кот"]!!.custom)
        lib = ToonCast.save(lib, ToonCharacter("Кот", look(2), custom = true)) // свой обновляет
        assertEquals(2, lib["кот"]!!.look.skin)
        lib = ToonCast.save(lib, ToonCharacter("Пёс", look()))
        val recent = ToonCast.recent(lib.values, 1)
        assertEquals("Кот", recent[0].name)
    }

    // ToonVoiceLang
    @Test
    fun voiceLangDetectAndAvailability() {
        assertEquals("en", ToonVoiceLang.detect("Hello world", ToonCharacter("A", look(), lang = "en")))
        assertEquals("ru", ToonVoiceLang.detect("Привет мир", ToonCharacter("A", look(), lang = "en")))
        assertEquals("de", ToonVoiceLang.detect("123", ToonCharacter("A", look(), lang = "de")))  // без букв — голос персонажа
        assertEquals("ru", ToonVoiceLang.detect("123", null))
        assertTrue(ToonVoiceLang.available("en", listOf("en_US", "ru_RU")))
        assertTrue(ToonVoiceLang.available("de", listOf("de-DE")))
        assertTrue(!ToonVoiceLang.available("fr", listOf("en_US")))
        assertEquals(java.util.Locale.forLanguageTag("en-US"), ToonVoiceLang.pickLocale("en", listOf("ru_RU", "en_US")))
        assertEquals(java.util.Locale("ru", "RU"), ToonVoiceLang.pickLocale("fr", listOf("ru_RU")))
        assertTrue(ToonVoiceLang.hint("fr").contains("fr"))
    }

    // ToonTranscribe
    @Test
    fun transcribeRussianAndEnglish() {
        assertEquals("пять котов", ToonTranscribe.transcribe("5 котов", "ru"))
        assertEquals("twenty five percent", ToonTranscribe.transcribe("25%", "en"))
        assertEquals("twelve thirty", ToonTranscribe.transcribe("12:30", "en"))
        assertEquals("minus three", ToonTranscribe.transcribe("-3", "en"))
        // словарь пользователя приоритетнее
        val ru = ToonTranscribe.transcribe("Гоша пришёл в 7", "ru", listOf("Гоша" to "Гоша-мяу"))
        assertTrue(ru.startsWith("Гоша-мяу"))
        // полный конвейер реплики
        val line = ToonTranscribe.line("Цена 100 $", ToonCharacter("Кот", look(), lang = "en"))
        assertTrue(line.contains("dollars") || line.contains("100"))
    }

    // PronounceEn
    @Test
    fun pronounceEnNumbers() {
        assertEquals("zero", PronounceEn.words(0))
        assertEquals("nineteen", PronounceEn.words(19))
        assertEquals("twenty one", PronounceEn.words(21))
        assertEquals("one hundred five", PronounceEn.words(105))
        assertEquals("two thousand three", PronounceEn.words(2003))
        assertEquals("minus seven", PronounceEn.words(-7))
    }
}
