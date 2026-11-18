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

    fun estimate(text: String): Double = maxOf(1.2, text.length / 13.0)

    /**
     * Собирает ролик. [pcms] — озвучка каждой реплики (или null), [voiceTexts] — что произносилось.
     * Между репликами пауза [gap]; перед первой репликой [lead] секунд на заголовок; в конце — выбор для зрителей.
     */
    fun build(
        script: ToonScript,
        pcms: List<Pcm?>,
        voiceTexts: List<String>,
        choicePcm: Pcm?,
        choiceText: String,
        gap: Double = 0.3,
        lead: Double = 0.6,
        choiceHold: Double = 4.0,
        minTotal: Double = 0.0,
    ): ToonPlan {
        require(pcms.size == script.lines.size && voiceTexts.size == script.lines.size) { "Число озвучек не совпадает с числом реплик" }
        val audios = pcms.map { p -> p?.let { Wav.trim(Wav.resample(it, RATE)) } }
        val clips = ArrayList<ToonClip>()
        var t = lead
        for ((i, line) in script.lines.withIndex()) {
            val a = audios[i]
            val dur = a?.seconds ?: estimate(voiceTexts[i])
            clips += ToonClip(line, t, t + dur, a, voiceTexts[i])
            t += dur + gap
        }
        var choiceStart = t
        var choiceAudio: Pcm? = null
        var total = t + 0.4
        if (script.choice != null) {
            choiceAudio = choicePcm?.let { Wav.trim(Wav.resample(it, RATE)) }
            val d = choiceAudio?.seconds ?: estimate(choiceText)
            total = choiceStart + 0.3 + d + choiceHold
        } else {
            choiceStart = -1.0
        }
        // серия не может быть короче заказанного: последний кадр (или выбор) просто держится дольше
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
