package com.pocketcode

/** Нормализация путей в диффах/логах: разобрать «a/… b/…» и «+++ ---». */
object DiffPath {
    fun strip(ref: String): String =
        ref.removePrefix("a/").removePrefix("b/").removePrefix("\"").removeSuffix("\"")

    fun fromHeader(line: String): String {
        val t = line.trim()
        if (t.startsWith("+++ ") || t.startsWith("--- ")) {
            return strip(t.drop(4).trim().split("\t")[0])
        }
        return ""
    }
}

/** Сравнение версий «1.2.10» > «1.2.9»; учитывает -rc/-beta суффиксы. */
object VersionCompare {
    fun compare(a: String, b: String): Int {
        val (numA, preA) = split(a)
        val (numB, preB) = split(b)
        for (i in 0 until maxOf(numA.size, numB.size)) {
            val x = numA.getOrNull(i) ?: 0
            val y = numB.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        if (preA == preB) return 0
        if (preA == null) return 1   // релиз новее любого пререлиза
        if (preB == null) return -1
        return preA.compareTo(preB)
    }

    fun isNewer(candidate: String, current: String): Boolean =
        compare(candidate, current) > 0

    private fun split(v: String): Pair<List<Int>, String?> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val idx = clean.indexOf('-')
        val numPart = if (idx < 0) clean else clean.substring(0, idx)
        val pre = if (idx < 0) null else clean.substring(idx + 1)
        return (numPart.split(".").map { it.toIntOrNull() ?: 0 }) to pre
    }
}

/** Быстрый парсинг «ключ=значение» из env-файлов без библиотек. */
object EnvParser {
    fun parse(text: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOfFirst { it == '=' }
            if (eq <= 0) return@forEach
            val k = line.substring(0, eq).trim()
            var v = line.substring(eq + 1).trim()
            if ((v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) ||
                (v.startsWith("'") && v.endsWith("'") && v.length >= 2)) v = v.substring(1, v.length - 1)
            out[k] = v
        }
        return out
    }

    fun render(map: Map<String, String>): String =
        map.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
}

/** Правило повторяющихся задач: «каждый день|неделю|месяц», «каждые N дней». */
object Recurrence {
    data class Rule(val kind: String, val every: Int) // kind: day|week|month

    fun parse(text: String): Rule? {
        val t = text.trim().lowercase()
        val m = Regex("(?:каждую|каждый|каждое|каждые)\\s+(\\d+)?\\s*?(день|дн|неделю|нед|месяц|мес)").find(t)
            ?: return null
        val n = (m.groupValues[1].toIntOrNull() ?: 1).coerceAtLeast(1)
        val kind = when {
            m.groupValues[2].startsWith("нед") -> "week"
            m.groupValues[2].startsWith("мес") -> "month"
            else -> "day"
        }
        return Rule(kind, n)
    }

    fun nextDays(rule: Rule, from: Int, count: Int): List<Int> {
        val step = if (rule.kind == "week") 7 * rule.every else rule.every
        return List(count) { from + step * (it + 1) }
    }

    fun describe(rule: Rule): String = when (rule.kind) {
        "week" -> if (rule.every == 1) "каждую неделю" else "каждые ${rule.every} недели"
        "month" -> if (rule.every == 1) "каждый месяц" else "каждые ${rule.every} месяца"
        else -> if (rule.every == 1) "каждый день" else "каждые ${rule.every} дня"
    }
}

/** Дедупликация списка задач по нормализованному тексту. */
object TaskDedup {
    fun norm(s: String): String =
        s.lowercase().replace("ё", "е")
            .replace(Regex("[^\\wа-я0-9\\s]"), "")
            .split(Regex("\\s+")).filter { it.isNotEmpty() }
            .sorted().joinToString(" ")

    fun keepUnique(tasks: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return tasks.filter { seen.add(norm(it)) }
    }
}

/** JSON-строка без библиотек: экранировать и снять экранирование. */
object JsonStr {
    fun escape(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append("\"").toString()
    }

    fun unescape(s: String): String {
        val t = s.trim()
        if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return t
        val body = t.substring(1, t.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            if (body[i] == '\\' && i + 1 < body.length) {
                when (body[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    'u' -> if (i + 5 < body.length) {
                        sb.append(body.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: ' ')
                        i += 6
                    } else { sb.append(body[i]); i++ }
                    else -> { sb.append(body[i + 1]); i += 2 }
                }
            } else { sb.append(body[i]); i++ }
        }
        return sb.toString()
    }
}

/** Оценка сложности промпта: длина, код, категория. */
object PromptScore {
    data class Score(val chars: Int, val words: Int, val hasCode: Boolean, val complexity: String)

    fun score(text: String): Score {
        val chars = text.length
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val hasCode = text.contains("```") ||
            Regex("\\b(fun|def|class|import|return)\\b").containsMatchIn(text)
        val complexity = when {
            chars < 60 && !hasCode -> "простой"
            chars < 400 -> "средний"
            else -> "сложный"
        }
        return Score(chars, words, hasCode, complexity)
    }
}
