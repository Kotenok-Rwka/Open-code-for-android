package com.pocketcode.assist

import android.content.Context
import com.pocketcode.AppLog
import com.pocketcode.SettingsStore
import com.pocketcode.agent.Agent
import com.pocketcode.agent.AgentEvent
import com.pocketcode.tools.WebFetchTool
import com.pocketcode.tools.WebSearchTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * Выполняет запросы ассистента. Живёт отдельно от окна: если окно закрыли, поиск продолжается и ответ приходит уведомлением.
 * Один разговор на всё время, пока окно не открыли заново (так работают уточнения: «а дальше?», «короче»).
 */
object AssistRunner {
    enum class Status { IDLE, RUNNING, DONE, ERROR }

    data class State(
        val status: Status = Status.IDLE,
        /** Номер запроса: поздние ответы отменённого запроса не должны затирать новый. */
        val runId: Int = 0,
        val query: String = "",
        val steps: List<Step> = emptyList(),
        val writing: Boolean = false,
        /** Ответ модели; пока идёт запрос, это уже написанная часть. */
        val answer: String = "",
        /** Всё, что нашёл поиск. */
        val hits: List<SearchHit> = emptyList(),
        /** Страницы, на которые опирался ответ (или список ссылок в режиме «только ссылки»). */
        val sources: List<SearchHit> = emptyList(),
        val linksOnly: Boolean = false,
        /** Ключ API не задан: вместо ответа показан список ссылок. */
        val noKey: Boolean = false,
        val error: String? = null,
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
    )

    private const val EMIT_EVERY_MS = 70L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow.asStateFlow()

    /** Окно ассистента сейчас на экране. */
    @Volatile var sessionVisible: Boolean = false

    /** Ответ готов, пока окна не было: при следующем открытии показываем его сразу. */
    @Volatile var revealPending: Boolean = false

    private var job: Job? = null
    private var agent: Agent? = null
    private var agentKey: String = ""
    private var counter = 0
    private var store: SettingsStore? = null

    @Synchronized
    private fun settingsStore(app: Context): SettingsStore = store ?: SettingsStore(app).also { store = it }

    fun consumeReveal(): Boolean {
        val r = revealPending
        revealPending = false
        return r
    }

    /** Следующий вопрос начнёт новый разговор. */
    fun newConversation() {
        agent = null
    }

    /** Возвращает в исходное состояние (кроме идущего запроса). */
    fun reset() {
        if (stateFlow.value.status == Status.RUNNING) return
        stateFlow.value = State(runId = counter)
    }

    /** Останавливает запрос; введённый текст остаётся, окно возвращается к вводу. */
    fun cancel() {
        job?.cancel()
        job = null
        agent = null
        stateFlow.update { if (it.status == Status.RUNNING) it.copy(status = Status.IDLE, writing = false) else it }
    }

    fun start(context: Context, query: String, forceSearch: Boolean, screenText: String?, pageUrl: String?, prefs: AssistPrefs) {
        val q = query.trim()
        if (q.isEmpty()) return
        val app = context.applicationContext
        job?.cancel()
        val id = ++counter
        stateFlow.value = State(status = Status.RUNNING, runId = id, query = q, startedAt = System.currentTimeMillis())
        job = scope.launch {
            try {
                val ready = if (prefs.answerKind == AnswerKind.AI) AssistBackend.prepare(settingsStore(app), prefs.profileId) else null
                if (ready == null) {
                    runLinks(id, q, noKey = prefs.answerKind == AnswerKind.AI)
                } else {
                    runAgent(id, ready, q, forceSearch, screenText, pageUrl, prefs.depth)
                }
                finished(app, id, q, prefs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.log("ASSIST", "ошибка запроса: " + (e.message ?: e.javaClass.simpleName))
                agent = null
                update(id) { it.copy(status = Status.ERROR, error = e.message ?: e.javaClass.simpleName, writing = false, finishedAt = System.currentTimeMillis()) }
            }
        }
    }

    private fun update(id: Int, f: (State) -> State) {
        stateFlow.update { if (it.runId == id) f(it) else it }
    }

    /** Режим «только ссылки» или нет ключа: обычный поиск без нейросети. */
    private suspend fun runLinks(id: Int, q: String, noKey: Boolean) {
        update(id) { it.copy(steps = listOf(AssistProgress.searchStep(q)), noKey = noKey) }
        val out = WebSearchTool().run(buildJsonObject { put("query", q) })
        val hits = AssistText.parseSearch(out)
        update(id) {
            it.copy(
                status = Status.DONE, steps = it.steps.map { s -> s.copy(done = true) }, hits = hits, sources = hits.take(8),
                linksOnly = true, finishedAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun runAgent(id: Int, ready: AssistBackend.Ready, q: String, forceSearch: Boolean, screenText: String?, pageUrl: String?, depth: AnswerDepth) {
        val current = agent
        val key = ready.key + "|" + depth.key
        val ag = if (current != null && agentKey == key) current else {
            val now = java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            Agent(
                llm = ready.llm,
                tools = listOf(WebSearchTool(), WebFetchTool()),
                system = AssistPrompt.system(now, java.util.TimeZone.getDefault().id, com.pocketcode.ui.I18n.code, depth),
                autoApprove = true,
                contextLimit = ready.contextLimit,
                approve = { _, _ -> true },
                maxSteps = depth.maxSteps,
                fallbackLlm = ready.fallback,
            ).also { agent = it; agentKey = key }
        }
        val tracker = ProgressTracker()
        val cur = StringBuilder()
        var hits: List<SearchHit> = emptyList()
        var failure: String? = null
        var stepLimit = false
        var lastEmit = 0L
        fun push() {
            update(id) { it.copy(steps = tracker.steps, writing = tracker.writing, answer = cur.toString(), hits = hits) }
        }
        ag.execute(AssistPrompt.userMessage(q, forceSearch, screenText, pageUrl)).collect { ev ->
            when (ev) {
                is AgentEvent.TextDelta -> {
                    cur.append(ev.text)
                    tracker.onText()
                    val t = System.currentTimeMillis()
                    if (t - lastEmit > EMIT_EVERY_MS) {
                        lastEmit = t
                        push()
                    }
                }
                is AgentEvent.ToolCall -> {
                    // текст перед вызовом инструмента («сейчас поищу…») в ответ не входит
                    cur.setLength(0)
                    tracker.onToolCall(ev.name, ev.description)
                    push()
                }
                is AgentEvent.ToolResult -> {
                    tracker.onToolResult(ev.name, ev.isError)
                    if (ev.name == AssistProgress.TOOL_SEARCH && !ev.isError) hits = AssistText.mergeHits(hits, AssistText.parseSearch(ev.output))
                    push()
                }
                is AgentEvent.Error -> failure = ev.message
                is AgentEvent.StepLimit -> stepLimit = true
                else -> {}
            }
        }
        tracker.finish()
        val text = cur.toString().trim()
        val fail = failure
        when {
            fail != null -> {
                agent = null
                update(id) { it.copy(status = Status.ERROR, error = fail, steps = tracker.steps, writing = false, answer = text, hits = hits, finishedAt = System.currentTimeMillis()) }
            }
            text.isEmpty() -> {
                agent = null
                val msg = if (stepLimit) "Не удалось найти ответ за отведённое число шагов. Попробуй уточнить вопрос." else "Модель вернула пустой ответ."
                update(id) { it.copy(status = Status.ERROR, error = msg, steps = tracker.steps, writing = false, hits = hits, finishedAt = System.currentTimeMillis()) }
            }
            else -> {
                val sources = AssistText.pickSources(hits, tracker.fetched, 4)
                update(id) {
                    it.copy(status = Status.DONE, steps = tracker.steps, writing = false, answer = text, hits = hits, sources = sources, finishedAt = System.currentTimeMillis())
                }
            }
        }
    }

    /** Ответ готов: если окно закрыто, сообщаем уведомлением. */
    private fun finished(app: Context, id: Int, q: String, prefs: AssistPrefs) {
        val s = stateFlow.value
        if (s.runId != id || s.status != Status.DONE) return
        if (AssistFlow.shouldNotify(sessionVisible, prefs)) {
            revealPending = true
            val body = if (s.linksOnly) s.sources.joinToString("\n") { it.title } else s.answer
            AssistNotifier.result(app, q, body)
        }
    }
}
