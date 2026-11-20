package com.pocketcode.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Чистая логика выбора беседы OpenCode: без Android, чтобы проверять юнит-тестами. */
object OcSessionPick {
    class Found(val id: String, val title: String)

    private val idRx = Regex("[A-Za-z0-9_\\-]{3,80}")
    private val missingRx = Regex("(?i)session[^\\n]{0,80}not found")

    /** Самая свежая беседа из вывода `opencode session list --format json -n 1`; null, если разобрать нельзя. */
    fun latest(raw: String): Found? {
        val text = raw.lines().filterNot { it.trim().startsWith("[код выхода") || it.trim().startsWith("[таймаут") }.joinToString("\n")
        val start = text.indexOf('[')
        if (start < 0) return null
        val arr = try { Json.parseToJsonElement(text.substring(start)) as? JsonArray } catch (e: Exception) { null } ?: return null
        val first = arr.firstOrNull() as? JsonObject ?: return null
        val id = (first["id"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (!idRx.matches(id)) return null
        val title = (first["title"] as? JsonPrimitive)?.content.orEmpty().trim()
        return Found(id, title)
    }

    /** Ответ OpenCode говорит, что беседы с таким id нет (например, он сбросил свою базу). */
    fun isMissing(out: String): Boolean = missingRx.containsMatchIn(out)
}
