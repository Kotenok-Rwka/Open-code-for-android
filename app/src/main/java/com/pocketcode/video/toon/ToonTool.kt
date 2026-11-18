package com.pocketcode.video.toon

import com.pocketcode.tools.Prop
import com.pocketcode.tools.Tool
import com.pocketcode.tools.bool
import com.pocketcode.tools.int
import com.pocketcode.tools.objSchema
import com.pocketcode.tools.str
import kotlinx.serialization.json.JsonObject

/** Агент в чате снимает мультик для TikTok: сам придумывает сценарий по теме или берёт готовый. */
class ToonTool(private val maker: ToonMaker) : Tool {
    override val name = "cartoon_make"
    override val description =
        "Снять вертикальный мультик для TikTok (плоские герои, озвучка, крупные субтитры) и сохранить MP4 в папку video проекта. " +
            "Либо передай topic, и сценарий придумает нейросеть, либо готовый script: строки «Имя: реплика», «Сцена: ночь|море|комната|город|заправка|лагерь», " +
            "в конце «Выбор: вопрос | вариант | вариант». Озвучка и ключи берутся из настроек экрана «Мультики для TikTok»."
    override val schema = objSchema(
        Prop("topic", "string", "Тема истории (если нет script)", required = false),
        Prop("script", "string", "Готовый сценарий (если есть, topic не нужен)", required = false),
        Prop("min_seconds", "integer", "Минимальная длина серии в секундах (по умолчанию 45)", required = false),
        Prop("with_choice", "boolean", "true — в конце выбор зрителей из двух вариантов, false — просто серия (по умолчанию true)", required = false),
    )

    // Снимает видео, нагружает процессор и может тратить токены и деньги за озвучку
    override fun needsApproval(input: JsonObject) = true

    override fun describe(input: JsonObject) = "снять мультик: ${input.str("topic")?.take(60) ?: "по своему сценарию"}"

    override suspend fun run(input: JsonObject): String {
        val script = input.str("script")
        val topic = input.str("topic").orEmpty()
        require(!script.isNullOrBlank() || topic.isNotBlank()) { "Нужна тема (topic) или готовый сценарий (script)." }
        val minSec = (input.int("min_seconds") ?: ToonPrompt.DEFAULT_MIN).coerceIn(10, 300)
        val fmt = if (input.bool("with_choice") == false) ToonFormat.SERIES else ToonFormat.CHOICE
        val rel = maker.produce(topic, script, minSec, fmt)
        return "Мультик готов: $rel (его можно открыть на экране «Мультики для TikTok», там же продолжение истории)."
    }
}
