package com.pocketcode.tools

import com.pocketcode.agent.Extensions
import kotlinx.serialization.json.JsonObject

/** Загружает текст навыка (набора инструкций) по имени. */
class SkillTool(private val ext: Extensions) : Tool {
    override val name = "skill"
    override val description =
        "Загрузить навык: готовую инструкцию по задаче. Вызывай, когда описание навыка из системного промпта подходит к задаче."
    override val schema = objSchema(Prop("name", "string", "Имя навыка из списка"))
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "навык ${input.str("name") ?: ""}"
    override suspend fun run(input: JsonObject): String {
        val n = input.need("name").trim()
        val available = ext.skills()
        val skill = available.firstOrNull { it.name == n }
            ?: throw IllegalArgumentException(
                if (ext.skills(all = true).any { it.name == n }) {
                    "Навык «$n» выключен пользователем. Включить можно в «Агент → Навыки, команды, MCP»."
                } else {
                    "Нет навыка «$n». Доступны: " + available.joinToString { it.name }
                },
            )
        return ext.readSkill(skill)
    }
}
