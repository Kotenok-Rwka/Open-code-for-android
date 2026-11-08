package com.pocketcode.video

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import com.pocketcode.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketcode.ui.AppIcons
import kotlinx.coroutines.launch

/** Режим «Видео»: выбрать ролик, вырезать паузы, добавить субтитры, сохранить или отправить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(v: VideoPipeline, onBack: () -> Unit, onOpenLinux: () -> Unit, onAsk: (String) -> Unit = {}, gen: VideoGenerator? = null) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) v.pick(uri) }

    // Чувствительность: от -50 дБ (режет только явную тишину) до -25 дБ (режет и тихую речь)
    var noise by rememberSaveable { mutableStateOf(-35f) }
    var minSilence by rememberSaveable { mutableStateOf(0.6) }
    var quality by rememberSaveable { mutableStateOf(VideoQuality.BALANCED) }
    var small by rememberSaveable { mutableStateOf(false) }
    var transition by rememberSaveable { mutableStateOf(Transition.ZOOM) }
    var shorts by rememberSaveable { mutableStateOf(false) }
    var enhance by rememberSaveable { mutableStateOf(false) }
    var blurBg by rememberSaveable { mutableStateOf(true) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var withPauses by rememberSaveable { mutableStateOf(false) }
    var doCut by rememberSaveable { mutableStateOf(true) }
    var doSubs by rememberSaveable { mutableStateOf(false) }
    var subStyle by rememberSaveable { mutableStateOf(SubStyle.CLASSIC) }
    var lang by rememberSaveable { mutableStateOf("") }
    var sttOpen by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var genMode by rememberSaveable { mutableStateOf(false) }
    var presetId by rememberSaveable { mutableStateOf<String?>(null) }
    var finTitle by rememberSaveable { mutableStateOf("") }
    var finSec by rememberSaveable { mutableStateOf(2.5) }
    var finBar by rememberSaveable { mutableStateOf(false) }
    var finSpeed by rememberSaveable { mutableStateOf(1.0) }
    var finVol by rememberSaveable { mutableStateOf(0.25) }
    var finDuck by rememberSaveable { mutableStateOf(true) }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) v.pickMusic(uri) }
    var frame by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    val info = v.info
    androidx.compose.runtime.LaunchedEffect(info?.rel, v.busy) {
        val rel = info?.rel
        if (rel == null) frame = null
        else if (!v.busy) {
            val file = v.frameOf(rel)
            frame = file?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }
        }
    }
    val a = v.analysis
    val opt = VideoOptions(quality, if (small) 720 else 0, transition, shorts, enhance, blurBg)

    fun start(rel: String, cut: Boolean, subs: SubStyle?) {
        note = null
        if (subs != null && !v.speechReady) {
            sttOpen = true
            note = "Для субтитров нужен ключ распознавания речи: введите его в блоке «Распознавание речи» ниже."
            return
        }
        v.produceUi(rel, cut, minSilence, noise.toInt(), opt, subs, lang)
    }

    if (genMode && gen != null) {
        VideoGenScreen(gen, v, onBack = { genMode = false }, onEdit = { rel -> genMode = false; note = null; v.select(rel) })
        return
    }

    val editor = v.textEdit()
    BackHandler(enabled = editing) { editing = false }
    if (editing && editor != null && info != null) {
        TextEditScreen(
            v = v, edit = editor, info = info,
            pauses = a?.takeIf { it.info.rel == info.rel }?.cuts,
            withPauses = withPauses, onWithPauses = { withPauses = it },
            busy = v.busy, onBack = { editing = false },
            onRender = { cuts ->
                editing = false
                note = null
                v.cutRangesUi(info.rel, cuts, opt, if (doSubs) subStyle else null, lang)
            },
        )
        return
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Видео", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!v.ready) {
                SoftCard {
                    Text("Нужна среда Linux", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Монтаж идёт через ffmpeg, а он работает внутри мини-Linux приложения. Установите и включите среду один раз, ffmpeg поставится сам при первом монтаже.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenLinux) { Text("Открыть «Среда Linux»") }
                }
            }

            if (gen != null) {
                SoftCard {
                    Text("Создать видео нейросетью", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Опишите ролик словами, а сервис по вашему ключу (fal.ai, Replicate, Google Gemini) снимет его. Готовое видео можно сразу монтировать.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { genMode = true }) { Text(if (gen.busy) "Видео создаётся…" else "Открыть") }
                }
            }

            // ---------- выбор видео ----------
            SoftCard {
                Text(if (info == null) "1. Выберите видео" else "Видео", style = MaterialTheme.typography.titleMedium)
                if (info != null) {
                    Text(java.io.File(info.rel).name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        buildString {
                            append(fmtTime(info.duration))
                            if (info.width > 0) append(" · ${info.width}×${info.height}")
                            append(" · ${fmtSize(info.bytes)}")
                            if (!info.hasAudio) append(" · без звука")
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Из галереи или файлов. Копия ляжет в папку video/ проекта, оригинал не изменится.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(enabled = v.ready && !v.busy, onClick = { note = null; picker.launch("video/*") }) {
                    Text(if (info == null) "Выбрать видео" else "Другое видео")
                }
                val recent = remember(v.stage, v.resultRel, info?.rel) { v.recent() }.filter { it != info?.rel }
                if (recent.isNotEmpty() && v.ready) {
                    Text("Уже в проекте:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        recent.forEach { rel ->
                            AssistChip(enabled = !v.busy, onClick = { note = null; v.select(rel) }, label = { Text(java.io.File(rel).name.take(26)) })
                        }
                    }
                }
            }

            // ---------- что сделать ----------
            if (info != null && v.ready) {
                SoftCard {
                    Text("2. Что сделать", style = MaterialTheme.typography.titleMedium)

                    Text("Готовые стили монтажа:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        VideoPresets.all.forEach { p ->
                            FilterChip(
                                selected = presetId == p.id, enabled = !v.busy, label = { Text(p.label) },
                                onClick = {
                                    presetId = p.id
                                    doCut = p.cut; minSilence = p.minSilence; noise = p.noiseDb.toFloat()
                                    doSubs = p.subs != null; p.subs?.let { subStyle = it }
                                    transition = p.transition; shorts = p.shorts; blurBg = p.blurBg
                                    enhance = p.enhance; quality = p.quality; small = p.small
                                    if (p.subs != null && !v.speechReady) sttOpen = true
                                },
                            )
                        }
                    }
                    VideoPresets.byId(presetId)?.let { p ->
                        Text(p.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.tags().forEach { tag -> AssistChip(onClick = {}, enabled = false, label = { Text(tag) }) }
                        }
                        Text("Настройки ниже выставлены. Их можно менять вручную.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    ToggleRow("Вырезать паузы", "Убирает молчание и «зависания»", doCut, !v.busy) { doCut = it }
                    if (doCut) {
                        Text("Как сильно резать: ${sensLabel(noise.toInt())} (${noise.toInt()} дБ)", style = MaterialTheme.typography.bodyMedium)
                        Slider(value = noise, onValueChange = { noise = it.toInt().toFloat() }, valueRange = -50f..-25f, enabled = !v.busy)
                        Text(
                            "Вырезается лишнее (тихая речь)? Сдвиньте влево. Паузы остаются (шумная комната)? Вправо.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Какие паузы считать:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.4 to "от 0,4 с", 0.6 to "от 0,6 с", 1.0 to "от 1 с", 1.5 to "от 1,5 с").forEach { (value, label) ->
                                FilterChip(selected = minSilence == value, enabled = !v.busy, onClick = { minSilence = value }, label = { Text(label) })
                            }
                        }
                    }

                    ToggleRow("Субтитры", "Распознаёт речь и вшивает текст в видео", doSubs, !v.busy) { doSubs = it; if (it && !v.speechReady) sttOpen = true }
                    if (doSubs) {
                        Text("Стиль:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SubStyle.values().forEach { st ->
                                FilterChip(selected = subStyle == st, enabled = !v.busy, onClick = { subStyle = st }, label = { Text(st.label) })
                            }
                        }
                        SubtitlePreview(subStyle, frame = frame)
                        Text(subStyle.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Язык речи:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("" to "Авто", "ru" to "Русский", "en" to "English", "uk" to "Українська", "de" to "Deutsch", "es" to "Español").forEach { (code, label) ->
                                FilterChip(selected = lang == code, enabled = !v.busy, onClick = { lang = code }, label = { Text(label) })
                            }
                        }
                        if (lang == "") Text(
                            "«Авто» иногда путает язык в начале ролика. Если знаете язык, выберите его: точность выше.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (doCut || doSubs) {
                        Text("Переходы между кусками:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Transition.values().forEach { t ->
                                FilterChip(selected = transition == t, enabled = !v.busy, onClick = { transition = t }, label = { Text(t.label) })
                            }
                        }
                        TransitionPreview(transition)
                        Text(transition.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (transition.xfade != null) Text(
                            "Наплывы монтируются медленнее обычных склеек. Если эффект не получится, приложение само сделает резкие склейки.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Качество и скорость:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            VideoQuality.values().forEach { q ->
                                FilterChip(selected = quality == q, enabled = !v.busy, onClick = { quality = q }, label = { Text(q.label) })
                            }
                            FilterChip(selected = small, enabled = !v.busy, onClick = { small = !small }, label = { Text("Сжать до 720p") })
                            FilterChip(selected = shorts, enabled = !v.busy, onClick = { shorts = !shorts }, label = { Text("Shorts 9:16") })
                            FilterChip(selected = enhance, enabled = !v.busy, onClick = { enhance = !enhance }, label = { Text("Улучшить звук") })
                        }
                    }

                    if (shorts) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = blurBg, enabled = !v.busy, onClick = { blurBg = true }, label = { Text("Размытый фон") })
                            FilterChip(selected = !blurBg, enabled = !v.busy, onClick = { blurBg = false }, label = { Text("Обрезка по центру") })
                        }
                        Text("Shorts: вертикальный кадр 9:16. " + if (blurBg) "Видео целиком, по краям размытый фон." else "Кадр обрезается по центру.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (enhance) Text("Звук: уберём шум и гул, выровняем громкость до уровня соцсетей.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(enabled = !v.busy, onClick = {
                        val styleName = if (doSubs) subStyle.name.lowercase() else "pop"
                        onAsk(
                            "Смонтируй видео ${info.rel} как короткий динамичный ролик: распознай речь (video_transcribe), " +
                                "найди и вырежи ошибки, дубли, повторы, слова-паразиты и долгое молчание, оставь лучшие дубли. " +
                                "Потом вызови video_cut_ranges с subtitles=true, style=$styleName, transition=${transition.name.lowercase()}" +
                                (if (shorts) ", shorts=true" + (if (blurBg) ", blur_background=true" else "") else "") + (if (enhance) ", enhance_audio=true" else "") + ". Перед вырезанием коротко перечисли, что убираешь."
                        )
                    }) { Text("Умный монтаж (ИИ в чате)") }
                    Button(
                        enabled = !v.busy && (doCut || doSubs),
                        onClick = { start(info.rel, doCut, if (doSubs) subStyle else null) },
                    ) { Text(if (doCut && doSubs) "Вырезать паузы и добавить субтитры" else if (doSubs) "Добавить субтитры" else "Вырезать паузы") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !v.busy, onClick = { note = null; v.removePauses(info.rel, minSilence, noise.toInt(), opt, false) }) { Text("Показать паузы") }
                        OutlinedButton(enabled = !v.busy, onClick = {
                            note = null
                            if (!v.speechReady) { sttOpen = true; note = "Для текста нужен ключ распознавания речи: введите его ниже." }
                            else v.transcribeUi(info.rel, lang)
                        }) { Text("Получить текст") }
                    }
                }

                // ---------- финальная отделка ----------
                SoftCard {
                    Text("3. Финальная отделка", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Добавьте цепляющий заголовок, полоску прогресса или ускорьте ролик. Лучше делать в конце, на готовом видео: результат ляжет рядом отдельным файлом.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = finTitle, onValueChange = { finTitle = it.take(120) }, enabled = !v.busy, singleLine = false, maxLines = 3,
                        label = { Text("Заголовок в начале (необязательно)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    if (finTitle.isNotBlank()) {
                        Text("Сколько секунд показывать:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1.5, 2.5, 4.0, 6.0).forEach { sec ->
                                FilterChip(selected = finSec == sec, enabled = !v.busy, onClick = { finSec = sec }, label = { Text((if (sec % 1.0 == 0.0) sec.toInt().toString() else sec.toString().replace('.', ',')) + " с") })
                            }
                        }
                    }
                    ToggleRow("Полоска прогресса", "Жёлтая линия внизу растёт до конца ролика: зрители чаще досматривают", finBar, !v.busy) { finBar = it }
                    val music = v.musicRel
                    Text("Фоновая музыка:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(enabled = !v.busy, onClick = { note = null; musicPicker.launch("audio/*") }) { Text(if (music == null) "Выбрать музыку" else "Другая музыка") }
                        if (music != null) TextButton(enabled = !v.busy, onClick = { v.clearMusic() }) { Text("Убрать") }
                    }
                    if (music != null) {
                        Text(java.io.File(music).name, style = MaterialTheme.typography.bodySmall)
                        Text("Громкость музыки:", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0.1 to "10%", 0.25 to "25%", 0.4 to "40%", 0.6 to "60%").forEach { (value, label) ->
                                FilterChip(selected = finVol == value, enabled = !v.busy, onClick = { finVol = value }, label = { Text(label) })
                            }
                        }
                        ToggleRow("Приглушать, когда говорят", "Музыка тише на речи и громче в паузах", finDuck, !v.busy) { finDuck = it }
                    }
                    Text("Скорость:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1.0 to "Обычная", 1.1 to "×1,1", 1.25 to "×1,25", 1.5 to "×1,5", 2.0 to "×2").forEach { (value, label) ->
                            FilterChip(selected = finSpeed == value, enabled = !v.busy, onClick = { finSpeed = value }, label = { Text(label) })
                        }
                    }
                    val fo = FinishOptions(finTitle, finSec, finBar, finSpeed, quality, v.musicRel.orEmpty(), finVol, finDuck)
                    if (!fo.isEmpty) Text(
                        "Получится ${fmtTime(Finish.outDuration(info.duration, fo))} вместо ${fmtTime(info.duration)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(enabled = !v.busy && !fo.isEmpty, onClick = {
                        note = null
                        v.finishUi(info.rel, fo)
                    }) { Text("Применить отделку") }
                    OutlinedButton(enabled = !v.busy, onClick = {
                        onAsk(
                            "Придумай цепляющий заголовок-хук для видео ${info.rel}: распознай речь (video_transcribe), пойми, о чём ролик, и предложи 3 варианта " +
                                "до 60 знаков (интрига, польза или вопрос, без кликбейта-обмана). Лучший вариант сразу наложи через video_finish (title=…, title_seconds=2.5" +
                                (if (finBar) ", progress_bar=true" else "") + "), остальные два перечисли в ответе."
                        )
                    }) { Text("Придумать заголовок (ИИ в чате)") }
                }

                // ---------- распознавание речи ----------
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Распознавание речи", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    v.sttKey.isNotBlank() -> "Ключ задан · ${v.sttModel}"
                                    v.sttKeySource.isNotBlank() -> "Ключ из профиля «${v.sttKeySource}» · ${v.sttModel}"
                                    else -> "Ключ не задан. Нужен для субтитров и текста."
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { sttOpen = !sttOpen }) { Text(if (sttOpen) "Свернуть" else "Настроить") }
                    }
                    if (sttOpen) {
                        var url by remember(v.sttUrl) { mutableStateOf(v.sttUrl) }
                        var model by remember(v.sttModel) { mutableStateOf(v.sttModel) }
                        var key by remember(v.sttKey) { mutableStateOf(v.sttKey) }
                        Text(
                            "Подойдёт любой сервис с OpenAI-совместимым распознаванием. Бесплатный вариант: Groq (около 8 часов звука в день). Звук отправляется выбранному сервису.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = { url = DEFAULT_STT_URL; model = DEFAULT_STT_MODEL }, label = { Text("Groq (бесплатно)") })
                            AssistChip(onClick = { url = "https://api.openai.com/v1"; model = "whisper-1" }, label = { Text("OpenAI") })
                            AssistChip(onClick = { runCatching { uriHandler.openUri("https://console.groq.com/keys") } }, label = { Text("Получить ключ Groq") })
                        }
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Адрес API") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Модель") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = key, onValueChange = { key = it }, label = { Text("Ключ API") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { v.saveSpeech(url, model, key) }) { Text("Сохранить") }
                            OutlinedButton(onClick = { v.saveSpeech(url, model, key); v.testSpeech() }) { Text("Проверить") }
                        }
                        if (v.speechTest.isNotBlank()) Text(v.speechTest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Сервис недоступен из вашей сети? Включите VPN или укажите прокси в настройках приложения.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---------- ход работы ----------
            if (v.busy) {
                SoftCard {
                    Text(
                        when (v.stage) {
                            VideoStage.IMPORT -> "Копирование"
                            VideoStage.TOOLS -> "Подготовка"
                            VideoStage.ANALYZE -> "Анализ звука"
                            VideoStage.TRANSCRIBE -> "Распознавание речи"
                            else -> "Монтаж"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (v.progress > 0f) LinearProgressIndicator(progress = { v.progress }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(v.message, style = MaterialTheme.typography.bodyMedium)
                    if (v.speed.isNotBlank()) Text("Скорость ${v.speed} от реального времени", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Можно свернуть приложение: работа продолжится, придёт уведомление.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { v.cancel() }) { Text("Остановить") }
                }
            }

            v.error?.let { e ->
                SoftCard(error = true) {
                    Text("Не получилось", style = MaterialTheme.typography.titleMedium)
                    Text(e, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { v.dismiss() }) { Text("Закрыть") }
                }
            }

            // ---------- что вырежем ----------
            if (a != null && info != null && a.info.rel == info.rel && !v.busy) {
                SoftCard {
                    Text("Что вырезается", style = MaterialTheme.typography.titleMedium)
                    Timeline(a)
                    Text(
                        if (a.cuts.isEmpty()) "Пауз не найдено. Сдвиньте ползунок вправо или выберите более короткие паузы."
                        else "Пауз: ${a.cuts.size}. Уйдёт ${fmtTime(a.cutSeconds)}, останется ${fmtTime(a.resultSeconds)} из ${fmtTime(a.info.duration)}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (a.cuts.isNotEmpty() && v.resultRel == null) {
                        Text("Красное: вырезается. Серое: остаётся.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { doCut = true; start(info.rel, true, if (doSubs) subStyle else null) }) { Text(if (doSubs) "Смонтировать с субтитрами" else "Смонтировать") }
                    }
                }
            }

            // ---------- текст ----------
            val tr = v.transcript
            if (tr != null && info != null && v.transcriptRel == info.rel && !v.busy) {
                SoftCard {
                    Text("Текст", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            tr.timedText(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    Button(onClick = { note = null; editing = true }) { Text("Монтаж по тексту: вычеркнуть слова") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(tr.plainText())); note = "Текст скопирован" }) { Text("Копировать") }
                        OutlinedButton(onClick = {
                            note = runCatching { "Сохранено: " + v.saveTranscriptFiles(info.rel) }.getOrElse { "Не удалось сохранить: ${it.message}" }
                        }) { Text("Сохранить .txt и .srt") }
                    }
                }
            }

            // ---------- результат ----------
            val res = v.resultRel
            if (res != null && v.stage == VideoStage.DONE) {
                SoftCard {
                    Text("Готово", style = MaterialTheme.typography.titleMedium)
                    Text(java.io.File(res).name, style = MaterialTheme.typography.bodyLarge)
                    Text(v.resultNote, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                runCatching {
                                    val uri = v.shareUri(res)
                                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(view, "Открыть в…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { note = "Не удалось открыть: ${it.message}" }
                            }
                        }) { Text("Смотреть") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { v.saveToGallery(res) }
                                    .onSuccess { note = "Сохранено в галерею: $it" }
                                    .onFailure { note = "Не удалось сохранить: ${it.message}" }
                            }
                        }) { Text("В галерею") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val uri = v.shareUri(res)
                                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(send, "Отправить видео").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { note = "Не удалось отправить: ${it.message}" }
                            }
                        }) { Text("Поделиться") }
                    }
                    OutlinedButton(enabled = !v.busy, onClick = { note = null; v.select(res) }) { Text("Отделать этот ролик") }
                    Text("Файл лежит в проекте: $res", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            Text(
                "Агент в чате умеет то же: «вырежи паузы из video/clip.mp4», «добавь субтитры». Можно сказать и так: «вырежи из видео все ошибки и повторы, субтитры в стиле неон».",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

private fun sensLabel(db: Int) = when {
    db <= -43 -> "осторожно"
    db <= -32 -> "обычно"
    else -> "сильно"
}

@Composable
internal fun ToggleRow(title: String, about: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
internal fun SoftCard(error: Boolean = false, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Полоса по длине видео: серое остаётся, красное вырезается. */
@Composable
private fun Timeline(a: SilenceAnalysis) {
    val keep = MaterialTheme.colorScheme.outline
    val cut = Color(0xFFE5484D)
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val total = a.info.duration.coerceAtLeast(0.1)
        drawRect(keep, Offset.Zero, size)
        for (c in a.cuts) {
            val x0 = (c.start / total * size.width).toFloat()
            val w = (c.length / total * size.width).toFloat().coerceAtLeast(2f)
            drawRect(cut, Offset(x0, 0f), Size(w, size.height))
        }
    }
}
