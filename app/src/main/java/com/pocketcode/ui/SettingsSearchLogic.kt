package com.pocketcode.ui

/** Результат поиска: сами пункты, найдены ли они только по части слов и подправлена ли раскладка. */
class SearchResult(val entries: List<SettingEntry>, val partial: Boolean, val layoutFixed: Boolean)

/**
 * Поиск по пунктам без сети и Android, поэтому его проверяют unit-тесты.
 * Находит по любому слову: по началу слова (окончания не мешают), с опечатками и при неверной раскладке.
 * Если по всем словам ничего нет, показывает пункты, подошедшие хотя бы по одному слову.
 */
object SearchLogic {
    private const val LAT = "qwertyuiop[]asdfghjkl;'zxcvbnm,."
    private const val CYR = "йцукенгшщзхъфывапролджэячсмитьбю"

    fun words(s: String): List<String> =
        s.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Слово, набранное в английской раскладке, превращается в русское (ghbdtn → привет). */
    fun fixLayout(s: String): String = buildString {
        for (c in s.lowercase()) {
            val i = LAT.indexOf(c)
            append(if (i >= 0) CYR[i] else c)
        }
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 3 — слово входит целиком, 2 — то же начало (другое окончание), 1 — опечатка, 0 — не подходит. */
    fun wordScore(token: String, word: String): Int {
        if (token.length < 2 || word.isEmpty()) return 0
        if (word == token || word.contains(token)) return 3
        if (token.length >= 3) {
            val cp = commonPrefix(token, word)
            if (cp >= 3 && cp >= minOf(token.length, word.length) - 2) return 2
        }
        if (token.length >= 4 && kotlin.math.abs(token.length - word.length) <= 2) {
            val limit = if (token.length >= 8) 2 else 1
            if (distance(token, word) <= limit) return 1
        }
        return 0
    }

    private fun hayWords(e: SettingEntry): List<String> = words(e.title + " " + e.words + " " + e.where)

    private fun tokenScore(token: String, hay: List<String>): Int = hay.maxOfOrNull { wordScore(token, it) } ?: 0

    private fun runSearch(index: List<SettingEntry>, tokens: List<String>, all: Boolean): List<SettingEntry> {
        val scored = index.mapNotNull { e ->
            val hay = hayWords(e)
            val per = tokens.map { tokenScore(it, hay) }
            val ok = if (all) per.all { it > 0 } else per.any { it > 0 }
            if (!ok) null else e to (per.sum() + tokens.count { t -> words(e.title).any { it.startsWith(t) } })
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    fun search(index: List<SettingEntry>, query: String): SearchResult {
        val tokens = words(query)
        if (tokens.isEmpty()) return SearchResult(index, partial = false, layoutFixed = false)
        runSearch(index, tokens, true).let { if (it.isNotEmpty()) return SearchResult(it, partial = false, layoutFixed = false) }
        if (tokens.any { t -> t.all { it in LAT || it.isDigit() } }) {
            val fixed = words(fixLayout(query))
            if (fixed.isNotEmpty() && fixed != tokens) {
                runSearch(index, fixed, true).let { if (it.isNotEmpty()) return SearchResult(it, partial = false, layoutFixed = true) }
            }
        }
        if (tokens.size > 1) runSearch(index, tokens, false).let { if (it.isNotEmpty()) return SearchResult(it, partial = true, layoutFixed = false) }
        return SearchResult(emptyList(), partial = false, layoutFixed = false)
    }

    /** Похожие слова из самих пунктов: другие, не совпадающие с набранным; чем ближе, тем раньше. */
    fun similarWords(index: List<SettingEntry>, query: String, limit: Int = 6): List<String> {
        val tokens = words(query)
        if (tokens.isEmpty()) return emptyList()
        val vocab = index.flatMap { hayWords(it) }.filter { it.length >= 3 && !it.all { c -> c.isDigit() } }.distinct()
        val seen = HashSet<String>(tokens)
        val out = ArrayList<String>()
        for (pass in 0..1) {
            if (pass == 1 && out.isNotEmpty()) break
            val list = if (pass == 0) tokens else words(fixLayout(query)).filter { it !in tokens }
            for (t in list) addNear(t, vocab, seen, out, limit)
        }
        return out
    }

    private fun addNear(t: String, vocab: List<String>, seen: MutableSet<String>, out: MutableList<String>, limit: Int) {
        val near = vocab.filter { it !in seen }.mapNotNull { w ->
            val d = distance(t, w)
            val cp = commonPrefix(t, w)
            val rank = when {
                t.length >= 2 && w.startsWith(t) -> 0
                t.length >= 3 && w.contains(t) -> 1
                cp >= 2 && cp >= minOf(t.length, w.length) / 2 -> 2 + (maxOf(t.length, w.length) - cp)
                d <= maxOf(2, t.length / 3) -> 10 + d
                else -> -1
            }
            if (rank < 0) null else w to rank
        }.sortedWith(compareBy<Pair<String, Int>>({ it.second }, { it.first.length })).map { it.first }
        for (w in near) {
            if (out.size >= limit) break
            if (seen.add(w)) out += w
        }
    }
}
