package com.pocketcode.video.toon

/** Фон сцены. */
enum class ToonScene(val label: String, val words: List<String>) {
    NIGHT("Ночь и снег", listOf("ночь", "ночью", "снег", "зима", "night", "snow")),
    SEA("Море днём", listOf("море", "день", "днём", "пирс", "пляж", "sea", "day")),
    ROOM("Комната", listOf("комната", "дом", "квартира", "room", "home")),
    CITY("Город", listOf("город", "улица", "вечер", "city", "street")),
    GAS("Заправка", listOf("заправка", "мотель", "трасса", "дорога", "gas", "road")),
    TENT("Лагерь", listOf("палатка", "лагерь", "лес", "горы", "tent", "camp")),
    BUNKER("Бункер/убежище", listOf("бункер", "убежище", "подвал", "темнота", "бомбоубежище", "бункере", "bunker", "dark"));

    companion object {
        fun fromWord(w: String?): ToonScene? {
            val k = w?.trim()?.lowercase().orEmpty()
            if (k.isEmpty()) return null
            return values().firstOrNull { it.name.lowercase() == k || k in it.words }
        }
    }
}

/** Одна реплика: кто говорит, что написать в субтитрах и что говорить голосом (null — подготовить из субтитров). */
data class ToonLine(val speaker: String, val sub: String, val say: String?, val scene: ToonScene? = null) {
    val narrator: Boolean get() = ToonScript.isNarrator(speaker)
}

data class ToonChoice(val question: String, val options: List<String>)

data class ToonScript(
    val title: String,
    val scene: ToonScene?,
    val lines: List<ToonLine>,
    val choice: ToonChoice?,
    val cta: String,
) {
    /** Герои в порядке появления, без рассказчика. */
    val speakers: List<String>
        get() = lines.filter { !it.narrator }.map { it.speaker }.distinct()

    /**
     * Самые говорящие герои (не больше [limit]) в порядке первого появления.
     * В кадре всего четыре места, поэтому в кадр должны попасть те, у кого больше реплик,
     * а не те, кто просто поздоровался первым.
     */
    fun topSpeakers(limit: Int = 4): List<String> {
        val order = speakers
        val counts = lines.filter { !it.narrator }.groupingBy { it.speaker }.eachCount()
        return order.sortedByDescending { counts[it] ?: 0 }.take(limit).sortedBy { order.indexOf(it) }
    }

    companion object {
        const val MAX_LINES = 80
        const val DEFAULT_CTA = "Пиши свой выбор в комментариях"
        private val narrators = setOf("рассказчик", "диктор", "голос", "автор", "за кадром", "narrator")
        private val lineRx = Regex("""^(\p{L}[\p{L}\p{N} _.\-]{0,23}):\s*(.+)$""")

        fun isNarrator(name: String) = name.trim().lowercase() in narrators

        private fun key(line: String, vararg names: String): String? {
            val l = line.lowercase()
            for (n in names) if (l.startsWith("$n:")) return line.substring(n.length + 1).trim()
            return null
        }

        /**
         * Сценарий — обычный текст:
         *   Название: …   Сцена: ночь | море | комната | город
         *   Имя: реплика            (после «||» можно записать, как это должно звучать)
         *   Выбор: вопрос | вариант 1 | вариант 2     Призыв: текст внизу
         */
        fun parse(text: String): ToonScript {
            var title = ""
            var scene: ToonScene? = null
            var current: ToonScene? = null
            var choice: ToonChoice? = null
            var cta = DEFAULT_CTA
            val lines = ArrayList<ToonLine>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val titleV = key(line, "название", "title")
                if (titleV != null) { title = titleV; continue }
                val sceneV = key(line, "сцена", "scene")
                if (sceneV != null) {
                    val sc = ToonScene.fromWord(sceneV)
                    if (sc != null) {
                        current = sc
                        if (scene == null) scene = sc
                    }
                    continue
                }
                val ctaV = key(line, "призыв", "cta")
                if (ctaV != null) { if (ctaV.isNotEmpty()) cta = ctaV; continue }
                val choiceV = key(line, "выбор", "choice")
                if (choiceV != null) {
                    val parts = choiceV.split('|').map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
                    if (parts.size >= 3) choice = ToonChoice(parts[0], parts.drop(1).take(3))
                    continue
                }
                val m = lineRx.find(line)
                val name = m?.groupValues?.get(1)?.trim() ?: "Рассказчик"
                val body = m?.groupValues?.get(2) ?: line
                val bits = body.split("||", limit = 2)
                val sub = bits[0].trim()
                val say = bits.getOrNull(1)?.trim()?.ifEmpty { null }
                if (sub.isEmpty() && say == null) continue
                lines += ToonLine(name, sub.ifEmpty { say.orEmpty() }, say, current)
            }
            require(lines.isNotEmpty()) { "В сценарии нет ни одной реплики. Пишите по строке: «Имя: реплика»." }
            require(lines.size <= MAX_LINES) { "Слишком длинный сценарий: не больше $MAX_LINES реплик." }
            return ToonScript(title, scene, lines, choice, cta)
        }

        /** Грубая оценка длины ролика в секундах до озвучки. */
        fun estimateSeconds(s: ToonScript): Double {
            var t = 0.6
            for (l in s.lines) t += maxOf(1.2, (l.say ?: l.sub).length / 13.0) + 0.3
            if (s.choice != null) t += 8.0
            return t + 0.6
        }
    }
}
