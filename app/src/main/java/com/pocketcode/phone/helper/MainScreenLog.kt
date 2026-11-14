// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

/**
 * Журнал задач, «всплывших» наверх на основном экране (экран 0). Помощник кормит его списком задач при каждой проверке ([scan]).
 * Журнал хранит только факты и не решает, вызвано ли это действием агента (это решает приложение, см. MainScreenSpill):
 * новая задача на вершине основного экрана — запись; та же задача, но сменилась верхняя страница — тоже запись ([Arrival.sameTask]).
 * В каждой записи есть экран, на котором задачу видели в прошлый раз ([Arrival.from], [NEW] — раньше не видели),
 * по нему узнаём задачи, перенесённые с другого экрана. Чистая логика, время передаёт вызывающий.
 */
internal class MainScreenLog(private val keepMs: Long = KEEP_MS) {
    /** Задача: [topActivity] — полное имя верхней страницы, [standard] — страница обычного приложения (не рабочий стол, не «недавние», не ассистент). */
    data class Task(
        val taskId: Int,
        val displayId: Int,
        val packageName: String?,
        val userId: Int,
        val topActivity: String?,
        val standard: Boolean = true,
    )

    data class Arrival(
        val taskId: Int,
        val packageName: String?,
        val userId: Int,
        val at: Long,
        val from: Int,
        val sameTask: Boolean,
        val standard: Boolean,
        val topActivity: String? = null,
    )

    private var top: Task? = null
    private var displays: Map<Int, Int> = emptyMap()
    private val arrivals = ArrayDeque<Arrival>()

    /**
     * Один проход по списку задач [tasks] (все экраны, по давности использования, как в системном getTasks).
     * При [record] = false запоминаем только текущий вид как базу, без записей о всплывших задачах.
     */
    fun scan(tasks: List<Task>, now: Long, record: Boolean) {
        val mainTop = tasks.firstOrNull { it.displayId == MAIN }
        val last = top
        if (record && mainTop != null && last != null) {
            when {
                mainTop.taskId != last.taskId -> add(mainTop, now, displays[mainTop.taskId] ?: NEW, sameTask = false)
                mainTop.topActivity != last.topActivity -> add(mainTop, now, MAIN, sameTask = true)
            }
        }
        top = mainTop
        displays = tasks.associate { it.taskId to it.displayId }
        while (arrivals.isNotEmpty() && now - arrivals.first().at > keepMs) arrivals.removeFirst()
    }

    private fun add(t: Task, now: Long, from: Int, sameTask: Boolean) {
        arrivals.addLast(Arrival(t.taskId, t.packageName, t.userId, now, from, sameTask, t.standard, t.topActivity))
    }

    /** Верхняя задача основного экрана сейчас; -1, если ещё не смотрели или задач нет. */
    val topTaskId: Int get() = top?.taskId ?: -1

    /** Записи начиная с [since] (включительно), по порядку. */
    fun since(since: Long): List<Arrival> = arrivals.filter { it.at >= since }

    /** Экран, на котором задачу видели в последний раз; -1, если её уже нет. */
    fun displayOf(taskId: Int): Int = displays[taskId] ?: -1

    companion object {
        const val MAIN = 0
        const val NEW = -1
        const val KEEP_MS = 60_000L
    }
}
