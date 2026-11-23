package com.pocketcode.ui

/**
 * Лёгкий перевод описаний репозиториев без вызова модели: словник частых GitHub-терминов.
 * Работает на устройстве, не отправляет данные никуда и не тратит токены.
 * Для слов вне словника текст остаётся как есть — лучше оставить оригинал, чем показать выдумку.
 */
object GitHubText {
    private val ru = mapOf(
        "skills" to "навыки", "skill" to "навык", "plugins" to "плагины", "plugin" to "плагин",
        "agents" to "агенты", "agent" to "агент", "tools" to "инструменты", "tool" to "инструмент",
        "collection" to "коллекция", "awesome" to "подборка", "examples" to "примеры", "example" to "пример",
        "framework" to "фреймворк", "library" to "библиотека", "client" to "клиент", "server" to "сервер",
        "open source" to "с открытым исходным кодом", "source" to "исходный код", "code" to "код",
        "documentation" to "документация", "docs" to "документация", "tutorial" to "руководство",
        "templates" to "шаблоны", "template" to "шаблон", "config" to "конфигурация",
        "integration" to "интеграция", "support" to "поддержка", "build" to "сборка",
        "cli" to "командная строка", "api" to "API", "model" to "модель", "models" to "модели",
        "prompt" to "промпт", "prompts" to "промпты", "workflow" to "рабочий процесс",
        "automation" to "автоматизация", "development" to "разработка", "developer" to "разработчик",
        "typescript" to "TypeScript", "javascript" to "JavaScript", "python" to "Python",
    )

    /** Возвращает переведённое описание, если язык не русский и словарь дал результат. */
    fun translate(text: String, language: String): String? {
        if (language == "ru" || text.isBlank()) return null
        var out = text
        // Сначала фразы, потом отдельные слова; границы слов защищают от замены внутри других слов.
        ru.entries.sortedByDescending { it.key.length }.forEach { (from, to) ->
            out = out.replace(Regex("\\b${Regex.escape(from)}\\b", RegexOption.IGNORE_CASE), to)
        }
        return out.takeIf { it != text }
    }
}
