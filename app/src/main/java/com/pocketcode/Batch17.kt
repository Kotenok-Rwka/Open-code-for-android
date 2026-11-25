package com.pocketcode

/** «С первого апреля» — разбор и формат русских дат без либ. */
object RuDate {
    val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря")

    fun formatDate(day: Int, month: Int): String {
        if (month !in 1..12 || day !in 1..31) return ""
        return "$day ${MONTHS[month - 1]}"
    }

    fun monthByName(name: String): Int {
        val n = name.trim().lowercase().removeSuffix(".")
        // «апрель» vs «апреля»: сравниваем по первым 5 буквам либо по префиксу
        val idx = MONTHS.indexOfFirst { it.startsWith(n.take(5)) || n.startsWith(it.dropLast(1)) }
        return idx + 1
    }
}

/** Номер сборки из истории: коммиты + 1, базовая версия «1.2.(N)». */
object BuildNumber {
    fun fromCommits(commits: Int, version: String = "1.0"): String {
        val parts = version.split(".").toMutableList()
        while (parts.size < 3) parts.add("0")
        val n = (parts[2].toIntOrNull() ?: 0) + commits + 1
        return "${parts[0]}.${parts[1]}.$n"
    }

    fun bump(commitCount: Int, base: String): String = fromCommits(commitCount, base)
}

/** Выравнивание кода: привести отступы к табам или пробелам. */
object Indent {
    fun toTabs(text: String, width: Int = 4): String =
        text.split("\n").joinToString("\n") { line ->
            val ws = line.takeWhile { it == ' ' }
            val tabs = ws.length / width
            val rest = ws.length % width
            "\t".repeat(tabs) + " ".repeat(rest) + line.drop(ws.length)
        }

    fun toSpaces(text: String, width: Int = 4): String =
        text.split("\n").joinToString("\n") { line ->
            val ws = line.takeWhile { it == '\t' }
            " ".repeat(ws.length * width) + line.drop(ws.length)
        }
}

/** Генератор имён: kebab/camel/snake/title из произвольного текста. */
object CaseFmt {
    fun words(s: String): List<String> =
        s.replace(Regex("([а-яё])([А-ЯЁ])"), "$1 $2")
            .replace(Regex("([A-Za-z])([A-Z][a-z])"), "$1 $2")
            .split(Regex("[^\\wа-яёА-ЯЁ0-9]+"))
            .filter { it.isNotEmpty() }

    fun kebab(s: String): String = words(s).joinToString("-") { it.lowercase() }
    fun snake(s: String): String = words(s).joinToString("_") { it.lowercase() }
    fun camel(s: String): String = words(s).joinToString("") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
    fun title(s: String): String = words(s).joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
}

/** Ключи черновиков: для чата и для заметки, обратный разбор. */
object DraftKey {
    fun forChat(chatId: String): String = "draft_chat_$chatId"
    fun forEdit(noteId: Int): String = "draft_note_$noteId"

    fun chatIdOf(key: String): String? =
        if (key.startsWith("draft_chat_")) key.removePrefix("draft_chat_").takeIf { it.isNotEmpty() } else null
}

/** Иконка по расширению файла (имя материала-иконки). */
object FileIcon {
    fun forName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java", "py", "pyw" -> "language"
            "md", "txt" -> "description"
            "json", "xml", "yaml", "yml", "toml" -> "data_object"
            "png", "jpg", "jpeg", "gif", "webp", "svg" -> "image"
            "zip", "apk", "jar" -> "folder_zip"
            "sh", "bat", "ps1" -> "terminal"
            "lock", "properties" -> "settings"
            else -> "draft"
        }
    }
}

/** Прогресс: процент, ETA из скорости и ASCII-полоска. */
object Progress {
    fun percent(done: Int, total: Int): Int =
        if (total <= 0) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)

    fun eta(done: Int, total: Int, elapsedSec: Long): Long {
        if (done <= 0 || total <= done) return 0
        return elapsedSec * (total - done) / done
    }

    fun bar(done: Int, total: Int, width: Int = 10): String {
        val filled = (percent(done, total) * width / 100).coerceIn(0, width)
        return "[" + "#".repeat(filled) + "-".repeat(width - filled) + "]"
    }
}
