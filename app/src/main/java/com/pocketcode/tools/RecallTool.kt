package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Поиск по заметкам проекта и их удаление (идея memory из AndroidHarness, код свой). */
class RecallTool(private val ws: Workspace) : Tool {
    override val name = "recall"
    override val description = "Найти заметки по слову: query, category (необязательно). Без query — показать все. Для удаления: forget=true и query — точная строка."
    override val schema = objSchema(
        Prop("query", "string", "Слово или точная строка заметки", required = false),
        Prop("category", "string", "Фильтр по категории", required = false),
        Prop("forget", "boolean", "Удалить точную заметку", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "поиск по заметкам: " + (input.str("query") ?: "все")

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val file = File(ws.root, ".pocketcode/NOTES.md")
        if (!file.isFile) return@withContext "Заметок пока нет."
        val lines = file.readLines().filter { it.isNotBlank() }
        val query = input.str("query")?.trim().orEmpty()
        val category = input.str("category")?.trim().orEmpty()
        val forget = input.bool("forget") ?: false
        if (forget && query.isNotBlank()) {
            val kept = lines.filterNot { it.contains(query, ignoreCase = true) }
            val removed = lines.size - kept.size
            file.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n")
            return@withContext "Удалено заметок: $removed"
        }
        val found = lines.filter { line ->
            (query.isBlank() || line.contains(query, ignoreCase = true)) &&
                (category.isBlank() || line.contains("[$category]", ignoreCase = true))
        }
        if (found.isEmpty()) "Ничего не найдено." else "Заметки (${found.size}):\n" + found.joinToString("\n") { "- $it" }
    }
}
