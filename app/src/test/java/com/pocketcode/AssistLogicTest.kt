package com.pocketcode

import com.pocketcode.assist.AnswerKind
import com.pocketcode.assist.AssistAppMatch
import com.pocketcode.assist.AssistFlow
import com.pocketcode.assist.AssistHints
import com.pocketcode.assist.AssistIntent
import com.pocketcode.assist.AssistIntentParser
import com.pocketcode.assist.AssistMarkdown
import com.pocketcode.assist.AssistPrefs
import com.pocketcode.assist.AssistPrefsStore
import com.pocketcode.assist.AssistProgress
import com.pocketcode.assist.AssistText
import com.pocketcode.assist.AssistVoice
import com.pocketcode.assist.MdStyle
import com.pocketcode.assist.Phase
import com.pocketcode.assist.ProgressMode
import com.pocketcode.assist.ProgressTracker
import com.pocketcode.assist.ResultMode
import com.pocketcode.assist.SearchHit
import com.pocketcode.assist.StepKind
import com.pocketcode.phone.AppInfo
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

class AssistLogicTest {
    // ===== разбор запроса =====

    @Test
    fun emptyInputIsEmpty() {
        assertEquals(AssistIntent.Empty, AssistIntentParser.parse("   "))
    }

    @Test
    fun openAppCommand() {
        assertEquals(AssistIntent.OpenApp("ютуб"), AssistIntentParser.parse("Открой ютуб"))
        assertEquals(AssistIntent.OpenApp("телеграм"), AssistIntentParser.parse("пожалуйста, открой приложение телеграм"))
    }

    @Test
    fun longOpenPhraseIsAQuestion() {
        val r = AssistIntentParser.parse("открой рецепт борща пошагово")
        assertTrue(r is AssistIntent.Ask)
        assertFalse((r as AssistIntent.Ask).forceSearch)
    }

    @Test
    fun domainOpensUrl() {
        assertEquals(AssistIntent.OpenUrl("https://example.com"), AssistIntentParser.parse("example.com"))
        assertEquals(AssistIntent.OpenUrl("https://github.com/a/b"), AssistIntentParser.parse("открой github.com/a/b"))
        assertEquals(AssistIntent.OpenUrl("https://a.ru/x"), AssistIntentParser.parse("https://a.ru/x"))
    }

    @Test
    fun plainWordWithDotIsNotUrl() {
        assertNull(AssistIntentParser.asUrl("файл.docx"))
        assertNull(AssistIntentParser.asUrl("два слова.com"))
    }

    @Test
    fun searchCommandForcesSearch() {
        assertEquals(AssistIntent.Ask("курс доллара", true), AssistIntentParser.parse("найди в интернете курс доллара"))
        assertEquals(AssistIntent.Ask("погода в москве", true), AssistIntentParser.parse("Загугли погода в москве"))
        assertEquals(AssistIntent.Empty, AssistIntentParser.parse("найди"))
    }

    @Test
    fun ordinaryQuestionIsNotForced() {
        assertEquals(AssistIntent.Ask("сколько лететь до луны", false), AssistIntentParser.parse("сколько лететь до луны"))
    }

    // ===== как показывать ответ =====

    @Test
    fun answerPhaseFollowsMode() {
        assertEquals(Phase.OFFER, AssistFlow.afterAnswer(ResultMode.ASK, null))
        assertEquals(Phase.POPUP, AssistFlow.afterAnswer(ResultMode.POPUP, Phase.WORKING))
        assertEquals(Phase.FULLSCREEN, AssistFlow.afterAnswer(ResultMode.FULLSCREEN, null))
    }

    @Test
    fun followUpStaysInSameView() {
        assertEquals(Phase.FULLSCREEN, AssistFlow.afterAnswer(ResultMode.ASK, Phase.FULLSCREEN))
        assertEquals(Phase.POPUP, AssistFlow.afterAnswer(ResultMode.FULLSCREEN, Phase.POPUP))
    }

    @Test
    fun rememberChoiceOnlyWhenAsked() {
        val p = AssistPrefs()
        assertEquals(p, AssistFlow.rememberChoice(p, true, false))
        assertEquals(ResultMode.FULLSCREEN, AssistFlow.rememberChoice(p, true, true).resultMode)
        assertEquals(ResultMode.POPUP, AssistFlow.rememberChoice(p, false, true).resultMode)
    }

    @Test
    fun progressModesControlDetail() {
        assertFalse(AssistFlow.showHeadline(ProgressMode.MINIMAL))
        assertTrue(AssistFlow.showHeadline(ProgressMode.COMPACT))
        assertFalse(AssistFlow.showDetails(ProgressMode.COMPACT))
        assertTrue(AssistFlow.showDetails(ProgressMode.DETAILED))
    }

    @Test
    fun notifyOnlyWhenWindowIsClosed() {
        assertTrue(AssistFlow.shouldNotify(false, AssistPrefs()))
        assertFalse(AssistFlow.shouldNotify(true, AssistPrefs()))
        assertFalse(AssistFlow.shouldNotify(false, AssistPrefs(notifyWhenHidden = false)))
    }

    @Test
    fun lockedPhoneOpensNothing() {
        assertFalse(AssistFlow.canOpenOutside(true))
        assertTrue(AssistFlow.canOpenOutside(false))
    }

    // ===== настройки =====

    @Test
    fun prefsRoundTrip() {
        val mem = HashMap<String, String>()
        val store = AssistPrefsStore({ mem[it] }, { k, v -> mem[k] = v })
        assertEquals(AssistPrefs(), store.load())
        val p = AssistPrefs(ResultMode.FULLSCREEN, ProgressMode.DETAILED, AnswerKind.LINKS, false, false, false, "p1")
        store.save(p)
        assertEquals(p, store.load())
    }

    @Test
    fun unknownPrefValuesFallBackToDefaults() {
        val store = AssistPrefsStore({ "мусор" }, { _, _ -> })
        assertEquals(AssistPrefs(), store.load().copy(profileId = ""))
    }

    // ===== прогресс =====

    @Test
    fun trackerFollowsToolEvents() {
        val t = ProgressTracker()
        t.onToolCall("web_search", "поиск: погода")
        assertEquals(StepKind.SEARCH, t.steps.single().kind)
        assertEquals("Ищу: погода", t.steps.single().label)
        t.onToolResult("web_search", false)
        assertTrue(t.steps.single().done)
        t.onToolCall("web_fetch", "открыть https://www.example.com/a")
        assertEquals(listOf("https://www.example.com/a"), t.fetched)
        assertEquals("Читаю: example.com", t.steps.last().label)
        t.onToolResult("web_fetch", true)
        assertTrue(t.steps.last().failed)
        t.onText()
        assertTrue(t.writing)
        t.finish()
        assertFalse(t.writing)
    }

    @Test
    fun otherToolsAreIgnored() {
        val t = ProgressTracker()
        t.onToolCall("bash", "ls")
        assertTrue(t.steps.isEmpty())
    }

    @Test
    fun headlineChoosesCurrentStep() {
        assertEquals("Думаю…", AssistProgress.headline(emptyList(), false))
        assertEquals("Пишу ответ…", AssistProgress.headline(emptyList(), true))
        val s = AssistProgress.searchStep("котики")
        assertEquals("Ищу: котики", AssistProgress.headline(listOf(s), false))
        assertEquals("Разбираю найденное…", AssistProgress.headline(listOf(s.copy(done = true)), false))
    }

    @Test
    fun elapsedFormat() {
        assertEquals("0:07", AssistProgress.elapsed(7_400))
        assertEquals("2:05", AssistProgress.elapsed(125_000))
        assertEquals("0:00", AssistProgress.elapsed(-5))
    }

    // ===== выдача поиска =====

    @Test
    fun parseSearchOutput() {
        val out = "1. Первая\nhttps://a.com/x\nописание\n2. Вторая\nhttps://www.b.org\n3. Без ссылки\nнет"
        val hits = AssistText.parseSearch(out)
        assertEquals(2, hits.size)
        assertEquals(SearchHit("Первая", "https://a.com/x", "описание"), hits[0])
        assertEquals("", hits[1].snippet)
        assertEquals("b.org", hits[1].domain)
    }

    @Test
    fun mergeAndPickSources() {
        val a = listOf(SearchHit("A", "https://a.com/", ""), SearchHit("B", "https://b.com", ""))
        val b = listOf(SearchHit("A2", "https://A.com", ""), SearchHit("C", "https://c.com", ""))
        val m = AssistText.mergeHits(a, b)
        assertEquals(listOf("A", "B", "C"), m.map { it.title })
        // открытые страницы важнее остальных
        assertEquals(listOf("C"), AssistText.pickSources(m, listOf("https://c.com")).map { it.title })
        // не открывали ничего: лучшие результаты, не больше лимита
        assertEquals(2, AssistText.pickSources(m, emptyList(), 2).size)
        // открытая страница вне выдачи тоже источник
        assertEquals("zzz.net", AssistText.pickSources(m, listOf("https://zzz.net/p")).single().title)
    }

    @Test
    fun firstSentenceIsPlainAndShort() {
        assertEquals("Москва — столица России.", AssistText.firstSentence("**Москва** — столица России. Там живут люди.", 100))
        assertEquals("", AssistText.firstSentence("   ", 50))
        assertTrue(AssistText.firstSentence("а".repeat(500), 40).length <= 40)
    }

    @Test
    fun passwordFieldsAreDetected() {
        assertTrue(AssistText.isPasswordInputType(0x81)) // text password
        assertTrue(AssistText.isPasswordInputType(0x91)) // visible password
        assertTrue(AssistText.isPasswordInputType(0x12)) // number password
        assertFalse(AssistText.isPasswordInputType(0x01)) // обычный текст
    }

    @Test
    fun joinScreenDedupesAndLimits() {
        assertEquals("a b\nc", AssistText.joinScreen(listOf("a   b", "", "a b", "c"), 100))
        assertTrue(AssistText.joinScreen(listOf("12345", "67890"), 8).length <= 8)
    }

    // ===== разметка ответа =====

    @Test
    fun markdownBoldLinkAndList() {
        val r = AssistMarkdown.parse("# Заголовок\n- пункт **жирный**\n[сайт](https://a.com)")
        assertEquals("Заголовок\n• пункт жирный\nсайт", r.text)
        assertTrue(r.spans.any { it.style == MdStyle.HEADING })
        assertTrue(r.spans.any { it.style == MdStyle.BOLD })
        assertEquals("https://a.com", r.spans.first { it.style == MdStyle.LINK }.url)
    }

    @Test
    fun markdownCodeBlockKeepsText() {
        val r = AssistMarkdown.parse("```\nval x = 1\n```")
        assertEquals("val x = 1", r.text)
        assertTrue(r.spans.any { it.style == MdStyle.CODE })
    }

    // ===== голос =====

    @Test
    fun recognitionLanguage() {
        assertEquals("ru-RU", AssistVoice.languageTag("ru", "en-US"))
        assertEquals("en-GB", AssistVoice.languageTag("en", "en-GB"))
        assertEquals("de-DE", AssistVoice.languageTag("de", "fr-FR"))
    }

    @Test
    fun recognizerSkipsOurselves() {
        val own = "com.pocketcode"
        val c = listOf("com.pocketcode/.assist.PocketRecognitionService", "com.other/.R", "com.google.android.googlequicksearchbox/.G")
        assertEquals("com.google.android.googlequicksearchbox/.G", AssistVoice.chooseRecognizer(c, null, own))
        assertEquals("com.other/.R", AssistVoice.chooseRecognizer(c, "com.other/.R", own))
        assertEquals("com.google.android.googlequicksearchbox/.G", AssistVoice.chooseRecognizer(c, "com.pocketcode/.assist.PocketRecognitionService", own))
        assertNull(AssistVoice.chooseRecognizer(listOf("com.pocketcode/.X"), null, own))
    }

    @Test
    fun everySpeechErrorHasText() {
        for (code in 0..12) assertTrue(AssistVoice.errorText(code).isNotBlank())
    }

    // ===== подсказки и поиск приложения =====

    @Test
    fun settingsPathDependsOnMaker() {
        assertTrue(AssistHints.settingsPath("samsung").contains("Выбор приложений по умолчанию"))
        assertTrue(AssistHints.settingsPath("Xiaomi").contains("Помощник"))
        assertTrue(AssistHints.settingsPath("unknown").contains("Цифровой помощник"))
    }

    @Test
    fun translitAndAliases() {
        assertEquals("telegram", AssistAppMatch.translit("телеграм"))
        assertTrue(AssistAppMatch.variants("ютуб").size >= 2)
        assertTrue(AssistAppMatch.variants("  ").isEmpty())
    }

    @Test
    fun findsAppByRussianName() {
        val apps = listOf(AppInfo("org.telegram.messenger", "Telegram", false), AppInfo("com.android.settings", "Settings", true))
        assertEquals("org.telegram.messenger", AssistAppMatch.find(apps, "телеграм")?.pkg)
        assertNull(AssistAppMatch.find(apps, "несуществующее приложение"))
        assertNotNull(AssistAppMatch.find(apps, "Telegram"))
    }

    // ===== переводы новых строк =====

    @Test
    fun everyAssistStringIsTranslated() {
        val root = File("src/main/java/com/pocketcode")
        val files = File(root, "assist").listFiles { f -> f.name.endsWith(".kt") }.orEmpty().toList() + File(root, "ui/AssistScreen.kt")
        // эти файлы содержат слова для разбора речи и текст для нейросети, а не строки интерфейса
        val notUi = setOf("AssistAppMatch.kt", "AssistIntent.kt", "AssistPrompt.kt")
        val cyr = Regex("[А-Яа-яЁё]")
        val lit = Regex("\"((?:[^\"\\\\\\n]|\\\\.)*)\"")
        val dir = File("src/main/assets/i18n")
        for (l in listOf("en", "de", "es", "zh", "ar")) {
            val m = (Json.parseToJsonElement(File(dir, "$l.json").readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            for (f in files) {
                if (f.name in notUi) continue
                for (mt in lit.findAll(f.readText())) {
                    val s = mt.groupValues[1]
                    if (!cyr.containsMatchIn(s) || s == "ошибка запроса: ") continue
                    val key = s.replace(Regex("\\$\\{[^}]*\\}|\\$\\w+"), "{0}")
                    assertTrue("Нет перевода «$key» в $l (${f.name})", !m[key].isNullOrBlank())
                }
            }
        }
    }
}
