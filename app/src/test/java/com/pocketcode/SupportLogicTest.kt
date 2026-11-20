package com.pocketcode

import com.pocketcode.ui.ACCENTS
import com.pocketcode.ui.EXTRA_ACCENTS
import com.pocketcode.ui.Support
import com.pocketcode.ui.accentById
import com.pocketcode.ui.visibleAccents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SupportLogicTest {
    @Test
    fun allSupportLinksAreAllowed() {
        assertEquals(4, Support.ALL.size)
        for (u in Support.ALL) assertTrue("Ссылка не проходит проверку: $u", Support.isAllowed(u))
    }

    @Test
    fun foreignAndBrokenLinksAreRejected() {
        assertFalse(Support.isAllowed("http://t.me/send?start=x"))
        assertFalse(Support.isAllowed("https://evil.example.com/p/1"))
        assertFalse(Support.isAllowed("https://t.me.evil.example.com/x"))
        assertFalse(Support.isAllowed("intent://pay#Intent;end"))
        assertFalse(Support.isAllowed("не ссылка"))
        assertFalse(Support.isAllowed(""))
    }

    @Test
    fun extraAccentsDoNotClashWithOthers() {
        val ids = (ACCENTS + EXTRA_ACCENTS).map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertFalse("system" in ids)
    }

    @Test
    fun extraAccentsAreFoundById() {
        assertNotNull(accentById("gold"))
        assertNotNull(accentById("blue"))
        assertNull(accentById("нет такого"))
    }

    @Test
    fun extraAccentsAreHiddenUntilUnlocked() {
        assertEquals(ACCENTS, visibleAccents(false, "blue"))
        assertEquals(ACCENTS + EXTRA_ACCENTS, visibleAccents(true, "blue"))
    }

    @Test
    fun selectedExtraAccentStaysVisibleEvenIfLocked() {
        val shown = visibleAccents(false, "coral").map { it.id }
        assertTrue("coral" in shown)
        assertFalse("gold" in shown)
    }

    @Test
    fun newMenuTextsAreTranslatedEverywhere() {
        val required = listOf(
            "Спасибо автору", "Ещё → Спасибо автору", "Проекты и файлы", "Запуск", "Телефон и устройства",
            "Устройства и ADB", "Ещё → Устройства и ADB", "Группа в Telegram", "Российские карты", "Криптовалюта", "Карты, в том числе российские, и криптовалюта",
            "В Telegram", "На сайте", "Лайм", "Коралл", "Индиго", "Золотой",
        )
        for (l in listOf("en", "de", "es", "zh", "ar")) {
            val m = (Json.parseToJsonElement(File("src/main/assets/i18n/$l.json").readText()) as JsonObject)
                .mapValues { (it.value as JsonPrimitive).content }
            for (k in required) assertTrue("Нет перевода «$k» в $l", !m[k].isNullOrBlank())
        }
    }
}
