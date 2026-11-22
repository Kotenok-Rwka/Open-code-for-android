package com.pocketcode

/** Caveman: короткие ответы и набор дополнительных навыков без отдельного API-ключа. */
object Caveman {
    const val SKILL_NAME = "caveman"
    const val INTRO = "Установить Caveman"

    /** Инструкция в системный промпт, если навык включён. */
    fun prompt(enabled: Boolean): String = if (enabled) """
[Caveman mode]
- Answer briefly: 1-4 sentences, no preamble, no lists unless asked.
- Prefer concrete commands and file paths over explanations.
- If the task needs deeper analysis, say so in one line and then do it.
""".trimIndent() else ""

    /** Навыки, которые включает Caveman: короткий ответ, критика, тесты, компрессия. */
    val skills: List<Pair<String, String>> = listOf(
        "caveman-brief" to "Короткий ответ: 1-4 предложения, без вступления.",
        "caveman-critic" to "Найди слабые места в решении и перечисли максимум 3.",
        "caveman-tests" to "Предложи минимальный набор тестов для изменения.",
        "caveman-compact" to "Сожми предыдущую часть диалога в 5 фактов.",
        "caveman-next" to "Какой следующий шаг? Один конкретный совет.",
    )
}
