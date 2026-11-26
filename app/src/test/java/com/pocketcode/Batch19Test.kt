package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch19Test {
    // TodayDigest
    @Test
    fun todayDigestBuildsEntries() {
        val entries = TodayDigest.build(
            listOf(Triple("a.kt", 3, "убрать хак")),
            listOf("src/a.kt", "src/b.kt"),
            "abc123 2026-11-25 fix: краш"
        )
        assertEquals("TODO a.kt:3", entries[0].title)
        assertEquals("Коммит", entries.last().title)
        assertEquals(7, entries.size)
    }

    // GlobalSearch
    @Test
    fun globalSearchFindsNamesAndContent() {
        val files = listOf("src/Main.kt", "src/util.kt", "README.md")
        assertEquals(listOf("src/Main.kt"), GlobalSearch.names(files, "main"))
        assertTrue(GlobalSearch.names(files, "  ").isEmpty())
        val hits = GlobalSearch.content(listOf("Fun()", "бар", "fun2()"), "fun", "f.kt")
        assertEquals(2, hits.size)
        assertEquals(1, hits[0].line)
        assertTrue(GlobalSearch.content(listOf("x"), "", "f.kt").isEmpty())
    }

    // PromptTpl
    @Test
    fun promptTplRendersPlaceholders() {
        assertEquals("проверь a.py в proj", PromptTpl.render("проверь {{файл}} в {{проект}}", "a.py", "proj"))
        val out = PromptTpl.renderList(mapOf("t" to "открой {{файл}}"), mapOf("файл" to "x.kt"))
        assertEquals("открой x.kt", out["t"])
    }

    // Reminders
    @Test
    fun remindersDueAndDone() {
        val items = listOf(
            Reminders.Item("а", "2026-01-01", false),
            Reminders.Item("б", "2026-03-01", false),
            Reminders.Item("в", "2026-01-05", true),
        )
        assertEquals(listOf("а"), Reminders.due(items, "2026-02-01").map { it.text })
        val after = Reminders.markDone(items, 0)
        assertEquals(emptyList<String>(), Reminders.due(after, "2026-02-01").map { it.text })
    }

    // UsageMeter
    @Test
    fun usageMeterTokensAndLimit() {
        assertEquals(3, UsageMeter.approxTokens(listOf("aaaa", "bbbbbbbb")))
        assertTrue(UsageMeter.check(1000, 500))
        assertTrue(!UsageMeter.check(1000, 1500))
        assertTrue(UsageMeter.check(0, 999999))
        assertEquals(500, UsageMeter.left(1000, 500))
        assertEquals(0, UsageMeter.left(100, 500))
    }

    // ChatExport
    @Test
    fun chatExportMarkdown() {
        val md = ChatExport.markdown(listOf("user" to "привет", "agent" to "готово"), "Т")
        assertTrue(md.contains("# Т") && md.contains("**user:**") && md.contains("готово"))
    }

    // TermHistory
    @Test
    fun termHistoryLastReversedAndSnippets() {
        val items = listOf("ls" to "out", "cd x" to "", "pwd" to "/x")
        assertEquals(listOf("pwd", "cd x", "ls"), TermHistory.last(items).map { it.first })
        assertEquals(listOf("ls"), TermHistory.last(items, 1).map { it.first })
        assertEquals("./build all", TermHistory.render("./build {{цель}}", mapOf("цель" to "all")))
    }

    // MiniDiff
    @Test
    fun miniDiffAndRegexReplace() {
        val d = MiniDiff.of("a\nb\nc".lines(), "a\nx\nc".lines())
        assertTrue(d.added.contains("x"))
        assertEquals(1, d.added.size)
        assertEquals(1, d.removed.size)
        val (text, n) = MiniDiff.regexReplace("foo 12 bar 33", Regex("\\d+"), "#")
        assertEquals("foo # bar #", text)
        assertEquals(2, n)
    }

    // UndoStack
    @Test
    fun undoStackPushPop() {
        var stack = emptyList<String>()
        for (i in 0..2) stack = UndoStack.push(stack, "v$i")
        val (content, rest) = UndoStack.pop(stack)!!
        assertEquals("v2", content)
        assertEquals("v1", UndoStack.pop(rest)!!.first)
        assertEquals(null, UndoStack.pop(emptyList()))
    }

    // EditRisk
    @Test
    fun editRiskLevels() {
        assertEquals("безопасно", EditRisk.label(EditRisk.score(2, 1, 1)))
        assertEquals("осторожно", EditRisk.label(EditRisk.score(20, 20, 2)))
        assertEquals("опасно", EditRisk.label(EditRisk.score(50, 50, 4)))
    }

    // AnswerCheck + Safety
    @Test
    fun answerCheckAndProtection() {
        val checks = AnswerCheck.verify(listOf("pkg/a.py", "fake/нет.py"), setOf("pkg/a.py"))
        assertEquals(true, checks[0].exists)
        assertEquals(false, checks[1].exists)
        assertTrue(Safety.isProtected("pkg/a.py/sub/x.kt", listOf("pkg/a.py")))
        assertTrue(!Safety.isProtected("pkg/b.py", listOf("pkg/a.py")))
        assertEquals("1.0 КБ", Safety.humanSize(1024))
    }

    // Tts + WhatsNew
    @Test
    fun ttsChunksAndWhatsNew() {
        val chunks = Tts.chunks("Первое. Второе! Третье? ".repeat(10))
        assertTrue(chunks.all { it.length <= 300 })
        val md = WhatsNew.markdown("4.7.1", "4.8.0", listOf("терминал", "флаги"))
        assertTrue(md.contains("4.7.1 → 4.8.0") && md.contains("- терминал"))
        assertEquals(5, WhatsNew.onboarding().size)
    }
}
