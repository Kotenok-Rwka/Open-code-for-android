package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Поиск с источниками и проверкой утверждений (идея AndroidHarness, код свой).
 * Ничего не выдумывает: возвращает найденные фрагменты с адресами, чтобы агент мог сослаться.
 */
class WebCiteTool : Tool {
    override val name = "web_evidence"
    override val description =
        "Найти подтверждения утверждения в интернете. Возвращает фрагменты с источниками (URL) и оценку: есть ли совпадения по ключевым словам. " +
            "Параметры: claim (проверяемое утверждение), deep=true — открыть первые страницы и искать фрагмент внутри."
    override val schema = objSchema(
        Prop("claim", "string", "Утверждение или вопрос для проверки"),
        Prop("deep", "boolean", "Открыть найденные страницы и искать там ключевые слова", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "проверка факта: ${input.str("claim")?.take(80) ?: ""}"

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val claim = input.need("claim").trim()
        val words = claim.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+")).filter { it.length >= 4 }.distinct().take(6)
        require(words.isNotEmpty()) { "Слишком короткое утверждение для поиска" }
        val search = WebSearchTool().run(kotlinx.serialization.json.buildJsonObject {
            put("query", kotlinx.serialization.json.JsonPrimitive(claim))
        })
        val deep = input.bool("deep") ?: false
        val urls = Regex("https?://\\S+").findAll(search).map { it.value.trimEnd(')', ',', '.') }.distinct().take(3).toList()
        val sb = StringBuilder("Утверждение: $claim\n\n$search")
        if (deep && urls.isNotEmpty()) {
            sb.append("\n\n--- Фрагменты со страниц ---\n")
            for (url in urls) {
                val body = runCatching {
                    WebFetchTool().run(kotlinx.serialization.json.buildJsonObject {
                        put("url", kotlinx.serialization.json.JsonPrimitive(url))
                    })
                }.getOrElse { "не удалось открыть: ${it.message}" }
                val hits = words.count { body.contains(it, ignoreCase = true) }
                sb.append("\n[$url] совпадений по словам: $hits/${words.size}\n")
                    .append(body.take(1200)).append('\n')
            }
        }
        sb.append("\n\nВНИМАНИЕ: это найденные тексты, а не доказанная истина. Сослаться можно только на реально открытые фрагменты; " +
            "если источники противоречат друг другу или ничего не нашли — скажи об этом прямо.")
        sb.toString()
    }
}
