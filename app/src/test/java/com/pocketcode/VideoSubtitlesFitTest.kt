package com.pocketcode

import com.pocketcode.video.Phrase
import com.pocketcode.video.PhraseWord
import com.pocketcode.video.SubStyle
import com.pocketcode.video.Subtitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Субтитры должны помещаться в кадр: длинный текст переносится, слишком широкий уменьшается. */
class VideoSubtitlesFitTest {
    private fun phrase(text: String): Phrase {
        var t = 0.0
        val ws = text.split(" ").map { PhraseWord(it, t, t + 0.3).also { _ -> t += 0.32 } }
        return Phrase(ws.first().start, ws.last().end, ws)
    }

    /** Имя стиля -> размер шрифта из блока Style. */
    private fun styleSizes(ass: String): Map<String, Int> =
        ass.lines().filter { it.startsWith("Style: ") }.associate {
            val p = it.removePrefix("Style: ").split(",")
            p[0] to p[2].toInt()
        }

    /** Пары (имя стиля, текст) из строк Dialogue. */
    private fun dialogues(ass: String): List<Pair<String, String>> =
        ass.lines().filter { it.startsWith("Dialogue: ") }.map {
            val p = it.split(",", limit = 10)
            p[3] to p[9]
        }

    @Test
    fun shortTextKeepsDefaultSize() {
        val ass = Subtitles.ass(listOf(phrase("Привет всем")), SubStyle.CLASSIC, 1920, 1080, 0.0)
        val d = dialogues(ass)
        assertTrue(d.isNotEmpty())
        assertTrue(d.all { it.first == "Default" })
    }

    @Test
    fun hugeWordIsShrunkInPortrait() {
        val ass = Subtitles.ass(listOf(phrase("Достопримечательности")), SubStyle.BIG, 1080, 1920, 0.0)
        val sizes = styleSizes(ass)
        val used = dialogues(ass).first().first
        assertTrue(used != "Default")
        assertTrue(sizes.getValue(used) < sizes.getValue("Default"))
    }

    @Test
    fun longPortraitTextIsWrapped() {
        val text = "Сегодня мы разберём как правильно монтировать видео на телефоне"
        val ass = Subtitles.ass(listOf(phrase(text)), SubStyle.CLASSIC, 1080, 1920, 0.0)
        assertTrue(dialogues(ass).first().second.contains("\\N"))
    }

    @Test
    fun everyUsedStyleIsDefined() {
        val texts = listOf(
            "Привет",
            "Программирование важно сейчас",
            "Достопримечательности Петропавловск-Камчатского международные",
            "International communication requires understanding",
        )
        for (style in SubStyle.values()) for ((w, h) in listOf(1080 to 1920, 1920 to 1080, 720 to 1280)) {
            val ass = Subtitles.ass(texts.map { phrase(it) }, style, w, h, 0.0)
            val sizes = styleSizes(ass)
            assertTrue("Default" in sizes)
            for ((name, _) in dialogues(ass)) assertTrue("$style $w x $h: нет стиля $name", name in sizes)
        }
    }

    @Test
    fun sizeNeverDropsBelowReadableMinimum() {
        val ass = Subtitles.ass(listOf(phrase("Достопримечательности")), SubStyle.POP, 360, 640, 0.0)
        val sizes = styleSizes(ass)
        val base = sizes.getValue("Default")
        assertTrue(sizes.values.all { it * 3 >= base })
    }

    @Test
    fun phraseKeepsAllWords() {
        val text = "один два три четыре пять шесть"
        val ass = Subtitles.ass(listOf(phrase(text)), SubStyle.CLASSIC, 1080, 1920, 0.0)
        val shown = dialogues(ass).first().second.replace(Regex("\\{[^}]*\\}"), "").replace("\\N", " ")
        assertEquals(text, shown)
    }
}
