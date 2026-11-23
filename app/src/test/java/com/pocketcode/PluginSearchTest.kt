package com.pocketcode

import com.pocketcode.ui.PluginSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSearchTest {
    @Test
    fun parsesGithubSearchResponse() {
        val json = """{"total_count":2,"items":[
            {"full_name":"org/skills","description":"Collection of agent skills","stargazers_count":1521,"html_url":"https://github.com/org/skills","language":"TypeScript","updated_at":"2026-01-01"},
            {"full_name":"org/plugin","description":"","stargazers_count":7,"html_url":"https://github.com/org/plugin"}
        ]}"""
        val hits = PluginSearch.parse(json)
        assertEquals(2, hits.size)
        assertEquals("org/skills", hits[0].fullName)
        assertEquals(1521, hits[0].stars)
        assertEquals("Collection of agent skills", hits[0].description)
        assertEquals("https://github.com/org/plugin", hits[1].url)
    }

    @Test
    fun buildsSearchUrl() {
        val url = PluginSearch.url("claude skills")
        assertTrue(url.contains("api.github.com/search/repositories"))
        assertTrue(url.contains("sort=stars"))
        assertTrue(url.contains("in%3Aname%2Cdescription%2Creadme"))
        assertTrue(url.contains("claude"))
        assertTrue(url.contains("per_page=20"))
    }

    @Test
    fun invalidSortIsNeverSentToGithub() {
        // GitHub отвечает 422 на sort=name — такой список сортируется на телефоне.
        val url = PluginSearch.url("skills", sort = "name")
        assertFalse(url.contains("sort="))
    }

    @Test
    fun categoryAddsItsWordToTheQuery() {
        val url = PluginSearch.url("telegram", category = "MCP")
        assertTrue(url.contains("mcp"))
        assertEquals("skills", PluginSearch.categoryWord("Навыки"))
        assertEquals("", PluginSearch.categoryWord("Все"))
    }

    @Test
    fun fallbacksGoFromNarrowToBroad() {
        val list = PluginSearch.fallbacks("claude code skills", "Навыки")
        assertEquals("claude code skills", list.first())
        assertTrue(list.contains("claude code"))
        assertTrue(list.any { it == "skills" })
        assertTrue(list.size <= 5)
        assertTrue(PluginSearch.fallbacks("   ").isEmpty())
    }

    @Test
    fun localFilterSearchesDescription() {
        data class X(val name: String, val desc: String)
        val items = listOf(X("android-build", "Set up and debug Android apps"), X("grill-me", "Ask sharp questions"))
        val found = PluginSearch.filterLocal(items, "android") { it.name + " " + it.desc }
        assertEquals(1, found.size)
        assertTrue(PluginSearch.filterLocal(items, "") { it.name }.size == 2)
    }
}
