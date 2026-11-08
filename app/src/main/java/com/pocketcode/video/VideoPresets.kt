package com.pocketcode.video

/**
 * Готовые стили монтажа под популярные форматы: одним нажатием выставляют все настройки экрана «Видео».
 * После применения всё можно поменять вручную.
 */
data class VideoPreset(
    val id: String,
    val label: String,
    val about: String,
    val cut: Boolean,
    val minSilence: Double,
    val noiseDb: Int,
    val subs: SubStyle?,
    val transition: Transition,
    val shorts: Boolean,
    val blurBg: Boolean,
    val enhance: Boolean,
    val quality: VideoQuality = VideoQuality.BALANCED,
    val small: Boolean = false,
) {
    /** Короткие метки того, что включено (каждая переводится отдельно). */
    fun tags(): List<String> = buildList {
        if (cut) add("Без пауз")
        subs?.let { add(it.label) }
        add(transition.label)
        if (shorts) add("Shorts 9:16")
        if (enhance) add("Чистый звук")
    }
}

object VideoPresets {
    val all: List<VideoPreset> = listOf(
        VideoPreset(
            "viral", "Вирусные Shorts",
            "Быстрый темп как у блогеров: паузы вырезаны, слова выскакивают на экране, зум на стыках, вертикальный кадр.",
            cut = true, minSilence = 0.4, noiseDb = -35, subs = SubStyle.POP, transition = Transition.ZOOM,
            shorts = true, blurBg = true, enhance = true,
        ),
        VideoPreset(
            "hook", "Громкий хук",
            "Для первых секунд ролика: по одному огромному слову на экране, вспышки на стыках, кадр обрезается под вертикаль.",
            cut = true, minSilence = 0.4, noiseDb = -35, subs = SubStyle.BIG, transition = Transition.FLASH,
            shorts = true, blurBg = false, enhance = true,
        ),
        VideoPreset(
            "podcast", "Клип из подкаста",
            "Разговор без долгих пауз, караоке-субтитры, мягкие наплывы, видео целиком на размытом фоне.",
            cut = true, minSilence = 0.6, noiseDb = -38, subs = SubStyle.KARAOKE, transition = Transition.FADE,
            shorts = true, blurBg = true, enhance = true,
        ),
        VideoPreset(
            "meme", "Мем и комикс",
            "Жёлтые комикс-титры с «ударом», зум на стыках и вертикальный кадр для шуток и реакций.",
            cut = true, minSilence = 0.4, noiseDb = -33, subs = SubStyle.COMIC, transition = Transition.ZOOM,
            shorts = true, blurBg = true, enhance = false,
        ),
        VideoPreset(
            "neon", "Неоновый ночной",
            "Светящиеся субтитры и размытие на стыках: для музыки, ночных кадров и стильных клипов.",
            cut = false, minSilence = 0.6, noiseDb = -35, subs = SubStyle.NEON, transition = Transition.BLUR,
            shorts = true, blurBg = true, enhance = false,
        ),
        VideoPreset(
            "lesson", "Урок и лекция",
            "Спокойный монтаж: убирает только долгое молчание, субтитры на плашке, горизонтальный кадр, высокое качество.",
            cut = true, minSilence = 1.0, noiseDb = -38, subs = SubStyle.BOX, transition = Transition.CUT,
            shorts = false, blurBg = true, enhance = true, quality = VideoQuality.HIGH,
        ),
        VideoPreset(
            "subs", "Просто субтитры",
            "Ничего не режет и не меняет в кадре: только аккуратные белые субтитры с обводкой.",
            cut = false, minSilence = 0.6, noiseDb = -35, subs = SubStyle.CLASSIC, transition = Transition.CUT,
            shorts = false, blurBg = true, enhance = false,
        ),
        VideoPreset(
            "clean", "Только чистка",
            "Без эффектов и текста: убрать паузы и почистить звук, остальное не трогать.",
            cut = true, minSilence = 0.6, noiseDb = -35, subs = null, transition = Transition.CUT,
            shorts = false, blurBg = true, enhance = true,
        ),
    )

    fun byId(id: String?): VideoPreset? = all.firstOrNull { it.id == id }
}
