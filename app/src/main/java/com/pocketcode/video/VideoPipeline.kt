package com.pocketcode.video

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.pocketcode.AppLog
import com.pocketcode.service.Notifier
import com.pocketcode.tools.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

enum class VideoStage { IDLE, IMPORT, TOOLS, ANALYZE, TRANSCRIBE, RENDER, DONE, ERROR }

enum class VideoQuality(val label: String, val preset: String, val crf: Int) {
    FAST("Быстро", "ultrafast", 27),
    BALANCED("Обычно", "veryfast", 23),
    HIGH("Качество", "faster", 20),
}

data class VideoOptions(val quality: VideoQuality = VideoQuality.BALANCED, val maxHeight: Int = 0, val transition: Transition = Transition.ZOOM, val vertical: Boolean = false, val enhanceAudio: Boolean = false, val blurBg: Boolean = false)

data class TimeRange(val start: Double, val end: Double) {
    val length: Double get() = end - start
}

data class VideoInfo(
    val rel: String,
    val duration: Double,
    val hasAudio: Boolean,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val fps: Double = 30.0,
)

data class SilenceAnalysis(
    val info: VideoInfo,
    val minSilence: Double,
    val noiseDb: Int,
    val cuts: List<TimeRange>,
    val keep: List<TimeRange>,
) {
    val cutSeconds: Double get() = cuts.sumOf { it.length }
    val resultSeconds: Double get() = keep.sumOf { it.length }
}

/** 83.4 -> «1:23», 3700 -> «1:01:40». */
fun fmtTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r) else String.format(Locale.US, "%d:%02d", m, r)
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f ГБ", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f МБ", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.0f КБ", bytes / 1024.0)
}

/**
 * Монтаж видео на телефоне: ffmpeg работает внутри среды Linux (Alpine в proot) и ставится одной командой apk.
 * Состояние наблюдаемое, поэтому экран «Видео» просто показывает его, а агент вызывает те же операции через VideoTool.
 */
class VideoPipeline(
    private val app: Application,
    private val shell: () -> Shell,
    private val root: () -> File,
    private val linuxReady: () -> Boolean,
    private val proxyEnv: () -> String,
    private val keepAlive: () -> Unit,
    private val release: () -> Unit,
    private val appVisible: () -> Boolean,
    private val speechLoad: () -> SpeechConfig,
    private val speechSave: (SpeechConfig) -> Unit,
    /** Ключ из профиля API с тем же адресом: (ключ, название профиля). */
    private val fallbackKey: (String) -> Pair<String, String>?,
    private val proxy: () -> String,
) {
    var stage by mutableStateOf(VideoStage.IDLE)
        private set
    var message by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var speed by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var info by mutableStateOf<VideoInfo?>(null)
        private set
    var analysis by mutableStateOf<SilenceAnalysis?>(null)
        private set
    var resultRel by mutableStateOf<String?>(null)
        private set
    var resultNote by mutableStateOf("")
        private set

    var transcript by mutableStateOf<Transcript?>(null)
        private set
    var transcriptRel by mutableStateOf<String?>(null)
        private set

    // Настройки распознавания речи (хранятся зашифрованно, редактируются на экране «Видео»)
    var sttUrl by mutableStateOf(DEFAULT_STT_URL)
        private set
    var sttModel by mutableStateOf(DEFAULT_STT_MODEL)
        private set
    var sttKey by mutableStateOf("")
        private set
    var speechTest by mutableStateOf("")
        private set

    init {
        val c = speechLoad()
        sttUrl = c.baseUrl.ifBlank { DEFAULT_STT_URL }
        sttModel = c.model.ifBlank { DEFAULT_STT_MODEL }
        sttKey = c.key
    }

    /** Если своего ключа нет, но есть профиль API с тем же адресом (например Groq), берём ключ оттуда. */
    val sttKeySource: String get() = if (sttKey.isNotBlank()) "" else fallbackKey(sttUrl)?.second.orEmpty()

    val speechReady: Boolean get() = effectiveSpeech() != null

    private fun effectiveSpeech(): SpeechConfig? {
        val key = sttKey.trim().ifBlank { fallbackKey(sttUrl)?.first.orEmpty() }
        if (key.isBlank()) return null
        return SpeechConfig(sttUrl.trim().ifBlank { DEFAULT_STT_URL }, sttModel.trim().ifBlank { DEFAULT_STT_MODEL }, key)
    }

    fun saveSpeech(url: String, model: String, key: String) {
        sttUrl = url.trim().ifBlank { DEFAULT_STT_URL }
        sttModel = model.trim().ifBlank { DEFAULT_STT_MODEL }
        sttKey = key.trim()
        speechSave(SpeechConfig(sttUrl, sttModel, sttKey))
        speechTest = ""
    }

    fun testSpeech() {
        val cfg = effectiveSpeech()
        if (cfg == null) { speechTest = "Сначала введите ключ"; return }
        speechTest = "Проверяю…"
        scope.launch {
            speechTest = try {
                SpeechClient(cfg, proxy()).ping()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.message ?: e.javaClass.simpleName
            }
        }
    }

    /** Видео работает только в среде Linux: там есть ffmpeg. */
    val ready: Boolean get() = linuxReady()

    val busy: Boolean
        get() = stage == VideoStage.IMPORT || stage == VideoStage.TOOLS || stage == VideoStage.ANALYZE || stage == VideoStage.TRANSCRIBE || stage == VideoStage.RENDER

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    @Volatile
    private var proc: Process? = null
    private val running = AtomicBoolean(false)
    private var toolsOk = false
    private var subsOk = false
    private var mp3Ok = false

    // ---------- запуск из интерфейса ----------

    private fun launch(block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.log("ERROR", "Видео: " + e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
        }
    }

    fun pick(uri: Uri) = launch { importUri(uri) }

    fun select(rel: String) = launch { open(rel) }

    fun removePauses(rel: String, minSilence: Double, noiseDb: Int, opt: VideoOptions, render: Boolean) =
        launch { cutSilence(rel, minSilence, noiseDb, opt, render) }

    fun transcribeUi(rel: String, lang: String) = launch { transcribe(rel, lang) }

    fun produceUi(rel: String, cut: Boolean, minSilence: Double, noiseDb: Int, opt: VideoOptions, subs: SubStyle?, lang: String) =
        launch { produce(rel, cut, minSilence, noiseDb, opt, subs, lang) }

    fun cancel() {
        job?.cancel()
        proc?.destroyForcibly()
    }

    /** Сбросить ошибку и итог (кнопка «ОК» на экране). */
    fun dismiss() {
        if (!busy) {
            error = null
            if (stage == VideoStage.ERROR || stage == VideoStage.DONE) stage = VideoStage.IDLE
        }
    }

    // ---------- операции (по одной за раз) ----------

    private suspend fun <T> guarded(first: VideoStage, body: suspend () -> T): T {
        if (!running.compareAndSet(false, true)) throw IllegalStateException("Другая операция с видео ещё идёт")
        error = null
        progress = 0f
        speed = ""
        stage = first
        keepAlive()
        try {
            return body()
        } catch (e: CancellationException) {
            stage = VideoStage.IDLE
            message = "Остановлено"
            throw e
        } catch (e: Throwable) {
            stage = VideoStage.ERROR
            error = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            running.set(false)
            release()
        }
    }

    /** Копирует видео из галереи в папку проекта (ffmpeg не умеет читать content://) и читает его параметры. */
    suspend fun importUri(uri: Uri): VideoInfo = guarded(VideoStage.IMPORT) {
        val rel = withContext(Dispatchers.IO) { copyIn(uri) }
        val vi = doOpen(rel)
        resetResult()
        stage = VideoStage.IDLE
        message = ""
        vi
    }

    suspend fun open(rel: String): VideoInfo = guarded(VideoStage.TOOLS) {
        val vi = doOpen(rel)
        resetResult()
        stage = VideoStage.IDLE
        message = ""
        vi
    }

    /**
     * Находит паузы и, если render = true, вырезает их в новый файл рядом с исходным.
     * Возвращает короткий итог для агента.
     */
    suspend fun cutSilence(rel: String, minSilence: Double, noiseDb: Int, opt: VideoOptions, render: Boolean): String =
        guarded(VideoStage.TOOLS) {
            val a = doAnalyze(rel, minSilence, noiseDb)
            when {
                a.cuts.isEmpty() -> {
                    stage = VideoStage.IDLE
                    message = "Пауз не найдено"
                    "Пауз не найдено: при минимальной паузе ${fmtSec(minSilence)} и пороге $noiseDb дБ вырезать нечего. Можно сделать порог ближе к нулю или паузу короче."
                }
                !render -> {
                    stage = VideoStage.IDLE
                    message = summary(a)
                    summary(a)
                }
                else -> {
                    val out = doRender(a.info, a.keep, opt, null, null, true)
                    "Готово: $out. " + resultNote
                }
            }
        }

    private fun summary(a: SilenceAnalysis) =
        "Найдено пауз: ${a.cuts.size}. Вырежем ${fmtTime(a.cutSeconds)} из ${fmtTime(a.info.duration)}, останется ${fmtTime(a.resultSeconds)}."

    private fun resetResult() {
        if (transcriptRel != info?.rel) transcript = null
        analysis = null
        resultRel = null
        resultNote = ""
    }

    private fun fmtSec(d: Double) = String.format(Locale.US, "%.1f с", d)

    // ---------- шаги ----------

    private suspend fun ensureTools() {
        if (toolsOk) return
        if (!ready) throw IllegalStateException("Нужна среда Linux: установите её в разделе «Ещё → Среда Linux» и включите. Там же ставится ffmpeg для монтажа.")
        stage = VideoStage.TOOLS
        message = "Проверяю ffmpeg…"
        var ok = false
        val script = "if command -v ffmpeg >/dev/null 2>&1; then echo PC_OK; else echo \"Ставлю ffmpeg (один раз)…\"; " +
            "apk add --no-cache ffmpeg 2>&1 | tail -n 3; if command -v ffmpeg >/dev/null 2>&1; then echo PC_OK; fi; fi"
        runStreaming(script, 900) { line ->
            if (line.contains("PC_OK")) ok = true else if (line.isNotBlank()) message = line.take(120)
        }
        if (!ok) throw IllegalStateException("Не удалось установить ffmpeg. Проверьте интернет (или прокси в настройках) и повторите.")
        toolsOk = true
    }

    private suspend fun doOpen(rel: String): VideoInfo {
        ensureTools()
        stage = VideoStage.ANALYZE
        message = "Читаю видео…"
        val lines = ArrayList<String>()
        // Без выходного файла ffmpeg печатает сведения о потоках и завершается с кодом 1: это нормально
        runStreaming("ffmpeg -nostdin -hide_banner -i ${q(rel)}", 120) { lines += it }
        val text = lines.joinToString("\n")
        val d = DURATION.find(text)
            ?: throw IllegalStateException("Не удалось прочитать видео (формат не поддерживается или файл повреждён): " + lines.takeLast(3).joinToString(" ").take(300))
        val dur = d.groupValues[1].toDouble() * 3600 + d.groupValues[2].toDouble() * 60 + d.groupValues[3].toDouble()
        if (!text.contains(Regex("Stream #\\S+.*: Video:"))) throw IllegalStateException("В файле нет видеодорожки")
        val hasAudio = text.contains(Regex("Stream #\\S+.*: Audio:"))
        val size = VIDEO_SIZE.find(text)
        val cw = size?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ch = size?.groupValues?.get(2)?.toIntOrNull() ?: 0
        // Телефонные ролики часто хранятся «лёжа» с пометкой о повороте: ffmpeg разворачивает их сам, значит размер меняем местами
        val rot = (ROTATION.find(text)?.groupValues?.get(1) ?: ROTATE_TAG.find(text)?.groupValues?.get(1))?.toDoubleOrNull()
        val swap = rot != null && kotlin.math.abs(rot).toInt() % 180 == 90
        val vi = VideoInfo(
            rel = rel,
            duration = dur,
            hasAudio = hasAudio,
            width = if (swap) ch else cw,
            height = if (swap) cw else ch,
            bytes = File(root(), rel).length(),
            fps = (VIDEO_FPS.find(text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 30.0).coerceIn(15.0, 60.0),
        )
        info = vi
        return vi
    }

    private suspend fun doAnalyze(rel: String, minSilence: Double, noiseDb: Int): SilenceAnalysis {
        val cur = info
        val vi = if (cur != null && cur.rel == rel) { ensureTools(); cur } else doOpen(rel)
        if (!vi.hasAudio) throw IllegalStateException("В видео нет звука: паузы искать не по чему")
        stage = VideoStage.ANALYZE
        progress = 0f
        message = "Ищу паузы…"
        val raw = ArrayList<TimeRange>()
        var open = -1.0
        val cmd = "ffmpeg -nostdin -hide_banner -nostats -progress pipe:1 -i ${q(rel)} -vn " +
            "-af silencedetect=noise=${noiseDb}dB:d=${f(minSilence)} -f null -"
        runStreaming(cmd, 1800) { line ->
            val t = progressSeconds(line)
            if (t != null) {
                if (vi.duration > 0) progress = (t / vi.duration).toFloat().coerceIn(0f, 1f)
            } else {
                SIL_START.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { open = maxOf(0.0, it) }
                SIL_END.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.let { e ->
                    if (open >= 0) raw += TimeRange(open, e)
                    open = -1.0
                }
            }
        }
        if (open >= 0) raw += TimeRange(open, vi.duration)
        val cuts = buildCuts(raw, vi.duration)
        val a = SilenceAnalysis(vi, minSilence, noiseDb, cuts, complement(cuts, vi.duration))
        analysis = a
        resultRel = null
        message = if (cuts.isEmpty()) "Пауз не найдено" else summary(a)
        return a
    }

    /**
     * Оставляем по 0,12 с тишины с каждой стороны паузы, чтобы речь не слипалась: пауза сжимается, а не исчезает.
     * Тишина в самом начале и в самом конце вырезается целиком.
     */
    private fun buildCuts(raw: List<TimeRange>, dur: Double): List<TimeRange> {
        val pad = 0.12
        val out = ArrayList<TimeRange>()
        for (s in raw.sortedBy { it.start }) {
            val a = if (s.start <= 0.05) 0.0 else s.start + pad
            val b = if (s.end >= dur - 0.05) dur else s.end - pad
            if (b - a >= 0.2) out += TimeRange(a, minOf(b, dur))
        }
        return out
    }

    private fun complement(cuts: List<TimeRange>, dur: Double): List<TimeRange> {
        val keep = ArrayList<TimeRange>()
        var cur = 0.0
        for (c in cuts) {
            if (c.start - cur >= 0.05) keep += TimeRange(cur, c.start)
            cur = maxOf(cur, c.end)
        }
        if (dur - cur >= 0.05) keep += TimeRange(cur, dur)
        return keep
    }

    private fun outSize(vi: VideoInfo, opt: VideoOptions): Pair<Int, Int> {
        if (opt.vertical) return if (opt.maxHeight in 1..1279) 720 to 1280 else 1080 to 1920
        if (vi.width <= 0 || vi.height <= 0) return 1280 to 720
        if (opt.maxHeight > 0 && vi.height > opt.maxHeight) {
            val h = opt.maxHeight
            val w = (vi.width.toDouble() * h / vi.height).toInt() and 1.inv()
            return w to h
        }
        return vi.width to vi.height
    }

    /**
     * Собирает готовый файл: оставляет куски [keep], при необходимости вшивает субтитры.
     * Длинные видео собираются порциями по BATCH кусков, чтобы не упереться в память.
     */
    private suspend fun doRender(vi: VideoInfo, keep: List<TimeRange>, opt: VideoOptions, subs: SubStyle?, tr: Transcript?, cut: Boolean): String {
        if (keep.isEmpty()) throw IllegalStateException("После вырезания ничего не осталось: верните часть слов или ослабьте поиск пауз (порог ближе к −45 дБ, пауза подлиннее).")
        if (subs != null) ensureSubtitleTools()
        if (opt.transition == Transition.CUT || keep.size < 2) return renderOnce(vi, keep, opt, subs, tr, cut)
        return try {
            renderOnce(vi, keep, opt, subs, tr, cut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Эффект не получился (например, в этом ffmpeg нет такого перехода): повторяем с обычными склейками
            AppLog.log("ERROR", "Видео: переход «${opt.transition.label}» не вышел: " + (e.message ?: "").take(300))
            val out = renderOnce(vi, keep, opt.copy(transition = Transition.CUT), subs, tr, cut)
            resultNote += " Переход «${opt.transition.label}» не получился, сделал резкие склейки."
            message = "Готово: $resultNote"
            out
        }
    }

    private suspend fun renderOnce(vi: VideoInfo, keep: List<TimeRange>, opt: VideoOptions, subs: SubStyle?, tr: Transcript?, cut: Boolean): String {
        if (keep.isEmpty()) throw IllegalStateException("После вырезания ничего не осталось: верните часть слов или ослабьте поиск пауз (порог ближе к −45 дБ, пауза подлиннее).")
        if (subs != null) ensureSubtitleTools()
        stage = VideoStage.RENDER
        progress = 0f
        message = "Монтаж…"
        val out = uniqueOut(vi.rel, (if (cut) "_cut" else "") + (if (subs != null) "_sub" else ""))
        val work = "video/.work"
        val workDir = File(root(), work)
        workDir.deleteRecursively()
        workDir.mkdirs()
        val batches = keep.chunked(BATCH)
        val plans = batches.map { makePlan(it, opt.transition, vi.fps) }
        val total = plans.sumOf { it.partDur }
        val starts = DoubleArray(keep.size)
        run {
            var idx = 0
            var off = 0.0
            for (p in plans) { for (st in p.starts) starts[idx++] = off + st; off += p.partDur }
        }
        val phrases = if (subs != null && tr != null) Subtitles.build(tr, keep, subs, starts) else emptyList()
        val (ow, oh) = outSize(vi, opt)
        val parts = ArrayList<String>()
        val tail = ArrayDeque<String>()
        var done = 0.0
        var ok = false
        try {
            for ((i, batch) in batches.withIndex()) {
                val w0 = batch.first().start
                val w1 = batch.last().end
                val partDur = plans[i].partDur
                val target = if (batches.size == 1) out else String.format(Locale.US, "%s/p%03d.mp4", work, i)
                val startDone = done
                var assRel: String? = null
                if (subs != null) {
                    val sel = phrases.filter { it.start >= startDone - 0.001 && it.start < startDone + partDur - 0.001 }
                    if (sel.isNotEmpty()) {
                        assRel = String.format(Locale.US, "%s/s%03d.ass", work, i)
                        File(root(), assRel).writeText(Subtitles.ass(sel, subs, ow, oh, startDone))
                    }
                }
                val code = runStreaming(ffmpegCut(vi, batch, w0, w1, opt, target, assRel, plans[i], i * BATCH), 3 * 3600L) { line ->
                    val t = progressSeconds(line)
                    if (t != null) {
                        val now = startDone + minOf(t, partDur)
                        progress = (now / total * 0.97).toFloat().coerceIn(0f, 0.97f)
                        message = "Монтаж: ${fmtTime(now)} из ${fmtTime(total)}"
                    } else if (line.startsWith("speed=")) {
                        speed = line.removePrefix("speed=").trim()
                    } else if (!isProgressLine(line) && line.isNotBlank()) {
                        tail.addLast(line)
                        if (tail.size > 8) tail.removeFirst()
                    }
                }
                if (code != 0) throw IllegalStateException("ffmpeg завершился с ошибкой ($code): " + tail.joinToString(" | ").take(500))
                done += partDur
                parts += target
            }
            if (batches.size > 1) {
                progress = 0.98f
                message = "Склеиваю части…"
                File(workDir, "list.txt").writeText(parts.joinToString("\n") { "file '${File(it).name}'" } + "\n")
                val code = runStreaming(
                    "ffmpeg -y -nostdin -hide_banner -nostats -f concat -safe 0 -i ${q("$work/list.txt")} -c copy -movflags +faststart ${q(out)}",
                    1800,
                ) { line -> if (line.isNotBlank()) { tail.addLast(line); if (tail.size > 8) tail.removeFirst() } }
                if (code != 0) throw IllegalStateException("Не удалось склеить части ($code): " + tail.joinToString(" | ").take(500))
            }
            val f = File(root(), out)
            if (!f.exists() || f.length() == 0L) throw IllegalStateException("ffmpeg не создал файл результата")
            ok = true
            resultRel = out
            var note = "Было ${fmtTime(vi.duration)}, стало ${fmtTime(total)}, файл ${fmtSize(f.length())}."
            if (subs != null && tr != null) {
                val srtPhrases = Subtitles.build(tr, keep, SubStyle.CLASSIC, starts)
                if (srtPhrases.isNotEmpty()) {
                    val srt = out.removeSuffix(".mp4") + ".srt"
                    File(root(), srt).writeText(Subtitles.srt(srtPhrases))
                    note += " Субтитры вшиты, файл ${File(srt).name} лежит рядом."
                } else note += " Речи в этом фрагменте не нашлось, субтитров нет."
            }
            resultNote = note
            progress = 1f
            speed = ""
            message = "Готово: $note"
            stage = VideoStage.DONE
            if (!appVisible()) Notifier.alert(app, "Видео готово", File(out).name + ": " + note)
            return out
        } finally {
            workDir.deleteRecursively()
            if (!ok) File(root(), out).delete()
        }
    }

    /**
     * Один проход ffmpeg: вырезает куски batch из окна [w0, w1], соединяет их выбранным переходом и (если нужно) вшивает субтитры из файла [ass].
     * [base] — номер первого куска порции во всём видео (чтобы зум и «Микс» чередовались сквозь порции).
     */
    private fun ffmpegCut(vi: VideoInfo, batch: List<TimeRange>, w0: Double, w1: Double, opt: VideoOptions, target: String, ass: String?, plan: Plan, base: Int): String {
        val tr = opt.transition
        val xf = tr.xfade != null && batch.size > 1
        val (ow, oh) = outSize(vi, opt)
        val blurFit = opt.vertical && opt.blurBg
        val zoom = tr.zoom && ow % 2 == 0 && oh % 2 == 0 && !blurFit
        val zw = (ow * 1.12).toInt() and 1.inv()
        val zh = (oh * 1.12).toInt() and 1.inv()
        val fps = String.format(Locale.US, "%.3f", plan.fps)
        val sb = StringBuilder()
        for ((i, r) in batch.withIndex()) {
            val a = f(r.start - w0)
            val b = f(r.end - w0)
            sb.append("[0:v]trim=start=$a:end=$b,setpts=PTS-STARTPTS")
            if (xf) sb.append(",fps=$fps,tpad=stop_mode=clone:stop_duration=0.5,trim=end_frame=${plan.frames[i]},setpts=PTS-STARTPTS")
            // setsar=1: после scale+crop пропорция пикселя чуть отличается у приближенных и обычных кусков, и concat отказывался их склеивать
            if (zoom) sb.append(if ((base + i) % 2 == 1) ",scale=$zw:$zh:force_original_aspect_ratio=increase,crop=$ow:$oh,setsar=1" else ",scale=$ow:$oh:force_original_aspect_ratio=increase,crop=$ow:$oh,setsar=1")
            sb.append("[v$i];")
            if (vi.hasAudio) {
                if (xf) {
                    val len = f(plan.frames[i] / plan.fps)
                    sb.append("[0:a]atrim=start=$a:end=$b,asetpts=PTS-STARTPTS,apad=whole_dur=$len,atrim=end=$len,asetpts=PTS-STARTPTS[a$i];")
                } else {
                    // Короткое затухание на стыках, чтобы не было щелчков
                    val d = r.length
                    val fade = if (d > 0.1) ",afade=t=in:st=0:d=0.012,afade=t=out:st=${f(d - 0.012)}:d=0.012" else ""
                    sb.append("[0:a]atrim=start=$a:end=$b,asetpts=PTS-STARTPTS$fade[a$i];")
                }
            }
        }
        var vl = "v0"
        var al = "a0"
        if (xf) {
            for (k in 1 until batch.size) {
                val ov = String.format(Locale.US, "%.4f", plan.overlap[k] / plan.fps)
                val off = String.format(Locale.US, "%.4f", plan.offsets[k] / plan.fps)
                sb.append("[$vl][v$k]xfade=transition=${xfadeName(tr, base + k)}:duration=$ov:offset=$off[xv$k];")
                vl = "xv$k"
                if (vi.hasAudio) {
                    sb.append("[$al][a$k]acrossfade=d=$ov:c1=tri:c2=tri[xa$k];")
                    al = "xa$k"
                }
            }
        } else {
            for (i in batch.indices) {
                sb.append("[v$i]")
                if (vi.hasAudio) sb.append("[a$i]")
            }
            sb.append("concat=n=${batch.size}:v=1:a=${if (vi.hasAudio) 1 else 0}[cv]")
            if (vi.hasAudio) sb.append("[ca]")
            sb.append(";")
            vl = "cv"
            al = "ca"
        }
        val scale = when {
            zoom -> "null"
            opt.vertical -> "scale=$ow:$oh:force_original_aspect_ratio=increase,crop=$ow:$oh"
            opt.maxHeight > 0 && vi.height > opt.maxHeight -> "scale=-2:${opt.maxHeight}"
            else -> "null"
        }
        if (blurFit) sb.append("[$vl]split[bg0][fg0];[bg0]scale=$ow:$oh:force_original_aspect_ratio=increase,crop=$ow:$oh,boxblur=20:2[bg1];" +
            "[fg0]scale=$ow:$oh:force_original_aspect_ratio=decrease[fg1];[bg1][fg1]overlay=(W-w)/2:(H-h)/2[vs]")
        else sb.append("[$vl]$scale[vs]")
        if (ass != null) sb.append(";[vs]ass=filename=$ass:fontsdir=$FONT_DIR[vo]") else sb.append(";[vs]null[vo]")
        if (vi.hasAudio) sb.append(";[$al]" + (if (opt.enhanceAudio) "highpass=f=80,afftdn=nf=-28,acompressor=threshold=-21dB:ratio=3:attack=5:release=80,loudnorm=I=-16:TP=-1.5:LRA=11" else "anull") + "[ao]")
        val audio = if (vi.hasAudio) " -map ${q("[ao]")} -c:a aac -b:a 128k" else ""
        return "ffmpeg -y -nostdin -hide_banner -nostats -progress pipe:1 -ss ${f(w0)} -t ${f(w1 - w0)} -i ${q(vi.rel)} " +
            "-filter_complex ${q(sb.toString())} -map ${q("[vo]")}$audio " +
            "-c:v libx264 -preset ${opt.quality.preset} -crf ${opt.quality.crf} -pix_fmt yuv420p -movflags +faststart ${q(target)}"
    }

    private fun uniqueOut(rel: String, suffix: String): String {
        val src = File(rel)
        val dir = src.parent ?: "video"
        val base = src.nameWithoutExtension
        var n = 1
        while (true) {
            val name = if (n == 1) "$base$suffix.mp4" else "$base$suffix$n.mp4"
            val cand = "$dir/$name"
            if (!File(root(), cand).exists()) return cand
            n++
        }
    }

    // ---------- речь и субтитры ----------

    /** Проверяет, что ffmpeg умеет вшивать субтитры (фильтр ass) и что есть шрифт с кириллицей. */
    private suspend fun ensureSubtitleTools() {
        if (subsOk) return
        ensureTools()
        stage = VideoStage.TOOLS
        message = "Проверяю шрифты для субтитров…"
        var ass = false
        var font = false
        val script = "if ffmpeg -hide_banner -filters 2>/dev/null | grep -q ' ass '; then echo PC_ASS; fi; " +
            "if ls $FONT_DIR/DejaVuSans-Bold.ttf >/dev/null 2>&1; then echo PC_FONT; else echo 'Ставлю шрифт (один раз)…'; " +
            "apk add --no-cache font-dejavu 2>&1 | tail -n 2; ls $FONT_DIR/DejaVuSans-Bold.ttf >/dev/null 2>&1 && echo PC_FONT; fi"
        runStreaming(script, 600) { line ->
            if (line.contains("PC_ASS")) ass = true else if (line.contains("PC_FONT")) font = true else if (line.isNotBlank()) message = line.take(120)
        }
        if (!ass) throw IllegalStateException("Этот ffmpeg не умеет вшивать субтитры (нет фильтра ass). Можно получить текст и файл .srt без вшивания.")
        if (!font) throw IllegalStateException("Не удалось поставить шрифт для субтитров. Проверьте интернет (или прокси в настройках) и повторите.")
        subsOk = true
    }

    private suspend fun ensureSpeechTools() {
        if (mp3Ok) return
        ensureTools()
        var ok = false
        runStreaming("if ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libmp3lame; then echo PC_MP3; fi", 60) { if (it.contains("PC_MP3")) ok = true }
        if (!ok) throw IllegalStateException("В этом ffmpeg нет кодека mp3, поэтому подготовить звук для распознавания не получится.")
        mp3Ok = true
    }

    private fun hashOf(s: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** Достаёт звук, отправляет на распознавание кусками по 10 минут и собирает слова с отметками времени. Результат кэшируется. */
    private suspend fun doTranscribe(rel: String, lang: String): Transcript {
        val cfg = effectiveSpeech()
            ?: throw IllegalStateException("Нужен ключ распознавания речи: введите его в блоке «Распознавание речи» на этом экране (подойдёт бесплатный ключ Groq).")
        val cur = info
        val vi = if (cur != null && cur.rel == rel) { ensureTools(); cur } else doOpen(rel)
        if (!vi.hasAudio) throw IllegalStateException("В видео нет звука: распознавать нечего")
        val src = File(root(), rel)
        val cache = File(File(root(), "video/.cache").apply { mkdirs() }, hashOf("$rel|${src.length()}|${src.lastModified()}|$lang|${cfg.model}|${cfg.baseUrl}") + ".json")
        if (cache.isFile) {
            val cached = runCatching { Transcript.fromJson(cache.readText()) }.getOrNull()
            if (cached != null && cached.segments.isNotEmpty()) {
                transcript = cached
                transcriptRel = rel
                message = "Текст взят из сохранённого: повторно распознавать не нужно"
                return cached
            }
        }
        ensureSpeechTools()
        stage = VideoStage.TRANSCRIBE
        progress = 0f
        message = "Готовлю звук…"
        val work = File(root(), "video/.work")
        work.deleteRecursively()
        work.mkdirs()
        try {
            val tail = ArrayDeque<String>()
            val code = runStreaming(
                "ffmpeg -y -nostdin -hide_banner -nostats -progress pipe:1 -i ${q(rel)} -vn -ac 1 -ar 16000 -c:a libmp3lame -b:a 32k " +
                    "-f segment -segment_time $CHUNK -reset_timestamps 1 ${q("video/.work/a%03d.mp3")}",
                1800,
            ) { line ->
                val t = progressSeconds(line)
                if (t != null) {
                    if (vi.duration > 0) progress = (t / vi.duration * 0.2).toFloat().coerceIn(0f, 0.2f)
                } else if (!isProgressLine(line) && line.isNotBlank()) {
                    tail.addLast(line)
                    if (tail.size > 8) tail.removeFirst()
                }
            }
            if (code != 0) throw IllegalStateException("Не удалось извлечь звук ($code): " + tail.joinToString(" | ").take(400))
            val chunks = (work.listFiles() ?: emptyArray()).filter { it.name.matches(Regex("a\\d{3}\\.mp3")) }.sortedBy { it.name }
            if (chunks.isEmpty()) throw IllegalStateException("Не удалось извлечь звук: ffmpeg не создал файлов")
            val client = SpeechClient(cfg, proxy())
            val parts = ArrayList<Transcript>()
            for ((i, f) in chunks.withIndex()) {
                message = "Распознаю речь: часть ${i + 1} из ${chunks.size}"
                progress = 0.2f + 0.8f * i / chunks.size
                val offset = i * CHUNK.toDouble()
                val chunkDur = minOf(CHUNK.toDouble(), vi.duration - offset).coerceAtLeast(1.0)
                parts += client.transcribe(f, lang.takeIf { it.isNotBlank() }, offset, chunkDur)
            }
            val tr = mergeTranscripts(parts)
            if (tr.segments.isEmpty()) throw IllegalStateException("Речь в видео не найдена. Если язык указан неверно, выберите другой или «Авто».")
            runCatching { cache.writeText(tr.toJson()) }
            transcript = tr
            transcriptRel = rel
            progress = 1f
            return tr
        } finally {
            work.deleteRecursively()
        }
    }

    /** Только текст: распознать и показать на экране (агенту возвращается текст с отметками времени). */
    suspend fun transcribe(rel: String, lang: String): String = guarded(VideoStage.TOOLS) {
        val tr = doTranscribe(rel, lang)
        stage = VideoStage.IDLE
        message = "Текст готов: ${tr.segments.size} фраз"
        tr.timedText()
    }

    /**
     * Главная операция: при необходимости вырезает паузы и/или вшивает субтитры, одним проходом кодирования.
     * Речь распознаётся по исходному видео один раз, а потом раскладывается на смонтированную шкалу времени.
     */
    suspend fun produce(rel: String, cut: Boolean, minSilence: Double, noiseDb: Int, opt: VideoOptions, subs: SubStyle?, lang: String): String =
        guarded(VideoStage.TOOLS) {
            if (!cut && subs == null) throw IllegalStateException("Выберите, что сделать: вырезать паузы или добавить субтитры")
            val tr = if (subs != null) doTranscribe(rel, lang) else null
            val vi: VideoInfo
            val keep: List<TimeRange>
            if (cut) {
                val a = doAnalyze(rel, minSilence, noiseDb)
                if (a.cuts.isEmpty() && subs == null) {
                    stage = VideoStage.IDLE
                    message = "Пауз не найдено"
                    return@guarded "Пауз не найдено: при минимальной паузе ${fmtSec(minSilence)} и пороге $noiseDb дБ вырезать нечего."
                }
                vi = a.info
                keep = a.keep
            } else {
                val cur = info
                vi = if (cur != null && cur.rel == rel) cur else doOpen(rel)
                keep = listOf(TimeRange(0.0, vi.duration))
                analysis = null
            }
            val out = doRender(vi, keep, opt, subs, tr, cut)
            "Готово: $out. $resultNote"
        }

    /** Кэш редактора: правки не пропадают, если выйти с экрана и вернуться. */
    private var editCache: Pair<Transcript, TextEdit>? = null

    /** Редактор по тексту для текущего видео, если речь уже распознана. */
    fun textEdit(): TextEdit? {
        val tr = transcript ?: return null
        val vi = info ?: return null
        if (transcriptRel != vi.rel) return null
        editCache?.let { if (it.first === tr) return it.second }
        return TextEdit(tr, vi.duration).also { editCache = tr to it }
    }

    /** Путь файла проекта на устройстве (для встроенного плеера). */
    fun absPath(rel: String): String = File(root(), rel).absolutePath

    fun cutRangesUi(rel: String, cuts: List<TimeRange>, opt: VideoOptions, subs: SubStyle?, lang: String) =
        launch { cutRanges(rel, cuts, opt, subs, lang) }

    /**
     * Вырезает указанные куски исходного видео (в секундах), при необходимости вшивает субтитры.
     * Используется редактором по тексту и агентом.
     */
    suspend fun cutRanges(rel: String, cuts: List<TimeRange>, opt: VideoOptions, subs: SubStyle?, lang: String): String =
        guarded(VideoStage.TOOLS) {
            if (cuts.isEmpty() && subs == null) throw IllegalStateException("Нечего вырезать: список кусков пуст")
            val tr = if (subs == null) null else (transcript?.takeIf { transcriptRel == rel } ?: doTranscribe(rel, lang))
            val cur = info
            val vi = if (cur != null && cur.rel == rel) { ensureTools(); cur } else doOpen(rel)
            val keep = rangesKeep(cuts, vi.duration)
            val out = doRender(vi, keep, opt, subs, tr, cuts.isNotEmpty())
            "Готово: $out. $resultNote"
        }

    /** Сохраняет текст и .srt рядом с видео. Возвращает короткий итог. */
    fun saveTranscriptFiles(rel: String): String {
        val tr = transcript ?: throw IllegalStateException("Сначала получите текст")
        val dir = File(rel).parent ?: "video"
        val base = File(rel).nameWithoutExtension
        val txt = "$dir/$base.txt"
        File(root(), txt).writeText(tr.timedText() + "\n")
        val phrases = Subtitles.build(tr, listOf(TimeRange(0.0, info?.takeIf { it.rel == rel }?.duration ?: (tr.segments.maxOfOrNull { it.end } ?: 0.0) + 1.0)), SubStyle.CLASSIC)
        val srt = "$dir/$base.srt"
        File(root(), srt).writeText(Subtitles.srt(phrases))
        return "$txt и $srt"
    }

    // ---------- файлы ----------

    private suspend fun copyIn(uri: Uri): String {
        val cr = app.contentResolver
        var name = "video.mp4"
        var total = 0L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) total = c.getLong(1)
            }
        }
        var clean = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "_").trim('_', '.')
        if (clean.isBlank()) clean = "video"
        if (File(clean).extension.lowercase() !in VIDEO_EXT) clean += ".mp4"
        val dir = File(root(), "video").apply { mkdirs() }
        val base = File(clean).nameWithoutExtension
        val ext = File(clean).extension
        var dest = File(dir, clean)
        var n = 1
        while (dest.exists()) dest = File(dir, "${base}_$n.$ext").also { n++ }
        var lastShown = -1L
        try {
            val input = cr.openInputStream(uri) ?: throw IllegalStateException("Не удалось открыть выбранный файл")
            input.use { src ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val r = src.read(buf)
                        if (r < 0) break
                        o.write(buf, 0, r)
                        copied += r
                        if (copied shr 23 != lastShown) {
                            lastShown = copied shr 23
                            if (total > 0) progress = (copied.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            message = "Копирую видео в проект: ${copied shr 20} МБ" + if (total > 0) " из ${total shr 20}" else ""
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
        return "video/" + dest.name
    }

    /** Файлы проекта с видео (для быстрого выбора). */
    fun recent(): List<String> {
        val dir = File(root(), "video")
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in VIDEO_EXT }
            ?.sortedByDescending { it.lastModified() }
            ?.take(12)
            ?.map { "video/" + it.name }
            ?: emptyList()
    }

    /** Копия во временную папку, доступную через FileProvider, чтобы поделиться файлом. */
    suspend fun shareUri(rel: String): Uri = withContext(Dispatchers.IO) {
        val src = File(root(), rel)
        require(src.isFile) { "Файл не найден: $rel" }
        val dir = File(app.cacheDir, "updates/video")
        dir.deleteRecursively()
        dir.mkdirs()
        val copy = File(dir, src.name)
        src.copyTo(copy, overwrite = true)
        FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", copy)
    }

    /** Сохраняет в галерею: Movies/PocketCode (Android 10 и новее). */
    suspend fun saveToGallery(rel: String): String = withContext(Dispatchers.IO) {
        val src = File(root(), rel)
        require(src.isFile) { "Файл не найден: $rel" }
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("Сохранение в галерею есть с Android 10. Нажмите «Поделиться».")
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PocketCode")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = app.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Не удалось создать файл в галерее")
        try {
            val os = cr.openOutputStream(uri) ?: throw IllegalStateException("Не удалось записать в галерею")
            os.use { o -> src.inputStream().use { it.copyTo(o) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
        } catch (e: Throwable) {
            cr.delete(uri, null, null)
            throw e
        }
        "Movies/PocketCode/${src.name}"
    }

    // ---------- запуск ffmpeg ----------

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun f(d: Double) = String.format(Locale.US, "%.3f", d)

    private fun progressSeconds(line: String): Double? {
        if (!line.startsWith("out_time_us=") && !line.startsWith("out_time_ms=")) return null
        val v = line.substringAfter('=').trim().toLongOrNull() ?: return null
        return if (v >= 0) v / 1_000_000.0 else null
    }

    private fun isProgressLine(line: String) = PROGRESS_KEYS.any { line.startsWith(it) }

    /** Запускает команду в среде Linux, отдаёт строки по мере вывода, возвращает код выхода. */
    private suspend fun runStreaming(cmd: String, timeoutSec: Long, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val p = shell().builder(proxyEnv() + cmd, root()).redirectErrorStream(true).start()
        proc = p
        try {
            p.outputStream.close()
            try {
                withTimeout(timeoutSec * 1000) {
                    val reader = p.inputStream.bufferedReader(Charsets.UTF_8)
                    while (true) {
                        val line = runInterruptible { reader.readLine() } ?: break
                        onLine(line)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                // Таймаут не должен выглядеть как «Остановлено» пользователем
                throw IllegalStateException("Команда не уложилась в ${timeoutSec / 60} мин и была остановлена")
            }
            p.waitFor()
        } finally {
            if (p.isAlive) p.destroyForcibly()
            proc = null
        }
    }

    private companion object {
        const val BATCH = 30
        val VIDEO_EXT = setOf("mp4", "mov", "mkv", "webm", "m4v", "3gp", "avi")
        val DURATION = Regex("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)")
        val ROTATION = Regex("rotation of (-?\\d+(?:\\.\\d+)?) degrees")
        val ROTATE_TAG = Regex("rotate\\s*:\\s*(-?\\d+)")
        const val CHUNK = 600
        const val FONT_DIR = "/usr/share/fonts/dejavu"
        val VIDEO_FPS = Regex("Video:[^\\n]*?,\\s*(\\d+(?:\\.\\d+)?)\\s*fps")
        val VIDEO_SIZE = Regex("Video:.*?,\\s*(\\d{2,5})x(\\d{2,5})")
        val SIL_START = Regex("silence_start:\\s*(-?\\d+(?:\\.\\d+)?)")
        val SIL_END = Regex("silence_end:\\s*(-?\\d+(?:\\.\\d+)?)")
        val PROGRESS_KEYS = listOf(
            "frame=", "fps=", "stream_", "bitrate=", "total_size=", "out_time", "dup_frames=", "drop_frames=", "speed=", "progress=",
        )
    }
}
