package com.pocketcode.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Атрибуция Mika: строки с указанием автора переведены на все языки, а README и файл лицензии на месте. */
class PhoneCreditsTest {
    private val langs = listOf("en", "de", "es", "zh", "ar")
    private val keys = listOf(
        "Основано на проекте Mika",
        "Ассистент управления телефоном в PocketCode создан на основе проекта Mika. Автор Mika: wangzan101. Лицензия Apache-2.0, код изменён для PocketCode.",
        "Mika на GitHub",
        "Автор: wangzan101",
    )

    private fun load(lang: String): Map<String, String> =
        (Json.parseToJsonElement(File("src/main/assets/i18n/$lang.json").readText()) as JsonObject)
            .mapValues { (it.value as JsonPrimitive).content }

    @Test fun creditStringsAreTranslatedAndKeepTheAuthor() {
        for (l in langs) {
            val m = load(l)
            for (k in keys) assertTrue("Нет перевода «$k» в $l", !m[k].isNullOrBlank())
            assertTrue("В переводе ($l) нет имени автора", m[keys[1]]!!.contains("wangzan101") && m[keys[1]]!!.contains("Mika"))
            assertTrue("В переводе ($l) нет Apache-2.0", m[keys[1]]!!.contains("Apache-2.0"))
        }
    }

    @Test fun readmeAndLicenseMentionTheAuthor() {
        val readme = File("../README.md").readText()
        assertTrue(readme.contains("https://github.com/wangzan101/Mika"))
        assertTrue(readme.contains("wangzan101"))
        assertTrue(File("../THIRD_PARTY_MIKA.txt").readText().contains("wangzan101"))
        assertTrue(File("../licenses/Mika-LICENSE-Apache-2.0.txt").readText().contains("Apache License"))
    }
}
