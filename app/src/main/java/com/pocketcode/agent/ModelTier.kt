package com.pocketcode.agent

/**
 * К какому режиму отнести модель. Слабые и «быстрые» модели хуже держат дисциплину инструментов:
 * пишут код в ответ вместо файлов, забывают проверки, выдумывают сигнатуры. Им нужны строительные леса.
 */
object ModelTier {
    /**
     * Признаки маленькой/быстрой модели в имени. «hy 4 flash», «gemini-2.5-flash», «gpt-4o-mini»,
     * «llama-3.1-8b», «...:free» — все сюда попадают.
     */
    private val WEAK_HINTS = listOf(
        "flash", "mini", "nano", "small", "lite", "light", "haiku", "turbo", "instant", "fast",
        "free", "gpt-3.5",
    )

    /** Размер модели в миллиардах параметров: «llama-3.1-8b» слабая, «...:14b» уже нет. */
    private val SIZE_B = Regex("""(?:^|[\s\-_/:(])(\d+(?:\.\d+)?)b(?=$|[^\p{L}\d])""", RegexOption.IGNORE_CASE)

    fun isWeak(model: String): Boolean {
        val m = model.lowercase()
        if (WEAK_HINTS.any { m.contains(it) }) return true
        val size = SIZE_B.find(m)?.groupValues?.get(1)?.toDoubleOrNull()
        return size != null && size <= 9.0
    }

    /** auto — обычный промпт; scaffold — жёсткие леса для слабой модели; focused — дисциплина контекста. */
    fun mode(model: String, contextTokens: Int, offline: Boolean): String = when {
        offline || isWeak(model) -> "scaffold"
        contextTokens in 1..32_000 -> "focused"
        else -> "auto"
    }
}
