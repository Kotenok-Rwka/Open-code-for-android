package com.pocketcode.llm

/**
 * Чинит типичный мусор в аргументах вызова инструмента у слабых моделей: текст вокруг JSON,
 * висячие запятые, обрыв потока. Безопасно вызывать только после того, как обычный разбор не удался.
 */
object JsonRepair {
    fun sanitize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return "{}"
        // Болтовня до/после объекта: «Конечно! {"path": ...}»
        val start = s.indexOf('{')
        if (start < 0) return s
        val end = s.lastIndexOf('}')
        s = if (end > start) s.substring(start, end + 1) else s.substring(start)
        // Висячая запятая перед закрывающей скобкой
        s = Regex(""",\s*([}\]])""").replace(s, "$1")
        // Строка обрезана на середине — закрываем её, потом скобки
        var inStr = false
        var esc = false
        for (ch in s) {
            when {
                esc -> esc = false
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
            }
        }
        if (inStr && !esc) s += "\""
        var depth = 0
        inStr = false
        esc = false
        for (ch in s) {
            when {
                esc -> esc = false
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '{' -> depth++
                !inStr && ch == '}' -> depth--
            }
        }
        if (depth > 0) s += "}".repeat(depth)
        return s
    }
}
