// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Часть помощника, которая следит за основным экраном: какие задачи всплывали наверх в короткое время вокруг действия агента
 * ([MainScreenLog]). Если приложение открывается не со своей страницы на экране агента, а из фона (служба, переход из уведомления,
 * системный выбор), система кладёт его на основной экран; приложение по этим записям узнаёт такой случай и возвращает задачу назад.
 *
 * Смотрим при каждом изменении задач: регистрируем слушателя стека задач (ITaskStackListener; у shell есть право управлять задачами).
 * Слушатель служит лишь сигналом «что-то изменилось», аргументы не разбираем (порядок методов различается между версиями) и
 * просто перечитываем список задач. Если зарегистрироваться не вышло, пока идёт слежение, опрашиваем каждые [POLL_MS].
 * При запросе ([arrivals]) тоже смотрим на месте, чтобы не пропустить последнее состояние. Вне слежения список задач не читаем.
 */
internal class MainScreenWatch(
    private val taskManager: () -> Any,
    /** Все задачи на всех экранах сейчас, по давности использования. */
    private val tasks: () -> List<MainScreenLog.Task>,
) {
    private val log = MainScreenLog()
    private val thread = HandlerThread("pocketcode-main-watch").apply { start() }
    private val handler = Handler(thread.looper)
    /** До какого момента следим (uptimeMillis). */
    @Volatile private var until = 0L
    /** true — слушатель стека задач зарегистрирован, false — не вышло, null — ещё не пробовали. */
    @Volatile private var listening: Boolean? = null
    private val queued = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val signal = StackSignal()

    /** Следить в течение [windowMs] с этого момента; возвращает верхнюю задачу основного экрана (нет — -1). */
    fun watch(windowMs: Int): Int {
        val now = SystemClock.uptimeMillis()
        val top = synchronized(log) {
            scanLocked(now, record = now < until)
            until = maxOf(until, now + windowMs.coerceIn(0, MAX_WINDOW_MS))
            log.topTaskId
        }
        if (listening == null) listening = register()
        if (listening == false && polling.compareAndSet(false, true)) handler.post(::poll)
        return top
    }

    /**
     * Задачи, всплывшие на основном экране после [since], по порядку; в каждой записи ещё и экран, где задача сейчас
     * (нет — -1), и признак, получены ли записи слушателем или опросом. Поля — в [toBundle].
     */
    fun arrivals(since: Long): Bundle = synchronized(log) {
        val now = SystemClock.uptimeMillis()
        if (now < until + GRACE_MS) scanLocked(now, record = true)
        toBundle(log.since(since), log::displayOf, listening == true)
    }

    fun close() {
        if (listening == true) runCatching {
            taskManager().javaClass.methods.first { it.name == "unregisterTaskStackListener" }.invoke(taskManager(), listener())
        }
        listening = null
        thread.quitSafely()
    }

    private fun scanLocked(now: Long, record: Boolean) {
        val list = try { tasks() } catch (t: Throwable) { Log.w(TAG, "cannot list tasks", t); return }
        log.scan(list, now, record)
    }

    private fun scan() {
        val now = SystemClock.uptimeMillis()
        if (now >= until) return
        synchronized(log) { scanLocked(now, record = true) }
    }

    /** Стек задач изменился: склеиваем серию изменений в один просмотр. */
    private fun onSignal() {
        if (SystemClock.uptimeMillis() >= until) return
        if (queued.compareAndSet(false, true)) handler.postDelayed({ queued.set(false); scan() }, COALESCE_MS)
    }

    private fun poll() {
        if (SystemClock.uptimeMillis() >= until) { polling.set(false); return }
        scan()
        handler.postDelayed(::poll, POLL_MS)
    }

    @SuppressLint("PrivateApi")
    private fun listener(): Any =
        Class.forName("android.app.ITaskStackListener\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, signal)!!

    private fun register(): Boolean = try {
        val am = taskManager()
        am.javaClass.methods.first { it.name == "registerTaskStackListener" && it.parameterTypes.size == 1 }.invoke(am, listener())
        Log.i(TAG, "task stack listener registered")
        true
    } catch (t: Throwable) {
        Log.w(TAG, "task stack listener unavailable, polling instead", t)
        false
    }

    /**
     * Binder, в который система вызывает методы ITaskStackListener. Нам важен сам факт вызова, аргументы не читаем;
     * вызовы односторонние, отвечать не нужно. Дескриптор — имя интерфейса, по нему система оборачивает объект.
     */
    private inner class StackSignal : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION) {
                onSignal()
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    companion object {
        private const val TAG = "PocketCodeShell"
        private const val DESCRIPTOR = "android.app.ITaskStackListener"
        const val POLL_MS = 100L
        private const val COALESCE_MS = 30L
        /** После конца окна по запросу всё равно смотрим на месте: приложению нужно время, чтобы вынести решение. */
        private const val GRACE_MS = 2_000L
        private const val MAX_WINDOW_MS = 30_000

        const val KEY_TASK = "task"
        const val KEY_PACKAGE = "package"
        const val KEY_USER = "user"
        const val KEY_AT = "at"
        const val KEY_FROM = "from"
        const val KEY_SAME_TASK = "sameTask"
        const val KEY_STANDARD = "standard"
        const val KEY_ACTIVITY = "activity"
        const val KEY_NOW = "now"
        const val KEY_LISTENING = "listening"

        /** Записи в виде параллельных массивов (в Bundle нет списков объектов); [now] — экран, где задача сейчас. */
        fun toBundle(list: List<MainScreenLog.Arrival>, now: (Int) -> Int, listening: Boolean): Bundle = Bundle().apply {
            putIntArray(KEY_TASK, list.map { it.taskId }.toIntArray())
            putStringArray(KEY_PACKAGE, list.map { it.packageName }.toTypedArray())
            putIntArray(KEY_USER, list.map { it.userId }.toIntArray())
            putLongArray(KEY_AT, list.map { it.at }.toLongArray())
            putIntArray(KEY_FROM, list.map { it.from }.toIntArray())
            putBooleanArray(KEY_SAME_TASK, list.map { it.sameTask }.toBooleanArray())
            putBooleanArray(KEY_STANDARD, list.map { it.standard }.toBooleanArray())
            putStringArray(KEY_ACTIVITY, list.map { it.topActivity }.toTypedArray())
            putIntArray(KEY_NOW, list.map { now(it.taskId) }.toIntArray())
            putBoolean(KEY_LISTENING, listening)
        }
    }
}
