package com.pocketcode

/** Поиск по файлу: строка/regex, строковый поиск без учёта регистра. */
object CodeSearch {
    data class Hit(val line: Int, val text: String)

    fun search(text: String, query: String, regex: Boolean = false, limit: Int = 50): List<Hit> {
        if (query.isEmpty()) return emptyList()
        val rx = try {
            if (regex) Regex(query) else Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            return emptyList()
        }
        return text.lineSequence()
            .withIndex()
            .filter { rx.containsMatchIn(it.value) }
            .take(limit)
            .map { Hit(it.index + 1, it.value) }
            .toList()
    }
}

/** Библиотека сниппетов с тегами; поиск по названию, телу и тегам. */
object SnippetLib {
    data class Snippet(val id: Int, val title: String, val body: String, val tags: List<String>)

    private val store = mutableListOf<Snippet>()
    private var nextId = 1

    fun add(title: String, body: String, tags: List<String> = emptyList()): Snippet {
        val s = Snippet(nextId++, title.trim(), body, tags.map { it.trim().lowercase() })
        store.add(s)
        return s
    }

    fun all(): List<Snippet> = store.toList()

    fun find(query: String): List<Snippet> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return store.filter {
            it.title.lowercase().contains(q) ||
                it.body.lowercase().contains(q) ||
                it.tags.any { t -> t.contains(q) }
        }
    }
}

/** Статистика unified diff: сколько файлов и сколько строк +/-. */
object DiffStat {
    data class Stat(val files: Int, val added: Int, val removed: Int)

    fun parse(diff: String): Stat {
        var files = 0
        var added = 0
        var removed = 0
        var inHunks = false
        diff.lineSequence().forEach { l ->
            when {
                l.startsWith("diff --git") -> { files++; inHunks = true }
                inHunks && l.startsWith("+") && !l.startsWith("+++") -> added++
                inHunks && l.startsWith("-") && !l.startsWith("---") -> removed++
            }
        }
        return Stat(files, added, removed)
    }
}

/** Упоминания и теги в сообщении: @user, #tag (латиница, кириллица, цифры). */
object MentionParser {
    fun mentions(text: String): List<String> =
        Regex("@[\\wа-яё]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.drop(1) }.toList()

    fun tags(text: String): List<String> =
        Regex("#[\\wа-яё]+", RegexOption.IGNORE_CASE).findAll(text).map { it.value.drop(1) }.toList()
}

/** План пакетного переименования: префикс + счётчик, без записи на диск. */
object BatchRename {
    data class Plan(val from: String, val to: String)

    fun plan(files: List<String>, prefix: String, sep: String = "-", pad: Int = 2): List<Plan> =
        files.mapIndexed { i, name ->
            val ext = name.substringAfterLast('.', "")
            val base = if (ext.isEmpty() || ext == name) name else name.substringBeforeLast('.')
            val extPart = if (ext.isEmpty() || ext == name) "" else ".$ext"
            Plan(name, "%s%s%0${pad}d%s".format(prefix, sep, i + 1, extPart))
        }
}

/** Нормализация host:port в URL, дефолтная схема и порт. */
object PortCheck {
    fun toUrl(host: String, defaultPort: Int = 8080, tls: Boolean = false): String {
        val secure = tls || host.trim().startsWith("https:", ignoreCase = true)
        val (h0, p) = split(host, defaultPort)
        val h = if (h0.contains(":")) "[$h0]" else h0 // ipv6 -> [::1]
        val scheme = if (secure || p == 443) "https" else "http"
        return "$scheme://$h:$p"
    }

    fun split(host: String, defaultPort: Int = 8080): Pair<String, Int> {
        val trimmed = host.trim()
        val isHttps = trimmed.startsWith("https:", ignoreCase = true)
        val clean = trimmed.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val def = if (isHttps) 443 else defaultPort
        if (clean.startsWith("[")) { // ipv6: [::1]:9000
            val end = clean.indexOf(']')
            if (end > 0) {
                val h = clean.substring(1, end)
                val p = clean.drop(end + 1).removePrefix(":").toIntOrNull()
                return h to (p ?: def)
            }
        }
        val parts = clean.split(":")
        return if (parts.size == 2 && parts[1].toIntOrNull() != null)
            parts[0] to parts[1].toInt()
        else clean to def
    }
}

/** «5 минут назад» по разнице в секундах, с русскими множественными формами. */
object TimeFmt {
    fun ago(seconds: Long): String {
        if (seconds < 60) return "только что"
        val m = seconds / 60
        val h = m / 60
        val d = h / 24
        return when {
            d > 0 -> plural(d, "день", "дня", "дней")
            h > 0 -> plural(h, "час", "часа", "часов")
            else -> plural(m, "минуту", "минуты", "минут")
        } + " назад"
    }

    fun plural(n: Long, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod10 == 1L && mod100 != 11L -> "$n $one"
            mod10 in 2..4 && (mod100 < 12 || mod100 > 14) -> "$n $few"
            else -> "$n $many"
        }
    }
}

/** Статистика текста для редактора: раскрытие табов, слова, строки. */
object EditorStats {
    fun expandTabs(text: String, tab: Int = 4): String =
        text.split("\n").joinToString("\n") { line ->
            var out = ""
            for (ch in line) out += if (ch == '\t') " ".repeat(tab - out.length % tab) else ch
            out
        }

    fun words(text: String): Int =
        text.split(Regex("\\s+")).count { it.isNotEmpty() }

    fun lines(text: String): Int = text.split("\n").size
}
