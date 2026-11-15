package com.pocketcode.assist

/** Страница из выдачи поиска. */
data class SearchHit(val title: String, val url: String, val snippet: String) {
    val domain: String get() = AssistText.domain(url)
}

/** Небольшие чистые функции: разбор выдачи поиска, выбор источников, подготовка текста с экрана. */
object AssistText {
    private val NUM_RX = Regex("^\\d+\\.\\s+(.*)$")

    /** Адрес сайта без «www.»; если адрес разобрать не вышло, возвращает его как есть. */
    fun domain(url: String): String {
        val t = url.trim()
        val host = try {
            java.net.URI(t).host
        } catch (_: Exception) {
            null
        }
        val h = (host ?: t.substringAfter("://").substringBefore('/').substringBefore('?')).lowercase().removePrefix("www.")
        return h.ifBlank { t }
    }

    /** Разбирает ответ инструмента web_search: «1. Заголовок», на следующей строке адрес, затем фрагмент. */
    fun parseSearch(output: String): List<SearchHit> {
        val lines = output.lines()
        val out = ArrayList<SearchHit>()
        var i = 0
        while (i < lines.size) {
            val m = NUM_RX.matchEntire(lines[i].trim())
            if (m == null) {
                i++
                continue
            }
            val url = lines.getOrNull(i + 1)?.trim().orEmpty()
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                i++
                continue
            }
            var snippet = ""
            var next = i + 2
            val cand = lines.getOrNull(i + 2)?.trim().orEmpty()
            if (cand.isNotEmpty() && NUM_RX.matchEntire(cand) == null) {
                snippet = cand
                next = i + 3
            }
            val title = m.groupValues[1].trim().ifEmpty { domain(url) }
            out += SearchHit(title, url, snippet)
            i = next
        }
        return out
    }

    fun normUrl(u: String): String = u.trim().substringBefore('#').trimEnd('/').lowercase()

    /** Объединяет выдачи нескольких поисков без повторов, порядок сохраняется. */
    fun mergeHits(a: List<SearchHit>, b: List<SearchHit>): List<SearchHit> {
        val seen = HashSet<String>()
        val out = ArrayList<SearchHit>()
        for (h in a + b) if (seen.add(normUrl(h.url))) out += h
        return out
    }

    /** Источники ответа: сначала страницы, которые ассистент открывал и читал; если не открывал ни одной, лучшие результаты поиска. */
    fun pickSources(hits: List<SearchHit>, fetchedUrls: List<String>, limit: Int = 4): List<SearchHit> {
        val byUrl = hits.associateBy { normUrl(it.url) }
        val out = LinkedHashMap<String, SearchHit>()
        for (u in fetchedUrls) {
            val k = normUrl(u)
            if (!out.containsKey(k)) out[k] = byUrl[k] ?: SearchHit(domain(u), u, "")
        }
        if (out.isEmpty()) {
            for (h in hits) {
                val k = normUrl(h.url)
                if (!out.containsKey(k)) out[k] = h
            }
        }
        return out.values.take(limit)
    }

    /** Первое предложение ответа без разметки, не длиннее [max] знаков: для уведомления и предложения «как показать». */
    fun firstSentence(text: String, max: Int): String {
        val plain = AssistMarkdown.parse(text).text.trim()
        val line = plain.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        var cut = line.length
        val m = Regex("[.!?…](\\s|$)").find(line, minOf(15, line.length))
        if (m != null) cut = m.range.first + 1
        val r = line.substring(0, cut)
        return if (r.length > max) r.take(max - 1).trimEnd() + "…" else r
    }

    /** Поле пароля по типу ввода Android (InputType): его содержимое никому не отправляем. */
    fun isPasswordInputType(t: Int): Boolean {
        val cls = t and 0xf
        val variation = t and 0xff0
        return when (cls) {
            1 -> variation == 0x80 || variation == 0x90 || variation == 0xe0
            2 -> variation == 0x10
            else -> false
        }
    }

    /** Склеивает строки с экрана: пробелы схлопываются, подряд идущие повторы и пустые строки убираются, общая длина не больше [max]. */
    fun joinScreen(lines: List<String>, max: Int): String {
        val sb = StringBuilder()
        var prev = ""
        for (raw in lines) {
            val l = raw.replace(Regex("\\s+"), " ").trim()
            if (l.isEmpty() || l == prev) continue
            if (sb.isEmpty() && l.length > max) {
                sb.append(l.take(max))
                break
            }
            if (sb.length + l.length + 1 > max) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(l)
            prev = l
        }
        return sb.toString()
    }
}
