package com.pocketcode.video.toon

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pocketcode.video.parseProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Настройки облачного голоса: любой сервис с OpenAI-совместимым адресом /audio/speech. */
data class ToonVoiceConfig(val baseUrl: String, val model: String, val key: String, val voices: List<String>)

/** Что и чем озвучивать. */
interface ToonVoice {
    /** Озвучивает текст; [index] — номер героя (у каждого свой голос или высота тона). */
    suspend fun speak(text: String, index: Int): Pcm
    /** Язык следующей реплики (BCP-47, напр. «en»); null — как раньше, русский. */
    var nextLang: String? = null
    fun close() {}
}

/** Системный движок синтеза речи: работает без интернета. Героям меняется высота тона. */
class SystemToonVoice(private val ctx: Context, private val engine: String, private val cache: File) : ToonVoice {
    private var tts: TextToSpeech? = null
    private var counter = 0

    private suspend fun ready(): TextToSpeech {
        tts?.let { return it }
        val t = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var made: TextToSpeech? = null
            made = TextToSpeech(ctx, { status ->
                if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) made else null)
            }, engine.ifEmpty { null })
            cont.invokeOnCancellation { runCatching { made?.shutdown() } }
        } ?: throw IOException("Системный синтезатор речи не запустился. Проверьте движок в настройках ассистента.")
        runCatching {
            val lang = nextLang ?: "ru"
            val installed = runCatching { t.availableLanguages.orEmpty().map { it.toString() } }.getOrElse { emptyList() }
            t.setLanguage(ToonVoiceLang.pickLocale(lang, installed))
        }
        tts = t
        return t
    }

    override suspend fun speak(text: String, index: Int): Pcm {
        val t = ready()
        val pitches = floatArrayOf(1.0f, 1.25f, 0.8f, 1.1f, 0.9f)
        t.setPitch(pitches[index % pitches.size])
        val file = File(cache, "tts_${System.nanoTime()}_${counter++}.wav")
        val id = "toon" + counter
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            })
            val r = t.synthesizeToFile(text, Bundle(), file, id)
            if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
        try {
            if (!ok || !file.isFile || file.length() < 100) throw IOException("Синтезатор не смог озвучить фразу: «${text.take(40)}»")
            return withContext(Dispatchers.IO) { Wav.parse(file.readBytes()) }
        } finally {
            file.delete()
        }
    }

    override fun close() {
        runCatching { tts?.shutdown() }
        tts = null
    }
}

/** Облачный голос (нейросеть): POST /audio/speech, ответ WAV. */
class CloudToonVoice(private val cfg: ToonVoiceConfig, proxy: String) : ToonVoice {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .apply { parseProxy(proxy)?.let { proxy(it) } }
        .build()

    override suspend fun speak(text: String, index: Int): Pcm = withContext(Dispatchers.IO) {
        val voices = cfg.voices.ifEmpty { listOf("alloy") }
        val body = buildJsonObject {
            put("model", cfg.model)
            put("input", text)
            put("voice", voices[index % voices.size])
            put("response_format", "wav")
        }.toString()
        val req = Request.Builder()
            .url(cfg.baseUrl.trim().trimEnd('/') + "/audio/speech")
            .header("Authorization", "Bearer " + cfg.key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = try { client.newCall(req).execute() } catch (e: IOException) {
            throw IOException("Нет связи с сервисом озвучки: ${e.message ?: e.javaClass.simpleName}. Проверьте интернет, VPN или прокси.")
        }
        resp.use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) {
                val tail = String(bytes, Charsets.UTF_8).take(300)
                throw IOException(
                    when (r.code) {
                        401, 403 -> "Ключ озвучки не принят (HTTP ${r.code})."
                        404 -> "Адрес или модель озвучки не найдены (HTTP 404). $tail"
                        429 -> "Лимит сервиса озвучки исчерпан (HTTP 429). $tail"
                        else -> "Сервис озвучки ответил HTTP ${r.code}: $tail"
                    },
                )
            }
            Wav.parse(bytes)
        }
    }
}

/** Знаки ударения для движков, которые их не понимают: «з+амок» и «за́мок» → «замок». */
fun stripStressMarks(s: String): String = s.replace("+", "").replace("́", "")
