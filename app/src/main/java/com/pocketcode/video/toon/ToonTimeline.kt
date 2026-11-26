package com.pocketcode.video.toon

import kotlin.math.ceil

/** Реплика на временной шкале. audio == null — звука нет (проверочный режим), длительность оценена по тексту. */
class ToonClip(val line: ToonLine, val start: Double, val end: Double, val audio: Pcm?, val voiceText: String)

/** Готовая раскладка ролика: когда кто говорит, когда показывается выбор и общий звук одной дорожкой. */
class ToonPlan(
    val clips: List<ToonClip>,
    val choiceStart: Double,
    val choiceAudio: Pcm?,
    val total: Double,
    val mix: ShortArray,
) {
    fun frames(fps: Int): Int = ceil(total * fps).toInt().coerceAtLeast(1)
}

object ToonTimeline {
    /** Общая частота звука в ролике. */
    const val RATE = 24000

    private const val BASE_GAP = 0.3
    private const val TAIL = 0.55

    fun estimate(text: String): Double = maxOf(1.2, text.length / 13.0)

    /** Пауза после реплики: вопрос и восклицание держат кадр чуть дольше. */
    fun gapAfter(text: String): Double {
        val t = text.trimEnd()
        return BASE_GAP + when {
            t.endsWith("…") || t.endsWith("...") -> 0.2
            t.endsWith("?") || t.endsWith("!") -> 0.15
            else -> 0.0
        }
    }

    /** Готовит реплику к миксу: одна громкость, без щелчков, тишина по краям срезана. */
    private fun ready(p: Pcm?): Pcm? = p?.let { Wav.fade(Wav.normalize(Wav.trim(Wav.resample(it, RATE)))) }

    /**
     * Собирает ролик. [pcms] — озвучка каждой реплики (или null), [voiceTexts] — что произносилось.
     * Перед первой репликой [lead] секунд на заголовок, в конце — хвост или выбор для зрителей.
     */
    fun build(
        script: ToonScript,
        pcms: List<Pcm?>,
        voiceTexts: List<String>,
        choicePcm: Pcm?,
        choiceText: String,
        gap: Double = BASE_GAP,
        lead: Double = 0.6,
        choiceHold: Double = 4.0,
        minTotal: Double = 0.0,
    ): ToonPlan {
        require(pcms.size == script.lines.size && voiceTexts.size == script.lines.size) { "Число озвучек не совпадает с числом реплик" }
        val audios = pcms.map { ready(it) }
        val durs = audios.mapIndexed { i, a -> a?.seconds?.takeIf { it > 0.05 } ?: estimate(voiceTexts[i]) }
        // Пауза после каждой реплики: после «?» и «!» чуть длиннее — так появляется ритм.
        val gaps = MutableList(script.lines.size) { gapAfter(script.lines[it].sub) + (gap - BASE_GAP) }
        val hasChoice = script.choice != null
        val choiceAudio = if (hasChoice) ready(choicePcm) else null
        val choiceDur = choiceAudio?.seconds ?: estimate(choiceText)
        val choiceBlock = if (hasChoice) 0.3 + choiceDur + choiceHold else 0.0
        val tail = TAIL

        // Серия короче заказанной: время уходит в паузы между репликами, а не в замороженный последний кадр.
        val need = minTotal - (lead + durs.sum() + gaps.sum() + tail + choiceBlock)
        if (need > 0 && gaps.isNotEmpty()) {
            val per = minOf(need / gaps.size, 0.8)
            for (i in gaps.indices) gaps[i] += per
        }

        val clips = ArrayList<ToonClip>()
        var t = lead
        for ((i, line) in script.lines.withIndex()) {
            clips += ToonClip(line, t, t + durs[i], audios[i], voiceTexts[i])
            t += durs[i] + gaps[i]
        }
        var total = t + tail
        val choiceStart = if (hasChoice) {
            total = t + choiceBlock
            t
        } else -1.0
        if (total < minTotal) total = minTotal
        val mix = ShortArray(ceil(total * RATE).toInt() + RATE / 10)
        for (c in clips) c.audio?.let { place(mix, it, c.start) }
        if (choiceAudio != null) place(mix, choiceAudio, choiceStart + 0.3)
        return ToonPlan(clips, choiceStart, choiceAudio, total, mix)
    }

    private fun place(mix: ShortArray, a: Pcm, at: Double) {
        val off = (at * RATE).toInt()
        for (i in a.samples.indices) {
            val j = off + i
            if (j >= mix.size) break
            mix[j] = (mix[j] + a.samples[i]).coerceIn(-32768, 32767).toShort()
        }
    }
}
