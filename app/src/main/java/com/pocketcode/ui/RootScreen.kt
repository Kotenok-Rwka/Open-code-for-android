package com.pocketcode.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import com.pocketcode.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val BRIDGE_WARNING =
    "Агент сможет присылать команды, которые выполнятся на твоём телефоне от root. Неверная команда может стереть данные, сломать систему " +
        "или открыть доступ к аккаунтам. Каждую команду ты подтверждаешь вручную: читай её целиком и, если не уверен, проверь у нейросети."

private const val AUTO_WARNING =
    "Агент сможет выполнять команды и менять файлы без твоего подтверждения. Если файловые действия проверяет нейросеть, она может ошибиться, " +
        "а изменения вне папки проекта откатить нельзя. Проверяющая нейросеть видит только само действие, а не твой чат. " +
        "Опасные операции (системные разделы, прошивка, перезагрузка, массовое удаление) всегда спрашивают тебя. " +
        "Режим выключится сам через 2 часа или при перезапуске приложения."

/** Обратный отсчёт: пока не дошёл до нуля, подтверждение недоступно. */
@Composable
private fun rememberCountdown(key: Any, seconds: Int = 15): Int {
    var left by remember(key) { mutableIntStateOf(seconds) }
    LaunchedEffect(key) { while (left > 0) { delay(1000); left-- } }
    return left
}

@Composable
private fun Option(title: String, about: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** Root и доступ агента к Android: четыре способа, от самого безопасного к самому свободному. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootScreen(vm: ChatController, onBack: () -> Unit, onToChat: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var confirmEnable by remember { mutableStateOf(false) }
    var confirmAuto by remember { mutableStateOf(false) }
    var cmd by remember { mutableStateOf("") }
    var confirmRun by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Root и Android") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Агент работает в песочнице Linux и сам не видит Android: ни системных данных, ни root (uid=0 там ненастоящий). " +
                    "Ниже четыре способа, как дать ему нужные данные: от самого безопасного к самому свободному. Выбирай любой.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Option("Подтверждение root-команд", "Под каждой командой пишется, что она делает. Здесь выбираешь, когда кнопка «Выполнить» станет доступной.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = vm.rootDelay == 0, onClick = { vm.setRootDelayValue(0) }, label = { Text("Без тайм-аута") })
                    androidx.compose.material3.FilterChip(selected = vm.rootDelay == 5, onClick = { vm.setRootDelayValue(5) }, label = { Text("Тайм-аут 5 с") })
                }
                Text(
                    if (vm.rootDelay == 0) "Кнопка подтверждения доступна сразу, окно ждёт тебя сколько нужно."
                    else "Кнопка подтверждения включается через 5 секунд после появления окна, чтобы нельзя было нажать не глядя.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Если команда удаляет, создаёт или перезаписывает системные файлы, меняет разделы или свойства Android, тайм-аут 5 с включается всегда, даже в режиме без тайм-аута.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Option("1. Вручную", "Ты сам выполняешь команду в терминале с root (Termux) и кладёшь вывод в общую папку проекта. Агент читает файл. Приложение ничего не запускает.") {
                val sample = "su -c 'dumpsys audio' > /storage/emulated/0/PocketCode/${vm.currentProject}/audio.txt"
                Text(sample, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(sample)) }) { Text("Скопировать пример") }
                Text("Путь зависит от того, где лежит проект. Если он внутри приложения, используй пункты 2 или 3.", style = MaterialTheme.typography.labelSmall)
            }

            Option("2. Собрать диагностику", "Приложение само снимает фиксированный набор данных через su и кладёт файлы в папку pc-diagnostics проекта. Агент только читает готовые файлы. Список команд виден заранее, лишнего не выполнится.") {
                vm.diagPresets.forEach { (name, cmds) ->
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Text(cmds.joinToString("\n") { "$ " + it.second }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    Button(onClick = { vm.runRootDiag(name) }, enabled = !vm.rootBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (vm.rootBusy) "Подожди…" else "Собрать: $name")
                    }
                }
                vm.rootMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(
                    onClick = {
                        vm.send("Диагностика телефона снята в папку pc-diagnostics проекта. Прочитай файлы там, найди причину моей проблемы и предложи безопасное решение. Ничего не меняй без моего согласия.")
                        onToChat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Передать агенту и вернуться в чат") }
            }

            Option("3. Агент предлагает, ты выполняешь", "Агент пишет команду в чате, ты проверяешь её и выполняешь здесь от root одной кнопкой. Вывод показывается и сохраняется в pc-diagnostics/last-command.txt.") {
                OutlinedTextField(value = cmd, onValueChange = { cmd = it }, label = { Text("Команда для su -c") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { cmd = vm.lastCodeBlock() }) { Text("Из ответа агента") }
                    OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString("Безопасно ли выполнять на Android от root эту команду? Что она сделает?\n\n$cmd")) }, enabled = cmd.isNotBlank()) { Text("Скопировать для проверки") }
                }
                dangerNote(cmd)?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                Button(onClick = { confirmRun = true }, enabled = cmd.isNotBlank() && !vm.rootBusy, modifier = Modifier.fillMaxWidth()) { Text("Выполнить от root") }
                vm.rootOut?.let {
                    Text(it.take(3000), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    OutlinedButton(
                        onClick = { vm.send("Результат команды от root сохранён в pc-diagnostics/last-command.txt. Прочитай его и продолжай."); onToChat() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Передать агенту") }
                }
            }

            Option("4. Мост: агент сам просит команды", "Агент в Linux получает команду pcroot и присылает через неё команды на телефон. Каждая показывается тебе в окне, выполняется только после твоего подтверждения. Самый свободный способ, по умолчанию выключен.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (vm.hostMode == "ask") "Включён: спрашивать каждый раз" else "Выключен")
                    }
                    AppSwitch(
                        checked = vm.hostMode == "ask",
                        onCheckedChange = { if (it) confirmEnable = true else vm.setHostModeValue("off") },
                    )
                }
                Text("⚠ $BRIDGE_WARNING", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Окно подтверждения не закрывается само. Если приложение свёрнуто, придёт уведомление.", style = MaterialTheme.typography.labelSmall)
            }

            Option("5. Авто-режим", "Всё подтверждается само, кроме создания, правки и удаления файлов: их одобряешь ты или нейросеть-проверяющая. Опасные команды всегда спрашивают тебя.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (vm.rootAuto) "Включён (выключится сам через 2 часа)" else "Выключен")
                    }
                    AppSwitch(
                        checked = vm.rootAuto,
                        onCheckedChange = { if (it) confirmAuto = true else vm.setRootAutoValue(false) },
                    )
                }
                Text("⚠ $AUTO_WARNING", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Файловые действия одобряет:", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(selected = vm.rootReviewMode == "me", onClick = { vm.setRootReviewModeValue("me") }, label = { Text("Я сам") })
                    androidx.compose.material3.FilterChip(selected = vm.rootReviewMode == "ai", onClick = { vm.setRootReviewModeValue("ai") }, label = { Text("Нейросеть") })
                }
                if (vm.rootReviewMode == "ai") {
                    Text("Какая нейросеть проверяет (профиль из «Модель и ключи»):", style = MaterialTheme.typography.bodySmall)
                    vm.profiles.forEach { p ->
                        androidx.compose.material3.FilterChip(
                            selected = vm.rootReviewerId == p.id,
                            onClick = { vm.setRootReviewer(p.id) },
                            label = { Text("${p.label} · ${p.model}", maxLines = 1) },
                        )
                    }
                    if (vm.profiles.none { it.id == vm.rootReviewerId }) {
                        Text("Профиль не выбран: пока ты не выберешь, файловые действия будут спрашивать тебя.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Нейросеть может только одобрить. Если она не ответила за 30 секунд, ответила непонятно или сомневается, решаешь ты.", style = MaterialTheme.typography.labelSmall)
                }
                if (vm.rootAutoLog.isNotEmpty()) {
                    Text("Журнал решений", style = MaterialTheme.typography.titleSmall)
                    vm.rootAutoLog.take(10).forEach {
                        Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (confirmAuto) {
        val left = rememberCountdown("auto", 20)
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text("Включить авто-режим?") },
            text = { Text("⚠ $AUTO_WARNING", color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = { confirmAuto = false; vm.setRootAutoValue(true) }, enabled = left == 0) {
                    Text(if (left > 0) "Включить ($left)" else "Включить")
                }
            },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("Отмена") } },
        )
    }

    if (confirmEnable) {
        val left = rememberCountdown("enable")
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Включить мост к Android?") },
            text = { Text("⚠ $BRIDGE_WARNING", color = MaterialTheme.colorScheme.error) },
            confirmButton = {
                TextButton(onClick = { confirmEnable = false; vm.setHostModeValue("ask") }, enabled = left == 0) {
                    Text(if (left > 0) "Включить ($left)" else "Включить")
                }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("Отмена") } },
        )
    }
    if (confirmRun) {
        val note = remember(cmd) { rootNote(cmd) }
        val left = rememberRootCountdown(cmd, rootDelaySeconds(vm.rootDelay, note))
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text("Выполнить от root?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(cmd.take(800), fontFamily = FontFamily.Monospace)
                    RootNoteView(note, vm.rootDelay)
                }
            },
            confirmButton = { TextButton(onClick = { confirmRun = false; vm.runRootOnce(cmd) }, enabled = left == 0) { Text(if (left > 0) "Выполнить ($left)" else "Выполнить") } },
            dismissButton = { TextButton(onClick = { confirmRun = false }) { Text("Отмена") } },
        )
    }
}

/** Окно подтверждения: агент в Linux просит выполнить команду на самом телефоне. Окно само не закрывается; кнопки включаются сразу или через 5 секунд (для изменения системы всегда через 5). */
@Composable
fun HostBridgeDialog(vm: ChatController) {
    val req = vm.hostPending.firstOrNull() ?: return
    val note = remember(req.command) { rootNote(req.command) }
    val left = rememberRootCountdown(req.id, rootDelaySeconds(vm.rootDelay, note))
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val summary = "Агент OpenCode в Linux-песочнице на моём телефоне просит выполнить на самом Android (root, Magisk) команду:\n\n" + req.command +
        "\n\nБезопасно ли её выполнять? Что она сделает?"
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Агент просит команду на телефоне") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚠ Команда выполнится на твоём телефоне. Прочитай её внимательно.", color = MaterialTheme.colorScheme.error)
                Text(req.command.take(1500), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                RootNoteView(note, vm.rootDelay)
                dangerNote(req.command)?.let { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
                Text(
                    "«От root» выполнит команду через Magisk (root должен быть выдан приложению). dumpsys, getprop, logcat только читают. «Без root» запустит её с правами самого приложения.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(summary)) }) { Text("Скопировать описание, чтобы спросить нейросеть") }
                if (vm.hostPending.size > 1) Text("Ещё запросов в очереди: ${vm.hostPending.size - 1}", style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { vm.hostAnswer(req, "shell") }, enabled = left == 0) { Text("Без root") }
                TextButton(onClick = { vm.hostAnswer(req, "root") }, enabled = left == 0) { Text(if (left > 0) "От root ($left)" else "От root") }
            }
        },
        dismissButton = { TextButton(onClick = { vm.hostAnswer(req, "deny") }) { Text("Отклонить") } },
    )
}
