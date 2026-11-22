package com.pocketcode.video.toon

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Несколько системных голосов одновременно: каждый движок/голос закрепляется за номером героя.
 * Позволяет поставить разные русские TTS-движки и использовать их вместе в одном мультике.
 */
class MultiToonVoice(
    private val ctx: Context,
    private val enginesCsv: String,
    private val cache: File,
    private val pitchCsv: String = "",
    private val rateCsv: String = "",
) : ToonVoice {
    private val engines: List<String> = enginesCsv.split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("") }
    private val ttsList = arrayOfNulls<TextToSpeech>(engines.size)
    private var counter = 0

    private suspend fun engine(i: Int): TextToSpeech {
        ttsList[i]?.let { return it }
        val t = suspendCancellableCoroutine<TextToSpeech?> { cont ->
            var made: TextToSpeech? = null
            made = TextToSpeech(ctx, { status ->
                if (cont.isActive) cont.resume(if (status == TextToSpeech.SUCCESS) made else null)
            }, engines[i].ifEmpty { null })
            cont.invokeOnCancellation { runCatching { made?.shutdown() } }
        } ?: throw IOException("TTS-движок «${engines[i].ifEmpty { "системный" }}» не запустился. Проверь, что он установлен и supports Russian.")
        runCatching { t.setLanguage(Locale("ru", "RU")) }
        ttsList[i] = t
        return t
    }

    override suspend fun speak(text: String, index: Int): Pcm {
        val slot = index % engines.size
        val t = engine(slot)
        t.setPitch(parseAt(pitchCsv, index, 1.0f).coerceIn(0.1f, 3f))
        t.setSpeechRate(parseAt(rateCsv, index, 1.0f).coerceIn(0.1f, 3f))
        val file = File(cache, "toon_multi_${System.nanoTime()}_${counter++}.wav")
        val id = "toonm" + counter
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            })
            if (t.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        }
        try {
            if (!ok || !file.isFile || file.length() < 100) throw IOException("Голос ${slot + 1} не смог озвучить: «${text.take(40)}»")
            return withContext(Dispatchers.IO) { Wav.parse(file.readBytes()) }
        } finally {
            file.delete()
        }
    }

    /** Список установленных движков — для подсказки в UI. */
    fun installedEngines(): List<String> = engines.map { it.ifEmpty { "системный по умолчанию" } }

    override fun close() {
        ttsList.forEach { runCatching { it?.shutdown() } }
        for (i in ttsList.indices) ttsList[i] = null
    }
}

private fun parseAt(csv: String, index: Int, default: Float): Float =
    csv.split(',', '|').getOrNull(index)?.trim()?.toFloatOrNull() ?: default
