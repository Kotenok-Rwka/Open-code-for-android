package com.pocketcode.ui

import androidx.compose.ui.graphics.Color

/** Вид фрагмента кода для подсветки. Обычный текст в список не попадает. */
enum class TokKind { COMMENT, STRING, NUMBER, KEYWORD, TYPE }

/** Фрагмент [start, end) исходного текста. */
data class Tok(val kind: TokKind, val start: Int, val end: Int)

/** Цвета подсветки для светлой и тёмной темы. */
data class CodePalette(val keyword: Color, val string: Color, val number: Color, val comment: Color, val type: Color) {
    fun colorOf(k: TokKind): Color = when (k) {
        TokKind.KEYWORD -> keyword
        TokKind.STRING -> string
        TokKind.NUMBER -> number
        TokKind.COMMENT -> comment
        TokKind.TYPE -> type
    }
}

fun codePalette(dark: Boolean): CodePalette = if (dark) {
    CodePalette(Color(0xFFC792EA), Color(0xFFA5D6A7), Color(0xFFFFB86C), Color(0xFF8B96A5), Color(0xFF82AAFF))
} else {
    CodePalette(Color(0xFF8E24AA), Color(0xFF2E7D32), Color(0xFFC25E00), Color(0xFF6B7280), Color(0xFF1565C0))
}

/** Выше этого размера подсветка не считается, чтобы не тормозить чат на огромных выводах. */
const val HIGHLIGHT_LIMIT = 20000

private val ALIASES = mapOf(
    "kt" to "kotlin", "kts" to "kotlin", "js" to "javascript", "jsx" to "javascript", "mjs" to "javascript",
    "ts" to "typescript", "tsx" to "typescript", "py" to "python", "python3" to "python",
    "sh" to "shell", "bash" to "shell", "zsh" to "shell", "shell" to "shell", "console" to "shell",
    "yml" to "yaml", "c++" to "cpp", "cc" to "cpp", "h" to "c", "hpp" to "cpp", "cs" to "csharp",
    "rs" to "rust", "golang" to "go", "rb" to "ruby", "htm" to "html", "svg" to "xml", "gradle" to "groovy",
    "kotlin" to "kotlin", "java" to "java", "json" to "json", "html" to "html", "xml" to "xml",
    "sql" to "sql", "yaml" to "yaml", "toml" to "toml", "rust" to "rust", "go" to "go", "ruby" to "ruby",
    "javascript" to "javascript", "typescript" to "typescript", "python" to "python", "c" to "c", "cpp" to "cpp",
    "csharp" to "csharp", "swift" to "swift", "php" to "php", "lua" to "lua", "groovy" to "groovy",
)

/** Приводит подпись после ``` к короткому имени языка; незнакомое и пустое даёт пустую строку. */
fun normLang(raw: String): String {
    val key = raw.trim().lowercase().takeWhile { !it.isWhitespace() && it != '{' && it != ',' }
    return ALIASES[key] ?: ""
}

/** Как показать язык в шапке блока: известный по короткому имени, иначе то, что написал автор. */
fun langLabel(raw: String): String {
    val key = raw.trim().takeWhile { !it.isWhitespace() && it != '{' && it != ',' }
    if (key.isEmpty()) return ""
    return when (normLang(key)) {
        "kotlin" -> "Kotlin"
        "java" -> "Java"
        "javascript" -> "JavaScript"
        "typescript" -> "TypeScript"
        "python" -> "Python"
        "shell" -> "Shell"
        "json" -> "JSON"
        "yaml" -> "YAML"
        "toml" -> "TOML"
        "xml" -> "XML"
        "html" -> "HTML"
        "sql" -> "SQL"
        "cpp" -> "C++"
        "c" -> "C"
        "csharp" -> "C#"
        "rust" -> "Rust"
        "go" -> "Go"
        "ruby" -> "Ruby"
        "swift" -> "Swift"
        "php" -> "PHP"
        "lua" -> "Lua"
        "groovy" -> "Groovy"
        else -> key
    }
}

private val COMMON = setOf(
    "if", "else", "for", "while", "do", "return", "break", "continue", "switch", "case", "default", "try", "catch",
    "finally", "throw", "new", "class", "interface", "enum", "import", "package", "from", "as", "in", "is", "true",
    "false", "null", "this", "super", "static", "public", "private", "protected", "void", "const", "let", "var",
    "val", "fun", "function", "def", "async", "await", "extends", "implements", "override", "when", "object", "None",
    "True", "False", "self", "lambda", "with", "yield", "not", "and", "or", "pass", "raise", "except", "elif",
)

private val BY_LANG: Map<String, Set<String>> = mapOf(
    "kotlin" to setOf("fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data", "when", "if", "else", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "import", "package", "is", "in", "as", "null", "true", "false", "this", "super", "override", "private", "public", "protected", "internal", "open", "abstract", "suspend", "inline", "companion", "const", "lateinit", "by", "init", "typealias", "where", "out", "vararg"),
    "java" to setOf("class", "interface", "enum", "extends", "implements", "public", "private", "protected", "static", "final", "abstract", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "throws", "import", "package", "this", "super", "null", "true", "false", "instanceof", "synchronized", "var"),
    "javascript" to setOf("function", "const", "let", "var", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "class", "extends", "import", "export", "from", "as", "async", "await", "yield", "this", "super", "null", "undefined", "true", "false", "typeof", "instanceof", "in", "of", "delete", "void"),
    "typescript" to setOf("function", "const", "let", "var", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "new", "class", "extends", "implements", "interface", "type", "enum", "import", "export", "from", "as", "async", "await", "this", "super", "null", "undefined", "true", "false", "typeof", "instanceof", "in", "of", "public", "private", "protected", "readonly", "abstract", "namespace", "declare"),
    "python" to setOf("def", "class", "return", "if", "elif", "else", "for", "while", "break", "continue", "try", "except", "finally", "raise", "import", "from", "as", "with", "pass", "yield", "lambda", "global", "nonlocal", "in", "is", "not", "and", "or", "None", "True", "False", "async", "await", "assert", "del"),
    "shell" to setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "in", "function", "return", "exit", "export", "local", "echo", "cd", "source", "set", "unset", "true", "false"),
    "json" to setOf("true", "false", "null"),
    "yaml" to setOf("true", "false", "null", "yes", "no"),
    "toml" to setOf("true", "false"),
    "sql" to setOf("select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "drop", "alter", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having", "limit", "as", "and", "or", "not", "null", "distinct", "union", "index", "primary", "key", "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "AS", "AND", "OR", "NOT", "NULL", "DISTINCT", "UNION", "INDEX", "PRIMARY", "KEY"),
    "rust" to setOf("fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait", "pub", "use", "mod", "crate", "self", "Self", "super", "match", "if", "else", "for", "while", "loop", "break", "continue", "return", "as", "in", "ref", "move", "where", "async", "await", "true", "false", "unsafe", "dyn", "type"),
    "go" to setOf("func", "var", "const", "type", "struct", "interface", "package", "import", "return", "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "go", "defer", "chan", "select", "map", "nil", "true", "false"),
)

private val HASH_LANGS = setOf("python", "shell", "yaml", "toml", "ruby")
private val DASH_LANGS = setOf("sql", "lua")
private val NO_COMMENT_LANGS = setOf("json")
private val NO_BLOCK_LANGS = setOf("python", "shell", "yaml", "toml", "ruby", "json", "sql")

private fun isIdStart(c: Char) = c.isLetter() || c == '_' || c == '$'
private fun isIdPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

/**
 * Простой разбор кода на комментарии, строки, числа, ключевые слова и типы.
 * Это не настоящий парсер: он должен быть быстрым и никогда не падать на любом тексте.
 * Язык задаётся уже нормализованным именем (см. normLang); пустой язык означает «общие слова».
 */
fun highlight(code: String, lang: String): List<Tok> {
    if (code.isEmpty() || code.length > HIGHLIGHT_LIMIT) return emptyList()
    val kw = BY_LANG[lang] ?: COMMON
    val lineC = when {
        lang in NO_COMMENT_LANGS -> null
        lang in HASH_LANGS -> "#"
        lang in DASH_LANGS -> "--"
        lang == "html" || lang == "xml" -> null
        else -> "//"
    }
    val blockOpen = when {
        lang in NO_BLOCK_LANGS -> null
        lang == "html" || lang == "xml" -> "<!--"
        else -> "/*"
    }
    val blockClose = if (lang == "html" || lang == "xml") "-->" else "*/"
    val out = ArrayList<Tok>()
    val n = code.length
    var i = 0
    while (i < n) {
        val c = code[i]
        if (lineC != null && code.startsWith(lineC, i) && !(lineC == "#" && lang == "shell" && i > 0 && code[i - 1] == '$')) {
            var e = code.indexOf('\n', i)
            if (e < 0) e = n
            out += Tok(TokKind.COMMENT, i, e)
            i = e
            continue
        }
        if (blockOpen != null && code.startsWith(blockOpen, i)) {
            val close = code.indexOf(blockClose, i + blockOpen.length)
            val e = if (close < 0) n else close + blockClose.length
            out += Tok(TokKind.COMMENT, i, e)
            i = e
            continue
        }
        if (c == '"' || c == '\'' || c == '`') {
            val triple = "$c$c$c"
            if (lang == "python" && c != '`' && code.startsWith(triple, i)) {
                val close = code.indexOf(triple, i + 3)
                val e = if (close < 0) n else close + 3
                out += Tok(TokKind.STRING, i, e)
                i = e
                continue
            }
            var j = i + 1
            while (j < n) {
                val d = code[j]
                if (d == '\\' && j + 1 < n) { j += 2; continue }
                if (d == c) { j++; break }
                if (d == '\n' && c != '`') break
                j++
            }
            out += Tok(TokKind.STRING, i, j)
            i = j
            continue
        }
        if (c.isDigit() && (i == 0 || !isIdPart(code[i - 1]))) {
            var j = i + 1
            while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
            out += Tok(TokKind.NUMBER, i, j)
            i = j
            continue
        }
        if (c == '@' && i + 1 < n && code[i + 1].isLetter() && lang != "shell" && lang != "sql") {
            var j = i + 1
            while (j < n && isIdPart(code[j])) j++
            out += Tok(TokKind.TYPE, i, j)
            i = j
            continue
        }
        if (isIdStart(c)) {
            var j = i + 1
            while (j < n && isIdPart(code[j])) j++
            val word = code.substring(i, j)
            if (word in kw) {
                out += Tok(TokKind.KEYWORD, i, j)
            } else if (lang != "json" && lang != "shell" && lang != "yaml" && lang != "toml" &&
                word.length > 1 && word[0].isUpperCase() && word.any { it.isLowerCase() }
            ) {
                out += Tok(TokKind.TYPE, i, j)
            }
            i = j
            continue
        }
        i++
    }
    return out
}
