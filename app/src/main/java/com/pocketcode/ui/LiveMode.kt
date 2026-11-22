package com.pocketcode.ui

/** Режимы живого (голосового) планирования: насколько широкие права у быстрой модели. */
@kotlinx.serialization.Serializable
enum class LiveModeScope(val label: String, val description: String) {
    TALK("Только разговор", "Без инструментов. Самые быстрые ответы, только из разговора."),
    READ("Только чтение", "Может читать файлы и искать, чтобы ответить, но ничего не меняет."),
    TOOLS("С инструментами", "Может вызывать инструменты; действия всё равно проходят разрешения основного чата."),
}

@kotlinx.serialization.Serializable
data class LiveModeConfig(
    val enabled: Boolean = false,
    val scope: LiveModeScope = LiveModeScope.READ,
    val modelId: String = "",
)

object LiveMode {
    /** Инструкция для быстрой модели: она планирует вслух, но не подменяет основной агент. */
    fun systemPrompt(scope: LiveModeScope): String = when (scope) {
        LiveModeScope.TALK -> "You are the live planning voice. Answer briefly out loud. No tools, no file changes."
        LiveModeScope.READ -> "You are the live planning voice. You may read files and search, then speak a short plan. Never edit files."
        LiveModeScope.TOOLS -> "You are the live planning voice. You may use tools to inspect the project and speak a short plan. Any changes still require the main agent's approvals."
    }

    /** Можно ли запускать: нужен включённый режим и выбранная модель. */
    fun canStart(config: LiveModeConfig): Boolean = config.enabled && config.modelId.isNotBlank()
}
