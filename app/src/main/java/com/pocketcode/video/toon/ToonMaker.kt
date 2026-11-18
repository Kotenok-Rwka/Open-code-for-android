package com.pocketcode.video.toon

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CancellationException

enum class ToonVoiceMode(val label: String) { SYSTEM("Встроенный голос (без интернета)"), CLOUD("Нейросеть по ключу"), SILENT("Без озвучки (быстрый показ)") }

enum class ToonStage { IDLE, VOICE, DRAW, DONE, ERROR }

const val TOON_DEFAULT_URL = "https://api.openai.com/v1"
const val TOON_DEFAULT_MODEL = "tts-1"
const val TOON_DEFAULT_VOICES = "alloy, nova, onyx, shimmer"

const val TOON_SAMPLE = """Название: Мотель на трассе
Сцена: ночь
Рассказчик: Ночью на заправке остановилась машина.
Маша: Нам нужен номер до утра.
Админ: Свободен только один. Но в нём живёт призрак.
Маша: Беру! || Бер+у!
Выбор: Что делает Маша? | Остаётся | Уезжает | Будит друга
Призыв: Пиши свой выбор в комментариях"""

/** Делает мультик: сценарий → озвучка → кадры → MP4. Результат лежит в папке video проекта. */
class ToonMaker(
    private val app: Application,
    private val root: () -> File,
    private val ttsEngine: () -> String,
    private val get: (String) -> String?,
    private val put: (String, String) -> Unit,
    private val secret: (String) -> Unit,
    private val fallbackKey: (String) -> Pair<String, String>?,
    private val proxy: () -> String,
    private val keepAlive: () -> Unit,
    private val release: () -> Unit,
) {
    var stage by mutableStateOf(ToonStage.IDLE)
        private set
    var message by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var resultRel by mutableStateOf<String?>(null)
        private set

    var script by mutableStateOf(get("toonScript") ?: TOON_SAMPLE)
    var dict by mutableStateOf(get("toonDict") ?: "")
    var autoSpeech by mutableStateOf((get("toonAuto") ?: "1") == "1")
    var mode by mutableStateOf(ToonVoiceMode.values().firstOrNull { it.name == get("toonMode") } ?: ToonVoiceMode.SYSTEM)
    var url by mutableStateOf(get("toonUrl") ?: TOON_DEFAULT_URL)
    var model by mutableStateOf(get("toonModel") ?: TOON_DEFAULT_MODEL)
    var key by mutableStateOf(get("toonKey") ?: "")
    var voices by mutableStateOf(get("toonVoices") ?: TOON_DEFAULT_VOICES)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var cancelFlag = false

    val busy: Boolean get() = stage == ToonStage.VOICE || stage == ToonStage.DRAW

    /** Ключ из профиля API с тем же адресом, если своего нет. */
    val keySource: String get() = if (key.isNotBlank()) "" else fallbackKey(url)?.second.orEmpty()

    fun saveSettings() {
        put("toonScript", script)
        put("toonDict", dict)
        put("toonAuto", if (autoSpeech) "1" else "0")
        put("toonMode", mode.name)
        put("toonUrl", url.trim())
        put("toonModel", model.trim())
        put("toonKey", key.trim())
        put("toonVoices", voices.trim())
        if (key.isNotBlank()) secret(key.trim())
    }

    fun estimate(): String? = runCatching { ToonScript.estimateSeconds(ToonScript.parse(script)) }.getOrNull()?.let { "≈ ${it.toInt()} с" }

    fun dismiss() {
        if (!busy) {
            stage = ToonStage.IDLE
            error = null
            message = ""
        }
    }

    fun cancel() {
        cancelFlag = true
        job?.cancel()
    }

    fun start() {
        if (busy) return
        saveSettings()
        cancelFlag = false
        error = null
        resultRel = null
        progress = 0f
        stage = ToonStage.VOICE
        message = "Разбираю сценарий"
        keepAlive()
        job = scope.launch {
            try {
                val rel = make()
                resultRel = rel
                stage = ToonStage.DONE
                message = "Готово"
            } catch (e: CancellationException) {
                stage = ToonStage.IDLE
                message = "Отменено"
            } catch (e: Throwable) {
                error = e.message ?: e.javaClass.simpleName
                stage = ToonStage.ERROR
            } finally {
                release()
            }
        }
    }

    private fun voiceFor(): ToonVoice? = when (mode) {
        ToonVoiceMode.SILENT -> null
        ToonVoiceMode.SYSTEM -> SystemToonVoice(app, ttsEngine(), app.cacheDir)
        ToonVoiceMode.CLOUD -> {
            val k = key.trim().ifBlank { fallbackKey(url)?.first.orEmpty() }
            require(k.isNotBlank()) { "Для озвучки нейросетью нужен ключ: впишите его в настройках озвучки на этом экране." }
            secret(k)
            CloudToonVoice(ToonVoiceConfig(url, model.trim().ifBlank { TOON_DEFAULT_MODEL }, k, voices.split(',').map { it.trim() }.filter { it.isNotEmpty() }), proxy())
        }
    }

    private suspend fun make(): String {
        val sc = ToonScript.parse(script)
        val dictList = Pronounce.parseDict(dict)
        val heroes = sc.speakers.take(4)
        val voice = voiceFor()
        val fps = 24
        try {
            val texts = sc.lines.map { stripStressMarks(Pronounce.forVoice(it.sub, it.say, dictList, autoSpeech)) }
            val pcms = ArrayList<Pcm?>()
            for ((i, l) in sc.lines.withIndex()) {
                if (cancelFlag) throw CancellationException()
                message = "Озвучиваю ${i + 1} из ${sc.lines.size}"
                progress = i.toFloat() / (sc.lines.size + 1)
                val idx = if (l.narrator) 4 else heroes.indexOf(l.speaker).coerceAtLeast(0)
                pcms += voice?.speak(texts[i], idx)
            }
            val choiceText = sc.choice?.let { c ->
                stripStressMarks(Pronounce.forVoice(c.question + ". " + c.options.joinToString(". "), null, dictList, autoSpeech))
            } ?: ""
            val choicePcm = if (voice != null && sc.choice != null) voice.speak(choiceText, 4) else null
            val plan = ToonTimeline.build(sc, pcms, texts, choicePcm, choiceText)
            val frames = plan.frames(fps)

            // подготовка: рот и субтитры
            val tracks = plan.clips.map { c ->
                val n = kotlin.math.ceil((c.end - c.start) * fps).toInt().coerceAtLeast(1)
                val env = c.audio?.let { Wav.envelope(it, fps, n) } ?: Lipsync.syntheticEnvelope(n, fps)
                Lipsync.track(env, c.voiceText)
            }
            val subs = plan.clips.flatMap { c -> ToonSubs.timed(ToonSubs.chunks(c.line.sub), c.start, c.end) }
            val draw = ToonDraw()
            val scene = sc.scene ?: ToonScene.CITY

            stage = ToonStage.DRAW
            message = "Рисую кадры"
            val cache = File(app.cacheDir, "toon").apply { mkdirs() }
            val vTmp = File(cache, "v.mp4")
            val aTmp = File(cache, "a.m4a")
            vTmp.delete(); aTmp.delete()
            val enc = ToonEncoder(draw.w, draw.h, fps)
            enc.encodeVideo(
                vTmp, frames,
                draw = { n, canvas ->
                    val t = n.toDouble() / fps
                    var speaker: String? = null
                    var open = 0f
                    var width = 0f
                    for ((ci, c) in plan.clips.withIndex()) {
                        if (t >= c.start && t < c.end) {
                            val k = tracks[ci]
                            val li = ((t - c.start) * fps).toInt().coerceIn(0, k.open.size - 1)
                            open = k.open[li]
                            width = k.width[li]
                            speaker = if (c.line.narrator) null else c.line.speaker
                            break
                        }
                    }
                    val sub = subs.firstOrNull { t >= it.start && t < it.end }?.text.orEmpty()
                    val ca = if (plan.choiceStart >= 0 && t >= plan.choiceStart) (t - plan.choiceStart).toFloat() else -1f
                    draw.draw(canvas, ToonFrame(t, scene, sc.title, heroes, speaker, open, width, sub, sc.choice, ca, sc.cta))
                },
                progress = { p -> progress = 0.3f + p * 0.6f },
                cancelled = { cancelFlag },
            )
            val hasAudio = pcms.any { it != null }
            if (hasAudio) {
                message = "Кодирую звук"
                progress = 0.92f
                enc.encodeAudio(aTmp, plan.mix, ToonTimeline.RATE) { cancelFlag }
            }
            message = "Собираю файл"
            progress = 0.96f
            val dir = File(root(), "video").apply { mkdirs() }
            val name = "toon_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            val out = File(dir, name)
            enc.mux(vTmp, if (hasAudio) aTmp else null, out)
            vTmp.delete(); aTmp.delete()
            progress = 1f
            return "video/$name"
        } finally {
            voice?.close()
        }
    }
}
