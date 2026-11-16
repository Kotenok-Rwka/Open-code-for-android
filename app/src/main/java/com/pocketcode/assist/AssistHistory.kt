package com.pocketcode.assist

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Одна запись истории: вопрос, готовый ответ и найденные страницы. Хранится только на телефоне. */
@Serializable
data class HistoryEntry(
    val time: Long,
    val query: String,
    val answer: String = "",
    val linksOnly: Boolean = false,
    val sources: List<HistorySource> = emptyList(),
)

@Serializable
data class HistorySource(val title: String, val url: String, val snippet: String = "")

object AssistHistory {
    const val MAX_ENTRIES = 40
    const val MAX_ANSWER = 3500
    private const val MAX_SOURCES = 8

    private val json = Json { ignoreUnknownKeys = true }

    /** Запись из готового результата запроса. */
    fun entryOf(time: Long, query: String, answer: String, linksOnly: Boolean, sources: List<SearchHit>): HistoryEntry = HistoryEntry(
        time = time,
        query = query.trim(),
        answer = answer.trim().take(MAX_ANSWER),
        linksOnly = linksOnly,
        sources = sources.take(MAX_SOURCES).map { HistorySource(it.title, it.url, it.snippet) },
    )

    /** Добавляет запись в начало; тот же вопрос, заданный ещё раз, заменяет прежнюю запись, лишнее старое отбрасывается. */
    fun add(list: List<HistoryEntry>, e: HistoryEntry, max: Int = MAX_ENTRIES): List<HistoryEntry> {
        if (e.query.isBlank()) return list
        val key = e.query.trim().lowercase()
        return (listOf(e) + list.filter { it.query.trim().lowercase() != key }).take(max)
    }

    fun remove(list: List<HistoryEntry>, time: Long): List<HistoryEntry> = list.filter { it.time != time }

    fun encode(list: List<HistoryEntry>): String = json.encodeToString(list)

    /** Повреждённые данные не должны ломать окно: вместо них пустая история. */
    fun decode(s: String?): List<HistoryEntry> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<HistoryEntry>>(s)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Результат запроса из записи: так сохранённый ответ открывается тем же окном. */
    fun toState(e: HistoryEntry): AssistRunner.State {
        val hits = e.sources.map { SearchHit(it.title, it.url, it.snippet) }
        return AssistRunner.State(
            status = AssistRunner.Status.DONE, query = e.query, answer = e.answer, hits = hits, sources = hits,
            linksOnly = e.linksOnly, startedAt = e.time, finishedAt = e.time,
        )
    }
}

/** Хранит историю в настройках приложения; чтение и запись передаются функциями ради теста. */
class AssistHistoryStore(private val get: (String) -> String?, private val put: (String, String) -> Unit) {
    fun list(): List<HistoryEntry> = AssistHistory.decode(get(KEY))

    fun add(e: HistoryEntry) = put(KEY, AssistHistory.encode(AssistHistory.add(list(), e)))

    fun remove(time: Long) = put(KEY, AssistHistory.encode(AssistHistory.remove(list(), time)))

    fun clear() = put(KEY, "[]")

    companion object {
        const val KEY = "assistHistory"
    }
}
