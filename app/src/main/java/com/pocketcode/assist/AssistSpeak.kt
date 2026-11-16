package com.pocketcode.assist

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Подготовка текста ответа к озвучке: без разметки, ссылок и списка источников, кусками по предложениям. */
object AssistSpeak {
    const val MAX_TOTAL = 2500
    const val MAX_CHUNK = 350

    private val SOURCES_RX = Regex("(?im)^\\s*(?:источники|sources|quellen|fuentes|来源|المصادر)\\s*:")
    private val URL_RX = Regex("https?://\\S+")
    private val SPLIT_RX = Regex("(?<=[.!?…])\\s+|\\n+")

    fun prepare(markdown: String): List<String> {
        var text = AssistMarkdown.parse(markdown).text
        SOURCES_RX.find(text)?.let { text = text.substring(0, it.range.first) }
        text = URL_RX.replace(text, "").replace("•", "").trim()
        if (text.isEmpty()) return emptyList()
        if (text.length > MAX_TOTAL) text = text.take(MAX_TOTAL)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (part in text.split(SPLIT_RX)) {
            val p = part.trim()
            if (p.isEmpty()) continue
            // слишком длинное предложение режем по длине: у движков озвучки есть предел на один кусок
            for (piece in p.chunked(MAX_CHUNK)) {
                if (cur.isNotEmpty() && cur.length + 1 + piece.length > MAX_CHUNK) {
                    out += cur.toString()
                    cur.setLength(0)
                }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(piece)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

/** Озвучка системным движком синтеза речи (Google или другой, установленный на телефоне). Колбэк [onSpeaking] приходит в главном потоке. */
class AssistTts(private val app: Context, private val onSpeaking: (Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var queued: List<String>? = null
    private var tag = "en-US"
    private var lastId = ""
    private var speaking = false

    fun speak(chunks: List<String>, langTag: String) {
        if (chunks.isEmpty()) return
        tag = langTag
        val t = tts
        if (t == null) {
            queued = chunks
            tts = TextToSpeech(app) { status -> main.post { onInit(status) } }
            return
        }
        if (!ready) {
            queued = chunks
            return
        }
        play(chunks)
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            runCatching { t.shutdown() }
            tts = null
            ready = false
            queued = null
            setSpeaking(false)
            return
        }
        ready = true
        runCatching {
            t.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) main.post { setSpeaking(false) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == lastId) main.post { setSpeaking(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == lastId) main.post { setSpeaking(false) }
            }
        })
        queued?.let {
            queued = null
            play(it)
        }
    }

    private fun play(chunks: List<String>) {
        val t = tts ?: return
        runCatching { t.setLanguage(Locale.forLanguageTag(tag)) }
        lastId = "a" + (chunks.size - 1)
        chunks.forEachIndexed { i, text ->
            t.speak(text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "a$i")
        }
        setSpeaking(true)
    }

    fun stop() {
        queued = null
        runCatching { tts?.stop() }
        setSpeaking(false)
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun setSpeaking(on: Boolean) {
        if (speaking == on) return
        speaking = on
        onSpeaking(on)
    }
}
