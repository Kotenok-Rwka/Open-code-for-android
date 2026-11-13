// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pocketcode.phone.AdbFailure
import com.pocketcode.phone.AdbResult
import com.pocketcode.phone.PairingFlow
import com.pocketcode.phone.WirelessAdb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сопряжение через уведомление: показывает уведомление с полем ввода, открывает настройки беспроводной отладки,
 * ищет окно «Сопряжение по коду» через mDNS и ждёт код из уведомления (его принимает [PairingCodeReceiver]).
 * Неверный код или закрытое окно не заканчивают сопряжение: уведомление остаётся для повторного ввода, пока не истекло время ожидания.
 * [PairingFlow.begin] вызывает onDone ровно один раз; [cancel] убирает уведомление и onDone не вызывает.
 */
class NotificationPairingFlow(context: Context, private val adb: WirelessAdb) : PairingFlow {
    private val app = context.applicationContext

    override fun begin(onDone: (AdbResult) -> Unit) {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            onDone(AdbResult.Failed(AdbFailure.UNSUPPORTED_OS, "Беспроводная отладка есть только в Android 11 и новее."))
            return
        }
        if (!PairingNotifier.enabled(app)) {
            onDone(AdbResult.Failed(AdbFailure.OTHER, "Уведомления выключены: разрешите их приложению, чтобы ввести код сопряжения."))
            return
        }
        val session = Session(app, adb, onDone)
        val old = synchronized(lockObj) { active.also { active = session } }
        old?.finish(AdbResult.Failed(AdbFailure.OTHER, "Начато новое сопряжение."))
        session.start()
    }

    override fun cancel() {
        val s = synchronized(lockObj) { active.also { active = null } } ?: return
        s.cancel()
    }

    /** Одна попытка сопряжения: живёт, пока не будет итога, отмены или таймаута. */
    private class Session(
        private val app: Context,
        private val adb: WirelessAdb,
        private val onDone: (AdbResult) -> Unit,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val finished = AtomicBoolean(false)
        private val busy = AtomicBoolean(false)
        @Volatile private var port: Int? = null
        private var discovery: Job? = null

        fun start() {
            PairingNotifier.show(app, PairingNotice.EnterCode(null))
            openSettings()
            startDiscovery()
            scope.launch {
                delay(WINDOW_MS)
                finish(AdbResult.Failed(AdbFailure.TIMEOUT, "Время ожидания кода сопряжения истекло."))
            }
        }

        /** Ищет окно сопряжения; найдя, обновляет уведомление (оно снова всплывает поверх окна настроек). */
        @Synchronized
        private fun startDiscovery() {
            discovery?.cancel()
            discovery = scope.launch {
                val found = try {
                    NsdAdbServiceFinder(app).findPorts(AdbServiceTypes.PAIRING, WINDOW_MS).firstOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "pairing port discovery failed", e)
                    null
                }
                if (found != null && !finished.get() && !busy.get()) {
                    port = found
                    PairingNotifier.show(app, PairingNotice.EnterCode(found))
                }
            }
        }

        private fun openSettings() {
            for (intent in DeveloperSettings.wirelessDebuggingPage()) {
                try {
                    app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (e: ActivityNotFoundException) {
                    // пробуем следующий вариант
                } catch (e: SecurityException) {
                    return
                }
            }
        }

        /** Код из уведомления. Возвращает задачу сопряжения (чтобы приёмник дождался её) или null, если код не принят. */
        fun submit(raw: String, notificationPort: Int?): Job? {
            if (finished.get()) return null
            val code = PairingCode.normalize(raw)
            if (code == null) {
                PairingNotifier.show(app, PairingNotice.EnterCode(port, "Код состоит из 6 цифр. Введите его ещё раз."))
                return null
            }
            if (!busy.compareAndSet(false, true)) return null
            PairingNotifier.show(app, PairingNotice.Working)
            return scope.launch {
                try {
                    when (val r = adb.pair(code, notificationPort ?: port)) {
                        AdbResult.Ok -> finish(r)
                        is AdbResult.Failed -> when (r.kind) {
                            // Код неверный или окно закрыто: можно ввести снова
                            AdbFailure.REJECTED, AdbFailure.PORT_NOT_FOUND -> {
                                if (r.kind == AdbFailure.PORT_NOT_FOUND) {
                                    port = null
                                    startDiscovery()
                                }
                                busy.set(false)
                                if (!finished.get()) PairingNotifier.show(app, PairingNotice.EnterCode(port, r.reason))
                            }
                            else -> finish(r)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    finish(AdbResult.Failed(AdbFailure.OTHER, "Ошибка сопряжения: ${e.javaClass.simpleName}."))
                }
            }
        }

        fun finish(result: AdbResult) {
            if (!finished.compareAndSet(false, true)) return
            synchronized(lockObj) { if (active === this) active = null }
            scope.coroutineContext[Job]?.cancel()
            PairingNotifier.show(
                app,
                if (result is AdbResult.Failed) PairingNotice.Problem(result.reason) else PairingNotice.Done,
            )
            runCatching { onDone(result) }.onFailure { Log.w(TAG, "onDone failed", it) }
        }

        fun cancel() {
            if (!finished.compareAndSet(false, true)) return
            scope.coroutineContext[Job]?.cancel()
            PairingNotifier.cancel(app)
        }
    }

    internal companion object {
        private const val TAG = "PocketAdb"
        private const val MIN_SDK = 30
        /** Сколько ждём код: столько же, сколько Mika ищет окно сопряжения. */
        private const val WINDOW_MS = 10 * 60_000L
        private val lockObj = Any()
        @Volatile private var active: Session? = null

        /**
         * Код пришёл из уведомления. Если сопряжение ведёт живой [NotificationPairingFlow], код отдаётся ему;
         * иначе (процесс был перезапущен) выполняется одиночное сопряжение и показывается итог в уведомлении.
         */
        fun handleReply(context: Context, raw: String, port: Int?): Job? {
            val app = context.applicationContext
            val s = synchronized(lockObj) { active }
            if (s != null) return s.submit(raw, port)
            val code = PairingCode.normalize(raw)
            if (code == null) {
                PairingNotifier.show(app, PairingNotice.EnterCode(port, "Код состоит из 6 цифр. Введите его ещё раз."))
                return null
            }
            PairingNotifier.show(app, PairingNotice.Working)
            return CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                val r = try {
                    AndroidWirelessAdb(app).pair(code, port)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AdbResult.Failed(AdbFailure.OTHER, "Ошибка сопряжения: ${e.javaClass.simpleName}.")
                }
                PairingNotifier.show(
                    app,
                    if (r is AdbResult.Failed) PairingNotice.Problem(r.reason) else PairingNotice.Done,
                )
            }
        }
    }
}
