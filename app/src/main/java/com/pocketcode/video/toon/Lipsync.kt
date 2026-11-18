package com.pocketcode.video.toon

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Рот на каждом кадре: open — насколько открыт (0..1), width — форма (−1 «у/о» вытянут в трубочку … +1 «и/е» растянут). */
class MouthTrack(val open: FloatArray, val width: FloatArray)

/**
 * Синхронизация рта с речью: степень открытия берётся из громкости звука, а форма — из гласных текста реплики
 * (она плавно «ползёт» по тексту вместе со временем). Не требует сети и нейросетей.
 */
object Lipsync {
    /** Форма рта для буквы: null — согласная (форма не меняется). */
    fun vowelWidth(c: Char): Float? = when (c.lowercaseChar()) {
        'а', 'я', 'a' -> 0.2f
        'о', 'ё', 'o' -> -0.55f
        'у', 'ю', 'u' -> -1f
        'э', 'е', 'e' -> 0.55f
        'и', 'ы', 'i', 'y' -> 1f
        else -> null
    }

    /** Подобие слогового ритма, если настоящего звука нет (проверочный режим «без озвучки»). */
    fun syntheticEnvelope(frames: Int, fps: Int): FloatArray =
        FloatArray(frames) { f ->
            val t = f.toDouble() / fps
            val v = 0.5 + 0.5 * sin(2 * PI * 4.3 * t) * (0.7 + 0.3 * sin(2 * PI * 0.9 * t))
            (v * 0.85).toFloat().coerceIn(0f, 1f)
        }

    /**
     * env — громкость по кадрам реплики (от первого до последнего кадра речи), text — что произносится.
     * Закрытый рот — когда звука почти нет.
     */
    fun track(env: FloatArray, text: String): MouthTrack {
        val n = env.size
        val open = FloatArray(n)
        val width = FloatArray(n)
        val letters = text.filter { it.isLetter() }
        var prevOpen = 0f
        var w = 0f
        for (i in 0 until n) {
            // быстро открываем и чуть медленнее закрываем — так рот выглядит живее
            val target = if (env[i] < 0.07f) 0f else (0.25f + env[i] * 0.75f).coerceAtMost(1f)
            prevOpen = if (target > prevOpen) target * 0.8f + prevOpen * 0.2f else max(target, prevOpen * 0.55f)
            open[i] = prevOpen
            if (letters.isNotEmpty() && n > 1) {
                val idx = ((i.toDouble() / (n - 1)) * (letters.length - 1)).toInt()
                var target2: Float? = null
                // ближайшая гласная в пределах трёх букв
                for (d in 0..3) {
                    target2 = vowelWidth(letters[(idx + d).coerceAtMost(letters.length - 1)])
                    if (target2 != null) break
                    target2 = vowelWidth(letters[(idx - d).coerceAtLeast(0)])
                    if (target2 != null) break
                }
                if (target2 != null) w += (target2 - w) * 0.45f
            }
            width[i] = w
        }
        return MouthTrack(open, width)
    }
}
