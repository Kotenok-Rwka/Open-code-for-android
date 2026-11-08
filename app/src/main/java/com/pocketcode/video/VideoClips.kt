package com.pocketcode.video

import java.util.Locale
import kotlin.math.max

/** Чистая логика нарезки, склейки и оценки времени монтажа (запуск ffmpeg делает VideoPipeline). */
object Clips {
    /** Куски, которые нужно оставить: обрезаются по длине видео, сортируются, пересекающиеся склеиваются, слишком короткие отбрасываются. */
    fun normalizeKeeps(keeps: List<TimeRange>, duration: Double, minLen: Double = 0.3): List<TimeRange> {
        val out = ArrayList<TimeRange>()
        for (r in keeps.map { TimeRange(max(0.0, it.start), minOf(duration, it.end)) }.filter { it.end > it.start }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end + 0.05) out[out.size - 1] = TimeRange(last.start, max(last.end, r.end))
            else out += r
        }
        return out.filter { it.end - it.start >= minLen }
    }

    /** «12.5-40, 1:05-1:30» → куски в секундах; понимает запятую в дробях и минуты:секунды. */
    fun parseRanges(text: String): List<TimeRange> {
        fun sec(s: String): Double? {
            val p = s.trim().replace(',', '.').split(':')
            return when (p.size) {
                1 -> p[0].toDoubleOrNull()
                2 -> (p[0].toDoubleOrNull() ?: return null) * 60 + (p[1].toDoubleOrNull() ?: return null)
                3 -> (p[0].toDoubleOrNull() ?: return null) * 3600 + (p[1].toDoubleOrNull() ?: return null) * 60 + (p[2].toDoubleOrNull() ?: return null)
                else -> null
            }
        }
        val rx = Regex("(\\d+(?::\\d{1,2}){0,2}(?:[.,]\\d+)?)\\s*[-–—]\\s*(\\d+(?::\\d{1,2}){0,2}(?:[.,]\\d+)?)")
        return rx.findAll(text).mapNotNull { m ->
            val a = sec(m.groupValues[1]); val b = sec(m.groupValues[2])
            if (a != null && b != null && b > a) TimeRange(a, b) else null
        }.toList()
    }

    class ClipInfo(val rel: String, val width: Int, val height: Int, val duration: Double, val hasAudio: Boolean)

    /** Склейка нескольких роликов в один: всё приводится к размеру первого (с полями), звук выравнивается; у роликов без звука подставляется тишина. */
    fun concatCommand(clips: List<ClipInfo>, target: String, quality: VideoQuality, q: (String) -> String): String {
        require(clips.size >= 2) { "Для склейки нужно хотя бы два видео" }
        val first = clips.first()
        val w = max(2, if (first.width > 0) first.width else 1280) and 1.inv()
        val h = max(2, if (first.height > 0) first.height else 720) and 1.inv()
        val sb = StringBuilder()
        for ((i, c) in clips.withIndex()) {
            sb.append("[$i:v]scale=$w:$h:force_original_aspect_ratio=decrease,pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v$i];")
            val fmt = "aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo"
            if (c.hasAudio) sb.append("[$i:a]$fmt[a$i];")
            else sb.append(String.format(Locale.US, "aevalsrc=0:s=44100:d=%.3f,%s[a$i];", max(0.1, c.duration), fmt))
        }
        for (i in clips.indices) sb.append("[v$i][a$i]")
        sb.append("concat=n=${clips.size}:v=1:a=1[vo][ao]")
        val inputs = clips.joinToString(" ") { "-i ${q(it.rel)}" }
        return "ffmpeg -y -nostdin -hide_banner -nostats -progress pipe:1 $inputs -filter_complex ${q(sb.toString())} " +
            "-map ${q("[vo]")} -map ${q("[ao]")} -c:v libx264 -preset ${quality.preset} -crf ${quality.crf} -pix_fmt yuv420p -c:a aac -b:a 160k -movflags +faststart ${q(target)}"
    }

    /** Грубая оценка работы: сколько займёт (сек) и какого размера получится файл (МБ). [measuredSpeed] — скорость прошлого монтажа (во сколько раз быстрее реального времени). */
    class Estimate(val seconds: Int, val megabytes: Int)

    fun estimate(outSeconds: Double, quality: VideoQuality, vertical: Boolean, small: Boolean, subs: Boolean, xfade: Boolean, measuredSpeed: Double?): Estimate {
        val base = when (quality) { VideoQuality.FAST -> 2.2; VideoQuality.BALANCED -> 1.3; VideoQuality.HIGH -> 0.7 }
        var speed = (measuredSpeed?.takeIf { it > 0.05 } ?: base)
        if (measuredSpeed == null) {
            if (subs) speed *= 0.85
            if (xfade) speed *= 0.75
            if (vertical && !small) speed *= 0.8
        }
        val mbps = when (quality) { VideoQuality.FAST -> 1.6; VideoQuality.BALANCED -> 2.6; VideoQuality.HIGH -> 4.5 } *
            (if (small) 0.55 else if (vertical) 1.0 else 1.1)
        return Estimate(max(5, (outSeconds / speed).toInt()), max(1, (outSeconds * mbps / 8).toInt()))
    }

    /** «около 3 мин», «около 40 с». */
    fun fmtWait(sec: Int): String = if (sec < 90) "около $sec с" else "около ${(sec + 30) / 60} мин"
}
