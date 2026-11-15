package com.pocketcode.assist

enum class StepKind { SEARCH, READ }

/** Один шаг работы ассистента: поиск в интернете или чтение страницы. */
data class Step(val kind: StepKind, val label: String, val done: Boolean = false, val failed: Boolean = false)

object AssistProgress {
    const val TOOL_SEARCH = "web_search"
    const val TOOL_FETCH = "web_fetch"

    fun searchStep(query: String): Step {
        val q = query.trim().take(60)
        return Step(StepKind.SEARCH, "Ищу: $q")
    }

    /** Шаг по вызову инструмента; [description] — то, что вернул Tool.describe («поиск: запрос», «открыть адрес»). */
    fun stepFor(tool: String, description: String): Step? = when (tool) {
        TOOL_SEARCH -> searchStep(description.substringAfter(':').trim())
        TOOL_FETCH -> {
            val site = AssistText.domain(fetchUrl(description))
            Step(StepKind.READ, "Читаю: $site")
        }
        else -> null
    }

    /** Адрес из описания вызова web_fetch. */
    fun fetchUrl(description: String): String = description.substringAfter(' ').trim()

    /** Главная строка для маленького окна. */
    fun headline(steps: List<Step>, writing: Boolean): String {
        if (writing) return "Пишу ответ…"
        val active = steps.lastOrNull { !it.done }
        if (active != null) return active.label
        return if (steps.isEmpty()) "Думаю…" else "Разбираю найденное…"
    }

    /** Время в виде «0:07» (минуты:секунды), всегда арабскими цифрами. */
    fun elapsed(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }
}

/** Копит шаги по событиям агента. */
class ProgressTracker {
    private val list = ArrayList<Step>()
    private val read = ArrayList<String>()

    var writing: Boolean = false
        private set

    val steps: List<Step> get() = list.toList()

    /** Адреса страниц, которые ассистент открывал. */
    val fetched: List<String> get() = read.toList()

    fun onToolCall(tool: String, description: String) {
        writing = false
        val st = AssistProgress.stepFor(tool, description) ?: return
        list += st
        if (tool == AssistProgress.TOOL_FETCH) read += AssistProgress.fetchUrl(description)
    }

    fun onToolResult(tool: String, isError: Boolean) {
        val kind = if (tool == AssistProgress.TOOL_SEARCH) StepKind.SEARCH else StepKind.READ
        val idx = list.indexOfLast { !it.done && it.kind == kind }
        if (idx >= 0) list[idx] = list[idx].copy(done = true, failed = isError)
    }

    fun onText() {
        writing = true
    }

    fun finish() {
        for (i in list.indices) if (!list[i].done) list[i] = list[i].copy(done = true)
        writing = false
    }
}
