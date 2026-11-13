// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.pocketcode.phone.helper.AccessibilityList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Служба доступности: читает дерево окон, нажимает элементы и выполняет жесты. Работой руководит [A11yScreenReader];
 * сама служба только хранит свой экземпляр и последнюю замеченную активность.
 */
class PocketAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // на всякий случай просим показывать и «неважные» элементы, даже если флаг не прочитан из xml
        serviceInfo = serviceInfo?.apply { flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        _instance.value = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = event.className?.toString() ?: return
        // диалоги и меню тоже шлют это событие, но у активностей имя не из пакета android.*
        if (!cls.startsWith("android.") && !cls.startsWith("androidx.")) {
            lastActivity = cls.substringAfterLast('.')
            lastActivityPkg = event.packageName?.toString().orEmpty()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        if (_instance.value === this) _instance.value = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (_instance.value === this) _instance.value = null
        super.onDestroy()
    }

    companion object {
        private val _instance = MutableStateFlow<PocketAccessibilityService?>(null)
        /** Подключённая сейчас служба или null. */
        val instance: StateFlow<PocketAccessibilityService?> = _instance.asStateFlow()

        @Volatile internal var lastActivity: String = ""
        @Volatile internal var lastActivityPkg: String = ""

        val isConnected: Boolean get() = _instance.value != null

        /** Включена ли наша служба в системных настройках (может быть включена, но ещё не подключена). */
        fun isEnabledInSettings(context: Context): Boolean {
            val raw = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            } catch (_: Exception) { null }
            return AccessibilityList.contains(raw, AccessibilityList.OUR_SERVICE)
        }

        /** Экран настроек специальных возможностей. */
        fun openSettingsIntent(): Intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
