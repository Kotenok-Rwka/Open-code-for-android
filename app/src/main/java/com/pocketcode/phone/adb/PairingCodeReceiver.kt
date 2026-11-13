// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Приёмник кода из поля ввода уведомления сопряжения. Система может заморозить фоновое приложение, как только широковещательный вызов
 * завершится, и сопряжение остановится на полпути, поэтому вызов не завершается, пока сопряжение не закончено (но не дольше [HOLD_MS]).
 */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        val job = NotificationPairingFlow.handleReply(context, code, port) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                withTimeoutOrNull(HOLD_MS) { job.join() }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** Широковещательный вызов без ответа дольше 10 секунд считается зависшим; оставляем запас. */
        const val HOLD_MS = 8_000L
    }
}
