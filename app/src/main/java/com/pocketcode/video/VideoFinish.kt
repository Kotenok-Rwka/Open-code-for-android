package com.pocketcode.video

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Финальная отделка готового ролика одним проходом ffmpeg: заголовок-«хук» в начале,
 * полоска прогресса внизу и ускорение. Здесь только сборка команды и титров (проверяется тестами),
 * запуск делает VideoPipeline.
 */
data class FinishOptions(
    /** Заголовок на первых секундах; пусто — без заголовка. */
    val title: String = "",
    val titleSec: Double = 2.5,
    val progressBar: Boolean = false,
    /** Скорость: 1.0 — без изменений, допустимо от 1.0 до 2.0. */
    val speed: Double = 1.0,
    val quality: VideoQuality = VideoQuality.BALANCED,
) {
    val hasTitle: Boolean get() = title.isNotBlank()
    val hasSpeed: Boolean get() = speed > 1.001
    val isEmpty: Boolean get() = !hasTitle && !progressBar && !hasSpeed
}

object Finish {
    const val MAX_SPEED = 2.0

    /** Скорость в допустимых пределах, шагом 0,05. */
    fun clampSpeed(s: Double): Double = ((s.coerceIn(1.0, MAX_SPEED) * 20).roundToInt() / 20.0)

    /** Длина результата после ускорения. */
    fun outDuration(duration: Double, o: FinishOptions): Double = duration / (if (o.hasSpeed) clampSpeed(o.speed) else 1.0)

    private fun assTime(t: Double): String {
        val cs = (t * 100).roundToInt().coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d:%02d.%02d", cs / 360000, cs / 6000 % 60, cs / 100 % 60, cs % 100)
    }

    /** Текст титра безопасен для ASS: фигурные скобки и переводы строк не должны ломать разметку. */
    fun cleanTitle(s: String): String =
        s.trim().replace(Regex("\\s*[\\r\\n]+\\s*"), " ").replace('{', '(').replace('}', ')').replace("\\", "/").take(120)

    /** Размер шрифта заголовка: длинный текст берёт меньше, чтобы уложиться в 2–3 строки. */
    fun titleFontSize(title: String, w: Int, h: Int): Int {
        val base = min(w, h) * 0.075
        val k = when {
            title.length > 80 -> 0.62
            title.length > 50 -> 0.75
            title.length > 28 -> 0.88
            else -> 1.0
        }
        return max(14.0, base * k).roundToInt()
    }

    /** ASS с одним заголовком: сверху по центру, с коротким «выскакиванием» и затуханием. Кадр [w]×[h] — итоговый. */
    fun titleAss(title: String, w: Int, h: Int, sec: Double): String {
        val text = cleanTitle(title)
        val fs = titleFontSize(text, w, h)
        val mx = (w * 0.07).roundToInt()
        val mv = (h * if (h > w) 0.12 else 0.08).roundToInt()
        val outline = max(2.0, fs * 0.1)
        val end = max(0.5, sec)
        return buildString {
            append("[Script Info]\nScriptType: v4.00+\nPlayResX: $w\nPlayResY: $h\nWrapStyle: 0\nScaledBorderAndShadow: yes\n\n")
            append("[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
            append("Style: Default,DejaVu Sans,$fs,&H0000F0FF,&H0000F0FF,&H00000000,&H64000000,-1,0,0,0,100,100,0,0,1,${String.format(Locale.US, "%.1f", outline)},2,8,$mx,$mx,$mv,1\n\n")
            append("[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
            append("Dialogue: 0,${assTime(0.0)},${assTime(end)},Default,,0,0,0,,{\\fad(120,300)\\fscx85\\fscy85\\t(0,160,\\fscx112\\fscy112)\\t(160,300,\\fscx100\\fscy100)}$text\n")
        }
    }

    /**
     * Команда ffmpeg. [infoW]×[infoH] — размер исходного файла, [duration] — его длина в секундах,
     * [ass] — путь к файлу титров (если нужен заголовок), [q] — экранирование пути для оболочки.
     */
    fun command(
        src: String, target: String, infoW: Int, infoH: Int, duration: Double, hasAudio: Boolean, fps: Double,
        o: FinishOptions, ass: String?, fontDir: String, q: (String) -> String,
    ): String {
        val s = if (o.hasSpeed) clampSpeed(o.speed) else 1.0
        val w = max(2, infoW) and 1.inv()
        val h = max(2, infoH) and 1.inv()
        val outDur = duration / s
        val sb = StringBuilder()
        sb.append("[0:v]").append(if (s != 1.0) String.format(Locale.US, "setpts=PTS/%.3f", s) else "null").append("[v1]")
        var last = "v1"
        val bar = max(4, (h * 0.008).roundToInt()) and 1.inv()
        if (o.progressBar) {
            // Полоска растёт слева направо за всё время ролика
            sb.append(String.format(Locale.US, ";[%s][1:v]overlay=x='-w+w*t/%.3f':y=H-h:shortest=1[v2]", last, max(0.1, outDur)))
            last = "v2"
        }
        if (ass != null && o.hasTitle) {
            sb.append(";[$last]ass=filename=$ass:fontsdir=$fontDir[v3]")
            last = "v3"
        }
        sb.append(";[$last]null[vo]")
        if (hasAudio) sb.append(";[0:a]").append(if (s != 1.0) String.format(Locale.US, "atempo=%.3f", s) else "anull").append("[ao]")
        val bars = if (o.progressBar) String.format(Locale.US, " -f lavfi -i color=c=0xFFD400:s=%dx%d:r=%.3f:d=%.3f", w, bar, max(1.0, fps), max(0.2, outDur) + 1.0) else ""
        val audio = if (hasAudio) " -map ${q("[ao]")} -c:a aac -b:a 128k" else ""
        return "ffmpeg -y -nostdin -hide_banner -nostats -progress pipe:1 -i ${q(src)}$bars " +
            "-filter_complex ${q(sb.toString())} -map ${q("[vo]")}$audio " +
            "-c:v libx264 -preset ${o.quality.preset} -crf ${o.quality.crf} -pix_fmt yuv420p -movflags +faststart ${q(target)}"
    }
}
