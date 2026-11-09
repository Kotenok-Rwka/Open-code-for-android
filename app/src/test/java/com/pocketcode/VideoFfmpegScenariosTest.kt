package com.pocketcode

import com.pocketcode.video.Clips
import com.pocketcode.video.Finish
import com.pocketcode.video.FinishOptions
import com.pocketcode.video.Phrase
import com.pocketcode.video.PhraseWord
import com.pocketcode.video.SubPos
import com.pocketcode.video.SubStyle
import com.pocketcode.video.SubTweak
import com.pocketcode.video.Subtitles
import com.pocketcode.video.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Сценарии с настоящим ffmpeg на коротких тестовых роликах: отделка, музыка, склейка и субтитры всех стилей.
 * Ловят ошибки в командах и ASS до релиза. Без ffmpeg на машине тесты пропускаются, а в CI (REQUIRE_FFMPEG=1) обязаны выполниться.
 */
class VideoFfmpegScenariosTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private class Out(val code: Int, val text: String)

    private fun sh(cmd: String): Out {
        val p = ProcessBuilder("sh", "-c", cmd).directory(dir).redirectErrorStream(true).start()
        val text = StringBuilder()
        val reader = Thread { text.append(p.inputStream.bufferedReader().readText()) }.also { it.start() }
        if (!p.waitFor(240, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return Out(-1, "таймаут: $cmd")
        }
        reader.join(5000)
        return Out(p.exitValue(), text.toString())
    }

    private fun ok(cmd: String): String {
        val r = sh(cmd)
        assertEquals("Команда упала:\n$cmd\n${r.text.takeLast(1500)}", 0, r.code)
        return r.text
    }

    @Before
    fun setUp() {
        dir = tmp.newFolder("work")
        val have = sh("ffmpeg -version && ffprobe -version").code == 0
        if (System.getenv("REQUIRE_FFMPEG") == "1") assertTrue("В CI должен быть ffmpeg", have)
        assumeTrue("ffmpeg не найден, тест пропущен", have)
    }

    private fun makeVideo(name: String, w: Int, h: Int, sec: Int, audio: Boolean = true) {
        val a = if (audio) "-f lavfi -i sine=frequency=440:duration=$sec -c:a aac -shortest" else "-an"
        ok("ffmpeg -y -loglevel error -f lavfi -i testsrc2=size=${w}x$h:rate=30:duration=$sec $a -c:v libx264 -preset ultrafast -pix_fmt yuv420p $name")
    }

    private class Probe(val width: Int, val height: Int, val duration: Double, val hasAudio: Boolean, val hasVideo: Boolean)

    private fun probe(name: String): Probe {
        val f = File(dir, name)
        assertTrue("Файл $name не создан", f.isFile && f.length() > 0)
        val s = ok("ffprobe -v error -show_entries stream=codec_type,width,height -show_entries format=duration -of default=noprint_wrappers=1 $name")
        fun v(k: String) = Regex("(?m)^$k=(.+)$").find(s)?.groupValues?.get(1)
        return Probe(
            v("width")?.toIntOrNull() ?: 0, v("height")?.toIntOrNull() ?: 0, v("duration")?.toDoubleOrNull() ?: 0.0,
            s.contains("codec_type=audio"), s.contains("codec_type=video"),
        )
    }

    private fun fontDir(): String = listOf("/usr/share/fonts/truetype/dejavu", "/usr/share/fonts/dejavu", "/usr/share/fonts").firstOrNull { File(it).isDirectory } ?: "."

    private fun finish(src: String, out: String, o: FinishOptions, w: Int, h: Int, dur: Double, audio: Boolean) {
        var ass: String? = null
        if (o.hasTitle) {
            ass = "t.ass"
            File(dir, ass).writeText(Finish.titleAss(o.title, w, h, o.titleSec))
        }
        ok(Finish.command(src, out, w, h, dur, audio, 30.0, o, ass, fontDir(), ::q))
    }

    @Test
    fun finishTitleBarAndSpeed() {
        makeVideo("a.mp4", 640, 360, 4)
        finish("a.mp4", "o.mp4", FinishOptions(title = "Смотри до конца! {скобки}", progressBar = true, speed = 1.25, quality = VideoQuality.FAST), 640, 360, 4.0, true)
        val p = probe("o.mp4")
        assertEquals(640, p.width)
        assertEquals(360, p.height)
        assertEquals(3.2, p.duration, 0.4)
        assertTrue(p.hasAudio && p.hasVideo)
    }

    @Test
    fun finishOnVerticalVideoWithoutAudio() {
        makeVideo("v.mp4", 360, 640, 3, audio = false)
        finish("v.mp4", "o.mp4", FinishOptions(title = "Очень длинный заголовок для вертикального ролика, который должен перенестись на несколько строк", progressBar = true, quality = VideoQuality.FAST), 360, 640, 3.0, false)
        val p = probe("o.mp4")
        assertEquals(360, p.width)
        assertEquals(640, p.height)
        assertEquals(3.0, p.duration, 0.4)
        assertTrue(!p.hasAudio)
    }

    @Test
    fun finishSpeedOnly() {
        makeVideo("a.mp4", 320, 180, 4)
        finish("a.mp4", "o.mp4", FinishOptions(speed = 2.0, quality = VideoQuality.FAST), 320, 180, 4.0, true)
        assertEquals(2.0, probe("o.mp4").duration, 0.4)
    }

    private fun makeMusic() = ok("ffmpeg -y -loglevel error -f lavfi -i sine=frequency=220:duration=2 -c:a libmp3lame -q:a 6 music.mp3").let { "music.mp3" }

    @Test
    fun musicWithDuckingLoopsToVideoLength() {
        makeVideo("a.mp4", 320, 180, 5)
        val m = makeMusic()
        finish("a.mp4", "o.mp4", FinishOptions(music = m, musicVolume = 0.3, duck = true, quality = VideoQuality.FAST), 320, 180, 5.0, true)
        val p = probe("o.mp4")
        assertEquals(5.0, p.duration, 0.5)
        assertTrue(p.hasAudio)
    }

    @Test
    fun musicWithoutDuckingAndWithBarAndSpeed() {
        makeVideo("a.mp4", 320, 180, 5)
        val m = makeMusic()
        finish("a.mp4", "o.mp4", FinishOptions(music = m, duck = false, progressBar = true, speed = 1.25, quality = VideoQuality.FAST), 320, 180, 5.0, true)
        assertEquals(4.0, probe("o.mp4").duration, 0.5)
    }

    @Test
    fun musicOnSilentVideo() {
        makeVideo("n.mp4", 320, 180, 3, audio = false)
        val m = makeMusic()
        finish("n.mp4", "o.mp4", FinishOptions(music = m, quality = VideoQuality.FAST), 320, 180, 3.0, false)
        val p = probe("o.mp4")
        assertTrue("Музыка должна появиться на беззвучном видео", p.hasAudio)
        assertEquals(3.0, p.duration, 0.5)
    }

    @Test
    fun concatMixedSizesAndSilentClip() {
        makeVideo("a.mp4", 640, 360, 2)
        makeVideo("b.mp4", 360, 640, 2)
        makeVideo("c.mp4", 320, 180, 2, audio = false)
        val clips = listOf("a.mp4", "b.mp4", "c.mp4").map {
            val p = probe(it)
            Clips.ClipInfo(it, p.width, p.height, p.duration, p.hasAudio)
        }
        ok(Clips.concatCommand(clips, "mix.mp4", VideoQuality.FAST, ::q))
        val p = probe("mix.mp4")
        assertEquals(640, p.width)
        assertEquals(360, p.height)
        assertEquals(6.0, p.duration, 0.7)
        assertTrue(p.hasAudio && p.hasVideo)
    }

    private fun phrases(): List<Phrase> {
        fun ph(text: String, from: Double): Phrase {
            var t = from
            val ws = text.split(" ").map { PhraseWord(it, t, t + 0.3).also { _ -> t += 0.32 } }
            return Phrase(ws.first().start, ws.last().end, ws)
        }
        return listOf(
            ph("Привет всем это пример субтитров", 0.0),
            ph("Достопримечательности Петропавловск-Камчатского", 2.0),
            ph("International communication", 4.0),
        )
    }

    @Test
    fun everySubtitleStyleBurnsInBothOrientations() {
        val sizes = listOf(640 to 360, 360 to 640)
        for ((w, h) in sizes) {
            makeVideo("s_${w}x$h.mp4", w, h, 3)
            for (style in SubStyle.values()) for (tweak in listOf(SubTweak(), SubTweak("50E850", 1.25, SubPos.TOP), SubTweak("FF8C28", 0.8, SubPos.MIDDLE))) {
                val name = "ass_${style.name}.ass"
                File(dir, name).writeText(Subtitles.ass(phrases(), style, w, h, 0.0, tweak))
                val out = ok("ffmpeg -y -nostdin -loglevel warning -i s_${w}x$h.mp4 -t 3 -vf ${q("ass=filename=$name:fontsdir=${fontDir()}")} -an -f null -")
                assertTrue("$style ${w}x$h: ${out.takeLast(300)}", !out.contains("Error", ignoreCase = true))
            }
        }
    }
}
