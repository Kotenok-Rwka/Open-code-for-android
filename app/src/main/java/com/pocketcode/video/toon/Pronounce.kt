package com.pocketcode.video.toon

/**
 * Подготовка текста для голоса: числа, проценты, время и сокращения записываются словами, как их произносят.
 * Свой словарь «слово=как говорить» применяется раньше и позволяет поправить ударения и необычные слова.
 */
object Pronounce {
    private val ones = listOf(
        "ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать",
        "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать",
    )
    private val fem = mapOf(1 to "одна", 2 to "две")
    private val tens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val hundreds = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")

    private fun triad(n: Int, feminine: Boolean): List<String> {
        val out = ArrayList<String>()
        val h = n / 100
        val t = n % 100
        if (h > 0) out += hundreds[h]
        if (t < 20) {
            if (t > 0) out += if (feminine && t in 1..2) fem.getValue(t) else ones[t]
        } else {
            out += tens[t / 10]
            val o = t % 10
            if (o > 0) out += if (feminine && o in 1..2) fem.getValue(o) else ones[o]
        }
        return out
    }

    /** Склонение слова после числа: 1 процент, 2 процента, 5 процентов, 11 процентов. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val m100 = (n % 100).toInt()
        val m10 = (n % 10).toInt()
        return when {
            m100 in 11..14 -> many
            m10 == 1 -> one
            m10 in 2..4 -> few
            else -> many
        }
    }

    /** Число словами (именительный падеж). Очень большие числа читаются по цифрам. */
    fun words(n: Long, feminine: Boolean = false): String {
        if (n < 0) return "минус " + words(-n, feminine)
        if (n == 0L) return ones[0]
        if (n >= 1_000_000_000_000L) return n.toString().map { ones[it - '0'] }.joinToString(" ")
        val parts = ArrayList<String>()
        var rest = n
        val groups = listOf(
            Triple(1_000_000_000L, listOf("миллиард", "миллиарда", "миллиардов"), false),
            Triple(1_000_000L, listOf("миллион", "миллиона", "миллионов"), false),
            Triple(1_000L, listOf("тысяча", "тысячи", "тысяч"), true),
        )
        for ((size, forms, f) in groups) {
            val q = (rest / size).toInt()
            if (q > 0) {
                parts += triad(q, f)
                parts += plural(q.toLong(), forms[0], forms[1], forms[2])
                rest %= size
            }
        }
        if (rest > 0) parts += triad(rest.toInt(), feminine)
        return parts.joinToString(" ")
    }

    private fun num(s: String): String = if (s.length > 12) s.map { ones[it - '0'] }.joinToString(" ") else words(s.toLong())

    private val markupRx = Regex("""[*_#~`]""")
    private val emojiRx = Regex("""[\x{1F000}-\x{1FAFF}←-⯿️‍]""")
    private val abbr = listOf(
        Regex("""(?iu)(?<![\p{L}])т\.\s?е\.""") to "то есть",
        Regex("""(?iu)(?<![\p{L}])т\.\s?д\.""") to "так далее",
        Regex("""(?iu)(?<![\p{L}])т\.\s?п\.""") to "тому подобное",
        Regex("""(?iu)(?<![\p{L}])напр\.""") to "например",
        Regex("""(?iu)(?<![\p{L}])см\.""") to "смотри",
    )
    private val timeRx = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?![\d:])""")
    private val decimalRx = Regex("""(?<![\d.,])(\d+)[.,](\d{1,2})(?![\d])""")
    private val unitRx = Regex("""(?iu)(\d+)\s*(%|₽|руб\.?|км|кг|°\s?C|°)(?![\p{L}])""")

    /** Убирает разметку и значки, которые голос не должен читать. */
    fun clean(text: String): String =
        emojiRx.replace(markupRx.replace(text, ""), "").replace(Regex("""[ \t]+"""), " ").trim()

    private fun unit(n: Long, u: String): String {
        val k = u.lowercase().replace(" ", "")
        return when {
            k == "%" -> plural(n, "процент", "процента", "процентов")
            k == "₽" || k.startsWith("руб") -> plural(n, "рубль", "рубля", "рублей")
            k == "км" -> plural(n, "километр", "километра", "километров")
            k == "кг" -> plural(n, "килограмм", "килограмма", "килограммов")
            k == "°c" -> plural(n, "градус", "градуса", "градусов") + " Цельсия"
            else -> plural(n, "градус", "градуса", "градусов")
        }
    }

    /** Числа, время, проценты и сокращения → слова. */
    fun normalize(text: String): String {
        var t = clean(text)
        for ((rx, to) in abbr) t = rx.replace(t, to)
        t = timeRx.replace(t) { m ->
            val h = m.groupValues[1].toInt()
            val mi = m.groupValues[2]
            val minutes = when {
                mi == "00" -> "ноль ноль"
                mi.startsWith("0") -> "ноль " + words(mi.toLong())
                else -> words(mi.toLong())
            }
            words(h.toLong()) + " " + minutes
        }
        t = decimalRx.replace(t) { m ->
            val whole = m.groupValues[1].toLong()
            val fr = m.groupValues[2]
            val f = fr.toLong()
            val wholeForm = if (whole % 10 == 1L && whole % 100 != 11L) "целая" else "целых"
            val fracForm = when (fr.length) {
                1 -> if (f % 10 == 1L && f != 11L) "десятая" else "десятых"
                else -> if (f % 10 == 1L && f % 100 != 11L) "сотая" else "сотых"
            }
            val wholeWords = words(whole, feminine = true)
            val fracWords = words(f, feminine = true)
            "$wholeWords $wholeForm $fracWords $fracForm"
        }
        t = unitRx.replace(t) { m ->
            val n = m.groupValues[1]
            val big = n.length > 12
            if (big) m.value else words(n.toLong()) + " " + unit(n.toLong(), m.groupValues[2])
        }
        t = Regex("""\d+""").replace(t) { num(it.value) }
        t = t.replace("&", " и ").replace("=", " равно ")
        t = Regex("""\s[—–-]\s""").replace(t, ", ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    fun parseDict(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0) null else {
                val from = l.substring(0, i).trim()
                val to = l.substring(i + 1).trim()
                if (from.isEmpty() || to.isEmpty()) null else from to to
            }
        }

    fun applyDict(text: String, dict: List<Pair<String, String>>): String {
        var t = text
        for ((from, to) in dict) {
            val rx = Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(from) + "(?![\\p{L}\\p{N}])")
            t = rx.replace(t, Regex.escapeReplacement(to))
        }
        return t
    }

    /** Итоговый текст для голоса. auto=false — режим «пишу как слышу»: только словарь и очистка. */
    fun forVoice(sub: String, say: String?, dict: List<Pair<String, String>>, auto: Boolean): String {
        if (say != null) return clean(applyDict(say, dict))
        val d = applyDict(sub, dict)
        return if (auto) normalize(d) else clean(d)
    }
}
