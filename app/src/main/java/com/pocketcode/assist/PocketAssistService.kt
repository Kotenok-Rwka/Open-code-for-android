package com.pocketcode.assist

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.widget.Toast
import com.pocketcode.MainActivity
import com.pocketcode.ui.I18n

/**
 * Служба ассистента. Когда пользователь выбирает PocketCode в «Приложение цифрового помощника», система вызывает её
 * долгим нажатием Home, жестом из угла экрана или кнопкой питания (как у Google), и показывает окно [AssistSession].
 */
class PocketAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
    }

    override fun onShutdown() {
        if (instance === this) instance = null
        super.onShutdown()
    }

    /** Вызов ассистента с экрана блокировки (кнопка помощника или жест на заблокированном телефоне). */
    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        runCatching { showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST) }
    }

    companion object {
        @Volatile
        var instance: PocketAssistService? = null
            private set

        /** Показывает окно ассистента. false — служба сейчас не активна (PocketCode не выбран ассистентом). */
        fun show(): Boolean {
            val s = instance ?: return false
            return try {
                s.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST)
                true
            } catch (_: Exception) {
                false
            }
        }

        /** PocketCode сейчас выбран ассистентом по умолчанию. */
        fun isDefault(ctx: Context): Boolean = try {
            VoiceInteractionService.isActiveService(ctx, ComponentName(ctx, PocketAssistService::class.java))
        } catch (_: Exception) {
            false
        }
    }
}

/** Создаёт окно ассистента для каждого вызова. */
class PocketAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/** Нажатие на уведомление «ответ готов»: снова открывает окно ассистента, а оно сразу показывает готовый ответ. */
class AssistReopenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AssistNotifier.cancel(context)
        if (!PocketAssistService.show()) {
            runCatching { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/**
 * Когда PocketCode становится ассистентом, система делает его и службой распознавания речи по умолчанию (для голосового ввода во всех
 * приложениях). Своего распознавания у нас нет, поэтому просто передаём речь установленной службе (Google или другой), иначе голосовой ввод в
 * клавиатуре перестал бы работать.
 */
class PocketRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: RecognitionService.Callback) {
        val target = AssistRecognizers.pick(this)
        if (target == null) {
            runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this, target)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runCatching { listener.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { runCatching { listener.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { runCatching { listener.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { runCatching { listener.bufferReceived(buffer ?: ByteArray(0)) } }
            override fun onEndOfSpeech() { runCatching { listener.endOfSpeech() } }
            override fun onError(error: Int) { runCatching { listener.error(error) } }
            override fun onResults(results: Bundle?) { runCatching { listener.results(results ?: Bundle()) } }
            override fun onPartialResults(partialResults: Bundle?) { runCatching { listener.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: RecognitionService.Callback) {
        recognizer?.stopListening()
    }

    override fun onCancel(listener: RecognitionService.Callback) {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}

/** Выбор службы распознавания речи на телефоне. */
object AssistRecognizers {
    fun pick(ctx: Context): ComponentName? {
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val flat = list.mapNotNull { it.serviceInfo }.map { ComponentName(it.packageName, it.name).flattenToString() }
        val configured = runCatching { Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service") }.getOrNull()
            ?.let { ComponentName.unflattenFromString(it)?.flattenToString() }
        val chosen = AssistVoice.chooseRecognizer(flat, configured, ctx.packageName) ?: return null
        return ComponentName.unflattenFromString(chosen)
    }
}

/** Прозрачный экран, который просит у пользователя доступ к микрофону: из окна ассистента системное окно разрешения не показать. */
class AssistPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.init(this)
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Toast.makeText(this, I18n.t(if (ok) "Микрофон разрешён. Вызови ассистента ещё раз" else "Без доступа к микрофону голосовой ввод не работает"), Toast.LENGTH_LONG).show()
        finish()
    }
}

/** «Настройки» ассистента в списке системных настроек: открывает экран ассистента в приложении. */
class AssistSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction("com.pocketcode.OPEN_SCREEN")
                .putExtra("screen", "ASSISTANT")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
