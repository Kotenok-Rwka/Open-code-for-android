package com.pocketcode.assist

enum class MdStyle { BOLD, CODE, LINK, HEADING }

data class MdSpan(val start: Int, val end: Int, val style: MdStyle, val url: String? = null)

/** Простой текст и диапазоны оформления: так ответ показывается в обычном TextView без библиотек. */
data class MdParsed(val text: String, val spans: List<MdSpan>)

/** Разбирает небольшую часть Markdown, которой отвечает ассистент: **жирный**, `код`, [ссылки](адрес), списки, заголовки, блоки кода. */
object AssistMarkdown {
    private val HEADING_RX = Regex("^#{1,6}\\s+(.*)$")
    private val BULLET_RX = Regex("^(\\s*)[-*•]\\s+(.*)$")
    private val RULE_RX = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(src: String): MdParsed {
        val sb = StringBuilder()
        val spans = ArrayList<MdSpan>()
        var fence = false
        var first = true
        fun newline() {
            if (!first) sb.append('\n')
            first = false
        }
        for (raw in src.replace("\r\n", "\n").split('\n')) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("```")) {
                fence = !fence
                continue
            }
            if (fence) {
                newline()
                val start = sb.length
                sb.append(raw)
                if (sb.length > start) spans += MdSpan(start, sb.length, MdStyle.CODE)
                continue
            }
            if (RULE_RX.matches(raw)) continue
            newline()
            val h = HEADING_RX.matchEntire(trimmed)
            val b = BULLET_RX.matchEntire(raw)
            when {
                h != null -> {
                    val start = sb.length
                    inline(h.groupValues[1], sb, spans)
                    spans += MdSpan(start, sb.length, MdStyle.HEADING)
                }
                b != null -> {
                    val indent = minOf(b.groupValues[1].length / 2, 2)
                    sb.append("  ".repeat(indent)).append("• ")
                    inline(b.groupValues[2], sb, spans)
                }
                else -> inline(raw.trimEnd(), sb, spans)
            }
        }
        return MdParsed(sb.toString(), spans.filter { it.end > it.start })
    }

    private fun inline(s: String, sb: StringBuilder, spans: MutableList<MdSpan>) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '*' && s.startsWith("**", i)) {
                val close = s.indexOf("**", i + 2)
                if (close > i + 2) {
                    val start = sb.length
                    inline(s.substring(i + 2, close), sb, spans)
                    spans += MdSpan(start, sb.length, MdStyle.BOLD)
                    i = close + 2
                    continue
                }
            }
            if (c == '`') {
                val close = s.indexOf('`', i + 1)
                if (close > i + 1) {
                    val start = sb.length
                    sb.append(s, i + 1, close)
                    spans += MdSpan(start, sb.length, MdStyle.CODE)
                    i = close + 1
                    continue
                }
            }
            if (c == '[') {
                val mid = s.indexOf("](", i + 1)
                if (mid > i) {
                    val end = closingParen(s, mid + 2)
                    if (end > mid + 2) {
                        val url = s.substring(mid + 2, end).trim()
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val start = sb.length
                            sb.append(s, i + 1, mid)
                            spans += MdSpan(start, sb.length, MdStyle.LINK, url)
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            sb.append(c)
            i++
        }
    }

    /** Закрывающая скобка адреса с учётом вложенных (в адресах Википедии они бывают); -1, если нет. */
    private fun closingParen(s: String, from: Int): Int {
        var depth = 0
        for (j in from until s.length) {
            when (s[j]) {
                '(' -> depth++
                ')' -> if (depth == 0) return j else depth--
            }
        }
        return -1
    }
}
