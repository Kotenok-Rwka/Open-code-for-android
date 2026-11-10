package com.pocketcode

import com.pocketcode.ui.ACCENTS
import com.pocketcode.ui.accentById
import com.pocketcode.ui.toolStateLabel
import com.pocketcode.ui.toolTitle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatLookTest {
    @Test
    fun toolStateShowsErrorFirstThenRunning() {
        assertEquals("ошибка", toolStateLabel(running = false, error = true))
        assertEquals("ошибка", toolStateLabel(running = true, error = true))
        assertEquals("выполняется…", toolStateLabel(running = true, error = false))
        assertEquals("готово", toolStateLabel(running = false, error = false))
    }

    @Test
    fun toolTitleTakesFirstLineOnly() {
        assertEquals("ls -la", toolTitle("  ls -la\nsecond line\nthird"))
        assertEquals("", toolTitle(""))
        assertEquals("", toolTitle("   \n  "))
    }

    @Test
    fun toolTitleIsCutAtEightyChars() {
        val long = "x".repeat(200)
        val t = toolTitle(long)
        assertEquals(81, t.length)
        assertTrue(t.endsWith("…"))
        assertEquals("y".repeat(80), toolTitle("y".repeat(80)))
    }

    @Test
    fun accentIdsAreUniqueAndFindable() {
        val ids = ACCENTS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (id in ids) assertNotNull(accentById(id))
        assertNull(accentById("system"))
        assertNull(accentById("нет такого"))
    }

    @Test
    fun presetAccentsArePresentAndKeepBlueFirst() {
        assertEquals("blue", ACCENTS.first().id)
        for (id in listOf("vscode", "sand", "contrast")) assertNotNull("Нет акцента $id", accentById(id))
    }

    @Test
    fun newChatStringsAreTranslatedEverywhere() {
        val dir = File("src/main/assets/i18n")
        val required = listOf(
            "Как в VS Code", "Песочный", "Контрастный", "код", "готово",
            "Правок пока нет.", "После работы агента здесь появятся его изменения файлов.",
        )
        for (l in listOf("en", "de", "es", "zh", "ar")) {
            val obj = Json.parseToJsonElement(File(dir, "$l.json").readText()) as JsonObject
            for (k in required) {
                val v = (obj[k] as? JsonPrimitive)?.content
                assertTrue("Нет перевода «$k» в $l", !v.isNullOrBlank())
            }
        }
    }
}
