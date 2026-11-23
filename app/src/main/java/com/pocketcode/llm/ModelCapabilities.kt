package com.pocketcode.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Разведка возможностей модели у провайдера: reasoning, vision, tools. */
data class ModelCapabilityInfo(
    val model: String,
    val reasoning: Boolean? = null,
    val reasoningEffort: Boolean? = null,
    val vision: Boolean? = null,
    val tools: Boolean? = null,
    val raw: String = "",
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val summary: String get() = buildString {
        append(model).append(": ")
        append("reasoning=").append(reasoning?.let { if (it) "да" else "нет" } ?: "?")
        append(", effort=").append(reasoningEffort?.let { if (it) "да" else "нет" } ?: "?")
        append(", vision=").append(vision?.let { if (it) "да" else "нет" } ?: "?")
        append(", tools=").append(tools?.let { if (it) "да" else "нет" } ?: "?")
    }
}

object ModelCapabilityProbe {
    suspend fun probe(baseUrl: String, apiKey: String, model: String): ModelCapabilityInfo = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        val out = runCatching {
            val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()
            val req = Request.Builder().url("$base/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext ModelCapabilityInfo(model, raw = "HTTP ${resp.code}: ${text.take(200)}")
                val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                val arr = root?.get("data")?.jsonArray
                val obj = arr?.firstOrNull { el ->
                    runCatching { el.jsonObject["id"]?.jsonPrimitive?.content == model }.getOrDefault(false)
                }?.jsonObject
                if (obj == null) return@withContext ModelCapabilityInfo(model, raw = "Модель не найдена в /models; всего: ${arr?.size ?: 0}")
                fun strings(key: String): List<String> = runCatching {
                    obj[key]?.jsonArray?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()
                }.getOrDefault(emptyList())
                val supported = strings("supported_parameters")
                val input = strings("input_modalities")
                val tags = (supported + strings("capabilities") + strings("tags")).map { it.lowercase() }
                ModelCapabilityInfo(
                    model = model,
                    reasoning = tags.any { it.contains("reason") || it.contains("thinking") },
                    reasoningEffort = supported.any { it == "reasoning_effort" },
                    vision = input.any { it == "image" } || tags.any { it.contains("vision") || it == "multimodal" },
                    tools = supported.any { it == "tools" } || tags.any { it.contains("tool") },
                    raw = obj.toString().take(1000),
                )
            }
        }.getOrElse { ModelCapabilityInfo(model, raw = "Ошибка: ${it.message}") }
        out
    }
}
