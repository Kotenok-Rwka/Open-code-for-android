package com.pocketcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Пакеты общего буфера обмена между устройствами (kind = "clipboard"). */
object ClipboardSync {
    const val KIND = "clipboard"

    fun buildPacket(text: String, device: String, seq: Long): String = buildJsonObject {
        put("v", SyncEngine.VERSION)
        put("type", "event")
        put("seq", seq)
        put("device", device)
        put("project", "")
        put("ts", System.currentTimeMillis())
        put("kind", KIND)
        put("data", text)
    }.toString()

    /** Достаёт текст из пакета буфера; чужой или битый пакет → null. */
    fun parsePacket(json: String): String? = runCatching {
        val obj = Json.parseToJsonElement(json).jsonObject
        if (obj["kind"]?.jsonPrimitive?.content != KIND) return null
        obj["data"]?.jsonPrimitive?.content
    }.getOrNull()
}

/** Устройство считается онлайн, если слышно в последние 120 секунд и время не из будущего. */
fun deviceOnline(lastSeenMs: Long, nowMs: Long): Boolean =
    nowMs - lastSeenMs in 0..120_000
