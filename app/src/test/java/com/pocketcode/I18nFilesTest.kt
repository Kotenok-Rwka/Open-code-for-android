package com.pocketcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class I18nFilesTest {
    private val dir = File("src/main/assets/i18n")
    private val langs = listOf("en", "de", "es", "zh", "ar")
    private val ph = Regex("""\{\d+\}""")

    private fun load(lang: String): Map<String, String> {
        val obj = Json.parseToJsonElement(File(dir, "$lang.json").readText()) as JsonObject
        return obj.mapValues { (it.value as JsonPrimitive).content }
    }

    @Test
    fun placeholdersMatchInEveryLanguage() {
        for (l in langs) {
            for ((k, v) in load(l)) {
                val a = ph.findAll(k).map { it.value }.sorted().toList()
                val b = ph.findAll(v).map { it.value }.sorted().toList()
                assertEquals("Не совпадают {n} в $l: $k", a, b)
            }
        }
    }

    @Test
    fun newScreensAreTranslatedEverywhere() {
        val required = listOf("Умные функции", "Обучение", "Показать, где это", "Предохранитель", "Быстрые режимы")
        for (l in langs) {
            val m = load(l)
            for (k in required) assertTrue("Нет перевода «$k» в $l", !m[k].isNullOrBlank())
        }
    }

    @Test
    fun startScreenSuggestionsAreTranslatedEverywhere() {
        val titles = listOf(
            "Разберись в проекте", "Найди ошибки", "Напиши тесты", "Telegram-бот", "Сайт",
            "Почини сборку", "Объясни код", "Упрости код", "Зависимости",
        )
        for (l in langs) {
            val m = load(l)
            for (k in titles) {
                val v = m[k]
                assertTrue("Нет перевода «$k» в $l", !v.isNullOrBlank())
                assertTrue("В переводе «$k» ($l) остался эмодзи в начале", v!!.first().isLetterOrDigit())
            }
        }
    }
}
