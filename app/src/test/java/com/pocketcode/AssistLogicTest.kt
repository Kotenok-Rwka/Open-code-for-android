package com.pocketcode

import com.pocketcode.assist.AnswerDepth
import com.pocketcode.assist.AnswerKind
import com.pocketcode.assist.AssistProfile
import com.pocketcode.assist.AssistProfileStore
import com.pocketcode.assist.AssistPrompt
import com.pocketcode.assist.AssistAppMatch
import com.pocketcode.assist.AssistFlow
import com.pocketcode.assist.AssistHistory
import com.pocketcode.assist.AssistHistoryStore
import com.pocketcode.assist.AssistRunner
import com.pocketcode.assist.AssistHints
import com.pocketcode.assist.AssistIntent
import com.pocketcode.assist.AssistIntentParser
import com.pocketcode.assist.AssistLocal
import com.pocketcode.assist.AssistNotes
import com.pocketcode.assist.AssistNotesStore
import com.pocketcode.assist.AssistSpeak
import com.pocketcode.assist.CalcResult
import com.pocketcode.assist.LocalCommand
import com.pocketcode.assist.Note
import com.pocketcode.assist.SpeakMode
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
        val r = AssistIntentParser.parse("открой рецепт борща с мясом пошагово")
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

    @Test
    fun depthIsStoredAndLimitsSteps() {
        val mem = HashMap<String, String>()
        val store = AssistPrefsStore({ mem[it] }, { k, v -> mem[k] = v })
        store.save(AssistPrefs(depth = AnswerDepth.DEEP))
        assertEquals(AnswerDepth.DEEP, store.load().depth)
        assertEquals(AnswerDepth.NORMAL, AnswerDepth.parse("что-то"))
        assertTrue(AnswerDepth.FAST.maxSteps < AnswerDepth.NORMAL.maxSteps)
        assertTrue(AnswerDepth.NORMAL.maxSteps < AnswerDepth.DEEP.maxSteps)
    }

    @Test
    fun promptMentionsDepthOnlyWhenNotNormal() {
        val normal = AssistPrompt.system("сегодня", "UTC", "ru")
        assertEquals(normal, AssistPrompt.system("сегодня", "UTC", "ru", AnswerDepth.NORMAL))
        assertTrue(AssistPrompt.system("сегодня", "UTC", "ru", AnswerDepth.FAST).contains("быстрого ответа"))
        assertTrue(AssistPrompt.system("сегодня", "UTC", "ru", AnswerDepth.DEEP).contains("подробного ответа"))
        assertFalse(normal.contains("Режим"))
    }

    @Test
    fun newSwitchesDefaultOnAndRoundTrip() {
        val d = AssistPrefs()
        assertTrue(d.autoListen && d.popupWhenReady && d.confirmClose)
        val mem = HashMap<String, String>()
        val store = AssistPrefsStore({ mem[it] }, { k, v -> mem[k] = v })
        store.save(d.copy(autoListen = false, popupWhenReady = false, confirmClose = false))
        val back = store.load()
        assertFalse(back.autoListen || back.popupWhenReady || back.confirmClose)
    }

    @Test
    fun popupOnlyWhenWindowIsClosed() {
        assertTrue(AssistFlow.shouldPopup(false, AssistPrefs()))
        assertFalse(AssistFlow.shouldPopup(true, AssistPrefs()))
        assertFalse(AssistFlow.shouldPopup(false, AssistPrefs(popupWhenReady = false)))
    }

    @Test
    fun closeConfirmOnlyForReadyAnswer() {
        val p = AssistPrefs()
        for (ph in listOf(Phase.OFFER, Phase.POPUP, Phase.FULLSCREEN)) assertTrue(AssistFlow.needsCloseConfirm(ph, p))
        for (ph in listOf(Phase.INPUT, Phase.WORKING, Phase.ERROR)) assertFalse(AssistFlow.needsCloseConfirm(ph, p))
        assertFalse(AssistFlow.needsCloseConfirm(Phase.POPUP, p.copy(confirmClose = false)))
    }

    // ===== «О себе» =====

    @Test
    fun profileIsEmptyUntilEnabledAndFilled() {
        assertEquals("", AssistProfile().toPrompt())
        assertEquals("", AssistProfile(enabled = true).toPrompt())
        assertEquals("", AssistProfile(enabled = false, city = "Казань").toPrompt())
    }

    @Test
    fun profilePromptListsOnlyFilledFields() {
        val t = AssistProfile(enabled = true, name = "Аня", city = "  Казань\n", style = "коротко").toPrompt()
        assertTrue(t.contains("- Как обращаться: Аня"))
        assertTrue(t.contains("- Город: Казань"))
        assertTrue(t.contains("- Как отвечать: коротко"))
        assertFalse(t.contains("Интересы"))
        assertFalse(t.contains("Чем занимается"))
    }

    @Test
    fun profileFieldsAreCleanedAndLimited() {
        val t = AssistProfile(enabled = true, interests = "а\n\nб   в", extra = "я".repeat(5000)).toPrompt()
        assertTrue(t.contains("- Интересы: а б в"))
        val extraLine = t.lines().first { it.startsWith("- Ещё: ") }
        assertEquals(AssistProfile.MAX_EXTRA, extraLine.removePrefix("- Ещё: ").length)
    }

    @Test
    fun profileRoundTripAndPromptSection() {
        val mem = HashMap<String, String>()
        val store = AssistProfileStore({ mem[it] }, { k, v -> mem[k] = v })
        assertEquals(AssistProfile(), store.load())
        val p = AssistProfile(true, "Аня", "Казань", "дизайнер", "кино", "коротко", "люблю чай")
        store.save(p)
        assertEquals(p, store.load())
        val sys = AssistPrompt.system("сегодня", "UTC", "ru", AnswerDepth.NORMAL, p.toPrompt())
        assertTrue(sys.contains("О пользователе"))
        assertFalse(AssistPrompt.system("сегодня", "UTC", "ru").contains("О пользователе"))
    }

    // ===== история =====

    private fun entry(t: Long, q: String) = AssistHistory.entryOf(t, q, "ответ " + q, false, listOf(SearchHit("T", "https://a.com", "s")))

    @Test
    fun historyNewestFirstAndDeduped() {
        var l = emptyList<com.pocketcode.assist.HistoryEntry>()
        l = AssistHistory.add(l, entry(1, "погода"))
        l = AssistHistory.add(l, entry(2, "курс"))
        l = AssistHistory.add(l, entry(3, "  Погода "))
        assertEquals(listOf("Погода", "курс"), l.map { it.query })
        assertEquals(3L, l.first().time)
    }

    @Test
    fun historyIsLimitedAndIgnoresBlank() {
        var l = emptyList<com.pocketcode.assist.HistoryEntry>()
        for (i in 1..50) l = AssistHistory.add(l, entry(i.toLong(), "запрос $i"))
        assertEquals(AssistHistory.MAX_ENTRIES, l.size)
        assertEquals("запрос 50", l.first().query)
        assertEquals(l, AssistHistory.add(l, entry(99, "   ")))
        assertEquals(AssistHistory.MAX_ANSWER, AssistHistory.entryOf(1, "q", "я".repeat(9000), false, emptyList()).answer.length)
    }

    @Test
    fun historyRoundTripAndBrokenData() {
        val mem = HashMap<String, String>()
        val st = AssistHistoryStore({ mem[it] }, { k, v -> mem[k] = v })
        assertTrue(st.list().isEmpty())
        st.add(entry(1, "один"))
        st.add(entry(2, "два"))
        assertEquals(listOf("два", "один"), st.list().map { it.query })
        assertEquals("https://a.com", st.list().first().sources.single().url)
        st.remove(2)
        assertEquals(listOf("один"), st.list().map { it.query })
        st.clear()
        assertTrue(st.list().isEmpty())
        mem[AssistHistoryStore.KEY] = "{не json"
        assertTrue(st.list().isEmpty())
    }

    @Test
    fun savedEntryOpensAsFinishedResult() {
        val s = AssistHistory.toState(entry(5, "вопрос"))
        assertEquals(AssistRunner.Status.DONE, s.status)
        assertEquals("вопрос", s.query)
        assertEquals("ответ вопрос", s.answer)
        assertEquals(1, s.sources.size)
    }

    // ===== индикатор работы =====

    @Test
    fun indicatorOnlyWhileRunningAndWindowClosed() {
        assertTrue(AssistFlow.indicatorActive(AssistRunner.Status.RUNNING, false))
        assertFalse(AssistFlow.indicatorActive(AssistRunner.Status.RUNNING, true))
        assertFalse(AssistFlow.indicatorActive(AssistRunner.Status.DONE, false))
        assertFalse(AssistFlow.indicatorActive(AssistRunner.Status.IDLE, false))
    }

    @Test
    fun historyAndOverlayPrefsRoundTrip() {
        assertTrue(AssistPrefs().saveHistory && AssistPrefs().overlayIndicator)
        val mem = HashMap<String, String>()
        val store = AssistPrefsStore({ mem[it] }, { k, v -> mem[k] = v })
        store.save(AssistPrefs(saveHistory = false, overlayIndicator = false))
        val b = store.load()
        assertFalse(b.saveHistory || b.overlayIndicator)
    }

    // ===== простые команды без нейросети =====

    private fun calc(q: String, comma: Boolean = false): String? {
        val cmd = AssistLocal.parse(q) as? LocalCommand.Calc ?: return null
        return (AssistLocal.calc(cmd.expr, comma) as? CalcResult.Ok)?.text
    }

    @Test
    fun calculatorBasics() {
        assertEquals("8", calc("2+2*3"))
        assertEquals("20", calc("(2+3)*4"))
        assertEquals("0.3", calc("0.1+0.2"))
        assertEquals("0,3", calc("0,1 + 0,2", comma = true))
        assertEquals("512", calc("2^3^2"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("4", calc("sqrt(16)"))
    }

    @Test
    fun calculatorUnderstandsWords() {
        assertEquals("30", calc("сколько будет 15% от 200"))
        assertEquals("30", calc("сколько будет 15 процентов от 200"))
        assertEquals("12", calc("посчитай 3 умножить на 4"))
        assertEquals("7", calc("вычисли 10 минус 3"))
        assertEquals("5", calc("сколько будет 20 разделить на 4"))
        assertEquals("25", calc("посчитай 5 в квадрате"))
        assertEquals("9", calc("сколько будет корень из 81"))
        assertEquals("6", calc("2 x 3"))
        assertEquals("6", calc("2 × 3"))
        assertEquals("3.5", calc("what is 7/2"))
    }

    @Test
    fun percentAddsToTheLeftOperand() {
        assertEquals("220", calc("200+10%"))
        assertEquals("180", calc("200-10%"))
        assertEquals("0.1", calc("10%"))
    }

    @Test
    fun divisionByZeroIsReportedNotCrashed() {
        val cmd = AssistLocal.parse("5/0") as LocalCommand.Calc
        assertEquals(CalcResult.DivZero, AssistLocal.calc(cmd.expr))
        assertEquals(CalcResult.Bad, AssistLocal.calc("sqrt(-4)"))
        assertEquals(CalcResult.Bad, AssistLocal.calc("2++"))
        assertEquals(CalcResult.Bad, AssistLocal.calc("1.2.3+1"))
    }

    @Test
    fun ordinaryTextIsNotCalculator() {
        for (q in listOf("2024", "погода в москве", "сколько стоит айфон", "8-900-123-45-67", "+7 900 123 45 67", "16.11.2026", "16/11/2026", "3:2", "-5", "3.5", "привет")) {
            assertNull("«$q» не пример", AssistLocal.parse(q).takeIf { it is LocalCommand.Calc })
        }
    }

    @Test
    fun timeAndDateQuestions() {
        assertEquals(LocalCommand.Time, AssistLocal.parse("Который час?"))
        assertEquals(LocalCommand.Time, AssistLocal.parse("сколько сейчас времени"))
        assertEquals(LocalCommand.Date, AssistLocal.parse("какое сегодня число?"))
        assertEquals(LocalCommand.Date, AssistLocal.parse("Какой сегодня день"))
        assertNull(AssistLocal.parse("который час в токио"))
    }

    @Test
    fun noteCommands() {
        assertEquals(LocalCommand.SaveNote("Ключи в ящике"), AssistLocal.parse("запомни, что ключи в ящике"))
        assertEquals(LocalCommand.SaveNote("Купить молоко"), AssistLocal.parse("Запиши в заметку купить молоко"))
        assertEquals(LocalCommand.SaveNote("Позвонить маме"), AssistLocal.parse("пожалуйста, добавь заметку позвонить маме"))
        assertEquals(LocalCommand.SaveNote("Buy milk"), AssistLocal.parse("remember that buy milk"))
        assertEquals(LocalCommand.ShowNotes, AssistLocal.parse("покажи заметки"))
        assertEquals(LocalCommand.ShowNotes, AssistLocal.parse("Мои заметки"))
        assertNull(AssistLocal.parse("запомни"))
        assertNull(AssistLocal.parse("запиши видео экрана"))
    }

    @Test
    fun notesStoreKeepsNewestFirst() {
        val mem = HashMap<String, String>()
        val st = AssistNotesStore({ mem[it] }, { k, v -> mem[k] = v })
        assertTrue(st.list().isEmpty())
        st.add(Note(1, "первая"))
        st.add(Note(2, "вторая"))
        st.add(Note(3, "   "))
        assertEquals(listOf("вторая", "первая"), st.list().map { it.text })
        assertEquals("- t2 — вторая\n- t1 — первая", AssistNotes.listText(st.list()) { "t$it" })
        var l = emptyList<Note>()
        for (i in 1..120) l = AssistNotes.add(l, Note(i.toLong(), "n$i"))
        assertEquals(AssistNotes.MAX_NOTES, l.size)
        st.clear()
        assertTrue(st.list().isEmpty())
        mem[AssistNotesStore.KEY] = "ой"
        assertTrue(st.list().isEmpty())
    }

    // ===== озвучка =====

    @Test
    fun speechTextHasNoMarkupLinksOrSources() {
        val chunks = AssistSpeak.prepare("**Москва** — столица. Смотри https://a.com/x тут.\n- пункт один\n\nИсточники:\n[Сайт](https://a.com)")
        val all = chunks.joinToString(" ")
        assertTrue(all.contains("Москва — столица."))
        assertTrue(all.contains("пункт один"))
        assertFalse(all.contains("http"))
        assertFalse(all.contains("*"))
        assertFalse(all.contains("Источники"))
        assertFalse(all.contains("Сайт"))
    }

    @Test
    fun speechChunksAreShortAndLimited() {
        val long = (1..400).joinToString(" ") { "Предложение номер $it." }
        val chunks = AssistSpeak.prepare(long)
        assertTrue(chunks.all { it.length <= AssistSpeak.MAX_CHUNK })
        assertTrue(chunks.sumOf { it.length } <= AssistSpeak.MAX_TOTAL + chunks.size)
        assertTrue(AssistSpeak.prepare("я".repeat(2000)).all { it.length <= AssistSpeak.MAX_CHUNK })
        assertTrue(AssistSpeak.prepare("   ").isEmpty())
    }

    @Test
    fun speakModeDecidesWhenToRead() {
        assertFalse(AssistFlow.shouldSpeak(SpeakMode.OFF, true))
        assertTrue(AssistFlow.shouldSpeak(SpeakMode.VOICE, true))
        assertFalse(AssistFlow.shouldSpeak(SpeakMode.VOICE, false))
        assertTrue(AssistFlow.shouldSpeak(SpeakMode.ALWAYS, false))
        assertEquals(SpeakMode.VOICE, SpeakMode.parse("что-то"))
        assertEquals(SpeakMode.VOICE, AssistPrefs().speakMode)
        assertTrue(AssistPrefs().localCommands)
    }

    @Test
    fun speakAndLocalPrefsRoundTrip() {
        val mem = HashMap<String, String>()
        val store = AssistPrefsStore({ mem[it] }, { k, v -> mem[k] = v })
        store.save(AssistPrefs(speakMode = SpeakMode.ALWAYS, localCommands = false))
        val b = store.load()
        assertEquals(SpeakMode.ALWAYS, b.speakMode)
        assertFalse(b.localCommands)
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
        val notUi = setOf("AssistAppMatch.kt", "AssistIntent.kt", "AssistPrompt.kt", "AssistProfile.kt", "AssistLocal.kt", "AssistSpeak.kt", "AssistNotes.kt")
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
