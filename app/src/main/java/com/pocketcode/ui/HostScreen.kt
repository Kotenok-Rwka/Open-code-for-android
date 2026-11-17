package com.pocketcode.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcode.host.HostApp
import com.pocketcode.host.HostLogic
import kotlinx.coroutines.delay
import java.io.File

/** Хостинг проектов: запуск ботов и серверов на телефоне фоном или пока приложение открыто, в заданное время. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(vm: ChatController, onBack: () -> Unit) {
    val host = vm.hostManager
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<HostApp?>(null) }
    var logOf by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<HostApp?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }
    // заготовка из терминала или кнопки «Добавить проект»
    LaunchedEffect(host.draft) {
        host.draft?.let {
            editing = it
            host.draft = null
        }
    }
    tick

    Scaffold(topBar = {
        AppTopBar(
            title = { Text("Хостинг проектов", maxLines = 1) },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.backIcon(), contentDescription = "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Здесь можно оставить на телефоне работать свои проекты: ботов, серверы. Они запускаются в той же среде, что и терминал (Linux, если он включён), в папке проекта. " +
                    "Фоном проект работает, пока висит уведомление службы; на некоторых телефонах нужно разрешить приложению работу без ограничений батареи.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { vm.hostDraftFromCurrent() }, modifier = Modifier.fillMaxWidth()) { Text("Добавить проект") }
            if (host.apps.isEmpty()) {
                Text("Пока ничего не добавлено.", style = MaterialTheme.typography.bodySmall)
            }
            host.apps.forEach { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall)
                        Text(host.statusText(a), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Text(a.project + " · " + a.command, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Text(if (a.background) "Работает фоном" else "Только пока приложение открыто", style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (a.useWindow) "Время: с ${HostLogic.fmtTime(a.startMin)} до ${HostLogic.fmtTime(a.endMin)}" else "Круглосуточно",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = a.enabled, onCheckedChange = { host.setEnabled(a.id, it) })
                            Text("Держать запущенным по расписанию", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (host.isRunning(a.id)) {
                                OutlinedButton(onClick = { host.stop(a.id) }) { Text("Остановить") }
                            } else {
                                Button(onClick = { host.runNow(a.id) }) { Text("Запустить сейчас") }
                            }
                            TextButton(onClick = { logOf = if (logOf == a.id) null else a.id }) { Text("Лог") }
                            TextButton(onClick = { editing = a }) { Text("Изменить") }
                            TextButton(onClick = { deleting = a }) { Text("Удалить") }
                        }
                        if (logOf == a.id) {
                            val log = host.log(a.id)
                            Text(
                                log.ifEmpty { "(пока пусто)" },
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { initial ->
        HostEditor(
            vm = vm,
            initial = initial,
            onSave = { host.save(it); editing = null },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Удалить проект из хостинга?") },
            text = { Text(a.name) },
            confirmButton = { TextButton(onClick = { host.remove(a.id); deleting = null }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostEditor(vm: ChatController, initial: HostApp, onSave: (HostApp) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var project by remember { mutableStateOf(initial.project) }
    var command by remember { mutableStateOf(initial.command) }
    var background by remember { mutableStateOf(initial.background) }
    var useWindow by remember { mutableStateOf(initial.useWindow) }
    var start by remember { mutableStateOf(HostLogic.fmtTime(initial.startMin)) }
    var end by remember { mutableStateOf(HostLogic.fmtTime(initial.endMin)) }
    var restart by remember { mutableStateOf(initial.autoRestart) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Проект для запуска") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("Название") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text("Проект (папка)", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.projects.ifEmpty { listOf(initial.project) }.forEach { p ->
                        FilterChip(selected = project == p, onClick = { project = p }, label = { Text(p) })
                    }
                }
                OutlinedTextField(
                    value = command, onValueChange = { command = it }, label = { Text("Команда запуска") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = {
                    val dir = vm.hostProjectDir(project)
                    val files = dir.list()?.toSet() ?: emptySet()
                    val pkg = File(dir, "package.json").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrDefault("") }.orEmpty()
                    val guess = HostLogic.guessCommand(files, HostLogic.hasNpmStart(pkg), vm.termInLinux)
                    if (guess == null) {
                        error = "Не нашёл bot.py, main.py, package.json и похожих файлов: введи команду сам."
                    } else {
                        command = guess
                        error = ""
                    }
                }) { Text("Подобрать команду по файлам проекта") }
                Text("Как запускать", style = MaterialTheme.typography.labelMedium)
                FilterChip(
                    selected = background, onClick = { background = true },
                    label = { Text("Фоном (служба с уведомлением)") }, modifier = Modifier.fillMaxWidth(),
                )
                FilterChip(
                    selected = !background, onClick = { background = false },
                    label = { Text("Только пока приложение открыто") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = useWindow, onCheckedChange = { useWindow = it })
                    Text("Запускать только в заданное время", style = MaterialTheme.typography.bodySmall)
                }
                if (useWindow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = start, onValueChange = { start = it }, label = { Text("С (чч:мм)") },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = end, onValueChange = { end = it }, label = { Text("До (чч:мм)") },
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                    }
                    Text("Окно может переходить через полночь, например с 22:00 до 06:00.", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = restart, onCheckedChange = { restart = it })
                    Text("Перезапускать, если упал", style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = HostLogic.parseTime(start)
                val e = HostLogic.parseTime(end)
                when {
                    name.isBlank() -> { error = "Укажи название" }
                    project.isBlank() -> { error = "Выбери проект" }
                    command.isBlank() -> { error = "Укажи команду запуска" }
                    useWindow && (s == null || e == null) -> { error = "Время нужно в виде чч:мм, например 22:30" }
                    else -> {
                        onSave(
                            initial.copy(
                                name = name.trim(), project = project, command = command.trim(),
                                background = background, useWindow = useWindow,
                                startMin = s ?: initial.startMin, endMin = e ?: initial.endMin,
                                autoRestart = restart,
                            ),
                        )
                    }
                }
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
