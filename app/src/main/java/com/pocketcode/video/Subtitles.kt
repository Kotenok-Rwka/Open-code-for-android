package com.pocketcode.video

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Где стоят субтитры (у «Громко» всегда по центру). */
enum class SubPos(val label: String) { DEFAULT("Как в стиле"), BOTTOM("Снизу"), MIDDLE("По центру"), TOP("Сверху") }

/** Подстройка выбранного стиля: цвет акцента (RRGGBB; пусто — как в стиле), размер и положение. */
data class SubTweak(val accent: String = "", val scale: Double = 1.0, val position: SubPos = SubPos.DEFAULT) {
    val isDefault: Boolean get() = accent.isEmpty() && scale == 1.0 && position == SubPos.DEFAULT

    companion object {
        /** Цвета на выбор в интерфейсе. */
        val COLORS = listOf("FFF000" to "Жёлтый", "FFFFFF" to "Белый", "50E850" to "Зелёный", "FF50B4" to "Розовый", "40C8FF" to "Голубой", "FF8C28" to "Оранжевый")
        val SCALES = listOf(0.8, 1.0, 1.25, 1.5)
    }
}

enum class SubStyle(val label: String, val about: String) {
    CLASSIC("Классика", "Белый текст с обводкой, фраза целиком"),
    BOX("Плашка", "Текст на тёмной полупрозрачной плашке, фраза целиком"),
    KARAOKE("Караоке", "Фраза целиком, произносимое слово закрашивается жёлтым"),
    POP("Поп", "2–3 слова: слова выскакивают по очереди с «прыжком», текущее жёлтое"),
    BIG("Громко", "По одному слову на весь экран, цвет меняется, слово «выпрыгивает»"),
    NEON("Неон", "Светящийся розовый и голубой текст, 2–3 слова"),
    COMIC("Комикс", "Жёлтые буквы с толстой чёрной обводкой, наклон и «удар»"),
}

/** Стиль по имени (для агента); неизвестное имя даёт «Классику». */
fun styleByName(s: String?): SubStyle = when (s?.trim()?.lowercase()) {
    "box", "плашка" -> SubStyle.BOX
    "karaoke", "караоке" -> SubStyle.KARAOKE
    "pop", "поп", "динамичные" -> SubStyle.POP
    "big", "громко" -> SubStyle.BIG
    "neon", "неон" -> SubStyle.NEON
    "comic", "комикс" -> SubStyle.COMIC
    else -> SubStyle.CLASSIC
}

data class PhraseWord(val text: String, val start: Double, val end: Double)

/** Одна надпись на экране. Времена уже в шкале готового (смонтированного) видео. */
data class Phrase(val start: Double, val end: Double, val words: List<PhraseWord>) {
    val text: String get() = words.joinToString(" ") { it.text }
}

object Subtitles {
    private class Rule(val maxWords: Int, val maxChars: Int, val maxSpan: Double, val sentence: Boolean, val sentenceMin: Int, val hold: Double, val minShow: Double)

    private fun rule(s: SubStyle) = when (s) {
        SubStyle.CLASSIC, SubStyle.BOX -> Rule(99, 70, 5.0, true, 24, 0.25, 0.3)
        SubStyle.KARAOKE -> Rule(6, 38, 4.0, true, 0, 0.15, 0.3)
        SubStyle.POP, SubStyle.NEON -> Rule(3, 26, 3.0, true, 0, 0.05, 0.3)
        SubStyle.COMIC -> Rule(4, 30, 3.0, true, 0, 0.1, 0.3)
        SubStyle.BIG -> Rule(1, 40, 2.0, false, 0, 0.0, 0.12)
    }

    /**
     * Раскладывает распознанные слова по готовому видео: слова из вырезанных кусков пропадают,
     * остальные сдвигаются. Надпись никогда не пересекает место склейки.
     * [keep] — оставляемые куски исходного видео по порядку (для видео без монтажа это один кусок на всё видео).
     * [starts] — где в готовом видео начинается каждый кусок; если не задано, куски идут встык.
     */
    fun build(tr: Transcript, keep: List<TimeRange>, style: SubStyle, starts: DoubleArray? = null): List<Phrase> {
        if (keep.isEmpty()) return emptyList()
        val rule = rule(style)
        val src = wordsOf(tr)
        val prefix = DoubleArray(keep.size)
        var acc = 0.0
        for (i in keep.indices) { prefix[i] = starts?.get(i) ?: acc; acc += keep[i].length }

        class W(val idx: Int, val w: PhraseWord)
        val mapped = ArrayList<W>()
        for (w in src.sortedBy { it.start }) {
            val mid = (w.start + w.end) / 2
            val idx = keep.indexOfFirst { mid >= it.start && mid <= it.end }
            if (idx < 0) continue
            val r = keep[idx]
            val s = prefix[idx] + (w.start.coerceIn(r.start, r.end) - r.start)
            val e = prefix[idx] + (w.end.coerceIn(r.start, r.end) - r.start)
            mapped += W(idx, PhraseWord(clean(w.text), s, max(e, s + 0.05)))
        }

        val out = ArrayList<Phrase>()
        var cur = ArrayList<PhraseWord>()
        var curIdx = -1
        fun flush() {
            if (cur.isNotEmpty()) out += Phrase(cur.first().start, cur.last().end, cur.toList())
            cur = ArrayList()
        }
        for (m in mapped) {
            if (m.w.text.isEmpty()) continue
            if (cur.isNotEmpty()) {
                val prev = cur.last()
                val chars = cur.sumOf { it.text.length + 1 } + m.w.text.length
                val span = m.w.end - cur.first().start
                val brk = m.idx != curIdx ||
                    m.w.start - prev.end > 0.7 ||
                    cur.size >= rule.maxWords ||
                    chars > rule.maxChars ||
                    span > rule.maxSpan ||
                    (rule.sentence && endsSentence(prev.text) && chars > rule.sentenceMin)
                if (brk) flush()
            }
            curIdx = m.idx
            cur += m.w
        }
        flush()

        // Держим надпись чуть дольше слова, но не дольше начала следующей: без мигания и без наложения
        return out.mapIndexed { i, p ->
            val next = out.getOrNull(i + 1)
            var end = p.end + rule.hold
            if (next != null) end = min(end, max(next.start, p.end))
            val floor = p.start + rule.minShow
            end = max(end, if (next != null) min(floor, max(next.start, p.end)) else floor)
            Phrase(p.start, end, p.words)
        }
    }

    private fun clean(s: String) = s.replace('\n', ' ').replace("\\", "").replace("{", "(").replace("}", ")").trim()

    private fun endsSentence(s: String) = s.trimEnd('"', '»', ')').lastOrNull() in setOf('.', '!', '?', '…')

    /** Слова с временем: от сервиса, а если их нет, то оценённые по фразам. */
    fun wordsOf(tr: Transcript): List<Word> = if (tr.words.isNotEmpty()) tr.words else estimateWords(tr.segments)

    /** Если сервис не вернул слова: делим время фразы между словами пропорционально их длине. */
    private fun estimateWords(segs: List<Segment>): List<Word> {
        val out = ArrayList<Word>()
        for (s in segs) {
            val toks = s.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (toks.isEmpty()) continue
            val total = toks.sumOf { it.length + 1 }.toDouble()
            var t = s.start
            for (tok in toks) {
                val d = (s.end - s.start) * (tok.length + 1) / total
                out += Word(tok, t, t + d)
                t += d
            }
        }
        return out
    }

    // ---------- форматы ----------

    private fun assTime(t: Double): String {
        val cs = (max(t, 0.0) * 100).roundToInt()
        return String.format(Locale.US, "%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100)
    }

    private fun srtTime(t: Double): String {
        val ms = (max(t, 0.0) * 1000).roundToInt()
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /** SRT для плееров и соцсетей. */
    fun srt(phrases: List<Phrase>): String {
        val sb = StringBuilder()
        for ((i, p) in phrases.withIndex()) {
            sb.append(i + 1).append('\n')
            sb.append(srtTime(p.start)).append(" --> ").append(srtTime(p.end)).append('\n')
            sb.append(wrap(p.text)).append("\n\n")
        }
        return sb.toString()
    }

    /** Одна строка не длиннее ~34 знаков, иначе ломаем пополам по ближайшему к середине пробелу. */
    private fun wrap(text: String): String {
        if (text.length <= 34) return text
        val mid = text.length / 2
        var best = -1
        for (i in text.indices) if (text[i] == ' ' && (best < 0 || abs(i - mid) < abs(best - mid))) best = i
        return if (best < 0) text else text.substring(0, best) + "\n" + text.substring(best + 1)
    }

    // ---------- подгонка текста под ширину кадра ----------

    /** Ширина символа жирного DejaVu Sans в долях размера шрифта (с небольшим запасом). */
    private fun glyphEm(c: Char): Double = when {
        c == ' ' -> 0.35
        c.isDigit() -> 0.70
        c.isLetter() && c.isUpperCase() -> if (c.code in 0x400..0x4FF) 0.88 else 0.78
        c.isLetter() -> if (c.code in 0x400..0x4FF) 0.70 else 0.66
        else -> 0.46
    }

    private fun widthEm(s: String): Double {
        var t = 0.0
        for (c in s) t += glyphEm(c)
        return t
    }

    /** Раскладка надписи: после каких слов делать перенос строки и какой размер шрифта взять. */
    private class Fit(val breaks: Set<Int>, val size: Int)

    /**
     * Подбирает переносы (не больше [maxLines] строк) и размер шрифта так, чтобы надпись целиком влезала в [avail] пикселей.
     * Шрифт уменьшается, только если иначе не помещается. [grow] — во сколько раз надпись раздувается в анимации.
     * [softChars] — при большей длине текста сразу делим на несколько строк, даже если в кадр влезло бы в одну.
     */
    private fun fit(words: List<String>, size: Int, avail: Double, maxLines: Int, grow: Double = 1.0, softChars: Int = 0): Fit {
        val n = words.size
        if (n == 0) return Fit(emptySet(), size)
        val limit = avail / grow
        val wEm = DoubleArray(n) { widthEm(words[it]) }
        val space = glyphEm(' ')
        fun lineEm(a: Int, b: Int): Double {
            var t = 0.0
            for (i in a..b) t += wEm[i]
            return t + space * (b - a)
        }
        val maxK = minOf(maxLines, n).coerceAtLeast(1)
        val inf = Double.MAX_VALUE
        // dp[k][i]: самая широкая строка при лучшем разбиении первых i слов на k строк
        val dp = Array(maxK + 1) { DoubleArray(n + 1) { inf } }
        val cut = Array(maxK + 1) { IntArray(n + 1) }
        dp[0][0] = 0.0
        for (k in 1..maxK) for (i in k..n) for (j in (k - 1) until i) {
            if (dp[k - 1][j] == inf) continue
            val v = maxOf(dp[k - 1][j], lineEm(j, i - 1))
            if (v < dp[k][i]) { dp[k][i] = v; cut[k][i] = j }
        }
        val chars = words.sumOf { it.length } + n - 1
        val first = if (softChars > 0) ((chars + softChars - 1) / softChars).coerceIn(1, maxK) else 1
        var bestK = first
        var bestSize = -1
        for (k in first..maxK) {
            val em = dp[k][n]
            val sz = if (em * size <= limit) size else maxOf((limit / em).toInt(), size * 2 / 5, 8)
            if (sz > bestSize + 1) { bestSize = sz; bestK = k }
            if (sz == size) break
        }
        val br = HashSet<Int>()
        var i = n
        for (k in bestK downTo 2) {
            val j = cut[k][i]
            br += j - 1
            i = j
        }
        return Fit(br, bestSize)
    }

    private fun joinFit(words: List<String>, f: Fit): String =
        words.withIndex().joinToString("") { (i, x) -> x + if (i in f.breaks) "\\N" else if (i < words.lastIndex) " " else "" }

    private data class Spec(
        val fs: Int, val primary: String, val secondary: String, val outlineColor: String, val backColor: String,
        val outline: Double, val shadow: Double, val border: Int, val align: Int, val mv: Int,
    )

    // Цвета: в стиле формат &HAABBGGRR, в тегах \c и \3c формат &HBBGGRR&
    private const val YELLOW_STYLE = "&H0000F0FF"
    private const val YELLOW = "&H00F0FF&"
    private const val WHITE = "&HFFFFFF&"
    private val BIG_COLORS = listOf("&HFFFFFF&", "&H00F0FF&", "&H50E850&", "&HB450FF&")

    private fun spec(style: SubStyle, w: Int, h: Int): Spec {
        val base = min(w, h).coerceAtLeast(240)
        val portrait = h > w
        fun mv(p: Double, l: Double) = (h * if (portrait) p else l).roundToInt()
        fun fs(f: Double) = (base * f).roundToInt()
        return when (style) {
            SubStyle.CLASSIC -> fs(0.052).let { Spec(it, "&H00FFFFFF", "&H00FFFFFF", "&H00000000", "&H64000000", it * 0.07, 1.0, 1, 2, mv(0.10, 0.07)) }
            SubStyle.BOX -> fs(0.05).let { Spec(it, "&H00FFFFFF", "&H00FFFFFF", "&H8C000000", "&H8C000000", it * 0.30, 0.0, 3, 2, mv(0.11, 0.08)) }
            SubStyle.KARAOKE -> fs(0.058).let { Spec(it, YELLOW_STYLE, "&H00FFFFFF", "&H00000000", "&H64000000", it * 0.08, 1.0, 1, 2, mv(0.16, 0.08)) }
            SubStyle.POP -> fs(0.085).let { Spec(it, "&H00FFFFFF", "&H00FFFFFF", "&H00000000", "&H64000000", it * 0.10, 2.0, 1, 2, mv(0.20, 0.10)) }
            SubStyle.BIG -> fs(0.13).let { Spec(it, "&H00FFFFFF", "&H00FFFFFF", "&H00000000", "&H64000000", it * 0.09, 3.0, 1, 5, 0) }
            SubStyle.NEON -> fs(0.065).let { Spec(it, "&H00FFFFFF", "&H00FFFFFF", "&H00FF40FF", "&H64000000", it * 0.03, 0.0, 1, 2, mv(0.16, 0.09)) }
            SubStyle.COMIC -> fs(0.09).let { Spec(it, YELLOW_STYLE, "&H0000F0FF", "&H00000000", "&H96000000", it * 0.15, 4.0, 1, 2, mv(0.22, 0.10)) }
        }
    }

    /** Цвет в формате тегов ASS (&HBBGGRR&); без подстройки жёлтый. */
    private fun accentTag(t: SubTweak): String = hexToAss(t.accent)?.let { "&H$it&" } ?: YELLOW

    private fun hexToAss(hex: String): String? {
        val h = hex.trim().removePrefix("#")
        if (!Regex("[0-9a-fA-F]{6}").matches(h)) return null
        return (h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2)).uppercase()
    }

    private fun tweaked(sp: Spec, style: SubStyle, h: Int, t: SubTweak): Spec {
        if (t.isDefault) return sp
        val k = t.scale.coerceIn(0.6, 1.6)
        var r = sp.copy(fs = (sp.fs * k).roundToInt().coerceAtLeast(8), outline = sp.outline * k, shadow = sp.shadow * k)
        val acc = hexToAss(t.accent)?.let { "&H00$it" }
        if (acc != null) {
            r = when (style) {
                SubStyle.CLASSIC, SubStyle.BOX -> r.copy(primary = acc, secondary = acc)
                SubStyle.KARAOKE -> r.copy(primary = acc)
                SubStyle.COMIC -> r.copy(primary = acc, secondary = acc)
                else -> r
            }
        }
        if (style != SubStyle.BIG) {
            r = when (t.position) {
                SubPos.DEFAULT -> r
                SubPos.BOTTOM -> r.copy(align = 2, mv = (h * 0.08).roundToInt())
                SubPos.MIDDLE -> r.copy(align = 5, mv = 0)
                SubPos.TOP -> r.copy(align = 8, mv = (h * 0.08).roundToInt())
            }
        }
        return r
    }

    private fun tidy(s: String) = s.trimEnd(',', '.', ';', ':')

    /**
     * ASS для вшивания через ffmpeg. [w] и [h] — размер готового кадра, поэтому масштабирования шрифта нет.
     * [shift] вычитается из времён (для частей, которые рендерятся отдельно).
     * Каждая надпись переносится и при нехватке места уменьшается так, чтобы целиком помещаться в кадр
     * (особенно важно для вертикального видео); для каждого размера создаётся свой стиль.
     */
    fun ass(phrases: List<Phrase>, style: SubStyle, w: Int, h: Int, shift: Double, tweak: SubTweak = SubTweak()): String {
        val sp = tweaked(spec(style, w, h), style, h, tweak)
        val accent = accentTag(tweak)
        val mx = (w * 0.06).roundToInt()
        // Плашка выступает за текст на величину обводки, это тоже должно поместиться
        val avail = (w - 2.0 * mx) - (if (sp.border == 3) 2 * sp.outline else 0.0)
        val sizes = LinkedHashMap<Int, String>()
        fun styleFor(size: Int): String = sizes.getOrPut(size) { if (size == sp.fs) "Default" else "S$size" }
        styleFor(sp.fs)
        val ev = StringBuilder()

        fun dl(layer: Int, s: Double, e: Double, size: Int, text: String) {
            if (e - s < 0.02) return
            ev.append("Dialogue: $layer,${assTime(s)},${assTime(e)},${styleFor(size)},,0,0,0,,").append(text).append('\n')
        }

        for ((pi, p) in phrases.withIndex()) {
            val s = p.start - shift
            val e = p.end - shift
            if (e <= 0.0) continue
            val ws = p.words
            when (style) {
                SubStyle.CLASSIC, SubStyle.BOX -> {
                    val words = ws.map { it.text }
                    val f = fit(words, sp.fs, avail, 3, softChars = 36)
                    dl(0, s, e, f.size, "{\\fad(90,90)}" + joinFit(words, f))
                }

                SubStyle.KARAOKE -> {
                    val f = fit(ws.map { it.text }, sp.fs, avail, 2, softChars = 32)
                    val text = ws.withIndex().joinToString("") { (i, x) ->
                        val until = if (i + 1 < ws.size) ws[i + 1].start else p.end
                        val k = ((until - x.start) * 100).roundToInt().coerceAtLeast(1)
                        "{\\k$k}" + x.text + if (i in f.breaks) "\\N" else if (i < ws.lastIndex) " " else ""
                    }
                    dl(0, s, e, f.size, "{\\fad(60,60)}$text")
                }

                // Слова появляются по очереди; ещё не сказанные скрыты (но место держат), поэтому строка не «ездит»
                SubStyle.POP -> {
                    val up = ws.map { tidy(it.text).uppercase() }
                    val f = fit(up, sp.fs, avail, 2, grow = 1.18)
                    for (k in ws.indices) {
                        val st = if (k == 0) s else ws[k].start - shift
                        val en = if (k + 1 < ws.size) ws[k + 1].start - shift else e
                        val text = buildString {
                            for ((i, t) in up.withIndex()) {
                                when {
                                    i < k -> append(t)
                                    i == k -> append("{\\c$accent\\fscx82\\fscy82\\t(0,90,\\fscx118\\fscy118)\\t(90,190,\\fscx100\\fscy100)}$t{\\r}")
                                    else -> append("{\\alpha&HFF&}$t{\\alpha&H00&}")
                                }
                                if (i < up.lastIndex) append(if (i in f.breaks) "\\N" else " ")
                            }
                        }
                        dl(0, st, en, f.size, text)
                    }
                }

                SubStyle.BIG -> {
                    val x = tidy(ws.first().text).uppercase()
                    val f = fit(listOf(x), sp.fs, avail, 1, grow = 1.12)
                    val color = BIG_COLORS[pi % BIG_COLORS.size]
                    val y = (h * if (h > w) 0.58 else 0.5).roundToInt()
                    dl(0, s, e, f.size, "{\\an5\\pos(${w / 2},$y)\\c$color\\fscx70\\fscy70\\t(0,90,\\fscx112\\fscy112)\\t(90,170,\\fscx100\\fscy100)}$x")
                }

                SubStyle.NEON -> {
                    val glow = if (pi % 2 == 0) "&HFF40FF&" else "&HFFE500&"
                    val words = ws.map { it.text }
                    val f = fit(words, sp.fs, avail, 2)
                    val k = f.size.toDouble() / sp.fs
                    val txt = joinFit(words, f)
                    val bord = String.format(Locale.US, "%.1f", 9 * k)
                    val blur = String.format(Locale.US, "%.1f", 12 * k)
                    val bord2 = String.format(Locale.US, "%.1f", 1.5 * k)
                    dl(0, s, e, f.size, "{\\fad(120,120)\\bord$bord\\3c$glow\\c$glow\\blur$blur\\alpha&H30&}$txt")
                    dl(1, s, e, f.size, "{\\fad(120,120)\\bord$bord2\\3c$glow\\c$WHITE\\blur0.8}$txt")
                }

                SubStyle.COMIC -> {
                    val words = tidy(p.text).uppercase().split(' ').filter { it.isNotEmpty() }
                    val f = fit(words, sp.fs, avail, 2, grow = 1.12)
                    dl(0, s, e, f.size, "{\\frz-3\\fad(30,40)\\fscx75\\fscy75\\t(0,110,\\fscx110\\fscy110)\\t(110,200,\\fscx100\\fscy100)}" + joinFit(words, f))
                }
            }
        }

        val sb = StringBuilder()
        sb.append("[Script Info]\nScriptType: v4.00+\nPlayResX: $w\nPlayResY: $h\nWrapStyle: 2\nScaledBorderAndShadow: yes\n\n")
        sb.append("[V4+ Styles]\n")
        sb.append("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
        for ((size, name) in sizes) {
            val k = size.toDouble() / sp.fs
            sb.append("Style: $name,DejaVu Sans,$size,${sp.primary},${sp.secondary},${sp.outlineColor},${sp.backColor},-1,0,0,0,100,100,0,0,${sp.border},${String.format(Locale.US, "%.1f", sp.outline * k)},${String.format(Locale.US, "%.1f", sp.shadow * k)},${sp.align},$mx,$mx,${sp.mv},1\n")
        }
        sb.append("\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
        sb.append(ev)
        return sb.toString()
    }
}
