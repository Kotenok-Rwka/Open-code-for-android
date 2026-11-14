// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketcode.SettingsStore
import com.pocketcode.phone.AppInfo
import com.pocketcode.phone.PhoneConfirm
import com.pocketcode.phone.PhoneController
import com.pocketcode.phone.PhonePrompt
import com.pocketcode.phone.PhoneText
import com.pocketcode.phone.HelperState
import com.pocketcode.phone.HelperMode
import com.pocketcode.phone.a11y.A11yScreenReader
import com.pocketcode.phone.a11y.PocketAccessibilityService
import com.pocketcode.phone.adb.AndroidWirelessAdb
import com.pocketcode.phone.adb.NotificationPairingFlow
import com.pocketcode.phone.helper.HelperHub
import com.pocketcode.tools.Tool
import com.pocketcode.tools.phoneTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние и действия режима «Управление телефоном» для экрана настроек и сборки инструментов агента. */
class PhoneModeState(
    private val app: Application,
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    /** Режим изменился: агента надо собрать заново. */
    private val onChanged: () -> Unit,
) {
    var enabled by mutableStateOf(store.loadPhoneEnabled())
        private set
    var confirm by mutableStateOf(PhoneConfirm.parse(store.loadPhoneConfirm()))
        private set
    /** Прикладывать скриншоты к ответам инструментов (нужна модель с поддержкой изображений). */
    var screenshots by mutableStateOf(store.loadPhoneShots())
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var helper by mutableStateOf(HelperState(false, HelperMode.NONE, ""))
        private set
    var accessibilityOn by mutableStateOf(false)
        private set

    private val hub by lazy {
        HelperHub.get(app).also { it.attachAdb(adb) }
    }
    private val adb by lazy { AndroidWirelessAdb(app) }
    private val pairing by lazy { NotificationPairingFlow(app, adb) }

    val controller: PhoneController by lazy {
        PhoneController(A11yScreenReader, hub.input, ::launchLocal, ::appsLocal)
    }

    init {
        scope.launch { hub.state.collect { helper = it } }
        scope.launch { PocketAccessibilityService.instance.collect { accessibilityOn = it != null } }
    }

    fun chooseEnabled(v: Boolean) {
        enabled = v
        store.savePhoneEnabled(v)
        onChanged()
    }

    fun chooseScreenshots(v: Boolean) {
        screenshots = v
        store.savePhoneShots(v)
    }

    /** Режим готов к работе: служба доступности включена или есть помощник. */
    val ready: Boolean get() = accessibilityOn || helper.connected

    fun chooseConfirm(c: PhoneConfirm) {
        confirm = c
        store.savePhoneConfirm(c.name)
    }

    /** Инструменты агента; пусто, если режим выключен. */
    fun tools(): List<Tool> = if (enabled) phoneTools(controller, { confirm }, { screenshots }) else emptyList()

    fun promptSection(): String? =
        if (enabled) PhonePrompt.section(controller.capabilities(), confirm, screenshots) else null

    fun openAccessibilitySettings() {
        runCatching { app.startActivity(PocketAccessibilityService.openSettingsIntent()) }
    }

    private fun run(start: String, block: suspend () -> String) {
        if (busy) return
        busy = true
        status = start
        scope.launch {
            try {
                status = block()
            } catch (e: Exception) {
                status = "Ошибка: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    fun startRoot() = run("Запускаю помощника через root… (менеджер root может показать запрос)") { PhoneText.outcome(hub.startViaRoot()) }

    fun startWireless() = run("Запускаю помощника через беспроводную отладку…") { PhoneText.outcome(hub.startViaWirelessDebugging()) }

    fun stopHelper() = run("Останавливаю помощника…") { hub.stop(); "Помощник остановлен." }

    fun enableAccessibilityViaHelper() = run("Включаю службу доступности…") {
        if (!hub.input.connected) "Сначала запусти помощника."
        else if (hub.input.enableAccessibility()) "Служба доступности включена." else "Не удалось включить службу доступности."
    }

    /** Показывает уведомление для ввода кода сопряжения и открывает настройки разработчика. */
    fun beginPairing() {
        if (busy) return
        status = "Открой в настройках разработчика «Беспроводная отладка» → «Подключить устройство с помощью кода сопряжения» и введи код в уведомлении."
        pairing.begin { r -> status = PhoneText.pairing(r) }
    }

    fun cancelPairing() {
        pairing.cancel()
        status = null
    }

    private suspend fun launchLocal(target: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val intent = if (target.startsWith("http://") || target.startsWith("https://") || target.contains(':'))
                Intent(Intent.ACTION_VIEW, Uri.parse(target)) else app.packageManager.getLaunchIntentForPackage(target)
            if (intent == null) false else {
                app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }
        } catch (_: Exception) { false }
    }

    private suspend fun appsLocal(): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(q, 0).map {
            val system = (it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString(), system)
        }.distinctBy { it.pkg }
    }
}
