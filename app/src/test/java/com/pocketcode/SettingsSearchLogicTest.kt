package com.pocketcode

import com.pocketcode.ui.SearchLogic
import com.pocketcode.ui.SettingEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchLogicTest {
    private val index = listOf(
        SettingEntry("Тема: тёмная, светлая", "Ещё → Настройки", "тема тёмная светлая цвет оформление", "SETTINGS", "theme"),
        SettingEntry("Терминал и запуск", "Проект → Терминал", "терминал команды запуск консоль", "TERMINAL"),
        SettingEntry("Прокси и VPN", "Агент → Настройки OpenCode", "прокси proxy vpn сеть", "OCSETTINGS"),
        SettingEntry("Git и GitHub", "Проект → Git", "git коммит ветка pr сборка", "GIT"),
    )

    private fun titles(q: String) = SearchLogic.search(index, q).entries.map { it.screen }

    @Test
    fun emptyQueryShowsEverything() {
        assertEquals(index.size, SearchLogic.search(index, "  ").entries.size)
    }

    @Test
    fun endingsDoNotMatter() {
        assertEquals(listOf("SETTINGS"), titles("темы"))
        assertTrue("TERMINAL" in titles("терминале"))
        assertTrue("OCSETTINGS" in titles("проксей") || "OCSETTINGS" in titles("прокси"))
    }

    @Test
    fun typosAreForgiven() {
        assertEquals(listOf("TERMINAL"), titles("терминл"))
        assertEquals(listOf("OCSETTINGS"), titles("прокчи".replace('ч', 'с')))
    }

    @Test
    fun wrongKeyboardLayoutIsFixed() {
        val r = SearchLogic.search(index, "ntvf")
        assertTrue(r.layoutFixed)
        assertEquals(listOf("SETTINGS"), r.entries.map { it.screen })
        assertEquals("тема", SearchLogic.fixLayout("ntvf"))
    }

    @Test
    fun englishWordsStillWork() {
        val r = SearchLogic.search(index, "github")
        assertFalse(r.layoutFixed)
        assertEquals(listOf("GIT"), r.entries.map { it.screen })
    }

    @Test
    fun partialMatchWhenOneWordIsUnknown() {
        val r = SearchLogic.search(index, "тема qqqq")
        assertTrue(r.partial)
        assertEquals(listOf("SETTINGS"), r.entries.map { it.screen })
    }

    @Test
    fun nothingFoundForNonsense() {
        assertTrue(SearchLogic.search(index, "ъъъъъъ").entries.isEmpty())
    }

    @Test
    fun similarWordsAreOtherWords() {
        val s = SearchLogic.similarWords(index, "темы")
        assertTrue("тема" in s)
        assertFalse("темы" in s)
        assertTrue(s.size <= 6)
        assertEquals(s.size, s.distinct().size)
    }

    @Test
    fun similarWordsForTypo() {
        assertTrue("терминал" in SearchLogic.similarWords(index, "тирминал"))
    }

    @Test
    fun distanceBasics() {
        assertEquals(0, SearchLogic.distance("abc", "abc"))
        assertEquals(1, SearchLogic.distance("abc", "abd"))
        assertEquals(1, SearchLogic.distance("abc", "ab"))
        assertEquals(3, SearchLogic.distance("", "abc"))
    }
}
