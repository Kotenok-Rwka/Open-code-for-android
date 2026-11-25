package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch15Test {
    // 209. CodeSearch
    @Test
    fun searchFindsCaseInsensitiveSubstrings() {
        val text = "fun alpha() {}\nfun Beta() {}\n"
        val hits = CodeSearch.search(text, "beta")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].line)
        assertEquals("fun Beta() {}", hits[0].text)
    }

    @Test
    fun searchRegexAndBadRegex() {
        val text = "x = 1\ny = 22\n"
        assertEquals(listOf(2), CodeSearch.search(text, "\\d{2}", regex = true).map { it.line })
        assertTrue(CodeSearch.search(text, "([", regex = true).isEmpty())
        assertTrue(CodeSearch.search(text, "").isEmpty())
    }

    // 210. SnippetLib
    @Test
    fun snippetsAddAndFindByTagOrTitle() {
        val s1 = SnippetLib.add("  сортировка  ", "list.sorted()", listOf(" Kotlin ", "СРП"))
        val s2 = SnippetLib.add("лог", "println(x)")
        assertEquals(1, s1.id)
        assertEquals(listOf("kotlin", "срп"), s1.tags)
        assertEquals(2, SnippetLib.all().size)
        assertEquals(listOf(1), SnippetLib.find("kotlin").map { it.id }) // матч по тегу
        assertEquals(listOf(2), SnippetLib.find("лог").map { it.id })    // матч по названию
        assertEquals(listOf(1, 2), SnippetLib.find("").map { it.id })
        assertTrue(SnippetLib.find("нет такого").isEmpty())
    }

    // 211. DiffStat
    @Test
    fun diffStatCountsFilesAndLines() {
        val diff = """
            diff --git a/a.kt b/a.kt
            --- a/a.kt
            +++ b/a.kt
            @@ -1,2 +1,2 @@
            -old line
            +new line
            +second new
            diff --git a/b.kt b/b.kt
            --- a/b.kt
            +++ b/b.kt
            @@ -1 +1 @@
            -gone
        """.trimIndent()
        val s = DiffStat.parse(diff)
        assertEquals(2, s.files)
        assertEquals(2, s.added)
        assertEquals(2, s.removed)
    }

    // 212. MentionParser
    @Test
    fun mentionsAndTagsAreExtracted() {
        val text = "спроси @Vasya и @мама, теги: #bug #Фича #42"
        assertEquals(listOf("Vasya", "мама"), MentionParser.mentions(text))
        assertEquals(listOf("bug", "Фича", "42"), MentionParser.tags(text))
        assertTrue(MentionParser.mentions("никого нет").isEmpty())
    }

    // 213. BatchRename
    @Test
    fun renamePlanKeepsExtensionAndPads() {
        val plan = BatchRename.plan(listOf("a.txt", "b.txt", "readme"), "img")
        assertEquals("img-01.txt", plan[0].to)
        assertEquals("img-03", plan[2].to)
        assertEquals("a.txt", plan[0].from)
        val wide = BatchRename.plan(listOf("x.txt"), "p", sep = "_", pad = 3)
        assertEquals("p_001.txt", wide[0].to)
    }

    // 214. PortCheck
    @Test
    fun portCheckNormalizesHosts() {
        assertEquals("http://pc:9000", PortCheck.toUrl("pc:9000"))
        assertEquals("http://pc:8080", PortCheck.toUrl("pc"))
        assertEquals("https://pc:443", PortCheck.toUrl("https://pc/"))
        assertEquals("http://[::1]:9000", PortCheck.toUrl("[::1]:9000"))
        assertEquals("http://[::1]:8080", PortCheck.toUrl("[::1]"))
        assertEquals("pc" to 9000, PortCheck.split(" http://pc:9000 "))
    }

    // 215. TimeFmt
    @Test
    fun timeFmtUsesCorrectRussianPlurals() {
        assertEquals("только что", TimeFmt.ago(30))
        assertEquals("1 минуту назад", TimeFmt.ago(60))
        assertEquals("5 минут назад", TimeFmt.ago(5 * 60))
        assertEquals("2 часа назад", TimeFmt.ago(2 * 3600))
        assertEquals("5 часов назад", TimeFmt.ago(5 * 3600))
        assertEquals("1 день назад", TimeFmt.ago(24 * 3600))
        assertEquals("3 дня назад", TimeFmt.ago(3 * 24 * 3600))
        assertEquals("7 дней назад", TimeFmt.ago(7 * 24 * 3600))
        assertEquals("только что", TimeFmt.ago(-5))
    }

    // 216. EditorStats
    @Test
    fun editorStatsExpandsTabsAndCounts() {
        assertEquals("    x", EditorStats.expandTabs("\tx"))
        assertEquals("ab  c", EditorStats.expandTabs("ab\tc", tab = 4)) // до колонки 4
        assertEquals(3, EditorStats.words(" один  два\nтри "))
        assertEquals(2, EditorStats.lines("a\nb"))
        assertEquals(1, EditorStats.lines(""))
    }
}
