// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.os.Build

/** Честная диагностика, почему беспроводная отладка может не запуститься на этом телефоне. */
object AdbDiagnostics {
    data class Report(val ok: Boolean, val lines: List<String>)

    fun report(paired: Boolean, portsFound: Int, tlsError: String? = null): Report {
        val lines = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < 30) lines += "Беспроводная отладка доступна с Android 11 (API 30)."
        if (Build.VERSION.SDK_INT in 30..33) lines += "Android 11–13: для экспорта TLS-ключа используется встроенный Conscrypt; если он отсутствует, сопряжение невозможно."
        if (!paired) lines += "Нет сохранённого сопряжения. Открой «Параметры разработчика → Беспроводная отладка → Сопряжение по коду» и введи свежий код."
        if (paired && portsFound == 0) lines += "Служба adbd не найдена через mDNS. Проверь, что беспроводная отладка включена, телефон в той же сети, и попробуй заново."
        if (tlsError != null) lines += "TLS: $tlsError"
        if (lines.isEmpty()) lines += "ADB готов к подключению."
        return Report(lines.size == 1 && lines[0].startsWith("ADB готов"), lines)
    }

    fun reason(e: Throwable): String = when (e) {
        is AdbPairingException -> when (e.reason) {
            AdbPairingException.Reason.WRONG_CODE -> "Неверный код сопряжения — открой новое окно и введи свежий код."
            AdbPairingException.Reason.TLS_FAILED -> "TLS-рукопожатие не удалось. На Android 11–13 проверь встроенный Conscrypt (он нужен для экспорта ключа)."
            AdbPairingException.Reason.NOT_LOCAL -> "ADB виден только на самом телефоне: адрес должен быть локальным (127.0.0.1)."
            AdbPairingException.Reason.CONNECT_FAILED -> "Не удалось подключиться к порту adbd. Проверь беспроводную отладку и сеть."
            AdbPairingException.Reason.PROTOCOL -> "Протокол сопряжения не совпал — повтори сопряжение с обеих сторон."
        }
        is KeyingMaterialUnavailableException -> "Платформа не даёт экспортировать TLS-ключ. Для Android 11–13 нужен встроенный Conscrypt; без него ADB-сопряжение невозможно."
        else -> e.message ?: e.javaClass.simpleName
    }
}
