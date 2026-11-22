package com.pocketcode.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import com.pocketcode.PocketCodeApp

/**
 * Foreground-сервис: пока агент работает, Android не убивает процесс,
 * а wake lock не даёт процессору уснуть при выключенном экране.
 * Сама работа идёт в ChatController, сервис только держит приложение живым.
 */
class AgentService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Не вызываем startForeground здесь с глушением ошибки: на Android 11 это даёт
        // RemoteServiceException, если уведомление ещё не готово. Фактический вызов — в onStartCommand.
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketcode:agent").apply {
            setReferenceCounted(false)
            // Без таймаута: длинная генерация/ffmpeg не должна просыпаться посреди работы.
            // Освобождаем строго в onDestroy, плюс перестраховка-пинг в heartbeat.
            acquire()
        }
        // На совсем слабых прошивках PARTIAL_WAKE_LOCK не хватает: опция держит экран включённым.
        @Suppress("DEPRECATION")
        if (runCatching { com.pocketcode.SettingsStore(this).rawGet("keepScreenOn") }.getOrNull() == "1") {
            wakeLock?.release()
            wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "pocketcode:screen").apply {
                setReferenceCounted(false); acquire()
            }
        }
        // Не даём Wi-Fi уснуть при выключенном экране (частая причина обрывов связи в долгих задачах)
        runCatching {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "pocketcode:wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    /** Периодически обновляем уведомление и подстраховываем wakelock:system не считает сервис «зависшим». */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!goForegroundSafe()) return
            wakeLock?.let { if (!it.isHeld) it.acquire() }
            handler.removeCallbacks(this)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private fun goForegroundSafe(): Boolean = runCatching { goForeground() }.isSuccess

    /** Android требует вызвать startForeground после каждого startForegroundService, даже если сервис тут же остановится. */
    private fun goForeground() {
        val mins = (System.currentTimeMillis() / 60000) % 60
        val notification = Notifier.working(this, "Агент работает · $mins мин")
        // startForeground — жёсткое требование Android 8+: если он не вызовется,
        // система убивает процесс с RemoteServiceException. Негарантированный вызов здесь недопустим.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            startForeground(Notifier.FOREGROUND_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == Notifier.ACTION_STOP) {
            (application as PocketCodeApp).tabs.forEach { it.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
        // STICKY: система восстановит сервис, если убьёт при нехватке памяти.
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(heartbeat)
        wakeLock?.let { if (it.isHeld) it.release() }
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        super.onDestroy()
    }

    private companion object {
        const val HEARTBEAT_MS = 45_000L
    }
}
