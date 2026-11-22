package com.pocketcode.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Описание модели в маршрутизаторе (9Router, LocalAI, LM Studio). */
@Serializable
data class RouterModel(val id: String, val ownedBy: String = "local")

object ModelRouter {
    /** Запрашивает /v1/models у локального роутера. Возвращает пустой список при любой ошибке. */
    fun list(baseUrl: String, apiKey: String, fetch: (String, Map<String, String>) -> String): List<RouterModel> {
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) return emptyList()
        val url = baseUrl.trimEnd('/') + "/v1/models"
        val headers = mutableMapOf("Accept" to "application/json")
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        val body = runCatching { fetch(url, headers) }.getOrNull() ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        return (root["data"]?.jsonArray ?: return emptyList()).mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val owner = o["owned_by"]?.jsonPrimitive?.content.orEmpty()
            RouterModel(id, owner)
        }
    }

    fun isRouterAvailable(baseUrl: String): Boolean = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
}
