package com.pocketcode.video

import com.pocketcode.tools.Prop
import com.pocketcode.tools.Tool
import com.pocketcode.tools.bool
import com.pocketcode.tools.int
import com.pocketcode.tools.need
import com.pocketcode.tools.objSchema
import com.pocketcode.tools.str
import kotlinx.serialization.json.JsonObject

/**
 * Монтаж видео для агента: те же операции, что и на экране «Видео».
 * Работает с файлами в папке проекта, ffmpeg ставится сам.
 */
class VideoTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_cut_pauses"
    override val description =
        "Найти паузы в видео и вырезать их (монтаж). Файл берётся из папки проекта (например video/clip.mp4). " +
            "Сначала можно вызвать с apply=false, чтобы только узнать, сколько получится. Результат сохраняется рядом с исходником как *_cut.mp4, оригинал не меняется. " +
            "Нужна включённая среда Linux, ffmpeg ставится автоматически."
    override val schema = objSchema(
        Prop("path", "string", "Путь к видео относительно папки проекта"),
        Prop("min_silence", "number", "Минимальная длина паузы в секундах (по умолчанию 0.6)", required = false),
        Prop("noise_db", "integer", "Порог тишины в дБ, от -60 до -20 (по умолчанию -35; ближе к нулю режет больше)", required = false),
        Prop("apply", "boolean", "true — смонтировать файл, false — только показать, что будет вырезано (по умолчанию true)", required = false),
    )

    // Создаёт новый файл в проекте и нагружает процессор: просим подтверждение
    override fun needsApproval(input: JsonObject) = input["apply"]?.toString() != "false"

    override fun describe(input: JsonObject) = "вырезать паузы: ${input.str("path") ?: ""}"

    override suspend fun run(input: JsonObject): String {
        val path = input.need("path").trim().trimStart('/')
        require(!path.contains("..")) { "Путь не должен содержать «..»" }
        val minSilence = (input["min_silence"]?.toString()?.trim('"')?.toDoubleOrNull() ?: 0.6).coerceIn(0.2, 10.0)
        val noise = (input.int("noise_db") ?: -35).coerceIn(-60, -20)
        val apply = input["apply"]?.toString() != "false"
        return pipeline.cutSilence(path, minSilence, noise, VideoOptions(), apply)
    }
}

private const val ENHANCE_HELP = "true — улучшить звук: убрать шум и гул, выровнять громкость (по умолчанию false)"
private const val BLUR_HELP = "true — при shorts=true вместо обрезки вписать кадр целиком на размытом фоне"

private const val SHORTS_HELP = "true — вертикальный ролик 9:16 для Shorts/Reels/TikTok (кадр обрезается по центру)"

private const val TRANSITION_HELP =
    "Переход между кусками: zoom (по умолчанию, чередующееся приближение), cut (резкий), fade (наплыв), flash (вспышка), slide (сдвиг), blur (размытие), circle (круг), mix (разные)"

private fun JsonObject.cleanPath(): String {
    val p = need("path").trim().trimStart('/')
    require(!p.contains("..")) { "Путь не должен содержать «..»" }
    return p
}

private fun JsonObject.number(key: String): Double? = this[key]?.toString()?.trim('"')?.toDoubleOrNull()

/** Распознать речь в видео и вернуть текст с отметками времени (ключ распознавания задаёт пользователь на экране «Видео»). */
class VideoTranscribeTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_transcribe"
    override val description =
        "Распознать речь в видео из папки проекта и вернуть текст с отметками времени. Заодно сохраняет рядом файлы .txt и .srt. " +
            "Нужны среда Linux и ключ распознавания речи в разделе «Видео» (например бесплатный Groq)."
    override val schema = objSchema(
        Prop("path", "string", "Путь к видео относительно папки проекта"),
        Prop("language", "string", "Код языка речи (ru, en…); пусто — определить автоматически", required = false),
    )

    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "распознать речь: ${input.str("path") ?: ""}"

    override suspend fun run(input: JsonObject): String {
        val path = input.cleanPath()
        val text = pipeline.transcribe(path, input.str("language")?.trim().orEmpty())
        val saved = runCatching { pipeline.saveTranscriptFiles(path) }.getOrNull()
        val body = if (text.length > 20_000) text.take(20_000) + "\n…(текст обрезан, полный лежит в файлах)" else text
        return (if (saved != null) "Сохранено: $saved\n\n" else "") + body
    }
}

/** Вшить субтитры (и при желании вырезать паузы) одним проходом. */
class VideoSubtitlesTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_subtitles"
    override val description =
        "Добавить субтитры в видео из папки проекта: распознаёт речь и вшивает текст. Можно сразу вырезать паузы (cut_pauses=true). " +
            "Результат сохраняется рядом как *_sub.mp4 (или *_cut_sub.mp4) и файл .srt, оригинал не меняется. Нужны среда Linux и ключ распознавания в разделе «Видео»."
    override val schema = objSchema(
        Prop("path", "string", "Путь к видео относительно папки проекта"),
        Prop("style", "string", "Стиль: classic (белый текст), box (на плашке), karaoke (слово закрашивается), pop (по 2–3 слова, выскакивают), big (по одному слову во весь экран), neon (светящийся), comic (комикс). По умолчанию classic", required = false),
        Prop("language", "string", "Код языка речи (ru, en…); пусто — автоматически", required = false),
        Prop("cut_pauses", "boolean", "Заодно вырезать паузы (по умолчанию false)", required = false),
        Prop("min_silence", "number", "Минимальная длина паузы в секундах при cut_pauses (по умолчанию 0.6)", required = false),
        Prop("noise_db", "integer", "Порог тишины в дБ, от -60 до -20 (по умолчанию -35)", required = false),
        Prop("transition", "string", TRANSITION_HELP, required = false),
        Prop("shorts", "boolean", SHORTS_HELP, required = false),
        Prop("enhance_audio", "boolean", ENHANCE_HELP, required = false),
        Prop("blur_background", "boolean", BLUR_HELP, required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "субтитры: ${input.str("path") ?: ""}"

    override suspend fun run(input: JsonObject): String {
        val style = styleByName(input.str("style"))
        return pipeline.produce(
            input.cleanPath(),
            input.bool("cut_pauses") == true,
            (input.number("min_silence") ?: 0.6).coerceIn(0.2, 10.0),
            (input.int("noise_db") ?: -35).coerceIn(-60, -20),
            VideoOptions(transition = transitionByName(input.str("transition")), vertical = input.bool("shorts") == true, enhanceAudio = input.bool("enhance_audio") == true, blurBg = input.bool("blur_background") == true),
            style,
            input.str("language")?.trim().orEmpty(),
        )
    }
}

/** Вырезать указанные куски видео по секундам (агент сам решает по тексту, что убрать). */
class VideoCutRangesTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_cut_ranges"
    override val description =
        "Вырезать из видео указанные куски по времени (в секундах исходного видео) и сохранить результат как *_cut.mp4. " +
            "Типичный порядок: video_transcribe, затем выбрать, что убрать (ошибки, повторы, слова-паразиты, лишние дубли), затем этот инструмент. " +
            "Можно сразу вшить субтитры (subtitles=true). Оригинал не меняется."
    override val schema = objSchema(
        Prop("path", "string", "Путь к видео относительно папки проекта"),
        Prop("ranges", "string", "Что вырезать: куски через запятую в секундах, например \"12.5-14.2, 40-41.8\""),
        Prop("subtitles", "boolean", "Вшить субтитры (по умолчанию false)", required = false),
        Prop("style", "string", "Стиль субтитров, если subtitles=true: classic, box, karaoke, pop, big, neon, comic", required = false),
        Prop("transition", "string", TRANSITION_HELP, required = false),
        Prop("language", "string", "Код языка речи для субтитров (ru, en…)", required = false),
        Prop("shorts", "boolean", SHORTS_HELP, required = false),
        Prop("enhance_audio", "boolean", ENHANCE_HELP, required = false),
        Prop("blur_background", "boolean", BLUR_HELP, required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "вырезать куски: ${input.str("path") ?: ""}"

    override suspend fun run(input: JsonObject): String {
        val rx = Regex("(\\d+(?:[.,]\\d+)?)\\s*[-–—]\\s*(\\d+(?:[.,]\\d+)?)")
        val cuts = rx.findAll(input.need("ranges")).mapNotNull { m ->
            val a = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            val b = m.groupValues[2].replace(',', '.').toDoubleOrNull()
            if (a != null && b != null && b > a) TimeRange(a, b) else null
        }.toList()
        require(cuts.isNotEmpty()) { "Не нашёл ни одного куска в формате «начало-конец» в секундах, например 12.5-14.2" }
        val subs = if (input.bool("subtitles") == true) {
            styleByName(input.str("style"))
        } else null
        return pipeline.cutRanges(input.cleanPath(), cuts, VideoOptions(transition = transitionByName(input.str("transition")), vertical = input.bool("shorts") == true, enhanceAudio = input.bool("enhance_audio") == true, blurBg = input.bool("blur_background") == true), subs, input.str("language")?.trim().orEmpty())
    }
}

/** Создать видео нейросетью по ключу пользователя (сервис и модель выбраны на экране «Видео»). Стоит денег, поэтому всегда с подтверждением. */
class VideoGenerateTool(private val gen: VideoGenerator) : Tool {
    override val name = "video_generate"
    override val description =
        "Создать новое видео нейросетью по текстовому описанию через сервис, ключ которого пользователь ввёл на экране «Видео» (fal.ai, Replicate или Google Gemini). " +
            "Это платная операция у сервиса, занимает 1–5 минут. Результат сохраняется в проект как video/gen_*.mp4, потом его можно монтировать другими инструментами. " +
            "Описание лучше писать подробно: кто или что в кадре, действие, движение камеры, свет, стиль."
    override val schema = objSchema(
        Prop("prompt", "string", "Описание видео"),
        Prop("aspect_ratio", "string", "Формат кадра: 16:9, 9:16 или 1:1 (если модель поддерживает; по умолчанию 16:9)", required = false),
        Prop("duration", "integer", "Длина в секундах (подбирается ближайшая из поддерживаемых моделью; по умолчанию 5)", required = false),
        Prop("resolution", "string", "Качество, например 720p (если модель поддерживает)", required = false),
        Prop("audio", "boolean", "Со звуком (если модель умеет; по умолчанию true)", required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "создать видео: ${(input.str("prompt") ?: "").take(60)}"

    override suspend fun run(input: JsonObject): String {
        val req = GenRequest(
            prompt = input.need("prompt"),
            aspect = input.str("aspect_ratio")?.trim().orEmpty().ifEmpty { "16:9" },
            duration = input.int("duration") ?: 5,
            resolution = input.str("resolution")?.trim().orEmpty(),
            audio = input.bool("audio") != false,
        )
        val rel = gen.generate(req)
        return "Видео создано: $rel (${gen.preset.label}). Его можно открыть на экране «Видео» или смонтировать инструментами video_*."
    }
}

/** Финальная отделка готового ролика: заголовок-хук, полоска прогресса, ускорение. */
class VideoFinishTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_finish"
    override val description =
        "Финальная отделка видео из папки проекта: заголовок на первых секундах (хук), полоска прогресса внизу, ускорение до ×2. " +
            "Результат сохраняется рядом как *_fx.mp4, оригинал не меняется. Обычно вызывается последним, на уже смонтированном ролике. Нужна среда Linux."
    override val schema = objSchema(
        Prop("path", "string", "Путь к видео относительно папки проекта"),
        Prop("title", "string", "Заголовок на первых секундах (цепляющая фраза до 120 знаков); пусто — без заголовка", required = false),
        Prop("title_seconds", "number", "Сколько секунд показывать заголовок (по умолчанию 2.5)", required = false),
        Prop("progress_bar", "boolean", "true — полоска прогресса внизу (по умолчанию false)", required = false),
        Prop("speed", "number", "Скорость от 1.0 до 2.0 (по умолчанию 1.0, без изменений)", required = false),
        Prop("music", "string", "Путь к звуковому файлу в папке проекта для фоновой музыки (например video/music/track.mp3); она зацикливается на длину ролика", required = false),
        Prop("music_volume", "number", "Громкость музыки от 0.05 до 1 (по умолчанию 0.25)", required = false),
        Prop("duck", "boolean", "Приглушать музыку, пока говорят (по умолчанию true)", required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "отделка видео: ${input.str("path") ?: ""}"

    override suspend fun run(input: JsonObject): String {
        val o = FinishOptions(
            title = input.str("title")?.trim().orEmpty(),
            titleSec = (input.number("title_seconds") ?: 2.5).coerceIn(0.5, 15.0),
            progressBar = input.bool("progress_bar") == true,
            speed = Finish.clampSpeed(input.number("speed") ?: 1.0),
            music = input.str("music")?.trim()?.trimStart('/').orEmpty().also { require(!it.contains("..")) { "Путь не должен содержать «..»" } },
            musicVolume = (input.number("music_volume") ?: 0.25).coerceIn(0.05, 1.0),
            duck = input.bool("duck") != false,
        )
        return pipeline.finish(input.cleanPath(), o)
    }
}

/** Сделать из длинного видео короткий клип: оставить только указанные куски (в секундах). */
class VideoClipTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_clip"
    override val description =
        "Сделать клип из длинного видео: оставить ТОЛЬКО указанные куски (в секундах или мм:сс исходного видео) и смонтировать их в один ролик *_cut.mp4. " +
            "Типичный порядок для нарезки: video_transcribe, выбрать 3–5 самых сильных законченных моментов по 20–60 секунд (мысль с началом и концом, зацепка в первых секундах), " +
            "затем вызвать этот инструмент для каждого момента отдельно (shorts=true и subtitles=true для вертикальных роликов), при желании потом video_finish с заголовком. Оригинал не меняется."
    override val schema = objSchema(
        Prop("path", "string", "Путь к видео относительно папки проекта"),
        Prop("keep", "string", "Что оставить: куски через запятую, например \"65-110\" или \"1:05-1:50, 3:00-3:20\""),
        Prop("subtitles", "boolean", "Вшить субтитры (по умолчанию false)", required = false),
        Prop("style", "string", "Стиль субтитров: classic, box, karaoke, pop, big, neon, comic", required = false),
        Prop("language", "string", "Код языка речи для субтитров (ru, en…)", required = false),
        Prop("transition", "string", TRANSITION_HELP, required = false),
        Prop("shorts", "boolean", SHORTS_HELP, required = false),
        Prop("enhance_audio", "boolean", ENHANCE_HELP, required = false),
        Prop("blur_background", "boolean", BLUR_HELP, required = false),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "клип из видео: ${input.str("path") ?: ""}"

    override suspend fun run(input: JsonObject): String {
        val keeps = Clips.parseRanges(input.need("keep"))
        require(keeps.isNotEmpty()) { "Не нашёл ни одного куска в формате «начало-конец», например 65-110 или 1:05-1:50" }
        val subs = if (input.bool("subtitles") == true) styleByName(input.str("style")) else null
        return pipeline.keepRanges(
            input.cleanPath(), keeps,
            VideoOptions(transition = transitionByName(input.str("transition")), vertical = input.bool("shorts") == true, enhanceAudio = input.bool("enhance_audio") == true, blurBg = input.bool("blur_background") == true),
            subs, input.str("language")?.trim().orEmpty(),
        )
    }
}

/** Склеить несколько роликов (например сгенерированных сцен) в один. */
class VideoConcatTool(private val pipeline: VideoPipeline) : Tool {
    override val name = "video_concat"
    override val description =
        "Склеить несколько видео из папки проекта подряд в один ролик (размер как у первого, у остальных добавляются поля). Результат: video/<первый>_mix.mp4. " +
            "Нужно, например, чтобы собрать сцены, созданные video_generate, в один фильм; потом можно добавить субтитры, музыку и заголовок. Нужна среда Linux."
    override val schema = objSchema(
        Prop("paths", "string", "Пути к видео по порядку через запятую или с новой строки, минимум два"),
    )

    override fun needsApproval(input: JsonObject) = true
    override fun describe(input: JsonObject) = "склеить видео"

    override suspend fun run(input: JsonObject): String {
        val list = input.need("paths").split(',', '\n', ';').map { it.trim().trimStart('/') }.filter { it.isNotEmpty() }
        list.forEach { require(!it.contains("..")) { "Путь не должен содержать «..»" } }
        return pipeline.concat(list, VideoQuality.BALANCED)
    }
}
