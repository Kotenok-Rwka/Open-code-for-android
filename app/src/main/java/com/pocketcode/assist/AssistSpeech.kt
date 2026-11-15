package com.pocketcode.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.pocketcode.ui.I18n

/** Голосовой ввод в окне ассистента. Нужен доступ к микрофону и любая установленная служба распознавания речи. */
class AssistSpeech(private val ctx: Context, private val cb: Callback) {
    interface Callback {
        fun onListening(on: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    enum class Start { OK, NO_PERMISSION, NO_RECOGNIZER }

    private var recognizer: SpeechRecognizer? = null

    var listening: Boolean = false
        private set

    fun start(): Start {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return Start.NO_PERMISSION
        val target = AssistRecognizers.pick(ctx) ?: return Start.NO_RECOGNIZER
        stop()
        val r = try {
            SpeechRecognizer.createSpeechRecognizer(ctx, target)
        } catch (_: Exception) {
            return Start.NO_RECOGNIZER
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                setListening(false)
                cb.onError(I18n.t(AssistVoice.errorText(error)))
            }
            override fun onResults(results: Bundle?) {
                setListening(false)
                val text = first(results)
                if (text.isNotBlank()) cb.onFinal(text) else cb.onError(I18n.t(AssistVoice.errorText(7)))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = first(partialResults)
                if (text.isNotBlank()) cb.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, AssistVoice.languageTag(I18n.code, java.util.Locale.getDefault().toLanguageTag()))
        return try {
            r.startListening(intent)
            Start.OK
        } catch (_: Exception) {
            stop()
            Start.NO_RECOGNIZER
        }
    }

    /** Заканчивает запись: то, что уже сказано, ещё будет распознано. */
    fun finishSpeaking() {
        runCatching { recognizer?.stopListening() }
    }

    fun stop() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        setListening(false)
    }

    private fun setListening(on: Boolean) {
        if (listening == on) return
        listening = on
        cb.onListening(on)
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
