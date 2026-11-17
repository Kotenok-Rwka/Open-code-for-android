package com.pocketcode.assist

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import com.pocketcode.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Движок озвучки, установленный на телефоне. */
data class TtsEngineInfo(val pkg: String, val label: String)

/**
 * Каталог нейроголосов и выбор движка озвучки. Голоса — готовые приложения-движки проекта sherpa-onnx (модели Piper),
 * они ставятся как отдельное приложение и появляются в списке движков озвучки Android. Файлы лежат у авторов на HuggingFace.
 */
object AssistVoices {
    const val SHERPA_VERSION = "1.12.22"
    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-new/"

    /** Голос Piper для русского языка; размеры нужны, чтобы проверить, что файл скачался целиком. */
    data class Voice(val id: String, val bytes64: Long, val bytes32: Long)

    val ALL: List<Voice> = listOf(
        Voice("irina", 83177789L, 82596161L),
        Voice("dmitri", 83194534L, 82612906L),
        Voice("denis", 83198269L, 82616641L),
        Voice("ruslan", 83198630L, 82617002L),
    )

    fun voice(id: String): Voice = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** Архитектура для скачивания: arm64 предпочтительнее; null, если телефон не ARM. */
    fun pickAbi(supported: Array<String>): String? = when {
        supported.contains("arm64-v8a") -> "arm64-v8a"
        supported.contains("armeabi-v7a") -> "armeabi-v7a"
        else -> null
    }

    fun fileName(v: Voice, abi: String): String = "sherpa-onnx-$SHERPA_VERSION-$abi-ru-tts-engine-vits-piper-ru_RU-${v.id}-medium.apk"

    fun url(v: Voice, abi: String): String = BASE + SHERPA_VERSION + "/" + fileName(v, abi)

    fun expectedBytes(v: Voice, abi: String): Long = if (abi == "arm64-v8a") v.bytes64 else v.bytes32

    fun sizeMb(v: Voice, abi: String): Long = (expectedBytes(v, abi) + 524288L) / 1048576L

    /** Движок нейроголоса sherpa-onnx (пакет с «sherpa» в имени). */
    fun isSherpa(pkg: String): Boolean = pkg.lowercase().contains("sherpa")

    /** Сохранённый движок, если он ещё установлен; иначе пусто — системный по умолчанию. */
    fun pickEngine(saved: String, installed: List<String>): String = if (saved.isNotEmpty() && saved in installed) saved else ""

    fun sizeMatches(actual: Long, expected: Long): Boolean = expected <= 0L || actual == expected

    /** Установленные движки озвучки (видимость пакетов открыта запросом TTS_SERVICE в манифесте). */
    fun installedEngines(ctx: Context): List<TtsEngineInfo> {
        val pm = ctx.packageManager
        val list = runCatching { pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0) }.getOrDefault(emptyList())
        return list.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            TtsEngineInfo(si.packageName, runCatching { ri.loadLabel(pm).toString() }.getOrDefault(si.packageName))
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Скачивает APK голоса в кэш (папка, которую знает FileProvider). Прогресс — в байтах. Ошибки — исключением. */
    suspend fun download(ctx: Context, v: Voice, abi: String, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val target = File(dir, fileName(v, abi))
        val expected = expectedBytes(v, abi)
        client.newCall(Request.Builder().url(url(v, abi)).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("Не удалось скачать (код ${resp.code})")
            val body = resp.body ?: error("Пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: expected
            var done = 0L
            var shown = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - shown > 1024 * 1024) {
                            shown = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (!sizeMatches(done, expected)) {
                target.delete()
                error("Файл скачался не полностью")
            }
        }
        target
    }

    fun install(ctx: Context, apk: File): String = Updater(ctx).install(apk)
}
