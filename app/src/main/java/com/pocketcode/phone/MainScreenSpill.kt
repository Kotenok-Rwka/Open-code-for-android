// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

/**
 * Решает, привело ли действие агента к тому, что приложение открылось на основном экране пользователя. Если приложение
 * открывается не со своей страницы на фоновом экране, а из фона (служба, переход из уведомления, системный выбор), система
 * по умолчанию кладёт его на основной экран; на фоновом экране изменений не видно, и модель думает, что нажатие не прошло.
 * Узнав такой случай, задачу переносят обратно на фоновый экран.
 *
 * Смотрим только на задачи, всплывшие наверх основного экрана вскоре после начала действия (их записывает помощник, см. MainScreenLog).
 * Не считаются: всплывшие вне этого окна времени; рабочий стол, «недавние» и подобное; сам PocketCode и приложение рабочего стола
 * ([excluded]); перенесённые с другого экрана; уже не находящиеся на основном экране; случаи, когда пользователь за это время
 * касался основного экрана или сначала вернулся на рабочий стол и открыл приложение оттуда (считаем, что открыл он сам);
 * возврат наверх той задачи, что была сверху до действия (она показалась после того, как другую убрали).
 * Если сменилась страница в задаче, которая была сверху до действия, значит открыли приложение, которым пользователь сейчас
 * пользуется, прямо у него на глазах: не трогаем ([InUse]). Если пользователь уже нажимает в окне всплывшей задачи, он ею пользуется: тоже не переносим.
 * Чистая логика.
 */
object MainScreenSpill {
    const val MAIN = 0
    const val NEW = -1

    /** Задача, всплывшая наверх основного экрана; [from] — экран, где её видели в прошлый раз ([NEW] — не видели), [now] — экран сейчас (-1 — её нет). */
    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val now: Int,
        val topActivity: String? = null,
    )

    /** Касание пользователя на основном экране: момент [at] и пакет окна, в которое попало касание (null — не определён). */
    data class Tap(val at: Long, val packageName: String?)

    sealed interface Verdict { val arrival: Arrival }

    /** Действие открыло приложение на основном экране: вернуть задачу на фоновый экран. */
    data class PullBack(override val arrival: Arrival) : Verdict

    /** Открылось приложение, которым пользователь сейчас пользуется на основном экране: не трогаем, сообщаем модели. */
    data class InUse(override val arrival: Arrival) : Verdict

    /**
     * [since] — момент начала действия; всплывшее позже [windowMs] к этому действию не относится; [topBefore] — верхняя задача
     * основного экрана перед действием; [userTaps] — касания пользователя на основном экране. Одна задача даёт одно решение
     * (по последней записи), результат упорядочен по времени.
     */
    fun decide(
        arrivals: List<Arrival>,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homePackages: Set<String> = emptySet(),
    ): List<Verdict> {
        val ordered = arrivals.sortedBy { it.at }
        val homeReturns = ordered.filter { it.packageName in homePackages }.map { it.at }
        val out = LinkedHashMap<Int, Verdict>()
        for (a in ordered) {
            val v = verdict(a, since, windowMs, topBefore, excluded, userTaps, homeReturns) ?: continue
            out.remove(a.taskId)
            out[a.taskId] = v
        }
        return out.values.toList()
    }

    private fun verdict(
        a: Arrival,
        since: Long,
        windowMs: Long,
        topBefore: Int,
        excluded: Set<String>,
        userTaps: List<Tap>,
        homeReturns: List<Long>,
    ): Verdict? {
        if (a.at < since || a.at > since + windowMs) return null
        val pkg = a.packageName ?: return null
        if (!a.standard || pkg in excluded) return null
        if (a.from != NEW && a.from != MAIN) return null
        if (a.now != MAIN) return null
        // пользователь сначала вернулся на рабочий стол, а потом открыл приложение оттуда или из шторки уведомлений
        if (homeReturns.any { it in since..a.at }) return null
        if (userTaps.any { it.at in since..a.at }) return null
        // пользователь уже нажимает в окне этого приложения: оно ему нужно, не переносим
        if (userTaps.any { it.at >= a.at && it.packageName == pkg }) return InUse(a)
        if (a.taskId == topBefore) return if (a.sameTask) InUse(a) else null
        return PullBack(a)
    }
}
