package com.pocketcode.llm

/** Нативные параметры размышлений для разных провайдеров. */
data class ReasoningSpec(
    val enabled: Boolean,
    val depth: Int,
    /** Что именно отправлять: "auto", "openai", "gemini", "deepseek", "off". */
    val vendor: String = "auto",
) {
    /** Поля для тела запроса OpenAI-совместимого endpoint. */
    fun fields(model: String, baseUrl: String): Map<String, Any> {
        if (vendor == "anthropic") return emptyMap() // задаётся отдельно в AnthropicClient
        if (!enabled) {
            return when {
                vendor == "gemini" || baseUrl.contains("generativelanguage", true) -> mapOf("thinkingConfig" to mapOf("thinkingBudget" to 0))
                vendor == "deepseek" || model.contains("deepseek-r1", true) -> emptyMap()
                else -> mapOf("reasoning_effort" to "none")
            }
        }
        val effort = when (depth) {
            0 -> "minimal"
            1 -> "medium"
            2 -> "high"
            else -> "xhigh"
        }
        return when {
            vendor == "gemini" || baseUrl.contains("generativelanguage", true) ->
                mapOf("thinkingConfig" to mapOf("thinkingBudget" to when (depth) { 0 -> 0; 1 -> 1024; 2 -> 4096; else -> 8192 }))
            vendor == "deepseek" || model.contains("deepseek-r1", true) -> emptyMap() // модель всегда думает
            baseUrl.contains("openai", true) || model.contains("gpt-5", true) || model.contains("o3", true) || model.contains("o4", true) ->
                mapOf("reasoning_effort" to effort)
            else -> mapOf("reasoning_effort" to effort)
        }
    }

    /**
     * Отправляем нативный параметр ЛЮБОМУ OpenAI-совместимому провайдеру: пусть провайдер
     * решит, поддерживает он его или нет. Список известных моделей нужен только для бейджа.
     */
    fun sendsNative(model: String, baseUrl: String): Boolean = enabled && vendor != "off"

    /** Известные модели — только для подсказки в UI, не для включения параметра. */
    fun supported(model: String, baseUrl: String): Boolean = when {
        vendor == "off" -> false
        vendor == "anthropic" -> true
        vendor == "gemini" || baseUrl.contains("generativelanguage", true) -> true
        model.contains("gpt-5", true) || model.contains("o1", true) || model.contains("o3", true) || model.contains("o4", true) -> true
        model.contains("claude-sonnet", true) || model.contains("claude-opus", true) -> true
        model.contains("deepseek-r1", true) || model.contains("qwq", true) -> true
        else -> false
    }
}
