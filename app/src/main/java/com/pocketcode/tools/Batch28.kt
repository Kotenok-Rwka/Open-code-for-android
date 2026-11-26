package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Batch 28: ассистент «умеет жить» — быстрые бытовые инструменты и лёгкая болтовня.
 * - WeatherTool: погода по городу через wttr.in (без поиска, один HTTP-запрос).
 * - SmallTalk: распознавание «просто общения» (привет, как дела, спасибо…), чтобы
 *   ассистент отвечал коротко и не запускал поиск/агента на пустяках.
 */

/** Погода по городу: wttr.in, формат «Погода в X: …». */
class WeatherTool : Tool {
    override val name = "weather"
    override val description =
        "Погода в городе прямо сейчас (температура, описание, ветер, влажность). Используй для вопросов «какая погода», «сколько градусов» — быстрее поиска. Только чтение."
    override val schema = objSchema(
        Prop("city", "string", "Город, например «Москва» или «Санкт-Петербург»"),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "погода: ${input.str("city") ?: ""}"
    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val city = input.need("city").trim()
        check(city.isNotEmpty()) { "укажи город" }
        val enc = java.net.URLEncoder.encode(city, "UTF-8")
        val req = okhttp3.Request.Builder()
            .url("https://wttr.in/$enc?format=j1&lang=ru")
            .header("User-Agent", UA)
            .build()
        webHttp.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body?.string() ?: ""
            val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
            val cur = json["current_condition"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: error("нет данных о погоде")
            fun str(key: String): String =
                cur[key]?.toString()?.trim('"') ?: "?"
            val desc = (cur["lang_ru"]?.jsonArray?.firstOrNull()?.jsonObject?.get("value")?.toString()
                ?: cur["weatherDesc"]?.jsonArray?.firstOrNull()?.jsonObject?.get("value")?.toString())
                ?.trim('"') ?: ""
            "Погода в $city: $desc, ${str("temp_C")}°C (ощущается как ${str("FeelsLikeC")}°C), " +
                "ветер ${str("windspeedKmph")} км/ч, влажность ${str("humidity")}%."
        }
    }
}

/** Простое общение без инструментов: приветствия, благодарность, «как дела». */
object SmallTalk {
    private val greetings = listOf("привет", "здравствуй", "здравствуйте", "хай", "hello", "hi", "салют", "доброе утро", "добрый день", "добрый вечер")
    private val howAreYou = listOf("как дела", "как ты", "что нового", "как настроение", "чем занят")
    private val thanks = listOf("спасибо", "благодар", "thanks", "thank you", "класс", "супер", "отлично", "молодец")
    private val bye = listOf("пока", "до встречи", "спокойной ночи", "bye", "прощай")
    private val about = listOf("кто ты", "что ты умеешь", "ты кто", "расскажи о себе")

    private fun startsWithAny(t: String, list: List<String>): Boolean = list.any { t.startsWith(it) }

    /** Это «просто общение» (не нужен поиск/агент)? Пустое/длинное — не болтовня. */
    fun isSmallTalk(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.length > 120) return false
        val hit = (greetings + howAreYou + thanks + bye + about)
            .filter { t.startsWith(it) }.maxByOrNull { it.length } ?: return false
        val rest = t.removePrefix(hit).trim().trimEnd('?', '!', '.', ' ')
        return rest.isEmpty() || rest.split(' ').size <= 2
    }

    /** Короткий живой ответ на болтовню (null — не болтовня, нужен обычный ход). */
    fun reply(text: String, appName: String = "Помощник"): String? {
        val t = text.trim().lowercase()
        if (!isSmallTalk(t)) return null
        return when {
            startsWithAny(t, greetings) ->
                "Привет! Я на связи. Могу поболтать, ответить на вопрос, найти что-то в интернете или помочь с проектом."
            startsWithAny(t, howAreYou) ->
                "Всё отлично, я готов работать. Что делаем — поболтаем, найдём что-то или займёмся проектом?"
            startsWithAny(t, thanks) -> "Всегда пожалуйста! Обращайся."
            startsWithAny(t, bye) -> "До встречи! Возвращайся, если что понадобится."
            startsWithAny(t, about) ->
                "Я — $appName: отвечаю на вопросы, ищу в интернете, узнаю погоду и помогаю с проектом. Просто напиши, что нужно."
            else -> null
        }
    }
}
