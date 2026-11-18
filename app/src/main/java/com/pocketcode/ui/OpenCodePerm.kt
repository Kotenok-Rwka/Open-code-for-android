package com.pocketcode.ui

/**
 * Права OpenCode для запуска `opencode run` (переменная OPENCODE_PERMISSION).
 *
 * По умолчанию OpenCode спрашивает разрешение на чтение файлов .env, а в режиме `run` спросить некого,
 * и чтение автоматически отклоняется. Проект лежит у самого пользователя, а .env нужен для работы
 * (токены ботов, адреса), поэтому чтение .env разрешено. Порядок правил важен: побеждает последнее подходящее.
 */
object OpenCodePerm {
    const val READ_RULES = "\"read\":{\"*\":\"allow\",\"*.env\":\"allow\",\"*.env.*\":\"allow\"}"

    /** JSON для переменной окружения: allowAll — всё вне проекта разрешено, иначе только /tmp и [granted]. */
    fun json(allowAll: Boolean, granted: List<String>): String {
        val ext = if (allowAll) {
            "\"allow\""
        } else {
            val rules = (listOf("/tmp", "/tmp/**") + granted).distinct().joinToString(",") { "\"" + it.replace("\"", "").replace("'", "") + "\":\"allow\"" }
            "{" + rules + "}"
        }
        return "{" + READ_RULES + ",\"external_directory\":" + ext + "}"
    }
}
