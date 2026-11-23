package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Точечная правка файла с проверкой контекста и atomic rollback. Идея AndroidHarness, код свой.
 * Формат входа: path, old (фрагмент), new (замена). Если old не найден или встречается несколько раз — ничего не меняем.
 */
class ApplyPatchTool(private val ws: Workspace, private val undo: UndoJournal? = null) : Tool {
    override val name = "apply_patch"
    override val description = "Безопасная точечная правка: path, old, new. Проверяет уникальность фрагмента перед заменой"
    override val schema = objSchema(
        Prop("path", "string", "Путь к файлу внутри проекта"),
        Prop("old", "string", "Точный фрагмент, который заменить"),
        Prop("new", "string", "Новый текст фрагмента"),
    )

    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "правка ${input.str("path") ?: "файла"} (apply_patch)"

    override suspend fun run(input: JsonObject): String {
        val path = input.need("path")
        val old = input.need("old")
        val new = input.str("new").orEmpty()
        val f = ws.resolve(path)
        require(f.isFile) { "Файл не найден: $path" }
        require(!SafeFiles.isBinary(f)) { "Это бинарный файл, apply_patch не поддерживает его" }
        val text = f.readText(Charsets.UTF_8)
        require(text.contains(old)) { "Фрагмент не найден в $path — файл не изменён" }
        require(text.indexOf(old) == text.lastIndexOf(old)) { "Фрагмент встречается несколько раз — правка отменена" }
        val updated = text.replaceFirst(old, new)
        undo?.record(f)
        SafeFiles.rememberOriginal(f)
        require(SafeFiles.atomicWrite(f, updated)) { "Не удалось записать $path — original восстановить можно через file_history" }
        return "Правка применена: $path (${old.length} → ${new.length} символов)"
    }
}
