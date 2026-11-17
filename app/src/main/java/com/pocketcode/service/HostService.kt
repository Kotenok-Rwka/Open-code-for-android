package com.pocketcode.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocketcode.MainActivity
import com.pocketcode.PocketCodeApp

/**
 * Foreground-служба хостинга: пока в приложении включены проекты «фоном», Android не убивает процесс,
 * а wake lock не даёт процессору уснуть. Сами проекты запускает HostManager.
 */
class HostService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketcode:host").apply {
            setReferenceCounted(false)
            acquire()
        }
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketcode:hostwifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as PocketCodeApp).host.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(this, lastText)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        (application as PocketCodeApp).host.ensureStarted()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.pocketcode.action.HOST_STOP"
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL = "host_work"

        @Volatile
        private var lastText = "Хостинг проектов"

        private fun notification(ctx: Context, text: String): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Хостинг проектов", NotificationManager.IMPORTANCE_LOW))
            }
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val stop = PendingIntent.getService(
                ctx, 2,
                Intent(ctx, HostService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("PocketCode: хостинг")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Стоп", stop)
                .build()
        }

        /** Запускает службу. Может не получиться, если приложение в фоне и система запретила старт. */
        fun start(ctx: Context, text: String) {
            lastText = text
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, HostService::class.java)) }
        }

        fun update(ctx: Context, text: String) {
            if (text == lastText) return
            lastText = text
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(ctx, text)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, HostService::class.java)) }
        }
    }
}
