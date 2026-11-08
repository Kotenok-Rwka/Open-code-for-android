package com.pocketcode.video

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketcode.ui.AppIcons
import com.pocketcode.ui.Text
import kotlinx.coroutines.launch

/** Экран «Создать видео нейросетью»: ключ сервиса, описание, формат и результат. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoGenScreen(g: VideoGenerator, v: VideoPipeline, onBack: () -> Unit, onEdit: (String) -> Unit, onAsk: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    BackHandler { onBack() }

    var prompt by rememberSaveable { mutableStateOf("") }
    var aspect by rememberSaveable { mutableStateOf("16:9") }
    var duration by rememberSaveable { mutableStateOf(5) }
    var resolution by rememberSaveable { mutableStateOf("") }
    var audio by rememberSaveable { mutableStateOf(true) }
    var extra by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf<String?>(null) }

    val store = remember { VideoStore(PrefsKeyValue(ctx.getSharedPreferences("video_ui", android.content.Context.MODE_PRIVATE))) }
    var history by remember { mutableStateOf(store.history()) }
    val s = g.settings
    val preset = g.preset

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Создать видео по API", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SoftCard {
                Text("1. Сервис и ключ", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Нужен ваш собственный ключ одного из сервисов. Видео создаётся на их серверах и оплачивается у них, приложение ничего не берёт.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GenProvider.values().forEach { p ->
                        FilterChip(selected = s.provider == p, enabled = !g.busy, onClick = { g.update(s.copy(provider = p, model = "")) }, label = { Text(p.label) })
                    }
                }
                Text(s.provider.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = g.key, onValueChange = { g.update(s.copy(keys = s.keys + (s.provider to it.trim()))) },
                    label = { Text("Ключ ${s.provider.label}") }, singleLine = true, enabled = !g.busy,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !g.busy && g.key.isNotBlank(), onClick = { g.testUi() }) { Text("Проверить ключ") }
                    TextButton(onClick = { runCatching { uriHandler.openUri(s.provider.keyPage) } }) { Text("Где взять ключ") }
                }
                if (g.test.isNotBlank()) Text(g.test, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            SoftCard {
                Text("2. Модель", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GenPresets.forProvider(s.provider).forEach { m ->
                        FilterChip(selected = preset.model == m.model, enabled = !g.busy, onClick = { g.update(s.copy(model = m.model)) }, label = { Text(m.label) })
                    }
                }
                Text(preset.about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val isListed = GenPresets.forProvider(s.provider).any { it.model == preset.model }
                OutlinedTextField(
                    value = if (isListed) "" else s.model, onValueChange = { g.update(s.copy(model = it.trim())) },
                    label = { Text("Или своя модель (название у сервиса)") }, singleLine = true, enabled = !g.busy, modifier = Modifier.fillMaxWidth(),
                )
            }

            SoftCard {
                Text("3. Что снять", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = prompt, onValueChange = { prompt = it }, enabled = !g.busy, minLines = 3, maxLines = 8,
                    label = { Text("Опишите видео: что в кадре, как движется камера, какой свет") }, modifier = Modifier.fillMaxWidth(),
                )
                if (history.isNotEmpty() && !g.busy) {
                    Text("Недавние запросы:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        history.forEach { h ->
                            AssistChip(onClick = {
                                prompt = h.prompt; aspect = h.aspect; duration = h.duration; resolution = h.resolution; audio = h.audio
                                GenProvider.values().firstOrNull { it.name == h.provider }?.let { p -> g.update(g.settings.copy(provider = p, model = h.model)) }
                            }, label = { Text(h.prompt.take(24) + if (h.prompt.length > 24) "…" else "") })
                        }
                    }
                }
                if (prompt.isBlank() && !g.busy) {
                    Text("Идеи:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GEN_IDEAS.forEach { (title, idea) -> AssistChip(onClick = { prompt = idea }, label = { Text(title) }) }
                    }
                }
                if (preset.aspects.isNotEmpty()) {
                    Text("Формат кадра:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        preset.aspects.forEach { a ->
                            val label = when (a) { "16:9" -> "16:9 горизонтальное"; "9:16" -> "9:16 вертикальное"; "1:1" -> "1:1 квадрат"; else -> a }
                            FilterChip(selected = (aspect.takeIf { it in preset.aspects } ?: preset.aspects.first()) == a, enabled = !g.busy, onClick = { aspect = a }, label = { Text(label) })
                        }
                    }
                }
                if (preset.durations.isNotEmpty()) {
                    Text("Длина:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        preset.durations.forEach { d ->
                            FilterChip(selected = (duration.takeIf { it in preset.durations } ?: preset.defaultDuration) == d, enabled = !g.busy, onClick = { duration = d }, label = { Text("$d с") })
                        }
                    }
                }
                if (preset.resolutions.isNotEmpty()) {
                    Text("Качество:", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        preset.resolutions.forEach { r ->
                            FilterChip(selected = (resolution.takeIf { it in preset.resolutions } ?: preset.defaultResolution) == r, enabled = !g.busy, onClick = { resolution = r }, label = { Text(r) })
                        }
                    }
                }
                if (preset.audioKey != null) ToggleRow("Со звуком", "Модель сама добавит звуки и речь (может стоить дороже)", audio, !g.busy) { audio = it }
                OutlinedTextField(
                    value = extra, onValueChange = { extra = it }, enabled = !g.busy, minLines = 1, maxLines = 4,
                    label = { Text("Доп. параметры модели, JSON (необязательно)") }, modifier = Modifier.fillMaxWidth(),
                )
                if (g.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(g.status, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Обычно 1–5 минут. Можно свернуть приложение: создание продолжится.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { g.cancel() }) { Text("Остановить") }
                } else {
                    Button(enabled = prompt.isNotBlank(), onClick = {
                        note = null
                        history = store.addHistory(GenHistoryItem(prompt.trim(), s.provider.name, preset.model, aspect, duration, resolution, audio))
                        g.generateUi(GenRequest(prompt, aspect, duration, resolution, audio, extra))
                    }) { Text("Создать видео") }
                    OutlinedButton(enabled = prompt.isNotBlank() && g.key.isNotBlank(), onClick = {
                        onAsk(
                            "Сделай готовый ролик на тему: «${prompt.trim()}». Придумай сценарий из 3–4 сцен по 5–8 секунд с единым героем и стилем, для каждой сцены напиши подробное описание " +
                                "(кто в кадре, действие, камера, свет) и создай её через video_generate (aspect_ratio=$aspect). Потом склей сцены через video_concat, " +
                                "а в конце добавь заголовок-хук через video_finish. Перед созданием коротко перечисли сцены: генерация платная."
                        )
                    }) { Text("Идея в готовый ролик (ИИ в чате)") }
                }
            }

            g.error?.let { err ->
                SoftCard(error = true) {
                    Text("Не получилось", style = MaterialTheme.typography.titleMedium)
                    Text(err, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { g.dismiss() }) { Text("ОК") }
                }
            }

            val res = g.resultRel
            if (res != null && !g.busy) {
                SoftCard {
                    Text("Готово", style = MaterialTheme.typography.titleMedium)
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
                    if (v.ready) OutlinedButton(onClick = { onEdit(res) }) { Text("Монтировать дальше") }
                    Text("Файл лежит в проекте: $res", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

private val GEN_IDEAS = listOf(
    "Кот в неоновом Токио" to "Рыжий кот в очках идёт по ночному Токио под неоновым дождём, камера плавно едет следом",
    "Горное озеро с дрона" to "Дрон пролетает над туманным горным озером на рассвете, тёплый свет, кинематографичный кадр",
    "Кофе и снег" to "Крупный план: чашка кофе, пар медленно поднимается, за окном идёт снег",
    "Космонавт в грибном лесу" to "Космонавт гуляет по лесу из гигантских грибов, мягкий фиолетовый свет, замедленная съёмка",
)
