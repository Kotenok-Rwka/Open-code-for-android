package com.pocketcode.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pocketcode.ui.I18n

/** Уведомление «ответ готов», когда окно ассистента закрыли до конца поиска. */
object AssistNotifier {
    private const val CHANNEL = "assist_result"
    private const val ID = 31

    fun result(ctx: Context, query: String, answer: String) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, I18n.t("Ответы ассистента"), NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getBroadcast(
                ctx, ID, Intent(ctx, AssistReopenReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val body = AssistText.firstSentence(answer, 280).ifEmpty { I18n.t("Ответ готов") }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(query.take(80))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(ID, n)
        }
    }

    fun cancel(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
