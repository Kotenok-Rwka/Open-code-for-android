package com.pocketcode

import com.pocketcode.ui.READING_WIDTH_DP
import com.pocketcode.ui.TokKind
import com.pocketcode.ui.cutText
import com.pocketcode.ui.highlight
import com.pocketcode.ui.isReadingScreen
import com.pocketcode.ui.isWideScreen
import com.pocketcode.ui.langLabel
import com.pocketcode.ui.normLang
import com.pocketcode.ui.revealStep
import com.pocketcode.ui.skeletonWidth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HighlighterTest {
    private fun kinds(code: String, lang: String) = highlight(code, lang).map { it.kind to code.substring(it.start, it.end) }

    @Test
    fun languageNamesAreNormalized() {
        assertEquals("kotlin", normLang("kt"))
        assertEquals("python", normLang("  PY "))
        assertEquals("shell", normLang("bash {title=x}"))
        assertEquals("", normLang("klingon"))
        assertEquals("", normLang(""))
    }

    @Test
    fun languageLabelIsPrettyOrAsWritten() {
        assertEquals("Kotlin", langLabel("kt"))
        assertEquals("C++", langLabel("cpp"))
        assertEquals("klingon", langLabel("klingon"))
        assertEquals("", langLabel("  "))
    }

    @Test
    fun kotlinKeywordsStringsNumbersAndComments() {
        val code = "val x = \"hi\" // note\nfun f() = 42"
        val k = kinds(code, "kotlin")
        assertTrue(k.contains(TokKind.KEYWORD to "val"))
        assertTrue(k.contains(TokKind.STRING to "\"hi\""))
        assertTrue(k.contains(TokKind.COMMENT to "// note"))
        assertTrue(k.contains(TokKind.KEYWORD to "fun"))
        assertTrue(k.contains(TokKind.NUMBER to "42"))
    }

    @Test
    fun pythonUsesHashCommentsAndTripleQuotes() {
        val code = "# top\ndef f():\n    \"\"\"doc\nmore\"\"\"\n    return None"
        val k = kinds(code, "python")
        assertTrue(k.contains(TokKind.COMMENT to "# top"))
        assertTrue(k.contains(TokKind.KEYWORD to "def"))
        assertTrue(k.contains(TokKind.STRING to "\"\"\"doc\nmore\"\"\""))
        assertTrue(k.contains(TokKind.KEYWORD to "None"))
    }

    @Test
    fun hashIsNotACommentInKotlinAndSlashesAreNotInPython() {
        assertFalse(kinds("a # b", "kotlin").any { it.first == TokKind.COMMENT })
        assertFalse(kinds("a // b", "python").any { it.first == TokKind.COMMENT })
    }

    @Test
    fun blockCommentsAreFoundAndUnterminatedOnesRunToTheEnd() {
        assertEquals(listOf(TokKind.COMMENT to "/* a\nb */"), kinds("/* a\nb */ x", "java").filter { it.first == TokKind.COMMENT })
        assertEquals(listOf(TokKind.COMMENT to "/* open"), kinds("/* open", "java"))
    }

    @Test
    fun escapedQuoteDoesNotEndString() {
        val k = kinds("\"a\\\"b\" c", "javascript")
        assertTrue(k.contains(TokKind.STRING to "\"a\\\"b\""))
    }

    @Test
    fun unterminatedStringStopsAtLineEnd() {
        val k = kinds("\"abc\nval", "kotlin")
        assertTrue(k.contains(TokKind.STRING to "\"abc"))
        assertTrue(k.contains(TokKind.KEYWORD to "val"))
    }

    @Test
    fun typesAndAnnotationsAreMarked() {
        val k = kinds("@Composable fun Foo(bar: String)", "kotlin")
        assertTrue(k.contains(TokKind.TYPE to "@Composable"))
        assertTrue(k.contains(TokKind.TYPE to "Foo"))
        assertTrue(k.contains(TokKind.TYPE to "String"))
        assertFalse(k.any { it.second == "bar" })
    }

    @Test
    fun digitsInsideNamesAreNotNumbers() {
        assertFalse(kinds("abc123 x2", "kotlin").any { it.first == TokKind.NUMBER })
    }

    @Test
    fun jsonOnlyKnowsStringsNumbersAndLiterals() {
        val k = kinds("{\"a\": 1, \"b\": true, \"c\": null}", "json")
        assertEquals(3, k.count { it.first == TokKind.STRING })
        assertTrue(k.contains(TokKind.NUMBER to "1"))
        assertTrue(k.contains(TokKind.KEYWORD to "true"))
        assertTrue(k.contains(TokKind.KEYWORD to "null"))
    }

    @Test
    fun tokensAreOrderedNonOverlappingAndInsideText() {
        val code = "fun a() { /* c */ val s = 'x'; // t\n return 1.5 }\n\"unterminated"
        for (lang in listOf("", "kotlin", "python", "shell", "json", "html", "sql")) {
            var last = 0
            for (t in highlight(code, lang)) {
                assertTrue("$lang: $t", t.start >= last && t.end > t.start && t.end <= code.length)
                last = t.end
            }
        }
    }

    @Test
    fun emptyAndHugeInputsGiveNoTokens() {
        assertTrue(highlight("", "kotlin").isEmpty())
        assertTrue(highlight("val ".repeat(10000), "kotlin").isEmpty())
    }

    @Test
    fun revealStepAlwaysMovesForwardAndFinishes() {
        assertEquals(1, revealStep(0, 1))
        assertEquals(5, revealStep(5, 5))
        assertEquals(5, revealStep(9, 5))
        var shown = 0
        var frames = 0
        while (shown < 5000) { val n = revealStep(shown, 5000); assertTrue(n > shown); shown = n; frames++ }
        assertTrue("слишком медленно: $frames кадров", frames < 120)
    }

    @Test
    fun cutTextDoesNotSplitEmoji() {
        val s = "a😀b"
        assertEquals("a", cutText(s, 2))
        assertEquals("a😀", cutText(s, 3))
        assertEquals(s, cutText(s, 99))
        assertEquals("", cutText(s, 0))
    }

    @Test
    fun skeletonWidthsStayInRange() {
        for (i in 0 until 50) assertTrue(skeletonWidth(i) in 0.44f..0.91f)
    }

    @Test
    fun tabletLayoutRules() {
        assertFalse(isWideScreen(839))
        assertTrue(isWideScreen(840))
        assertTrue(isReadingScreen("SETTINGS"))
        assertFalse(isReadingScreen("CHAT"))
        assertFalse(isReadingScreen("FILES"))
        assertTrue(READING_WIDTH_DP in 600..900)
    }

    @Test
    fun reduceMotionStringsAreTranslated() {
        val dir = File("src/main/assets/i18n")
        val required = listOf(
            "Уменьшить анимации",
            "Без переходов между экранами, пульсации и плавного появления текста. Включается и сама, если анимации выключены в системе.",
        )
        for (l in listOf("en", "de", "es", "zh", "ar")) {
            val obj = Json.parseToJsonElement(File(dir, "$l.json").readText()) as JsonObject
            for (k in required) assertTrue("Нет перевода в $l: $k", !(obj[k] as? JsonPrimitive)?.content.isNullOrBlank())
        }
    }
}
