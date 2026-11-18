package com.pocketcode.video.toon

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketcode.ui.AppTopBar
import com.pocketcode.ui.Text
import com.pocketcode.ui.backIcon
import com.pocketcode.video.SoftCard
import kotlinx.coroutines.launch

/** Режим «Мультики»: пишете сценарий, приложение рисует героев, озвучивает и собирает вертикальный ролик. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToonScreen(
    v: ToonMaker,
    share: suspend (String) -> Uri,
    toGallery: suspend (String) -> String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { v.saveSettings() } }

    Scaffold(topBar = {
        AppTopBar(
            title = { Text("Мультики для TikTok", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(backIcon(), contentDescription = "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val res = v.resultRel
            if (res != null && v.stage == ToonStage.DONE) {
                SoftCard {
                    Text("Ролик готов", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                runCatching {
                                    val uri = share(res)
                                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(view, "Открыть в…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { note = "Не удалось открыть: ${it.message}" }
                            }
                        }) { Text("Смотреть") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching { toGallery(res) }
                                    .onSuccess { note = "Сохранено в галерею: $it" }
                                    .onFailure { note = "Не удалось сохранить: ${it.message}" }
                            }
                        }) { Text("В галерею") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                runCatching {
                                    val uri = share(res)
                                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(Intent.createChooser(send, "Отправить видео").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { note = "Не удалось отправить: ${it.message}" }
                            }
                        }) { Text("Поделиться") }
                    }
                    Text("Файл лежит в проекте: $res", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

            if (v.stage == ToonStage.ERROR) {
                SoftCard(error = true) {
                    Text("Не получилось", style = MaterialTheme.typography.titleMedium)
                    Text(v.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { v.dismiss() }) { Text("Понятно") }
                }
            }

            if (v.busy) {
                SoftCard {
                    Text(v.message, style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { v.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { v.cancel() }) { Text("Отменить") }
                }
            }

            SoftCard {
                Text("Сценарий", style = MaterialTheme.typography.titleMedium)
                Text(
                    "По строке на реплику: «Имя: текст». Строки без имени читает рассказчик. После «||» можно записать, как слово должно звучать (ударение ставится плюсом перед гласной: з+амок). " +
                        "«Сцена:» ночь, море, комната или город. «Выбор: вопрос | вариант | вариант» добавит голосование для зрителей в конце, без него будет просто серия.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = v.script, onValueChange = { v.script = it }, enabled = !v.busy,
                    modifier = Modifier.fillMaxWidth(), minLines = 8, maxLines = 24,
                )
                v.estimate()?.let { Text("Длина ролика: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedButton(enabled = !v.busy, onClick = { v.script = TOON_SAMPLE }) { Text("Вставить пример") }
            }

            SoftCard {
                Text("Произношение", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Числа, время и сокращения прописью", Modifier.weight(1f))
                    Switch(checked = v.autoSpeech, onCheckedChange = { v.autoSpeech = it }, enabled = !v.busy)
                }
                Text(
                    "Выключите, если пишете текст для озвучки сами «как слышится». Субтитры при этом остаются обычными.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = v.dict, onValueChange = { v.dict = it }, enabled = !v.busy,
                    label = { Text("Словарь: слово=как говорить (по строке)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 8,
                )
            }

            SoftCard {
                Text("Голос", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in ToonVoiceMode.values()) {
                        FilterChip(selected = v.mode == m, onClick = { v.mode = m }, enabled = !v.busy, label = { Text(m.label) })
                    }
                }
                if (v.mode == ToonVoiceMode.SYSTEM) {
                    Text(
                        "Используется движок синтеза речи из настроек ассистента. У каждого героя своя высота голоса.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (v.mode == ToonVoiceMode.CLOUD) {
                    OutlinedTextField(value = v.url, onValueChange = { v.url = it }, label = { Text("Адрес (OpenAI-совместимый)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !v.busy)
                    OutlinedTextField(value = v.model, onValueChange = { v.model = it }, label = { Text("Модель озвучки") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !v.busy)
                    OutlinedTextField(value = v.voices, onValueChange = { v.voices = it }, label = { Text("Голоса героев через запятую") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !v.busy)
                    OutlinedTextField(
                        value = v.key, onValueChange = { v.key = it }, label = { Text("Ключ") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), enabled = !v.busy,
                    )
                    if (v.keySource.isNotEmpty()) {
                        Text("Свой ключ не задан: будет взят из профиля «${v.keySource}».", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (v.mode == ToonVoiceMode.SILENT) {
                    Text("Ролик получится без звука, рот героев двигается по ритму. Удобно, чтобы быстро проверить картинку и субтитры.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Button(enabled = !v.busy, onClick = { note = null; v.start() }, modifier = Modifier.fillMaxWidth()) { Text("Сделать мультик") }
            Text(
                "Герои, фон и субтитры рисуются кодом прямо на телефоне, запись идёт встроенным кодеком, среда Linux не нужна. Ролик вертикальный 720×1280, 24 кадра в секунду.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
