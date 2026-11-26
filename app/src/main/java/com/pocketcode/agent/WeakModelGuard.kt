package com.pocketcode.agent

/**
 * Защита от типовых провалов слабых моделей: код остаётся в ответе вместо файлов,
 * «проверено» пишется без проверки, ошибка диагностики игнорируется.
 */
object WeakModelGuard {
    private val CHANGE_WORDS = listOf(
        "напиш", "созда", "добав", "исправ", "почин", "измен", "поменя", "удали", "удал", "реализ", "внедр",
        "перепиш", "рефактор", "обнови", "сделай", "сделать", "подключ", "замени", "доделай",
        "write", "create", "add ", "fix", "implement", "change", "update", "delete", "remove", "refactor",
    )

    /** Вопросы и объяснения: там код в ответе — норма, а не провал. */
    private val QUESTION_START = listOf(
        "как ", "что ", "почему", "зачем", "чем ", "можно ли", "стоит ли", "how ", "what ", "why ", "can i", "could ",
    )

    /** Просит ли пользователь что-то сделать с проектом (а не объяснить). */
    fun asksToChange(userText: String): Boolean {
        val t = userText.trim().lowercase()
        if (t.isEmpty()) return false
        if (QUESTION_START.any { t.startsWith(it) }) return false
        return CHANGE_WORDS.any { t.contains(it) }
    }

    /** Похоже ли, что в ответе лежит готовый файл кода, а не короткая иллюстрация. */
    fun hasCode(answer: String): Boolean = Regex("```[\\s\\S]*?```").findAll(answer).any { f ->
        val body = f.value.removePrefix("```").substringAfter('\n', "").substringBeforeLast("```")
        body.lines().count { it.isNotBlank() } >= 3
    }

    /** Слабая модель «сделала» задачу текстом: пользователь просил изменить проект, а в ответе только код. */
    fun codeInChat(userText: String, answer: String): Boolean = asksToChange(userText) && hasCode(answer)

    /**
     * Отчёт diagnostics без ошибок: «файл: OK», а также «проверить нельзя» (нет node, нет python,
     * файл слишком большой) — это не ошибка кода, и гонять модель по кругу из-за этого нельзя.
     */
    fun diagnosticsOk(report: String): Boolean {
        val lines = report.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return true
        return lines.all { line ->
            val l = line.lowercase()
            l.contains(": ok") || l.contains("не найден") || l.contains("не удалось проверить") || l.contains("слишком большой")
        }
    }
}
