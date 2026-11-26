package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch18Test {
    // VersionBump
    @Test
    fun versionBumpAllParts() {
        assertEquals("1.2.4", VersionBump.bump("1.2.3", "patch"))
        assertEquals("1.3.0", VersionBump.bump("1.2.3", "minor"))
        assertEquals("2.0.0", VersionBump.bump("1.2.3", "major"))
        assertNull(VersionBump.bump("1.2", "patch"))
        assertNull(VersionBump.bump("1.2.3", "beta"))
        assertNull(VersionBump.bump("1.2.3.1", "patch"))
        assertNull(VersionBump.bump("a.b.c", "patch"))
    }

    // Changelog
    @Test
    fun changelogGroupsAndMarkdown() {
        val g = Changelog.group(
            listOf("feat: экспорт", "fix: краш при старте", "Исправлено дублирование", "документация")
        )
        assertEquals(listOf("feat: экспорт"), g.features)
        assertEquals(listOf("fix: краш при старте", "Исправлено дублирование"), g.fixes)
        assertEquals(listOf("документация"), g.other)
        val md = Changelog.markdown(g, "v1")
        assertTrue(md.contains("## Новое") && md.contains("- feat: экспорт") && md.contains("## Исправления"))
    }

    // TechDebt
    @Test
    fun techDebtScan() {
        val src = "fun a() {\n    // TODO: убрать\n    // обычный комментарий\n    // FIXME: краш\n}"
        val items = TechDebt.scan(src)
        assertEquals(2, items.size)
        assertEquals("TODO", items[0].kind)
        assertEquals(2, items[0].line)
        assertEquals("убрать", items[0].text)
        assertEquals("FIXME", items[1].kind)
        assertNull(TechDebt.scanLine(1, "нет меток здесь"))
    }

    // Complexity
    @Test
    fun complexityCountsBranches() {
        assertEquals(1, Complexity.ofBody("return 1"))
        assertEquals(3, Complexity.ofBody("if (a) x else y\nwhile (b) {}"))
        assertEquals("простая", Complexity.label(3))
        assertEquals("средняя", Complexity.label(8))
        assertEquals("сложная", Complexity.label(15))
        assertEquals("очень сложная", Complexity.label(30))
    }

    // DeadCode
    @Test
    fun deadCodeFindsUnused() {
        val decl = mapOf(
            "used" to listOf("App.kt:1", "Main.kt:5 fun used() {}", "Other.kt:2 used()"),
            "deadFn" to listOf("App.kt:10 fun deadFn() {}")
        )
        assertEquals(listOf("deadFn"), DeadCode.find(decl))
    }

    // CoverageGaps
    @Test
    fun coverageGapsListsUntested() {
        val files = listOf(
            "src/A.kt", "src/ATest.kt", "src/B.kt", "src/C.py", "src/test_C.py", "src/D.kt", "src/DTest.kt"
        )
        assertEquals(listOf("src/B.kt"), CoverageGaps.untested(files))
    }

    // FeatureFlags
    @Test
    fun featureFlagsRoundTrip() {
        val defaults = mapOf("a" to true, "b" to false, "c" to true)
        val st = FeatureFlags.parse("-a,c", defaults)
        assertEquals(false, st["a"])
        assertEquals(false, st["b"])
        assertEquals(true, st["c"])
        val ser = FeatureFlags.serialize(st)
        assertEquals(st, FeatureFlags.parse(ser, defaults))
        assertEquals(false, FeatureFlags.isEnabled(emptyMap(), "x"))
    }

    // ProjectExport
    @Test
    fun projectExportManifestAndSize() {
        val m = ProjectExport.manifest(
            mapOf("src/A.kt" to 100L, ".git/config" to 5000L, "build/out.apk" to 9000L, "readme.md" to 50L)
        )
        assertEquals(listOf("readme.md", "src/A.kt"), m.files)
        assertEquals(150L, m.totalBytes)
        assertEquals("150 Б", ProjectExport.humanSize(150))
        assertEquals("1.0 КБ", ProjectExport.humanSize(1024))
        assertEquals("1.0 МБ", ProjectExport.humanSize(1048576))
    }

    // Outline
    @Test
    fun outlineExtractsSymbols() {
        val src = "class A {\n    fun b() {}\n}\nobject C\ndef d():\n    pass\n"
        val syms = Outline.scan(src)
        assertEquals(listOf("A", "b", "C", "d"), syms.map { it.name })
        assertEquals(listOf(1, 2, 4, 5), syms.map { it.line })
        assertEquals("def", syms[3].kind)
    }

    // VoiceTask
    @Test
    fun voiceTaskDraft() {
        assertEquals("[голос · 12:00] Сделай тесты", VoiceTask.draft("  сделай   тесты ", "12:00"))
        assertEquals("", VoiceTask.draft("   ", "12:00"))
    }
}
