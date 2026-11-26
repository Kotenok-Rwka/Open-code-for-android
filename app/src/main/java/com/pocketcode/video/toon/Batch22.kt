package com.pocketcode.video.toon

/**
 * Batch 22: максимум для TikTok-мультиков.
 * - Пул всех офлайн-движков сразу: 4 sherpa-голоса распределяются по персонажам.
 * - «Произносимый текст»: реплика превращается в фонемно-точную запись для Piper
 *   (пишется так, как говорятся) — ударения, ё, числа, словарь.
 * - Фон как объект: сохраняется, дорабатывается (цвета, детали), переиспользуется.
 */

// ---------- Пул офлайн-движков на всех персонажей ----------

object ToonEnginePool {
    /** 4 качественных русских sherpa-голоса. */
    val SHERPA = listOf("irina", "dmitri", "denis", "ruslan")

    /** Распределить движки по персонажам: по кругу, с учётом пожелания голоса. */
    fun assign(speakers: List<String>, wishes: Map<String, String> = emptyMap()):
            Map<String, String> {
        val out = mutableMapOf<String, String>()
        var next = 0
        for (sp in speakers) {
            val wish = wishes[sp]?.lowercase()?.trim()
            val engine = if (wish != null && SHERPA.any { it == wish }) wish
            else SHERPA[next % SHERPA.size].also { next++ }
            out[sp] = engine
        }
        // если пожеланий было много и пул занят — добираем свободные голоса
        for (sp in speakers) {
            val dup = out.values.count { it == out[sp] } > 1
            if (dup) {
                val free = SHERPA.firstOrNull { it !in out.values }
                if (free != null) out[sp] = free
            }
        }
        return out
    }

    /** Строка движков для MultiToonVoice: имена через «|». */
    fun enginesCsv(mapping: Map<String, String>): String =
        mapping.values.distinct().joinToString("|")

    /** Тембр: голоса irina/dmitri — нейтральные, denis — ниже, ruslan — ниже ещё. */
    fun pitchFor(engine: String): Float = when (engine) {
        "denis" -> 0.9f
        "ruslan" -> 0.85f
        "irina" -> 1.05f
        else -> 1.0f
    }
}

// ---------- Произносимый текст: как пишется, так и говорится ----------

object SayText {
    /** Ударение Piper-стилем: знак «+» перед ударной гласной сохраняем в озвучке. */
    fun forPiper(text: String, stressDict: Map<String, String> = emptyMap()): String {
        var t = text.trim()
        stressDict.forEach { (word, stressed) ->
            t = Regex("(?i)\\b" + Regex.escape(word) + "\\b").replace(t) { m ->
                val s = stressed
                if (m.value[0].isUpperCase()) s.replaceFirstChar { it.uppercase() } else s
            }
        }
        return t
    }

    /** Готовая озвучиваемая строка: транскрипция + ударения. */
    fun say(text: String, lang: String, stressDict: Map<String, String> = emptyMap(),
            dict: List<Pair<String, String>> = emptyList()): String =
        forPiper(ToonTranscribe.transcribe(text, lang, dict), stressDict)

    /** Проверка «пишется как говорится»: слово неоднозначно без ударения/ё. */
    fun needsStressMark(word: String): Boolean =
        Regex("(?i)^[а-яё]{2,}$").matches(word) &&
            word.lowercase().let { w -> w.any { it == 'ё' } || AMBIGUOUS.any { w.startsWith(it) } }

    private val AMBIGUOUS = listOf("зам", "сто", "мук", "вор", "зво", "па", "те")
}

// ---------- Фон как объект: сохранить, доработать, переиспользовать ----------

data class ToonBackdrop(
    val id: String,
    val scene: ToonScene,
    /** Доработки поверх базовой сцены: цвета неба, солнца, деталей в ARGB. */
    val skyTop: Int? = null,
    val skyBottom: Int? = null,
    val sun: Int? = null,
    val detail: Int? = null,
    /** Свои элементы: список «что|цвет|x%|y%|размер%». */
    val props: List<String> = emptyList(),
)

object ToonBackdropLib {
    /** Сериализация библиотеки фонов в текст (для настроек). */
    fun serialize(all: List<ToonBackdrop>): String = all.joinToString("\n") { b ->
        listOf(b.id, b.scene.name, b.skyTop?.let { Integer.toHexString(it) } ?: "",
            b.skyBottom?.let { Integer.toHexString(it) } ?: "",
            b.sun?.let { Integer.toHexString(it) } ?: "",
            b.detail?.let { Integer.toHexString(it) } ?: "")
            .joinToString("|") + "|" + b.props.joinToString(";;")
    }

    fun parse(text: String): List<ToonBackdrop> = text.lines().filter { it.isNotBlank() }.mapNotNull { ln ->
        val p = ln.split("|")
        if (p.size < 6) return@mapNotNull null
        ToonBackdrop(
            p[0], runCatching { ToonScene.valueOf(p[1]) }.getOrDefault(ToonScene.NIGHT),
            p[2].takeIf { it.isNotBlank() }?.toLong(16)?.toInt(),
            p[3].takeIf { it.isNotBlank() }?.toLong(16)?.toInt(),
            p[4].takeIf { it.isNotBlank() }?.toLong(16)?.toInt(),
            p[5].takeIf { it.isNotBlank() }?.toLong(16)?.toInt(),
            p.getOrElse(6) { "" }.split(";;").filter { it.isNotBlank() },
        )
    }

    /** Применить доработки к сцене: вернуть перекрашенные цвета (null — как в базовой). */
    fun override(b: ToonBackdrop): Triple<Int?, Int?, Pair<Int?, Int?>?> =
        Triple(b.skyTop, b.skyBottom, b.sun to b.detail)
}
