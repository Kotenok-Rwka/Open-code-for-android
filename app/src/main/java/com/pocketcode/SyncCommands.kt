package com.pocketcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Пакеты управления и прогресса для синхронизации (телефон → ПК и обратно). */
object SyncCommands {
    /** Пакет «стоп агенту»: data = id сессии или "*" для всех. */
    fun buildStop(device: String, seq: Long, session: String = "*"): String = packet(device, seq, "stop", session)

    /** Пакет передачи задачи на другое устройство. */
    fun buildHandoff(device: String, seq: Long, project: String, task: String): String =
        packet(device, seq, "handoff", "$project|$task")

    /** Пакет общей задачи из списка. */
    fun buildTask(device: String, seq: Long, text: String, done: Boolean = false): String =
        packet(device, seq, "task", (if (done) "x " else "") + text)

    private fun packet(device: String, seq: Long, kind: String, data: String): String = buildJsonObject {
        put("v", SyncEngine.VERSION)
        put("type", "event")
        put("seq", seq)
        put("device", device)
        put("project", "")
        put("ts", System.currentTimeMillis())
        put("kind", kind)
        put("data", data)
    }.toString()

    fun kind(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["kind"]?.jsonPrimitive?.content
    }.getOrNull()

    fun data(json: String): String? = runCatching {
        Json.parseToJsonElement(json).jsonObject["data"]?.jsonPrimitive?.content
    }.getOrNull()
}

/** Прогресс задачи по чек-листу: строка вида "[x] готово" считается выполненной. */
fun progressPercent(checklist: List<String>): Int {
    if (checklist.isEmpty()) return 0
    val done = checklist.count { it.trim().startsWith("[x]") || it.trim().startsWith("x ") }
    return Math.round(100.0 * done / checklist.size).toInt()
}

/** Оценка задачи: грубые шаги и токены по длине текста. */
fun estimateTask(text: String): Pair<Int, Int> {
    val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
    val steps = (words / 12 + 1).coerceIn(1, 16)
    return steps to 800 + steps * 1500
}

/** Политика «только по Wi-Fi»: запрет на мобильном интернете. */
fun syncAllowedNow(wifiOnly: Boolean, enabled: Boolean, onMetered: Boolean): Boolean =
    enabled && !(wifiOnly && onMetered)
