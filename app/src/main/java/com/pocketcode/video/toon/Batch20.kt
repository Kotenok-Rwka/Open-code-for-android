package com.pocketcode.video.toon

/** Пресет качества мультика: размер кадра, fps, битрейт. */
enum class ToonQuality(val label: String, val w: Int, val h: Int, val fps: Int, val bitrate: Int) {
    FAST("Быстро", 540, 960, 24, 2_000_000),
    TIKTOK("TikTok", 720, 1280, 30, 3_500_000),
    HD("Чётко", 1080, 1920, 30, 6_000_000);

    companion object {
        fun byIdOrNull(id: String?): ToonQuality? = entries.firstOrNull { it.name.equals(id, true) }
    }
}

/** Каптион для TikTok: хук + хэштеги, лимит символов. */
object ToonCaption {
    private val filler = setOf("и", "в", "на", "что", "это", "как", "а", "но", "у", "за", "по", "до", "не")

    fun hashtags(title: String, scene: String?, max: Int = 6): List<String> {
        val words = title.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length > 2 && it !in filler }
            .map { "#" + it }
        val base = mutableListOf("#мульттик", "#анимация", "#рекомендации")
        scene?.let { base.add(0, "#" + it.lowercase()) }
        return (base + words).distinct().take(max)
    }

    fun caption(title: String, hook: String?, scene: String?, limit: Int = 2200): String {
        val tags = hashtags(title, scene).joinToString(" ")
        val first = (hook?.takeIf { it.isNotBlank() } ?: title).trim().trimEnd('.', '!')
        val text = "$first 👀 $tags"
        return if (text.length <= limit) text else text.take(limit - 1) + "…"
    }
}

/** Серия: номер эпизода, клиффхэнгер, заголовок эпизода. */
object ToonSeries {
    fun title(topic: String, number: Int): String = "Часть $number: ${topic.trim().removeSuffix(".")} 😱"

    fun cliffhanger(question: String, optionA: String, optionB: String): String =
        "Выбор: ${question.trim()} | ${optionA.trim()} | ${optionB.trim()}"

    fun nextTitle(prevTitle: String, topic: String): String {
        val m = Regex("(?i)часть\\s*(\\d+)").find(prevTitle)
        val next = (m?.groupValues?.get(1)?.toIntOrNull() ?: 1) + 1
        return title(topic, next)
    }
}

/** Планировщик клипов TikTok: раскладка реплик по слотам с хуком в начале. */
object ToonPlanner {
    data class Slot(val speaker: String, val text: String, val seconds: Double)

    fun plan(lines: List<Pair<String, String>>, hookSec: Double = 1.2, perLineSec: Double = 2.2): List<Slot> {
        if (lines.isEmpty()) return emptyList()
        val (firstSpeaker, firstText) = lines.first()
        val slots = mutableListOf(Slot(firstSpeaker, firstText, hookSec))
        lines.drop(1).forEach { (sp, tx) -> slots.add(Slot(sp, tx, perLineSec)) }
        return slots
    }

    fun totalSeconds(slots: List<Slot>): Double = slots.sumOf { it.seconds }

    /** Хук первых секунд: короткая фраза вверх, чтобы удержать зрителя. */
    fun hook(text: String, maxChars: Int = 40): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return if (t.length <= maxChars) t else t.take(maxChars).trimEnd() + "…"
    }
}
