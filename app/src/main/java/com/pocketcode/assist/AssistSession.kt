package com.pocketcode.assist

import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.pocketcode.MainActivity
import com.pocketcode.SettingsStore
import com.pocketcode.phone.AppInfo
import com.pocketcode.ui.I18n
import com.pocketcode.ui.accentById
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Цвета окна ассистента: те же тема, акцент и чёрный фон, что выбраны в приложении. */
object AssistTheme {
    fun load(ctx: Context, store: SettingsStore): AssistColors {
        val dark = when (store.rawGet("themeMode") ?: "dark") {
            "dark" -> true
            "light" -> false
            else -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val amoled = store.rawGet("amoled") == "1"
        val accent = accentById(store.rawGet("palette") ?: "blue")
        val color = when {
            accent != null -> (if (dark) accent.dark else accent.light).toArgb()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> runCatching {
                ctx.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600)
            }.getOrDefault(if (dark) 0xFF8AB4F8.toInt() else 0xFF2F5DA8.toInt())
            else -> if (dark) 0xFF8AB4F8.toInt() else 0xFF2F5DA8.toInt()
        }
        return AssistColors(dark, amoled && dark, color)
    }
}

/**
 * Окно ассистента: открывается по вызову ассистента системой (долгое нажатие Home, жест, кнопка).
 * Само окно и поиск разделены: поиск ведёт [AssistRunner] и не зависит от того, открыто ли окно.
 */
class AssistSession(private val svc: Context) : VoiceInteractionSession(svc) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ui: AssistUi? = null
    private var speech: AssistSpeech? = null
    private var settings: SettingsStore? = null
    private var prefStore: AssistPrefsStore? = null
    private var prefs = AssistPrefs()
    private var collector: Job? = null
    private var locked = false
    private var screenText: String? = null
    private var pageUrl: String? = null

    private fun app(): Context = svc.applicationContext

    private fun store(): SettingsStore = settings ?: SettingsStore(app()).also { settings = it }

    override fun onCreate() {
        super.onCreate()
        I18n.init(app())
        val st = store()
        prefStore = AssistPrefsStore({ st.rawGet(it) }, { k, v -> st.rawPut(k, v) })
    }

    override fun onCreateContentView(): View {
        val u = AssistUi(context, hostImpl, AssistTheme.load(app(), store()))
        ui = u
        speech = AssistSpeech(app(), speechCallback)
        return u.root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val u = ui ?: return
        prefs = prefStore?.load() ?: AssistPrefs()
        locked = svc.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        screenText = null
        pageUrl = null
        AssistRunner.sessionVisible = true
        AssistNotifier.cancel(app())
        u.reset(locked, prefs)
        val st = AssistRunner.state.value
        var listenNow = false
        if (AssistRunner.consumeReveal() && st.status == AssistRunner.Status.DONE) {
            u.reveal(st, prefs)
        } else if (st.status != AssistRunner.Status.RUNNING) {
            AssistRunner.reset()
            AssistRunner.newConversation()
            listenNow = prefs.autoListen
        }
        collector?.cancel()
        collector = scope.launch { AssistRunner.state.collect { u.render(it, prefs) } }
        // сразу слушаем: говорить быстрее, чем печатать; текст можно ввести, коснувшись поля
        if (listenNow) u.root.post { if (AssistRunner.sessionVisible) { u.targetMainInput(); onToggleMic(auto = true) } }
    }

    override fun onHide() {
        super.onHide()
        AssistRunner.sessionVisible = false
        collector?.cancel()
        collector = null
        speech?.stop()
        ui?.release()
    }

    override fun onDestroy() {
        AssistRunner.sessionVisible = false
        scope.cancel()
        speech?.stop()
        ui?.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (ui?.handleBack() == true) return
        hide()
    }

    /** Система передаёт то, что было на экране в момент вызова. Берём только если пользователь это разрешил, и используем лишь по нажатию «Что на экране?». */
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        if (!prefs.useScreenText || locked) return
        pageUrl = content?.webUri?.toString()
        val lines = ArrayList<String>()
        if (structure != null) {
            val budget = intArrayOf(4000)
            for (i in 0 until structure.windowNodeCount) {
                val root = structure.getWindowNodeAt(i).rootViewNode
                if (root.idPackage == svc.packageName) continue
                walk(root, lines, 0, budget)
            }
        }
        val text = AssistText.joinScreen(lines, 6000)
        screenText = text.ifBlank { null }
        ui?.setScreenContextAvailable(screenText != null || pageUrl != null)
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<String>, depth: Int, budget: IntArray) {
        if (budget[0] <= 0 || depth > 60) return
        budget[0]--
        if (node.visibility != View.VISIBLE || node.isAssistBlocked) return
        if (!AssistText.isPasswordInputType(node.inputType)) {
            val t = node.text?.toString()
            if (!t.isNullOrBlank()) out += t else node.contentDescription?.toString()?.let { if (it.isNotBlank()) out += it }
        }
        for (i in 0 until node.childCount) walk(node.getChildAt(i), out, depth + 1, budget)
    }

    // ===== что делает окно по нажатиям =====

    private val hostImpl = object : AssistUi.Host {
        override fun close() {
            hide()
        }

        override fun submit(text: String, followUp: Boolean, withScreen: Boolean) {
            onSubmit(text, followUp, withScreen)
        }

        override fun cancelRun() {
            AssistRunner.cancel()
        }

        override fun toggleMic() {
            onToggleMic(auto = false)
        }

        override fun openUrl(url: String) {
            if (!AssistFlow.canOpenOutside(locked)) {
                ui?.flash(I18n.t("Разблокируй телефон, чтобы открыть это"))
                return
            }
            if (startOutside(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) hide()
        }

        override fun openChat(text: String) {
            if (!AssistFlow.canOpenOutside(locked)) {
                ui?.flash(I18n.t("Разблокируй телефон, чтобы открыть это"))
                return
            }
            val i = Intent(app(), MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            if (startOutside(i)) hide()
        }

        override fun openSettings(screen: String) {
            val i = Intent(app(), MainActivity::class.java).setAction("com.pocketcode.OPEN_SCREEN").putExtra("screen", screen)
            if (startOutside(i)) hide()
        }

        override fun runPhoneTask(text: String) {
            if (!AssistFlow.canOpenOutside(locked)) {
                ui?.flash(I18n.t("Разблокируй телефон, чтобы открыть это"))
                return
            }
            if (!store().loadPhoneEnabled()) {
                ui?.flash(I18n.t("Режим «Управление телефоном» выключен: включи его в приложении"))
                return
            }
            val i = Intent(app(), MainActivity::class.java).setAction("com.pocketcode.RUN_TASK").putExtra("text", text)
            if (startOutside(i)) hide()
        }

        override fun copy(text: String) {
            runCatching {
                app().getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
                ui?.flash(I18n.t("Скопировано"))
            }
        }

        override fun share(text: String) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            if (startOutside(Intent.createChooser(send, null))) hide()
        }

        override fun rememberResult(fullscreen: Boolean) {
            prefs = AssistFlow.rememberChoice(prefs, fullscreen, true)
            prefStore?.save(prefs)
            ui?.updatePrefs(prefs)
        }
    }

    private fun startOutside(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app().startActivity(intent)
            true
        } catch (_: Exception) {
            ui?.flash(I18n.t("Не удалось открыть"))
            false
        }
    }

    private fun onSubmit(text: String, followUp: Boolean, withScreen: Boolean) {
        val u = ui ?: return
        speech?.stop()
        when (val intent = AssistIntentParser.parse(text)) {
            AssistIntent.Empty -> u.flash(I18n.t("Напиши или скажи, что найти"))
            is AssistIntent.OpenUrl -> hostImpl.openUrl(intent.url)
            is AssistIntent.OpenApp -> {
                if (!AssistFlow.canOpenOutside(locked)) {
                    u.flash(I18n.t("Разблокируй телефон, чтобы открыть это"))
                    return
                }
                val found = findApp(intent.name)
                val launch = found?.let { app().packageManager.getLaunchIntentForPackage(it.pkg) }
                if (launch != null) {
                    if (startOutside(launch)) hide()
                } else {
                    // такого приложения нет: значит, это был поисковый запрос
                    startRun(text, force = true, withScreen = false, followUp = followUp)
                }
            }
            is AssistIntent.Ask -> startRun(intent.text, intent.forceSearch, withScreen, followUp)
        }
    }

    private fun startRun(q: String, force: Boolean, withScreen: Boolean, followUp: Boolean) {
        if (!followUp) AssistRunner.newConversation()
        AssistRunner.start(app(), q, force, if (withScreen) screenText else null, if (withScreen) pageUrl else null, prefs)
    }

    private fun findApp(name: String): AppInfo? {
        val pm = app().packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0).map { ri ->
            val ai = ri.activityInfo
            AppInfo(ai.packageName, ri.loadLabel(pm).toString(), (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }
        return AssistAppMatch.find(apps, name)
    }

    // ===== голос =====

    /** [auto] — микрофон включается сам при вызове: без доступа не уводим человека на экран разрешений, а подсказываем нажать на микрофон. */
    private fun onToggleMic(auto: Boolean) {
        val sp = speech ?: return
        if (sp.listening) {
            if (!auto) sp.finishSpeaking()
            return
        }
        when (sp.start()) {
            AssistSpeech.Start.OK -> {}
            AssistSpeech.Start.NO_PERMISSION -> {
                if (auto) {
                    ui?.flash(I18n.t("Нажми на микрофон, чтобы разрешить доступ"))
                    return
                }
                ui?.flash(I18n.t("Нужен доступ к микрофону"))
                val i = Intent(app(), AssistPermissionActivity::class.java)
                if (startOutside(i)) hide()
            }
            AssistSpeech.Start.NO_RECOGNIZER -> ui?.flash(I18n.t("На телефоне нет службы распознавания речи"))
        }
    }

    private val speechCallback = object : AssistSpeech.Callback {
        override fun onListening(on: Boolean) {
            ui?.setListening(on)
        }

        override fun onPartial(text: String) {
            ui?.setVoiceText(text, false)
        }

        override fun onFinal(text: String) {
            ui?.setVoiceText(text, true)
        }

        override fun onError(message: String) {
            ui?.flash(message)
        }
    }
}
