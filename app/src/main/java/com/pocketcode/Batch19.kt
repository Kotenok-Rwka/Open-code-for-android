package com.pocketcode

/** Экран «Сегодня»: незавершённое и недавнее одним списком. */
object TodayDigest {
    data class Entry(val title: String, val detail: String)

    fun build(todoItems: List<Triple<String, Int, String>>, recentFiles: List<String>,
              lastCommit: String): List<Entry> {
        val out = mutableListOf<Entry>()
        todoItems.take(5).forEach { (file, line, text) ->
            out.add(Entry("TODO $file:$line", text))
        }
        recentFiles.take(5).forEach { out.add(Entry("Файл", it)) }
        if (lastCommit.isNotBlank()) out.add(Entry("Коммит", lastCommit))
        return out
    }
}

/** Глобальный поиск: по именам и содержимому. */
object GlobalSearch {
    data class Hit(val file: String, val line: Int, val snippet: String)

    fun content(lines: List<String>, query: String, file: String, maxHits: Int = 40): List<Hit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return lines.mapIndexedNotNull { i, ln ->
            if (ln.lowercase().contains(q)) Hit(file, i + 1, ln.trim().take(160)) else null
        }.take(maxHits)
    }

    fun names(files: List<String>, query: String): List<String> {
        val q = query.trim().lowercase()
        return files.filter { it.lowercase().contains(q) }
    }
}

/** Шаблоны промптов с подстановками. */
object PromptTpl {
    fun render(template: String, filename: String = "", project: String = ""): String =
        template.replace("{{файл}}", filename).replace("{{проект}}", project)

    fun renderList(templates: Map<String, String>, params: Map<String, String>): Map<String, String> =
        templates.mapValues { (_, t) ->
            params.entries.fold(t) { acc, (k, v) -> acc.replace("{{" + k + "}}", v) }
        }
}

/** Отложенные задачи. */
object Reminders {
    data class Item(val text: String, val whenStr: String, val done: Boolean)

    fun due(items: List<Item>, today: String): List<Item> =
        items.filter { !it.done && it.whenStr <= today }

    fun markDone(items: List<Item>, index: Int): List<Item> =
        items.mapIndexed { i, it -> if (i == index) it.copy(done = true) else it }
}

/** Токены сессии и лимит. */
object UsageMeter {
    fun approxTokens(messages: List<String>): Int =
        messages.sumOf { it.length } / 4

    fun check(dailyLimit: Int, usedToday: Int): Boolean =
        dailyLimit == 0 || usedToday < dailyLimit

    fun left(dailyLimit: Int, usedToday: Int): Int = (dailyLimit - usedToday).coerceAtLeast(0)
}

/** Экспорт чата в Markdown. */
object ChatExport {
    fun markdown(messages: List<Pair<String, String>>, title: String = "Чат"): String = buildString {
        appendLine("# $title")
        messages.forEach { (role, text) ->
            appendLine()
            appendLine("**$role:**")
            appendLine(text)
        }
    }.trimEnd() + "\n"
}

/** История команд и сниппеты терминала. */
object TermHistory {
    fun last(items: List<Pair<String, String>>, limit: Int = 30): List<Pair<String, String>> =
        items.takeLast(limit).reversed()

    fun render(snippet: String, params: Map<String, String> = emptyMap()): String =
        params.entries.fold(snippet) { acc, (k, v) -> acc.replace("{{" + k + "}}", v) }
}

/** Мини-дифф и замены. */
object MiniDiff {
    data class Result(val added: List<String>, val removed: List<String>) {
        val total: Int get() = added.size + removed.size
    }

    fun of(oldText: List<String>, newText: List<String>): Result {
        val oldSet = oldText.toSet()
        val newSet = newText.toSet()
        return Result((newSet - oldSet).toList().sorted(), (oldSet - newSet).toList().sorted())
    }

    fun regexReplace(text: String, pattern: Regex, repl: String): Pair<String, Int> {
        var count = 0
        val out = pattern.replace(text) { m ->
            count++
            repl.replace("$0", m.value)
        }
        return out to count
    }
}

/** Многошаговая отмена файла. */
object UndoStack {
    fun push(stack: List<String>, content: String, limit: Int = 20): List<String> =
        (stack + content).takeLast(limit)

    fun pop(stack: List<String>): Pair<String, List<String>>? =
        stack.lastOrNull()?.let { it to stack.dropLast(1) }
}

/** Оценка риска правки. */
object EditRisk {
    fun score(added: Int, removed: Int, files: Int): Int = added + removed + files * 5

    fun label(score: Int): String = when {
        score < 30 -> "безопасно"
        score < 120 -> "осторожно"
        else -> "опасно"
    }
}

/** Анти-галлюцинация: пути из ответа против реальных файлов. */
object AnswerCheck {
    data class Check(val path: String, val exists: Boolean)

    fun verify(mentioned: List<String>, realFiles: Set<String>): List<Check> =
        mentioned.map { p -> Check(p, p.replace("\\", "/").removePrefix("./") in realFiles ||
            realFiles.any { it.endsWith(p.replace("\\", "/").removePrefix("./")) }) }
}

/** Корзина и защищённые файлы. */
object Safety {
    fun isProtected(path: String, protected: List<String>): Boolean {
        val p = path.replace("\\", "/").trimEnd('/')
        return protected.any { f -> p == f || p.startsWith(f.trimEnd('/') + "/") }
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / 1048576.0)
        bytes >= 1L shl 10 -> "%.1f КБ".format(bytes / 1024.0)
        else -> "$bytes Б"
    }
}

/** Озвучивание ответа: разбивка на куски для TTS. */
object Tts {
    fun chunks(text: String, maxChars: Int = 300): List<String> {
        val sents = text.trim().split(Regex("(?<=[.!?…])\\s+"))
        val out = mutableListOf<String>()
        var cur = ""
        for (s in sents) {
            if (cur.length + s.length + 1 > maxChars && cur.isNotBlank()) {
                out.add(cur.trim()); cur = ""
            }
            cur += (s + " ")
        }
        if (cur.isNotBlank()) out.add(cur.trim())
        return out.take(50)
    }
}

/** Экран «Что нового» и онбординг. */
object WhatsNew {
    fun markdown(from: String, to: String, changes: List<String>): String = buildString {
        appendLine("# Обновление $from → $to")
        appendLine()
        changes.forEach { appendLine("- $it") }
    }.trimEnd() + "\n"

    fun onboarding(): List<Pair<Int, String>> = listOf(
        1 to "Проект: открой папку с кодом",
        2 to "Чат: пиши агенту задачу",
        3 to "Инструменты: дашборд, техдолг, тесты",
        4 to "Терминал: живая сессия sh",
        5 to "Настройки: переключатели функций",
    )
}
