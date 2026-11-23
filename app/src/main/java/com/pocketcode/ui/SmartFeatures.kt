package com.pocketcode.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketcode.SettingsStore

/**
 * Выключатели «умных» функций. Хранятся в тех же настройках приложения, но отдельно от общей формы настроек.
 * Изменения вступают в силу сразу; только карта проекта подхватывается при начале нового чата.
 */
class SmartFeatures(private val store: SettingsStore) {
    private fun flag(key: String, default: Boolean): Boolean = store.rawGet(key)?.let { it == "1" } ?: default
    private fun save(key: String, on: Boolean) = store.rawPut(key, if (on) "1" else "0")

    /** Предохранитель авто-режима: слишком много файловых действий подряд выключают его. */
    var breakerOn by mutableStateOf(flag("sf_breaker", true))
        private set
    var breakerLimit by mutableStateOf(store.rawGet("sf_breaker_n")?.toIntOrNull() ?: 20)
        private set

    /** Корзина: rm в командах агента переносит файлы, а не удаляет. */
    var trashOn by mutableStateOf(flag("sf_trash", false))
        private set

    /** Удаление в авто-режиме должны одобрить две нейросети. */
    var doubleDelete by mutableStateOf(flag("sf_double", false))
        private set
    var secondReviewer by mutableStateOf(store.rawGet("sf_reviewer2") ?: "")
        private set

    /** Показывать в чате, что проверка прошла и почему. */
    var verdictNotes by mutableStateOf(flag("sf_verdict", true))
        private set

    /** Не вывести в чат служебные подсказки авто-режима (предохранитель, проверки, роутинг). */
    var quietAuto by mutableStateOf(flag("sf_quietauto", false))
        private set
    fun changeQuietAuto(on: Boolean) { quietAuto = on; save("sf_quietauto", on) }

    /** Писать в чате, когда ответ отдан модели-помощнику. */
    var routeNotes by mutableStateOf(flag("sf_routenote", true))
        private set

    /** Карта проекта в подсказке агенту. */
    var memoryOn by mutableStateOf(flag("sf_memory", false))
        private set

    /** Не тратить токены на фоновые вызовы:helper-модель, карта проекта и сводки отключаются, основной ответ не меняется. */
    var leanCalls by mutableStateOf(flag("sf_leancalls", false))
        private set
    fun changeLeanCalls(v: Boolean) { leanCalls = v; save("sf_leancalls", v) }

    /** Длинный вывод инструментов режется с сохранением начала и конца. */
    var smartTrim by mutableStateOf(flag("sf_trim", true))
        private set

    /** Откат правок: перед записью файла агентом запоминается прежнее содержимое. */
    var undoOn by mutableStateOf(flag("sf_undo", true))
        private set

    /** Журнал авто-режима сохраняется в файл. */
    var logToFile by mutableStateOf(flag("sf_autolog", true))
        private set

    fun changeUndo(v: Boolean) { undoOn = v; save("sf_undo", v) }
    fun changeLogToFile(v: Boolean) { logToFile = v; save("sf_autolog", v) }

    fun changeBreaker(v: Boolean) { breakerOn = v; save("sf_breaker", v) }

    fun changeBreakerLimit(n: Int) {
        breakerLimit = n
        store.rawPut("sf_breaker_n", n.toString())
    }

    fun changeTrash(v: Boolean) { trashOn = v; save("sf_trash", v) }
    fun changeDoubleDelete(v: Boolean) { doubleDelete = v; save("sf_double", v) }

    fun changeSecondReviewer(id: String) {
        secondReviewer = id
        store.rawPut("sf_reviewer2", id)
    }

    fun changeVerdictNotes(v: Boolean) { verdictNotes = v; save("sf_verdict", v) }
    fun changeRouteNotes(v: Boolean) { routeNotes = v; save("sf_routenote", v) }
    fun changeMemory(v: Boolean) { memoryOn = v; save("sf_memory", v) }
    fun changeSmartTrim(v: Boolean) { smartTrim = v; save("sf_trim", v) }
}
