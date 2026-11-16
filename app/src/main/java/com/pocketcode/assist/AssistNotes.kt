package com.pocketcode.assist

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Заметка, которую ассистент записал по команде «запомни…» или «запиши в заметку…». Хранится только на телефоне. */
@Serializable
data class Note(val time: Long, val text: String)

object AssistNotes {
    const val MAX_NOTES = 100
    private val json = Json { ignoreUnknownKeys = true }

    /** Новая заметка идёт первой; лишние старые отбрасываются. */
    fun add(list: List<Note>, n: Note, max: Int = MAX_NOTES): List<Note> {
        if (n.text.isBlank()) return list
        return (listOf(n) + list).take(max)
    }

    fun encode(list: List<Note>): String = json.encodeToString(list)

    fun decode(s: String?): List<Note> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<Note>>(s)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Список для ответа: последние заметки, по строке на каждую. [fmt] превращает время в подпись. */
    fun listText(list: List<Note>, limit: Int = 15, fmt: (Long) -> String): String =
        list.take(limit).joinToString("\n") { "- " + fmt(it.time) + " — " + AssistLocal.noteLine(it.text) } +
            if (list.size > limit) "\n…" else ""
}

class AssistNotesStore(private val get: (String) -> String?, private val put: (String, String) -> Unit) {
    fun list(): List<Note> = AssistNotes.decode(get(KEY))

    fun add(n: Note) = put(KEY, AssistNotes.encode(AssistNotes.add(list(), n)))

    fun clear() = put(KEY, "[]")

    companion object {
        const val KEY = "assistNotes"
    }
}
