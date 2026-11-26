package com.pocketcode.video.toon

import kotlin.math.abs

/**
 * Batch 26: полная свобода нейронки — персонаж и сцена задаются текстом.
 *
 * Персонаж (строка каста, 5-е поле — описание внешности через запятую):
 *   Аня|ru|irina|0|волосы:рыжие, кофта:зелёная, очки, борода, рост:высокий, причёска:хвост
 * Всё необязательно; неизвестные слова игнорируются, остальное — из имени (ToonLooks).
 *
 * Сцена (строки в сценарии):
 *   ВРЕМЯ: ночь | утро | вечер | день      — тонировка кадра
 *   ПОГОДА: дождь | снег | туман           — частицы поверх сцены
 *   ЭМОЦИЯ Аня: грустит                     — настроение реплик героя
 */

/** Разбор описания внешности. Цвета — по русским словам или #RRGGBB. */
data class ToonAppearance(
    val hairColor: Int? = null,
    val shirtColor: Int? = null,
    val skinColor: Int? = null,
    val style: HairStyle? = null,
    val glasses: Boolean? = null,
    val beard: Boolean? = null,
    val tall: Float? = null, // масштаб роста 0.7..1.2
) {
    companion object {
        private val hairWords = mapOf<String, Int>(
            "рыжие" to 0xFFB5382B.toInt(), "рыжий" to 0xFFB5382B.toInt(), "чёрные" to 0xFF1E1E24.toInt(), "чёрный" to 0xFF1E1E24.toInt(),
            "русые" to 0xFFC9A66B.toInt(), "блонд" to 0xFFE5C07B.toInt(), "белокурые" to 0xFFE5C07B.toInt(), "седые" to 0xFFC9C9CF.toInt(),
            "каштановые" to 0xFF6B4326.toInt(), "серые" to 0xFF8A8A92.toInt(),
        )
        private val shirtWords = mapOf<String, Int>(
            "красная" to 0xFFE5533D.toInt(), "красный" to 0xFFE5533D.toInt(), "синяя" to 0xFF3D7BE5.toInt(), "синий" to 0xFF3D7BE5.toInt(),
            "зелёная" to 0xFF3DB27A.toInt(), "зеленая" to 0xFF3DB27A.toInt(), "зелёный" to 0xFF3DB27A.toInt(), "фиолетовая" to 0xFF9B59D0.toInt(),
            "жёлтая" to 0xFFEFB43A.toInt(), "желтая" to 0xFFEFB43A.toInt(), "белая" to 0xFFF2F0EA.toInt(), "белый" to 0xFFF2F0EA.toInt(),
            "серая" to 0xFF5C6B7A.toInt(), "серый" to 0xFF5C6B7A.toInt(), "тельняшка" to 0xFF2B4A7A.toInt(),
        )
        private val skinWords = mapOf<String, Int>(
            "светлая" to 0xFFFAD7B5.toInt(), "тёмная" to 0xFF9B6B4A.toInt(), "темная" to 0xFF9B6B4A.toInt(), "смуглая" to 0xFFC68B63.toInt(),
        )
        private val styleWords = mapOf(
            "короткая" to HairStyle.SHORT, "короткие" to HairStyle.SHORT, "длинные" to HairStyle.LONG,
            "пучок" to HairStyle.BUN, "хвост" to HairStyle.BUN, "лысый" to HairStyle.BALD, "лысина" to HairStyle.BALD,
            "кепка" to HairStyle.CAP, "шапка" to HairStyle.CAP,
        )

        fun parse(desc: String): ToonAppearance {
            var a = ToonAppearance()
            if (desc.isBlank()) return a
            for (raw in desc.split(',', ';')) {
                val item = raw.trim().lowercase()
                if (item.isEmpty()) continue
                val key = item.substringBefore(':').trim()
                val value = item.substringAfter(':', "").trim()
                when {
                    key == "волосы" || key == "волос" -> a = a.copy(hairColor = color(value, hairWords) ?: a.hairColor)
                    key == "кофта" || key == "одежда" || key == "рубашка" -> a = a.copy(shirtColor = color(value, shirtWords) ?: a.shirtColor)
                    key == "кожа" -> a = a.copy(skinColor = color(value, skinWords) ?: a.skinColor)
                    key == "причёска" || key == "прическа" -> a = a.copy(style = styleWords[value])
                    key == "рост" -> a = a.copy(tall = when (value) {
                        "высокий", "высокая" -> 1.12f
                        "низкий", "низкая" -> 0.88f
                        "ребёнок", "ребенок", "малыш" -> 0.75f
                        else -> value.toFloatOrNull()?.coerceIn(0.7f, 1.2f)
                    })
                    key == "очки" -> a = a.copy(glasses = true)
                    key == "борода" -> a = a.copy(beard = true)
                    // без двоеточия: «рыжие», «очки», «борода», «кепка»…
                    else -> {
                        val hairHit = hairWords[key]
                        val shirtHit = shirtWords[key]
                        val styleHit = styleWords[key]
                        when {
                            hairHit != null -> a = a.copy(hairColor = hairHit)
                            shirtHit != null -> a = a.copy(shirtColor = shirtHit)
                            styleHit != null -> a = a.copy(style = styleHit)
                            else -> when (key) {
                                "очки" -> a = a.copy(glasses = true)
                                "борода" -> a = a.copy(beard = true)
                                "высокий", "высокая" -> a = a.copy(tall = 1.12f)
                                "низкий", "низкая" -> a = a.copy(tall = 0.88f)
                            }
                        }
                    }
                }
            }
            return a
        }

        /** Цвет: слово из словаря, иначе #RRGGBB. */
        fun color(s: String, dict: Map<String, Int>): Int? {
            dict[s]?.let { return it }
            val t = s.replace("#", "").replace("0x", "")
            if (t.length == 6) return t.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
            return null
        }
    }
}

/** Директивы сцены: время суток и погода (парсятся из сценария). */
data class ToonSceneFx(
    val time: String = "",
    val weather: String = "",
) {
    companion object {
        val times = mapOf(
            "ночь" to 0x66101830, "утро" to 0x33FFD9A0, "вечер" to 0x55FF8C42, "день" to 0,
        )
        val weathers = setOf("дождь", "снег", "туман")

        /** Строки «ВРЕМЯ: ночь» и «ПОГОДА: дождь» из сценария (последняя wins). */
        fun fromScript(script: String): ToonSceneFx {
            var fx = ToonSceneFx()
            for (ln in script.lines()) {
                val h = ln.trim()
                val up = h.uppercase()
                if (up.startsWith("ВРЕМЯ:")) {
                    val v = h.substringAfter(':').trim().lowercase()
                    if (v in times) fx = fx.copy(time = v)
                } else if (up.startsWith("ПОГОДА:")) {
                    val v = h.substringAfter(':').trim().lowercase()
                    if (v in weathers) fx = fx.copy(weather = v)
                }
            }
            return fx
        }

        /** Настроение по слову в директиве «ЭМОЦИЯ Имя: грустит» (неизвестное — CALM). */
        fun moodOf(word: String): Mood = when (word.trim().lowercase()) {
            "грустит", "грустный", "печаль", "плачет" -> Mood.SAD
            "удивлён", "удивлен", "вау", "радуется", "смеётся" -> Mood.WOW
            "спрашивает", "вопрос" -> Mood.ASK
            else -> Mood.CALM
        }

        /**
         * Каст, заданный прямо в сценарии: строки
         * «КАСТ: Имя|lang|voice|0|волосы:рыжие, …» (нейронка создаёт героев сама).
         */
        fun castFromScript(script: String): List<ToonCharacter> =
            script.lines().filter { it.trim().uppercase().startsWith("КАСТ:") }.mapNotNull { ln ->
                val body = ln.trim().substringAfter(':').trim()
                if (body.isEmpty()) return@mapNotNull null
                val p = body.split("|")
                if (p.size < 2 || p[0].isBlank() || p[1].isBlank()) return@mapNotNull null
                val name = p[0].trim()
                ToonCharacter(name,
                    ToonLooks.of(name, abs(name.hashCode()) % 7).applyAppearance(ToonAppearance.parse(p.getOrElse(4) { "" })),
                    voice = p.getOrElse(2) { "нейтрально" },
                    lang = p.getOrElse(1) { "ru" },
                    custom = p.getOrElse(3) { "0" } == "1")
            }

        /** Настроения героев из директив «ЭМОЦИЯ Имя: слово» (последняя wins). */
        fun moodsFromScript(script: String): Map<String, Mood> {
            val out = HashMap<String, Mood>()
            for (ln in script.lines()) {
                val h = ln.trim()
                if (!h.uppercase().startsWith("ЭМОЦИЯ")) continue
                val body = h.substringAfter("ЭМОЦИЯ").trim()
                val name = body.substringBefore(':').trim()
                if (name.isEmpty()) continue
                out[name.lowercase()] = moodOf(body.substringAfter(':', ""))
            }
            return out
        }

        /** Затемнение/тёплая тонировка кадра по времени суток (0 — нет). */
        fun tintOf(time: String): Int = times[time] ?: 0
    }
}
