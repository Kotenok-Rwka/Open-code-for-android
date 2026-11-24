package com.pocketcode.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Результат поиска навыков/плагинов на GitHub. */
@Serializable
data class GitHubRepoHit(
    val fullName: String,
    val description: String,
    val stars: Int,
    val url: String,
    val language: String = "",
    val updatedAt: String = "",
)

object PluginSearch {
    /** Категории поиска. Слово категории добавляется к запросу, а не фильтрует уже найденное. */
    val CATEGORIES = listOf("Все", "Навыки", "Агенты", "MCP", "Плагины")
    private val CATEGORY_WORDS = mapOf(
        "Навыки" to "skills",
        "Агенты" to "agent skill",
        "MCP" to "mcp server",
        "Плагины" to "plugin",
    )

    /** GitHub принимает sort только из этого набора; «по названию» сортируем на телефоне. */
    private val VALID_SORTS = setOf("stars", "forks", "updated")

    fun categoryWord(category: String): String = CATEGORY_WORDS[category].orEmpty()

    /** Разбирает ответ GitHub /search/repositories. Чистая функция — покрывается тестом. */
    fun parse(json: String): List<GitHubRepoHit> {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = o["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            // description/language могут быть null — JsonNull даёт строку «null», поэтому берём contentOrNull.
            fun text(key: String): String = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
            GitHubRepoHit(
                fullName = name,
                description = text("description"),
                stars = o["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                url = o["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$name",
                language = text("language"),
                updatedAt = text("updated_at"),
            )
        }
    }

    /**
     * URL поиска. Ключевые слова ищутся в названии, описании и README, чтобы не терять навыки,
     * у которых нужное слово есть только в README. Неизвестный sort не отправляется вовсе.
     */
    fun url(query: String, category: String = "Все", sort: String = "stars", page: Int = 1, perPage: Int = 50): String {
        val words = mutableListOf(query.trim())
        val cat = categoryWord(category)
        if (cat.isNotBlank()) words += cat
        if (query.isNotBlank()) words += "in:name,description,readme"
        val q = words.filter { it.isNotBlank() }.joinToString(" ")
        val sortParam = sort.takeIf { it in VALID_SORTS }
        return buildString {
            append("https://api.github.com/search/repositories?q=")
            append(java.net.URLEncoder.encode(q, "UTF-8"))
            if (sortParam != null) append("&sort=").append(sortParam).append("&order=desc")
            append("&per_page=").append(perPage.coerceIn(1, 50)).append("&page=").append(page.coerceAtLeast(1))
        }
    }

    /**
     * Запросы от точного к короткому: GitHub считает все слова обязательными, поэтому длинная фраза
     * часто даёт ноль. Но последним шагом не должно быть одно слово категории — иначе в списке
     * оказываются public-apis и фоторедакторы, вообще не связанные с запросом.
     */
    fun fallbacks(query: String, category: String = "Все"): List<String> {
        val base = query.trim()
        if (base.isEmpty()) return emptyList()
        val words = base.split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = LinkedHashSet<String>()
        out += base
        if (words.size > 2) out += words.take(2).joinToString(" ")
        if (words.size > 1) out += words.first()
        return out.toList()
    }

    /** Имя репозитория из адреса GitHub: owner/repo -> repo. */
    fun repoName(url: String): String = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")

    private fun queryWords(query: String): List<String> =
        query.lowercase().split(Regex("[^\\p{L}\\p{N}+#._-]+")).filter { it.length >= 2 }

    /** Категория должна быть видна в названии или описании, а не только в подставленном слове запроса. */
    private fun categoryMatches(hit: GitHubRepoHit, category: String): Boolean {
        val text = (hit.fullName + " " + hit.description).lowercase()
        return when (category) {
            "Навыки" -> Regex("\\bskills?\\b").containsMatchIn(text)
            "Агенты" -> Regex("\\bagents?\\b").containsMatchIn(text)
            "MCP" -> Regex("\\bmcp\\b").containsMatchIn(text)
            "Плагины" -> Regex("\\bplugins?\\b|\\bextensions?\\b").containsMatchIn(text)
            else -> true
        }
    }

    /**
     * Оставляет только репозитории по теме запроса. GitHub сортирует по звёздам и на «claude code skills»
     * легко отдаёт общие списки и обычные программы — их отсекаем до показа пользователю.
     */
    fun relevant(hits: List<GitHubRepoHit>, query: String, category: String = "Все"): List<GitHubRepoHit> {
        val words = queryWords(query)
        return hits.filter { hit ->
            val text = (hit.fullName + " " + hit.description).lowercase()
            (words.isEmpty() || words.any { text.contains(it) }) && categoryMatches(hit, category)
        }
    }

    /** Локальный фильтр: навыки/команды/плагины по названию и описанию. */
    fun <T> filterLocal(items: List<T>, query: String, text: (T) -> String): List<T> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) items else items.filter { text(it).lowercase().contains(q) }
    }
}
