package com.pocketcode.assist

import com.pocketcode.phone.AppInfo
import com.pocketcode.phone.PhoneController

/** Поиск приложения по названию, сказанному голосом: «телеграм», «ютуб», «вк». Названия на телефоне часто английские. */
object AssistAppMatch {
    private val ALIASES = mapOf(
        "ютуб" to "youtube", "ютьюб" to "youtube", "вк" to "vk", "вконтакте" to "vk", "тг" to "telegram", "телега" to "telegram",
        "ватсап" to "whatsapp", "вотсап" to "whatsapp", "вацап" to "whatsapp", "хром" to "chrome", "гугл хром" to "chrome",
        "карты" to "maps", "гугл карты" to "maps", "гмейл" to "gmail", "почта" to "mail", "камера" to "camera", "галерея" to "gallery",
        "календарь" to "calendar", "часы" to "clock", "калькулятор" to "calculator", "настройки" to "settings", "контакты" to "contacts",
        "музыка" to "music", "плей маркет" to "play store", "гугл плей" to "play store", "вайбер" to "viber", "скайп" to "skype",
        "зум" to "zoom", "дискорд" to "discord", "нетфликс" to "netflix", "спотифай" to "spotify", "тикток" to "tiktok",
    )

    private val LETTERS = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** Русское слово латиницей: «телеграм» → «telegram». Остальные знаки не меняются. */
    fun translit(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) sb.append(LETTERS[ch] ?: ch.toString())
        return sb.toString()
    }

    /** Варианты названия для поиска, от самого точного к приблизительным, без повторов. */
    fun variants(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += q
        ALIASES[q]?.let { out += it }
        val t = translit(q)
        if (t != q) out += t
        return out.toList()
    }

    fun find(apps: List<AppInfo>, query: String): AppInfo? {
        for (v in variants(query)) {
            PhoneController.matchApp(apps, v)?.let { return it }
        }
        return null
    }
}
