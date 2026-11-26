package com.pocketcode.video

import kotlin.math.abs

/**
 * Batch 27: доработка режима видео.
 * 1. Ключевое слово в субтитрах: самое «длинное/значимое» слово фразы подсвечивается
 *    цветом акцента (TikTok-стиль) в стилях CLASSIC/BOX/POP/NEON/COMIC.
 * 2. Авто-раскадровка: детект склеек по скачкам отпечатков кадров, из склеек
 *    строятся TimeRange — «Видео → клип» в один тап.
 * 3. Пресеты платформ: TikTok / Shorts / Reels с безопасными зонами (доля кадра,
 *    свободная от кнопок интерфейса) — субтитры не залезают под кнопки.
 */

object SubHighlight {
    /** Стоп-слова: их не подсвечиваем. */
    private val STOP = setOf(
        "и", "в", "во", "не", "что", "он", "на", "я", "с", "со", "как", "а", "то", "все", "она", "так",
        "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее", "мне", "было",
        "вот", "от", "меня", "еще", "нет", "о", "из", "ему", "теперь", "когда", "даже", "ну", "вдруг",
        "ли", "если", "уже", "или", "ни", "быть", "был", "него", "до", "вас", "нибудь", "опять", "уж",
        "вам", "ведь", "там", "потом", "себя", "ничего", "ей", "они", "тут", "где", "есть", "надо",
        "the", "a", "an", "and", "or", "but", "is", "are", "was", "were", "to", "of", "in", "on", "it",
        "this", "that", "i", "you", "he", "she", "we", "they", "my", "your",
    )

    /** Нормализация слова для сравнения со стоп-листом. */
    fun norm(w: String): String =
        w.trim().trimStart('«', '"', '(', '-', '–').trimEnd(',', '.', '!', '?', ':', ';', '»', '"', ')', '…').lowercase()

    private fun tidy(s: String) = norm(s)

    /**
     * Индекс ключевого слова фразы: самое длинное не-стоп слово; при равенстве длины —
     * то, что произносится дольше. Одно слово — 0, пустая фраза — -1.
     */
    fun keyIndex(words: List<PhraseWord>): Int {
        if (words.isEmpty()) return -1
        if (words.size == 1) return 0
        var best = -1
        var bestLen = -1
        var bestSpan = -1.0
        for ((i, w) in words.withIndex()) {
            val t = tidy(w.text)
            if (norm(t) in STOP) continue
            val len = t.length
            val span = w.end - w.start
            if (len > bestLen || (len == bestLen && span > bestSpan)) {
                best = i; bestLen = len; bestSpan = span
            }
        }
        return if (bestLen < 2) 0 else best // совсем короткие слова — первое
    }

    /** ASS-цвет акцента для подсветки (&HBBGGRR&), с учётом SubTweak; по умолчанию жёлтый. */
    fun accentColor(t: SubTweak): String {
        val h = t.accent.trim().removePrefix("#")
        if (Regex("[0-9a-fA-F]{6}").matches(h)) {
            return "&H" + h.substring(4, 6) + h.substring(2, 4) + h.substring(0, 2) + "&"
        }
        return "&H00F0FF&" // жёлтый (BGR)
    }
}

/** Пресеты платформ: пропорции кадра и безопасные зоны для субтитров. */
data class PlatformPreset(
    val id: String,
    val label: String,
    /** Целевые пропорции кадра (w/h). */
    val aspect: Double,
    /** Безопасная зона по вертикали: [доля сверху, доля снизу], свободная от кнопок. */
    val safeTop: Double,
    val safeBottom: Double,
) {
    companion object {
        val ALL = listOf(
            PlatformPreset("tiktok", "TikTok", 9.0 / 16.0, 0.10, 0.18),
            PlatformPreset("shorts", "YouTube Shorts", 9.0 / 16.0, 0.09, 0.14),
            PlatformPreset("reels", "Instagram Reels", 9.0 / 16.0, 0.11, 0.20),
            PlatformPreset("square", "Квадрат (лента)", 1.0, 0.06, 0.06),
            PlatformPreset("wide", "16:9 (YouTube)", 16.0 / 9.0, 0.05, 0.05),
        )

        fun byId(id: String?): PlatformPreset? = ALL.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** Подвинуть вертикаль субтитров (доля высоты 0..1) в безопасную зону. */
        fun safeY(p: PlatformPreset, y: Double): Double {
            val v = y.coerceIn(0.0, 1.0)
            return when {
                v < p.safeTop -> p.safeTop
                v > 1.0 - p.safeBottom -> 1.0 - p.safeBottom
                else -> v
            }
        }
    }
}

/** Детект склеек/смены сцены по отпечаткам кадров — для авто-раскадровки. */
object SceneCuts {

    /**
     * Моменты смены сцены: кадры, чей отпечаток резко отличается от предыдущего.
     * [fingerprints] — по числу на кадр (например, средняя яркость).
     */
    fun detect(fingerprints: DoubleArray, fps: Double, threshold: Double = 26.0, minGap: Double = 0.8): List<Double> {
        if (fingerprints.size < 2 || fps <= 0.0) return emptyList()
        val out = ArrayList<Double>()
        val minFrames = (minGap * fps).toInt().coerceAtLeast(1)
        var lastCut = -minFrames
        for (i in 1 until fingerprints.size) {
            if (abs(fingerprints[i] - fingerprints[i - 1]) >= threshold && i - lastCut >= minFrames) {
                out += i / fps
                lastCut = i
            }
        }
        return out
    }

    /**
     * Раскадровка из моментов склеек: интервалы, обрезанные по [minLen]/[maxLen];
     * короткие хвосты приклеиваются к предыдущему интервалу.
     */
    fun ranges(cuts: List<Double>, duration: Double, minLen: Double = 1.2, maxLen: Double = 60.0): List<TimeRange> {
        if (duration <= 0.0) return emptyList()
        val points = ArrayList<Double>()
        for (v in listOf(0.0) + cuts.filter { it in 0.05..duration - 0.05 }.distinct().sorted() + duration) {
            if (points.isEmpty() || v - points.last() > 0.05) points.add(v)
        }
        val out = ArrayList<TimeRange>()
        for (i in 0 until points.size - 1) {
            val s = points[i]; val e = points[i + 1]
            val len = e - s
            when {
                len < minLen && out.isNotEmpty() -> {
                    val last = out.removeAt(out.size - 1)
                    out += TimeRange(last.start, e)
                }
                len < minLen -> out += TimeRange(s, min(e, s + minLen))
                len <= maxLen -> out += TimeRange(s, e)
                else -> {
                    var cur = s
                    while (e - cur > maxLen) { out += TimeRange(cur, cur + maxLen); cur += maxLen }
                    if (e - cur > 0.05) out += TimeRange(cur, e)
                }
            }
        }
        return out
    }
}
