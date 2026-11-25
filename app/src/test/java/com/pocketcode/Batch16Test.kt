package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch16Test {
    // 217. DiffPath
    @Test
    fun diffPathStripsPrefixesAndParsesHeaders() {
        assertEquals("src/A.kt", DiffPath.strip("a/src/A.kt"))
        assertEquals("src/A.kt", DiffPath.strip("b/src/A.kt"))
        assertEquals("src/A.kt", DiffPath.fromHeader("+++ b/src/A.kt"))
        assertEquals("src/A.kt", DiffPath.fromHeader("--- a/src/A.kt\t2026-01-01"))
        assertEquals("/dev/null", DiffPath.fromHeader("+++ /dev/null"))
        assertEquals("", DiffPath.fromHeader("не заголовок"))
    }

    // 218. VersionCompare
    @Test
    fun versionCompareHandlesNumericAndPreRelease() {
        assertTrue(VersionCompare.isNewer("1.2.10", "1.2.9"))
        assertTrue(VersionCompare.isNewer("v2.0", "1.9.9"))
        assertTrue(VersionCompare.isNewer("1.0", "1.0-rc1"))
        assertTrue(VersionCompare.isNewer("1.0-beta2", "1.0-beta1"))
        assertTrue(!VersionCompare.isNewer("1.2.9", "1.2.10"))
        assertEquals(0, VersionCompare.compare("V1.2", "v1.2"))
    }

    // 219. EnvParser
    @Test
    fun envParserReadsAndWritesKeyValue() {
        val env = EnvParser.parse(
            "# комментарий\n\nHOST=pc\nPORT=9000\nQUOTED=\"hello world\"\nSINGLE='x y'\nБЕЗ= \n=пусто\n"
        )
        assertEquals("pc", env["HOST"])
        assertEquals("9000", env["PORT"])
        assertEquals("hello world", env["QUOTED"])
        assertEquals("x y", env["SINGLE"])
        assertTrue(env.containsKey("БЕЗ"))
        assertTrue(!env.containsKey(""))
        assertEquals("A=1\nB=2", EnvParser.render(mapOf("B" to "2", "A" to "1")))
    }

    // 220. Recurrence
    @Test
    fun recurrenceParsesRussianRules() {
        assertEquals(Recurrence.Rule("day", 1), Recurrence.parse("Каждый день поливать"))
        assertEquals(Recurrence.Rule("week", 1), Recurrence.parse("каждую неделю"))
        assertEquals(Recurrence.Rule("month", 1), Recurrence.parse("Каждый месяц"))
        assertEquals(Recurrence.Rule("day", 3), Recurrence.parse("каждые 3 дня"))
        assertEquals(Recurrence.Rule("week", 2), Recurrence.parse("каждые 2 недели"))
        assertNull(Recurrence.parse("как-нибудь потом"))
        assertEquals(listOf(11, 14, 17), Recurrence.nextDays(Recurrence.Rule("day", 3), 8, 3))
        assertEquals(listOf(15, 22), Recurrence.nextDays(Recurrence.Rule("week", 1), 8, 2))
        assertEquals("каждые 3 дня", Recurrence.describe(Recurrence.Rule("day", 3)))
    }

    // 221. TaskDedup
    @Test
    fun taskDedupNormalizesAndKeepsFirst() {
        assertEquals("брысь мур", TaskDedup.norm("Мур, брысь!"))
        assertEquals(listOf("Купить хлеб", "звонок маме"),
            TaskDedup.keepUnique(listOf("Купить хлеб", "звонок маме", "купить хлеб!")))
        assertEquals("звонок мамее", TaskDedup.norm("Звонок  маме-е"))
    }

    // 222. JsonStr
    @Test
    fun jsonStrRoundTripsEscapes() {
        val s = "а\"б\\в\nж@"
        val esc = JsonStr.escape(s)
        assertTrue(esc.startsWith("\"") && esc.endsWith("\""))
        assertEquals(s, JsonStr.unescape(esc))
        assertEquals("\"tab\\there\"", JsonStr.escape("tab\there"))
        assertEquals("\"\\u0007\"", JsonStr.escape(""))
        assertEquals("не строка", JsonStr.unescape("не строка"))
    }

    // 223. PromptScore
    @Test
    fun promptScoreClassifiesComplexity() {
        assertEquals("простой", PromptScore.score("привет").complexity)
        assertEquals("средний", PromptScore.score("сделай ".repeat(50).trim()).complexity)
        assertEquals("сложный", PromptScore.score("очень ".repeat(80).trim()).complexity)
        assertTrue(PromptScore.score("напиши код:\n```kotlin\nfun a(){}\n```").hasCode)
        assertTrue(PromptScore.score("def f(): return 1").hasCode)
        assertEquals(3, PromptScore.score("один   два\nтри").words)
    }
}
