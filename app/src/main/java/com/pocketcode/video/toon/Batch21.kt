package com.pocketcode.video.toon

import java.util.Locale

/**
 * Batch 21: свобода настройки мультиков.
 * - Библиотека персонажей: свои (имя + лук + голос) и запоминание прошлых.
 * - Озвучка на любом офлайн-языке TTS (не только русский).
 * - Правильная транскрипция: нормализация чисел/сокращений под язык реплики.
 */

// ---------- Персонажи: библиотека со своими и прошлыми ----------

data class ToonCharacter(
    val name: String,
    val look: ToonLook,
    /** Тег голоса: «муж», «жен», «ребёнок» — движок TTS подбирает тембр, если умеет. */
    val voice: String = "нейтрально",
    /** Офлайн-язык персонажа в формате BCP-47, напр. «ru», «en», «de». */
    val lang: String = "ru",
    /** Признак «сделан вручную» — не перезаписывается автогенерацией. */
    val custom: Boolean = false,
)

object ToonCast {
    /** Собрать каст из сценария: известные персонажи берутся из библиотеки, новые получают автолук. */
    fun resolve(speakers: List<String>, library: Map<String, ToonCharacter>,
                autoLook: (String) -> ToonLook): Map<String, ToonCharacter> {
        val out = mutableMapOf<String, ToonCharacter>()
        var fresh = 0
        speakers.forEachIndexed { i, name ->
            val key = name.trim().lowercase()
            val fromLib = library[key] ?: library[name]
            out[name] = fromLib
                ?: ToonCharacter(name, autoLook(name), voice = if (fresh++ % 2 == 0) "муж" else "жен")
        }
        return out
    }

    /** Добавить/обновить персонажа в библиотеке (персональные настройки не затираются). */
    fun save(library: Map<String, ToonCharacter>, character: ToonCharacter): Map<String, ToonCharacter> {
        val key = character.name.trim().lowercase()
        val old = library[key]
        val merged = if (old != null && !character.custom) old else character
        return library + (key to merged)
    }

    /** Персонажи, участвовавшие в прошлых мультиках, для быстрого выбора. */
    fun recent(all: Collection<ToonCharacter>, limit: Int = 12): List<ToonCharacter> =
        all.sortedByDescending { it.custom }.take(limit)
}

// ---------- Озвучка: офлайн-языки ----------

object ToonVoiceLang {
    /** Язык реплики: по буквам текста (кириллица→ru, латиница→en); без букв — голос персонажа. */
    fun detect(text: String, character: ToonCharacter?): String {
        val cyr = text.count { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        val lat = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (cyr == 0 && lat == 0) return character?.lang ?: "ru"
        return when {
            cyr >= lat -> character?.lang?.takeIf { it.startsWith("ru") } ?: "ru"
            else -> character?.lang?.takeIf { !it.startsWith("ru") } ?: "en"
        }
    }

    /** Проверка: установлен ли офлайн-голос для языка (по списку доступных Locale). */
    fun available(lang: String, installed: List<String>): Boolean =
        installed.any { it.equals(lang, true) || it.startsWith(lang + "_") || it.startsWith(lang + "-") }

    /** Выбор лучшего Locale: точное совпадение → язык → откат на ru. */
    fun pickLocale(lang: String, installed: List<String>): Locale? {
        val exact = installed.firstOrNull { it.replace('_', '-').equals(lang, true) }
        if (exact != null) return Locale.forLanguageTag(exact.replace('_', '-'))
        val byLang = installed.firstOrNull { it.lowercase().startsWith(lang.lowercase()) }
        if (byLang != null) return Locale.forLanguageTag(byLang.replace('_', '-'))
        return Locale("ru", "RU")
    }

    /** Что показать пользователю, если офлайн-голоса нет. */
    fun hint(lang: String): String =
        "Офлайн-голос «$lang» не найден. Скачайте его: Настройки → Язык и ввод → Синтез речи → офлайн-языки."
}

// ---------- Транскрипция: нормализация под язык реплики ----------

object ToonTranscribe {
    private val enUnits = mapOf(
        "%" to " percent", "$" to " dollars", "€" to " euros", "&" to " and",
        "+" to " plus", "=" to " equals", "@" to " at", "#" to " number ",
    )

    /** Словарь замен пользователя имеет приоритет: имена, бренды и т.п. */
    fun transcribe(text: String, lang: String, dict: List<Pair<String, String>> = emptyList()): String {
        var t = Pronounce.applyDict(text, dict)
        t = if (lang.startsWith("ru")) Pronounce.normalize(t) else normalizeEn(t)
        return t
    }

    /** Английская нормализация: числа, символы, время. */
    fun normalizeEn(text: String): String {
        var t = text
        enUnits.forEach { (sym, word) -> t = t.replace(sym, word) }
        t = t.replace(Regex("(?i)\\b(\\d{1,2}):(\\d{2})\\b")) { m ->
            val h = m.groupValues[1].toLong()
            val min = m.groupValues[2]
            "${PronounceEn.words(h)} ${if (min == "00") "o'clock" else PronounceEn.words(min.toLong())}"
        }
        t = t.replace(Regex("(?<=\\s|^)-(\\d+)\\b")) { m -> "minus " + PronounceEn.words(m.groupValues[1].toLong()) }
        t = t.replace(Regex("\\b\\d+\\b")) { m -> PronounceEn.words(m.value.toLong()) }
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** Полный конвейер одной реплики: язык → словарь → нормализация. */
    fun line(text: String, character: ToonCharacter?, dict: List<Pair<String, String>> = emptyList()): String {
        // реплика озвучивается голосом персонажа; detect() — только когда голоса нет
        val lang = character?.lang?.takeIf { it.isNotBlank() } ?: ToonVoiceLang.detect(text, null)
        return transcribe(text, lang, dict)
    }
}

/** Простые числа словами на английском (0…999 999 999) — для транскрипции. */
object PronounceEn {
    private val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    fun words(n: Long): String {
        if (n < 0L) return "minus " + words(-n)
        if (n < 20L) return ones[n.toInt()]
        if (n < 100L) return tens[(n / 10).toInt()] + (if (n % 10 != 0L) " " + ones[(n % 10).toInt()] else "")
        if (n < 1_000L) return ones[(n / 100).toInt()] + " hundred" + (if (n % 100 != 0L) " " + words(n % 100) else "")
        if (n < 1_000_000L) {
            val head = words(n / 1000) + " thousand"
            return head + (if (n % 1000 != 0L) " " + words(n % 1000) else "")
        }
        return words(n / 1_000_000) + " million" + (if (n % 1_000_000 != 0L) " " + words(n % 1_000_000) else "")
    }
}
