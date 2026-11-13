// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import java.lang.reflect.Method

/**
 * Не даёт виртуальному экрану агента заснуть. Экран, созданный с OWN_DISPLAY_GROUP (доверенные экраны Android 13+),
 * образует свою группу со своим таймером сна; внедрённый ввод не считается действием пользователя, и по таймеру
 * группа засыпает: кадры не рисуются, скриншот не меняется, нажатия не доходят. Пока экран есть, каждые [PERIOD_MS]
 * сообщаем системе о «действии пользователя» для него; экран убрали — перестаём. Если [poke] не работает
 * (в системе нет метода по экрану), больше не пытаемся — экран всё равно рабочий.
 */
internal class DisplayAwake(
    /** Сообщить о действии пользователя для экрана; false — не получилось. */
    private val poke: (Int) -> Boolean,
    /** Выполнить сразу и затем каждые [PERIOD_MS]; возвращает способ остановить. */
    private val repeat: (Runnable) -> () -> Unit,
) {
    private val running = HashMap<Int, () -> Unit>()
    @Volatile private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = repeat(Runnable { if (!poke(displayId)) giveUp() })
        synchronized(running) { running.put(displayId, cancel) }?.invoke()
        // если первый же вызов не удался, giveUp мог сработать раньше регистрации: останавливаем ещё раз
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) { running.values.toList().also { running.clear() } }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        // PowerManager.USER_ACTIVITY_EVENT_TOUCH
        const val EVENT_TOUCH = 2
        // Display.DEFAULT_DISPLAY_GROUP (скрытая константа)
        private const val DEFAULT_DISPLAY_GROUP = 0

        /**
         * Нужно ли будить этот экран: только если он действительно в своей группе. Сообщение в основную группу
         * не дало бы основному экрану пользователя погаснуть. [groupId] не прочитан (null) — решаем по флагу [ownGroupFlag].
         */
        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            flags and ownGroupFlag != 0 && groupId != DEFAULT_DISPLAY_GROUP

        /**
         * Перегрузка IPowerManager `userActivity(int displayId, long time, int event, int flags)` (Android 12+);
         * есть только старая без номера экрана — null (она бы будила основной экран, не используем).
         */
        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
        }

        private val PER_DISPLAY = arrayOf<Class<*>>(
            Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        )
    }
}
