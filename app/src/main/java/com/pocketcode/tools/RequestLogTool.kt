package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject

/** Просмотр и очистка журнала запросов/ошибок. Секреты уже замаскированы в AppLog. */
class RequestLogTool : Tool {
    override val name = "request_log"
    override val description = "Показать последние строки журнала приложения (маскировка секретов уже применена). Параметры: lines=40, clear=true."
    override val schema = objSchema(
        Prop("lines", "string", "Сколько строк показать", required = false),
        Prop("clear", "boolean", "Очистить журнал", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "журнал запросов"

    override suspend fun run(input: JsonObject): String {
        if (input.bool("clear") == true) {
            com.pocketcode.AppLog.clear()
            return "Журнал очищен."
        }
        val n = (input.str("lines")?.toIntOrNull() ?: 40).coerceIn(5, 200)
        return com.pocketcode.AppLog.tail(n).ifBlank { "Журнал пуст." }
    }
}
