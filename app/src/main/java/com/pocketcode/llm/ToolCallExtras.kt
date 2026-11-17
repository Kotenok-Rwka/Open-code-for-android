package com.pocketcode.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Лишние поля вызовов инструментов у OpenAI-совместимых провайдеров. Gemini через такой шлюз присылает подпись мысли
 * (tool_calls[].extra_content.google.thought_signature) и требует вернуть её в истории; всё неизвестное сохраняем и отправляем обратно без изменений.
 */
internal object ToolCallExtras {
    private val CALL_KNOWN = setOf("index", "id", "type", "function")
    private val FUNCTION_KNOWN = setOf("name", "arguments")

    /** Подпись-заглушка, которую Gemini принимает вместо настоящей (для истории, где подписи нет). */
    const val DUMMY_SIGNATURE = "skip_thought_signature_validator"

    /** Неизвестные поля самого вызова; пусто — null. */
    fun callExtra(call: JsonObject): JsonObject? = call.filterKeys { it !in CALL_KNOWN }.takeIf { it.isNotEmpty() }?.let(::JsonObject)

    /** Неизвестные поля объекта function; пусто — null. */
    fun functionExtra(function: JsonObject?): JsonObject? =
        function?.filterKeys { it !in FUNCTION_KNOWN }?.takeIf { it.isNotEmpty() }?.let(::JsonObject)

    /** Склейка кусков одного вызова при потоковой передаче: новые значения заменяют старые. */
    fun merge(old: JsonObject?, new: JsonObject?): JsonObject? = when {
        new == null -> old
        old == null -> new
        else -> JsonObject(old + new)
    }

    /** Есть ли у вызова подпись мысли Gemini. */
    fun hasSignature(extra: JsonObject?): Boolean = extra?.containsKey("extra_content") == true

    private fun isGemini(model: String) = model.contains("gemini", ignoreCase = true)

    /**
     * Дополнительные поля для отправки обратно. Если модель Gemini и ни у одного вызова сообщения нет подписи (например, история сохранена
     * до этой поддержки или шлюз её не прислал), первому вызову ставим подпись-заглушку: иначе Gemini отвечает ошибкой 400.
     */
    fun forSending(calls: List<Block.ToolUse>, model: String): List<JsonObject?> {
        val extras = calls.map { it.extra }
        if (calls.isEmpty() || !isGemini(model) || extras.any { hasSignature(it) }) return extras
        val dummy = buildJsonObject { putJsonObject("extra_content") { putJsonObject("google") { put("thought_signature", JsonPrimitive(DUMMY_SIGNATURE)) } } }
        return extras.mapIndexed { i, e -> if (i == 0) JsonObject((e ?: JsonObject(emptyMap())) + dummy) else e }
    }
}
