// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pocketcode.MainActivity

/** Что сейчас показывает уведомление сопряжения. */
internal sealed interface PairingNotice {
    /** Уведомление с полем ввода кода; [port] — найденный порт окна сопряжения (если уже найден), [error] — почему прошлая попытка не удалась. */
    data class EnterCode(val port: Int?, val error: String? = null) : PairingNotice
    object Working : PairingNotice
    object Done : PairingNotice
    data class Problem(val text: String) : PairingNotice
}

/**
 * Уведомление сопряжения: высокая важность, со звуком и вибрацией, всплывает поверх окна настроек. Окно «Сопряжение по коду» в системных настройках
 * закрывается, когда пользователь уходит из него, поэтому код вводится в поле ввода уведомления (RemoteInput), не покидая окна.
 * Введённый код попадает в [PairingCodeReceiver]. Свой канал и свой идентификатор, общий [com.pocketcode.service.Notifier] не затрагивается.
 */
internal object PairingNotifier {
    const val KEY_CODE = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    private const val CHANNEL_ID = "phone_pairing"
    private const val ID = 4401
    private const val TAG = "PocketAdb"
    /** Уведомление о результате само исчезает через это время. */
    private const val DONE_TIMEOUT_MS = 8_000L
    /** Уведомление с полем ввода висит не дольше времени ожидания кода, чтобы не остаться навсегда, если приложение убили. */
    private const val INPUT_TIMEOUT_MS = 10 * 60_000L

    /** Показываются ли уведомления этому приложению (на Android 13+ нужно разрешение POST_NOTIFICATIONS). */
    fun enabled(context: Context): Boolean =
        runCatching { context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }.getOrDefault(true)

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Сопряжение с отладкой по Wi-Fi", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setShowBadge(false)
            },
        )
    }

    fun show(context: Context, notice: PairingNotice) {
        val nm = context.getSystemService(NotificationManager::class.java)
        runCatching {
            ensureChannel(context, nm)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open)
            when (notice) {
                is PairingNotice.EnterCode -> {
                    val input = RemoteInput.Builder(KEY_CODE).setLabel("Код сопряжения (6 цифр)").setAllowFreeFormInput(true).build()
                    // RemoteInput кладёт результат в Intent, поэтому PendingIntent обязан быть изменяемым (Intent явный, как требует Android 14)
                    val reply = PendingIntent.getBroadcast(
                        context, 1,
                        Intent(context, PairingCodeReceiver::class.java).apply { notice.port?.let { putExtra(EXTRA_PORT, it) } },
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    val action = Notification.Action.Builder(null, "Ввести код", reply)
                        .addRemoteInput(input)
                        .setAllowGeneratedReplies(false)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) action.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                    // На заблокированном экране код можно ввести сразу, без разблокировки
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) action.setAuthenticationRequired(false)
                    val text = notice.error
                        ?: "В окне «Сопряжение по коду» посмотрите шестизначный код и введите его здесь, не закрывая окно."
                    b.setContentTitle("Введите код сопряжения")
                        .setContentText(text)
                        .setStyle(Notification.BigTextStyle().bigText(text))
                        .setOngoing(true)
                        // Каждый показ должен звучать и всплывать, в том числе после ошибки ввода
                        .setOnlyAlertOnce(false)
                        .setTimeoutAfter(INPUT_TIMEOUT_MS)
                        .addAction(action.build())
                    if (notice.port != null) b.setSubText("Окно сопряжения найдено")
                }
                PairingNotice.Working -> b.setContentTitle("Сопряжение…").setOngoing(true).setOnlyAlertOnce(true)
                    .setProgress(0, 0, true)
                PairingNotice.Done -> b.setContentTitle("Сопряжение выполнено")
                    .setContentText("Теперь приложение может подключаться к беспроводной отладке этого телефона.")
                    .setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(DONE_TIMEOUT_MS)
                is PairingNotice.Problem -> b.setContentTitle("Сопряжение не удалось")
                    .setContentText(notice.text)
                    .setStyle(Notification.BigTextStyle().bigText(notice.text))
                    .setAutoCancel(true)
            }
            nm.notify(ID, b.build())
        }.onFailure { Log.w(TAG, "pairing notification not posted", it) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
    }
}
