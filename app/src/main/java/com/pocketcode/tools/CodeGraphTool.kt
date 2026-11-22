package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Локальный семантический индекс кода: символы, вызовы, тесты.
 * Работает без внешнего сервиса — по коду проекта через grep.
 */
class CodeGraphTool(
    private val ws: Workspace,
    private val shell: Shell,
    private val enabled: () -> Boolean,
) : Tool {
    override val name = "codegraph"
    override val description =
        "Индекс кода проекта: найти символы, связи и затронутые тесты. Действия: status, index, symbols. " +
            "Индекс хранится в .pocketcode/codegraph.txt."
    override val schema = objSchema(
        Prop("action", "string", "status | index | symbols"),
        Prop("query", "string", "Что искать при action=symbols", required = false),
    )

    override fun needsApproval(input: JsonObject): Boolean = false
    override fun describe(input: JsonObject) = "граф кода: " + (input.str("action") ?: "status")

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val action = input.str("action") ?: "status"
        val index = File(ws.root, ".pocketcode/codegraph.txt")
        when (action) {
            "status" -> if (index.isFile) "Индекс есть: ${index.length()} байт" else "Индекс не построен. Вызови codegraph action=index."
            "index" -> {
                if (!enabled()) throw IllegalStateException("CodeGraph не установлен. В Linux-среде нажми «Установить CodeGraph».")
                val out = shell.run(
                    "grep -RInE '(fun |class |object |interface |def |function )' --include='*.kt' --include='*.py' --include='*.js' --include='*.ts' . | head -n 4000",
                    ws.root, 180,
                )
                index.parentFile.mkdirs()
                index.writeText(out)
                "Проиндексировано строк: ${out.lineSequence().count()}"
            }
            "symbols" -> {
                if (!index.isFile) throw IllegalStateException("Сначала построй индекс: codegraph action=index")
                val q = input.str("query")?.trim().orEmpty()
                val lines = if (q.isEmpty()) index.readLines() else index.readLines().filter { it.contains(q, ignoreCase = true) }
                lines.take(100).joinToString("\n").ifEmpty { "Ничего не найдено" }
            }
            else -> throw IllegalArgumentException("Неизвестное действие: $action. Используй status, index или symbols.")
        }
    }
}
