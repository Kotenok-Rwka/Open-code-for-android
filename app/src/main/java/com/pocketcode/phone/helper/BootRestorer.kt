// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pocketcode.SettingsStore
import com.pocketcode.phone.BootRestore
import com.pocketcode.phone.RecoveryInputs
import com.pocketcode.phone.RecoveryPolicy
import com.pocketcode.phone.RestoreOutcome
import com.pocketcode.phone.StartOutcome
import com.pocketcode.phone.adb.AndroidWirelessAdb
import com.pocketcode.phone.adb.DeveloperSettings
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Автозапуск помощника после перезагрузки: если пользователь включил «Запускать помощника после перезагрузки», то при необходимости
 * включает беспроводную отладку (нужно право WRITE_SECURE_SETTINGS) и запускает помощника тем способом, которым он был запущен
 * в прошлый раз (root или беспроводная отладка). Итог записывается в настройки, чтобы показать его в интерфейсе.
 */
internal class BootRestorer(context: Context) {
    private val app = context.applicationContext

    /** null — ничего делать не нужно (функция выключена или помощника ещё ни разу не запускали). */
    suspend fun run(): RestoreOutcome? {
        val store = SettingsStore(app)
        val mode = store.loadPhoneStartMode()
        val holds = app.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        val result = when (BootRestore.gate(Build.VERSION.SDK_INT, store.loadPhoneRestore(), mode, holds)) {
            BootRestore.Gate.Skip -> return null
            BootRestore.Gate.PermissionMissing -> RestoreOutcome.PermissionMissing
            BootRestore.Gate.Act -> if (mode == BootRestore.MODE_ROOT) viaRoot() else viaWireless()
        }
        store.savePhoneRestoreResult(result.name)
        return result
    }

    private suspend fun viaRoot(): RestoreOutcome {
        val o = withTimeoutOrNull(BOOT_BUDGET_MS) { HelperHub.get(app).startViaRoot() }
        return if (o is StartOutcome.Started) RestoreOutcome.Restored else RestoreOutcome.RootFailed
    }

    private suspend fun viaWireless(): RestoreOutcome {
        val cr = app.contentResolver
        // Запись отклонена или значение тут же откатилось: этот телефон так не умеет
        if (!DeveloperSettings.turnOnWirelessDebugging(cr)) return RestoreOutcome.SettingUnavailable
        val hub = HelperHub.get(app)
        val adb = AndroidWirelessAdb(app)
        hub.attachAdb(adb)
        var last: StartOutcome? = null
        val outcome = withTimeoutOrNull(BOOT_BUDGET_MS) {
            RecoveryPolicy.recover(
                inputs = {
                    RecoveryInputs(
                        paired = adb.isPaired(),
                        // вне доверенной сети Wi-Fi система возвращает переключатель в «выключено»: читаем на каждом круге
                        wirelessDebugging = DeveloperSettings.wirelessDebugging(cr),
                        connected = hub.state.value.connected,
                        last = last,
                    )
                },
                attempt = { last = hub.startViaWirelessDebugging() },
            )
        }
        return if (outcome?.recovered == true) RestoreOutcome.Restored else RestoreOutcome.NotRecovered
    }

    companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        /** После goAsync у приёмника около минуты: оставляем запас. */
        const val BOOT_BUDGET_MS = 50_000L
    }
}
