package com.pocketcode.video

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Создание видео нейросетью через API сервисов, где у пользователя есть свой ключ.
 * Здесь только чистая логика (пресеты, сборка запросов, разбор ответов): сетевая часть в VideoGenClient.
 */
enum class GenProvider(val label: String, val about: String, val keyPage: String, val host: String) {
    FAL("fal.ai", "Много моделей (Seedance, Wan и другие) с одним ключом, оплата за каждое видео", "https://fal.ai/dashboard/keys", "https://queue.fal.run"),
    REPLICATE("Replicate", "Каталог моделей, оплата за секунды работы", "https://replicate.com/account/api-tokens", "https://api.replicate.com"),
    GEMINI("Google Gemini", "Gemini Omni Flash: ключ из Google AI Studio, видео в одном запросе", "https://aistudio.google.com/apikey", "https://generativelanguage.googleapis.com"),
}

/**
 * Готовая модель с известными параметрами. Пустые списки значат «этот параметр модели не передаём».
 * Для своей модели (custom) отправляется только описание и «дополнительные параметры».
 */
data class GenPreset(
    val provider: GenProvider,
    val model: String,
    val label: String,
    val about: String,
    val aspects: List<String> = emptyList(),
    val durations: List<Int> = emptyList(),
    val defaultDuration: Int = 5,
    val durationAsString: Boolean = false,
    val resolutions: List<String> = emptyList(),
    val defaultResolution: String = "",
    val audioKey: String? = null,
    val custom: Boolean = false,
)

object GenPresets {
    val SEEDANCE_FAL = GenPreset(
        GenProvider.FAL, "bytedance/seedance-2.0/text-to-video", "Seedance 2.0",
        "Кинематографичные ролики со звуком и речью, 4–15 секунд",
        aspects = listOf("16:9", "9:16", "1:1"), durations = listOf(4, 5, 6, 8, 10, 12, 15), defaultDuration = 5, durationAsString = true,
        resolutions = listOf("480p", "720p"), defaultResolution = "720p", audioKey = "generate_audio",
    )
    val WAN_FAL = GenPreset(
        GenProvider.FAL, "alibaba/wan-3.0/text-to-video", "Wan 3.0",
        "Универсальная модель Alibaba, можно до 1080p",
        aspects = listOf("16:9", "9:16", "1:1"), durations = listOf(5, 10), defaultDuration = 5,
        resolutions = listOf("480p", "720p", "1080p"), defaultResolution = "720p", audioKey = "audio",
    )
    val SEEDANCE_REPLICATE = GenPreset(
        GenProvider.REPLICATE, "bytedance/seedance-2.5", "Seedance 2.5",
        "Ролики со звуком, до 30 секунд",
        aspects = listOf("16:9", "9:16", "1:1"), durations = listOf(5, 8, 10, 15), defaultDuration = 5,
        audioKey = "generate_audio",
    )
    val OMNI_GEMINI = GenPreset(
        GenProvider.GEMINI, "gemini-omni-1.1-flash", "Gemini Omni Flash",
        "Видео от Google, формат 16:9 или 9:16 (длительность выбирает сама модель)",
        aspects = listOf("16:9", "9:16"), resolutions = listOf("360p", "720p", "1080p"), defaultResolution = "720p",
    )

    val all: List<GenPreset> = listOf(SEEDANCE_FAL, WAN_FAL, SEEDANCE_REPLICATE, OMNI_GEMINI)

    fun forProvider(p: GenProvider): List<GenPreset> = all.filter { it.provider == p }

    fun defaultFor(p: GenProvider): GenPreset = forProvider(p).first()

    /** Модель из списка, а для остальных названий: у Gemini остаются те же параметры, у прочих только описание. */
    fun resolve(p: GenProvider, model: String): GenPreset {
        val m = model.trim()
        if (m.isEmpty()) return defaultFor(p)
        forProvider(p).firstOrNull { it.model == m }?.let { return it }
        return if (p == GenProvider.GEMINI) OMNI_GEMINI.copy(model = m, label = m, custom = true)
        else GenPreset(p, m, m, "Своя модель: отправляется только описание и дополнительные параметры", custom = true)
    }
}

/** Что просит пользователь. Поля, которых нет у выбранной модели, игнорируются. */
data class GenRequest(
    val prompt: String,
    val aspect: String = "16:9",
    val duration: Int = 5,
    val resolution: String = "",
    val audio: Boolean = true,
    /** Дополнительные параметры модели в виде JSON-объекта (необязательно). */
    val extra: String = "",
)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/** Вливает пользовательский JSON поверх собранных параметров. */
fun mergeExtra(base: JsonObject, extra: String): JsonObject {
    val t = extra.trim()
    if (t.isEmpty()) return base
    val obj = try {
        lenient.parseToJsonElement(t).jsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("Дополнительные параметры должны быть JSON-объектом, например {\"seed\": 42}")
    }
    return JsonObject(base + obj)
}

/** Параметры для fal.ai и Replicate (поле input). */
fun buildGenInput(preset: GenPreset, r: GenRequest): JsonObject {
    val base = buildJsonObject {
        put("prompt", r.prompt.trim())
        if (preset.aspects.isNotEmpty()) put("aspect_ratio", r.aspect.takeIf { it in preset.aspects } ?: preset.aspects.first())
        if (preset.durations.isNotEmpty()) {
            val d = r.duration.takeIf { it in preset.durations } ?: preset.defaultDuration
            if (preset.durationAsString) put("duration", d.toString()) else put("duration", d)
        }
        if (preset.resolutions.isNotEmpty()) {
            put("resolution", r.resolution.takeIf { it in preset.resolutions } ?: preset.defaultResolution.ifBlank { preset.resolutions.first() })
        }
        preset.audioKey?.let { put(it, r.audio) }
    }
    return mergeExtra(base, r.extra)
}

/** Запрос Replicate: параметры лежат в поле input. */
fun buildReplicateBody(preset: GenPreset, r: GenRequest): JsonObject = buildJsonObject {
    put("input", buildGenInput(preset, r))
}

/** Запрос Gemini Omni Flash: сразу просим ссылку на файл, чтобы не тащить большое видео внутри JSON. */
fun buildGeminiBody(preset: GenPreset, r: GenRequest): JsonObject {
    val fmt = buildJsonObject {
        put("type", "video")
        put("aspect_ratio", r.aspect.takeIf { it in preset.aspects } ?: preset.aspects.firstOrNull() ?: "16:9")
        if (preset.resolutions.isNotEmpty()) {
            put("resolution", r.resolution.takeIf { it in preset.resolutions } ?: preset.defaultResolution.ifBlank { preset.resolutions.first() })
        }
        put("delivery", "uri")
    }
    val base = buildJsonObject {
        put("model", preset.model)
        put("input", r.prompt.trim())
        put("response_format", fmt)
    }
    return mergeExtra(base, r.extra)
}

// ---------- разбор ответов ----------

private fun parseObj(body: String): JsonObject? = runCatching { lenient.parseToJsonElement(body).jsonObject }.getOrNull()

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

/** Понятный текст ошибки из тела ответа сервиса (разные сервисы кладут его в разные поля). */
fun apiErrorText(body: String): String {
    val root = runCatching { lenient.parseToJsonElement(body) }.getOrNull()
    fun text(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> el.mapNotNull { text(it) }.joinToString("; ").takeIf { it.isNotBlank() }
        is JsonObject -> listOf("message", "msg", "detail", "error").firstNotNullOfOrNull { text(el[it]) }
    }
    val found = (root as? JsonObject)?.let { o -> listOf("detail", "error", "message", "title", "errors").firstNotNullOfOrNull { text(o[it]) } }
    return (found ?: body.trim()).replace(Regex("\\s+"), " ").take(300)
}

/** Объяснение HTTP-ошибки сервиса на человеческом языке. */
fun explainGenHttp(provider: GenProvider, code: Int, body: String): String {
    val tail = apiErrorText(body)
    val name = provider.label
    return when (code) {
        401, 403 -> "Ключ $name не принят (HTTP $code). Проверьте ключ в блоке «Создание видео по API». $tail"
        402 -> "На счёте $name не хватает средств (HTTP 402). Пополните баланс в кабинете сервиса. $tail"
        404 -> "Модель или адрес не найдены (HTTP 404): проверьте название модели. $tail"
        400, 422 -> "Сервис не принял параметры (HTTP $code): $tail"
        429 -> "Слишком много запросов или исчерпан лимит $name (HTTP 429). Подождите и повторите. $tail"
        in 500..599 -> "Сервис $name сейчас недоступен (HTTP $code). Повторите позже. $tail"
        else -> "Сервис $name ответил HTTP $code: $tail"
    }
}

/** Ссылки на файлы в произвольном ответе: сначала те, что помечены как видео, потом остальные. */
private fun collectUrls(el: JsonElement?, preferred: MutableList<String>, all: MutableList<String>) {
    when (el) {
        null, JsonNull -> Unit
        is JsonPrimitive -> el.contentOrNull?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { all += it }
        is JsonArray -> el.forEach { collectUrls(it, preferred, all) }
        is JsonObject -> {
            val url = el.str("url")
            val type = el.str("content_type") ?: el.str("mime_type")
            if (url != null && type != null && type.startsWith("video/")) preferred += url
            for (k in listOf("video", "video_url", "videos", "output", "url", "file")) collectUrls(el[k], preferred, all)
            for ((k, v) in el) if (k !in listOf("video", "video_url", "videos", "output", "url", "file")) collectUrls(v, preferred, all)
        }
    }
}

private val VIDEO_URL = Regex("\\.(mp4|webm|mov|m4v)(\\?|#|$)", RegexOption.IGNORE_CASE)

/** Ссылка на готовое видео в ответе fal.ai или Replicate (формат ответа у моделей разный). */
fun findVideoUrl(el: JsonElement?): String? {
    val preferred = ArrayList<String>()
    val all = ArrayList<String>()
    collectUrls(el, preferred, all)
    return preferred.firstOrNull() ?: all.firstOrNull { VIDEO_URL.containsMatchIn(it) } ?: all.firstOrNull()
}

fun findVideoUrl(body: String): String? = runCatching { lenient.parseToJsonElement(body) }.getOrNull()?.let { findVideoUrl(it) }

// ----- fal.ai -----

data class FalSubmit(val requestId: String, val statusUrl: String, val responseUrl: String, val cancelUrl: String)

/** Ответ на постановку в очередь. Если сервис не прислал ссылки, собираем их по правилам fal (владелец и приложение, без хвоста модели). */
fun parseFalSubmit(body: String, model: String): FalSubmit {
    val o = parseObj(body) ?: throw IllegalStateException("fal.ai вернул не JSON: ${body.take(200)}")
    val id = o.str("request_id") ?: throw IllegalStateException("fal.ai не вернул номер запроса: ${apiErrorText(body)}")
    val app = model.trim().trim('/').split('/').take(2).joinToString("/")
    val base = "https://queue.fal.run/$app/requests/$id"
    return FalSubmit(id, o.str("status_url") ?: "$base/status", o.str("response_url") ?: base, o.str("cancel_url") ?: "$base/cancel")
}

data class FalStatus(val status: String, val queuePosition: Int?, val error: String?)

fun parseFalStatus(body: String): FalStatus {
    val o = parseObj(body) ?: throw IllegalStateException("fal.ai вернул не JSON: ${body.take(200)}")
    val err = o["error"]?.takeIf { it !is JsonNull }?.let { e ->
        if (e is JsonPrimitive) e.contentOrNull else apiErrorText(e.toString())
    }?.takeIf { it.isNotBlank() }
    return FalStatus((o.str("status") ?: "").uppercase(), (o["queue_position"] as? JsonPrimitive)?.intOrNull, err)
}

// ----- Replicate -----

data class RepPrediction(val id: String, val status: String, val getUrl: String, val cancelUrl: String, val output: JsonElement?, val error: String?)

fun parseReplicate(body: String): RepPrediction {
    val o = parseObj(body) ?: throw IllegalStateException("Replicate вернул не JSON: ${body.take(200)}")
    val id = o.str("id") ?: throw IllegalStateException("Replicate не вернул номер запроса: ${apiErrorText(body)}")
    val urls = o["urls"] as? JsonObject
    return RepPrediction(
        id = id,
        status = (o.str("status") ?: "").lowercase(),
        getUrl = urls?.str("get") ?: "https://api.replicate.com/v1/predictions/$id",
        cancelUrl = urls?.str("cancel") ?: "https://api.replicate.com/v1/predictions/$id/cancel",
        output = o["output"],
        error = o.str("error"),
    )
}

// ----- Gemini -----

sealed class GeminiVideo {
    /** Видео прямо в ответе (base64). */
    class Inline(val base64: String) : GeminiVideo()

    /** Ссылка на файл в хранилище Gemini. */
    class Uri(val uri: String) : GeminiVideo()
}

/** Ищет видео в ответе Interactions API: шаги steps → model_output → content → video. */
fun parseGeminiVideo(body: String): GeminiVideo? {
    val root = runCatching { lenient.parseToJsonElement(body) }.getOrNull() ?: return null
    fun walk(el: JsonElement?): GeminiVideo? {
        when (el) {
            is JsonObject -> {
                val type = el.str("type")
                val mime = el.str("mime_type")
                if (type == "video" || (mime != null && mime.startsWith("video/"))) {
                    el.str("data")?.takeIf { it.isNotBlank() }?.let { return GeminiVideo.Inline(it) }
                    el.str("uri")?.takeIf { it.isNotBlank() }?.let { return GeminiVideo.Uri(it) }
                }
                for ((_, v) in el) walk(v)?.let { return it }
            }
            is JsonArray -> for (x in el) walk(x)?.let { return it }
            else -> Unit
        }
        return null
    }
    return walk(root)
}

/** Номер файла из ссылки вида …/v1beta/files/abc123 (или …/files/abc123:download?alt=media). */
fun geminiFileId(uri: String): String? {
    val i = uri.indexOf("/files/")
    if (i < 0) return null
    return uri.substring(i + 7).substringBefore(':').substringBefore('?').substringBefore('/').takeIf { it.isNotBlank() }
}

/** Безопасное имя файла для результата: буквы и цифры из описания, до 24 знаков. */
fun genFileName(prompt: String, stamp: String): String {
    val slug = prompt.trim().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(24).trim('_')
    return "gen_${stamp}_${slug.ifEmpty { "video" }}.mp4"
}

/** 83 → «1:23». */
fun genElapsed(sec: Long): String = String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)
