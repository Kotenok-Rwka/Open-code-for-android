package com.pocketcode.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    /** Разбирает ответ GitHub /search/repositories. Чистая функция — покрывается тестом. */
    fun parse(json: String): List<GitHubRepoHit> {
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return emptyList()
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val name = o["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            GitHubRepoHit(
                fullName = name,
                description = o["description"]?.jsonPrimitive?.content.orEmpty(),
                stars = o["stargazers_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                url = o["html_url"]?.jsonPrimitive?.content ?: "https://github.com/$name",
                language = o["language"]?.jsonPrimitive?.content.orEmpty(),
                updatedAt = o["updated_at"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    /** URL поиска: ищем по ключевым словам «pocketcode skill plugin agent», сортируем по звёздам. */
    fun url(query: String, topic: String = "agent", sort: String = "stars"): String {
        val q = listOf(query.trim(), if (topic == "agent") "" else topic).filter { it.isNotBlank() }.joinToString(" ")
        return "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode(q, "UTF-8") + "&sort=$sort&per_page=20"
    }

    /** Локальный фильтр: навыки/команды/плагины по названию и описанию. */
    fun <T> filterLocal(items: List<T>, query: String, text: (T) -> String): List<T> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) items else items.filter { text(it).lowercase().contains(q) }
    }
}
