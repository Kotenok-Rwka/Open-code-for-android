package com.pocketcode

import androidx.compose.ui.graphics.Color
import com.pocketcode.ui.ACCENTS
import com.pocketcode.ui.DEFAULT_UI_STYLE
import com.pocketcode.ui.UiStyle
import com.pocketcode.ui.buildIosScheme
import com.pocketcode.ui.groupBySections
import com.pocketcode.ui.metricsOf
import com.pocketcode.ui.uiStyleById
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiStyleTest {
    @Test
    fun defaultStyleIsMaterial3() {
        assertEquals(UiStyle.MD3, DEFAULT_UI_STYLE)
        assertEquals(UiStyle.MD3, uiStyleById(null))
    }

    @Test
    fun unknownOrEmptyIdFallsBackToDefault() {
        assertEquals(DEFAULT_UI_STYLE, uiStyleById(""))
        assertEquals(DEFAULT_UI_STYLE, uiStyleById("windows"))
        assertEquals(DEFAULT_UI_STYLE, uiStyleById("IOS "))
    }

    @Test
    fun everyStyleSurvivesSaveAndLoad() {
        for (s in UiStyle.values()) assertEquals(s, uiStyleById(s.id))
        val ids = UiStyle.values().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(UiStyle.values().size >= 2)
    }

    @Test
    fun iosListsAreGroupedAndMaterialListsAreCards() {
        val ios = metricsOf(UiStyle.IOS)
        val md3 = metricsOf(UiStyle.MD3)
        assertTrue(ios.groupedLists)
        assertFalse(ios.navIndicator)
        assertFalse(md3.groupedLists)
        assertTrue(md3.navIndicator)
        // в Material 3 значок в кружке, в iOS в скруглённом квадрате
        assertEquals(md3.iconTile, md3.iconTileRadius * 2)
        assertTrue(ios.iconTileRadius * 2 < ios.iconTile)
    }

    @Test
    fun sectionsSplitByHeaders() {
        val items = listOf("#Один", "a1", "a2", "#Два", "b1", "#Три")
        val res = groupBySections(items, { it.startsWith("#") }, { it.removePrefix("#") })
        assertEquals(listOf("Один" to listOf("a1", "a2"), "Два" to listOf("b1")), res)
    }

    @Test
    fun itemsBeforeFirstHeaderGoToUntitledGroup() {
        val res = groupBySections(listOf("x", "#Раздел", "y"), { it.startsWith("#") }, { it.removePrefix("#") })
        assertEquals(2, res.size)
        assertEquals(null, res[0].first)
        assertEquals(listOf("x"), res[0].second)
        assertEquals("Раздел", res[1].first)
    }

    @Test
    fun emptyInputAndEmptyGroupsGiveNoSections() {
        assertTrue(groupBySections(emptyList<String>(), { it.startsWith("#") }, { it }).isEmpty())
        assertTrue(groupBySections(listOf("#A", "#B"), { it.startsWith("#") }, { it }).isEmpty())
    }

    @Test
    fun iosLightSchemeUsesGroupedBackgroundAndWhiteCards() {
        val s = buildIosScheme(dark = false, amoled = false, a = ACCENTS.first())
        assertEquals(Color(0xFFF2F2F7), s.background)
        assertEquals(Color.White, s.surfaceContainer)
        assertEquals(Color(0xFF007AFF), s.primary)
    }

    @Test
    fun iosDarkSchemeIsBlackWithAmoledAndKeepsOtherAccents() {
        val dark = buildIosScheme(dark = true, amoled = true, a = ACCENTS.first())
        assertEquals(Color.Black, dark.background)
        assertEquals(Color(0xFF1C1C1E), dark.surfaceContainer)
        assertEquals(Color(0xFF0A84FF), dark.primary)
        val teal = ACCENTS.first { it.id == "teal" }
        assertEquals(teal.light, buildIosScheme(dark = false, amoled = false, a = teal).primary)
        assertEquals(teal.dark, buildIosScheme(dark = true, amoled = false, a = teal).primary)
    }

    @Test
    fun styleStringsAreTranslatedEverywhere() {
        val dir = File("src/main/assets/i18n")
        val required = listOf("Стиль интерфейса", "Стиль интерфейса: Material 3 или iOS") +
            UiStyle.values().map { it.about }
        for (l in listOf("en", "de", "es", "zh", "ar")) {
            val obj = Json.parseToJsonElement(File(dir, "$l.json").readText()) as JsonObject
            for (k in required) {
                val v = (obj[k] as? JsonPrimitive)?.content
                assertTrue("Нет перевода «$k» в $l", !v.isNullOrBlank())
            }
        }
    }
}
