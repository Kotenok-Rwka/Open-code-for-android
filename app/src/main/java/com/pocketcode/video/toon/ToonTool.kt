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
        Prop("with_choice", "boolean", "true — в конце выбор зрителей, false — просто серия (по умолчанию true)", required = false),
        Prop("choice_count", "integer", "Сколько вариантов в конце (1–8, по умолчанию 2)", required = false),
        Prop("extra", "string", "Дополнительные требования к сценарию: name=value построчно", required = false),
        Prop("voices", "string", "Системные TTS-движки через | — по одному на героя", required = false),
        Prop("pitches", "string", "Высота голосов через запятую (1.0 = норма)", required = false),
        Prop("rates", "string", "Скорость голосов через запятую (1.0 = норма)", required = false),
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
        input.int("choice_count")?.let { maker.choiceCount = it.coerceIn(0, 8) }
        input.str("extra")?.takeIf { it.isNotBlank() }?.let { maker.extraParams = it }
        input.str("voices")?.takeIf { it.isNotBlank() }?.let {
            maker.multiEngines = it
            maker.mode = ToonVoiceMode.SYSTEM_MULTI
        }
        input.str("pitches")?.takeIf { it.isNotBlank() }?.let { maker.multiPitches = it }
        input.str("rates")?.takeIf { it.isNotBlank() }?.let { maker.multiRates = it }
        val rel = maker.produce(topic, script, minSec, fmt)
        return "Мультик готов: $rel (его можно открыть на экране «Мультики для TikTok», там же продолжение истории)."
    }
}
