package com.pocketcode.video.toon

/** Кусок субтитра: текст и время, когда он на экране (секунды от начала ролика). */
data class SubChunk(val text: String, val start: Double, val end: Double)

/** Нарезка реплики на короткие крупные субтитры, как в вертикальных роликах: по 2–4 слова на экране. */
object ToonSubs {
    private fun endsPhrase(w: String) = w.isNotEmpty() && w.last() in ".,!?…:;"

    fun chunks(text: String, maxWords: Int = 3, maxChars: Int = 22): List<String> {
        val words = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        val cur = ArrayList<String>()
        fun flush() {
            if (cur.isNotEmpty()) {
                out += cur.joinToString(" ")
                cur.clear()
            }
        }
        for (w in words) {
            val len = cur.sumOf { it.length + 1 } + w.length
            if (cur.isNotEmpty() && (cur.size >= maxWords || len > maxChars)) flush()
            cur += w
            if (endsPhrase(w)) flush()
        }
        flush()
        // одиночное короткое слово («и», «но») приклеиваем к соседнему куску
        var i = 0
        while (i < out.size - 1) {
            if (out[i].length <= 2 && !endsPhrase(out[i]) && out[i].length + 1 + out[i + 1].length <= maxChars + 6) {
                out[i + 1] = out[i] + " " + out[i + 1]
                out.removeAt(i)
            } else i++
        }
        return out
    }

    private const val VOWELS = "аеёиоуыэюяaeiouy"

    /**
     * Сколько времени занимает кусок: буквы + гласные (они тянутся дольше согласных)
     * + пауза после знака препинания. Так субтитры не отстают от речи на длинных словах.
     */
    private fun weight(chunk: String): Double {
        val letters = chunk.count { it.isLetter() }
        val vowels = chunk.count { it.lowercaseChar() in VOWELS }
        val pause = if (endsPhrase(chunk)) 3.0 else 0.0
        return (letters + vowels * 0.7 + pause).coerceAtLeast(1.0)
    }

    /** Раскладывает куски по времени пропорционально весу: речь, а не число символов. */
    fun timed(chunks: List<String>, start: Double, end: Double): List<SubChunk> {
        if (chunks.isEmpty() || end <= start) return emptyList()
        val weights = chunks.map { weight(it) }
        val total = weights.sum().toDouble()
        val out = ArrayList<SubChunk>()
        var t = start
        for ((i, c) in chunks.withIndex()) {
            val d = (end - start) * weights[i] / total
            out += SubChunk(c, t, if (i == chunks.size - 1) end else t + d)
            t += d
        }
        return out
    }
}
