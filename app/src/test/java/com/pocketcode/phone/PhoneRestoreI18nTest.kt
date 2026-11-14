// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Строки автозапуска помощника и барабанов переведены на все языки. */
class PhoneRestoreI18nTest {
    private val langs = listOf("en", "de", "es", "zh", "ar")

    private fun keys(lang: String): Set<String> =
        (Json.parseToJsonElement(File("src/main/assets/i18n/$lang.json").readText()) as JsonObject).keys

    @Test fun restoreStringsAreTranslatedEverywhere() {
        val required = RestoreOutcome.values().map { restoreText(it.name)!! } + listOf(
            "Запускать помощника после перезагрузки",
            "Сначала запусти помощника: он выдаст приложению право включать отладку после перезагрузки.",
            "Не удалось выдать право менять системные настройки: {0}",
            "Автозапуск включён. Запусти помощника один раз, и после перезагрузки он запустится сам.",
            "Автозапуск включён.",
        )
        for (l in langs) {
            val k = keys(l)
            for (r in required) assertTrue("Нет перевода «$r» в $l", r in k)
        }
    }
}
