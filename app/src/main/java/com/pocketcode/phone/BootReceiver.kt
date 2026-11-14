// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketcode.phone.helper.BootRestorer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Сигнал о загрузке системы. Если автозапуск помощника выключен, ничего не делает (проверка внутри [BootRestorer.run]). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                runCatching { BootRestorer(app).run() }
            } finally {
                pending.finish()
            }
        }
    }
}
