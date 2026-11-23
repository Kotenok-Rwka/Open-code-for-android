package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Лёгкий «RAG без моделей»: ищет по проекту и возвращает короткие фрагменты с путями.
 * Агент сам решает, что из этого положить в промпт. Идея локальной памяти Agora, код свой.
 */
class ProjectSearchTool(private val ws: Workspace) : Tool {
    override val name = "project_recall"
    override val description = "Найти по проекту фрагменты по смыслу/словам: query, limit. Возвращает путь и куски текста рядом с совпадением."
    override val schema = objSchema(
        Prop("query", "string", "Что найти в проекте"),
        Prop("limit", "string", "Сколько фрагментов (по умолчанию 8)", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "поиск по проекту: ${input.str("query")?.take(80) ?: ""}"

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val query = input.need("query").trim()
        val limit = (input.str("limit")?.toIntOrNull() ?: 8).coerceIn(1, 30)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }.distinct()
        require(words.isNotEmpty()) { "Слишком короткий запрос" }
        val skip = setOf(".git", "build", "node_modules", ".gradle", "__pycache__", ".cache", "venv", ".venv")
        val exts = setOf("kt", "java", "py", "js", "ts", "md", "json", "xml", "sh", "gradle", "txt")
        val results = mutableListOf<Pair<String, Int>>()
        ws.root.walkTopDown().maxDepth(6).forEach { f ->
            if (!f.isFile || f.length() > 1_000_000 || f.extension.lowercase() !in exts) return@forEach
            if (f.absolutePath.pathSegments().any { it in skip }) return@forEach
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val score = words.count { text.contains(it, ignoreCase = true) }
            if (score > 0) {
                val idx = text.lowercase().indexOf(words.first())
                val from = (idx - 200).coerceAtLeast(0)
                val to = (idx + 600).coerceAtMost(text.length)
                results += (ws.rel(f) + "\n" + text.substring(from, to)) to score
            }
        }
        if (results.isEmpty()) return@withContext "По проекту ничего не найдено."
        results.sortByDescending { it.second }
        results.take(limit).joinToString("\n\n---\n\n") { it.first }
    }

    private fun File.pathSegments(): List<String> = absolutePath.split(File.separatorChar)
}
