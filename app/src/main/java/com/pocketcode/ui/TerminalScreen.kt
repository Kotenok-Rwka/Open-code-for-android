package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.pocketcode.ui.Text
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

/** Ручной терминал: команды выполняются в папке проекта, без участия модели. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(vm: ChatController, onBack: () -> Unit, onOpenHost: () -> Unit = {}, onOpenChat: () -> Unit = onBack, settings: Settings = Settings()) {
    var cmd by remember { mutableStateOf("") }
    var tty by remember { mutableStateOf(false) }
    var danger by remember { mutableStateOf<String?>(null) }
    var cheat by remember { mutableStateOf(false) }
    val runCmd: (String) -> Unit = { c -> if (TermGuide.isDangerous(c)) danger = c else { vm.termRun(c, 600, tty); cmd = "" } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val list = rememberLazyListState()
    LaunchedEffect(vm.term.size, vm.term.lastOrNull()?.out) { if (vm.term.isNotEmpty()) list.scrollToItem(vm.term.lastIndex) }
    Scaffold(topBar = {
        AppTopBar(
            title = { Text("Терминал", maxLines = 1) },
            navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(com.pocketcode.ui.backIcon(), contentDescription = "Назад") } },
            actions = {
                TextButton(onClick = onOpenHost) { Text("Хостинг") }
                TextButton(onClick = { cheat = true }) { Text("Шпаргалка") }
                TextButton(onClick = { vm.term.clear() }) { Text("Очистить") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.term.isEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Официальный OpenCode можно запустить внутри Linux (нужны: Linux включён и «Установить python, git, nodejs» в разделе «Проекты»). Это его собственный клиент, поэтому его бесплатные модели работают так же, как на ПК, но скорость в proot ниже и на телефоне это не проверено.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AssistChip(onClick = { vm.termRun(OpenCodeInstall.command(), 900) }, label = { Text("Установить OpenCode") })
                            AssistChip(onClick = { vm.termRun(AndroidSdkInstall.command(), 1500) }, label = { Text("Установить Android SDK") })
                            AssistChip(onClick = { vm.termRun("opencode --version") }, label = { Text("Версия") })
                            AssistChip(onClick = { vm.termRun("opencode models 2>&1 | head -n 40", 300) }, label = { Text("Список моделей") })
                            AssistChip(onClick = { cmd = "opencode run \"привет\"" }, label = { Text("Пример запуска") })
                            AssistChip(onClick = { tty = true; vm.termRun("opencode auth login", 600, true) }, label = { Text("Войти (auth login)") })
                            AssistChip(onClick = { vm.termRun("opencode stats", 120) }, label = { Text("Статистика") })
                        }
                    }
                    Text("Команды идут в ту же среду, что и у агента (Linux, если включён, иначе Android-оболочка), в папке проекта. Лимит одной команды 10 минут (кнопка «Стоп» прервёт раньше).", style = MaterialTheme.typography.bodySmall)
                }
                items(vm.term.toList()) { e ->
                    Column {
                        Text(
                            "$ ${e.cmd}",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(e.out)) },
                        )
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                if (e.out.isEmpty()) (if (e.done) "(нет вывода)" else "выполняется…") else e.out + (if (e.done) "" else "\n▍"),
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                            )
                        }
                        if (e === vm.term.lastOrNull() && e.done && !vm.termBusy) {
                            val note = remember(e.out, e.code) { TermGuide.diagnose(e.out, e.code) ?: TermGuide.nextStep(e.cmd, e.code) }
                            if (note != null) Text(
                                (if ((e.code ?: 0) != 0) "Что это значит: " else "Подсказка: ") + note,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (e === vm.term.lastOrNull() && e.done && (e.code ?: 0) != 0 && !vm.termBusy && !e.cmd.startsWith("cd")) {
                            val fixes = remember(e.out, vm.rootFsChoice) { TermHints.fixes(e.cmd, e.out, vm.termInLinux, vm.rootFsChoice) }
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                AssistChip(onClick = { vm.termAskAssistant(e); onOpenChat() }, label = { Text("Спросить ассистента") })
                                fixes.forEach { f -> AssistChip(onClick = { vm.termRun(f, 600, false) }, label = { Text("▶ " + f.take(34)) }) }
                            }
                        }
                    }
                }
            }
            if (!vm.termBusy) {
                val runs = remember(vm.currentProject) { vm.detectRunCommands() }
                if (runs.isNotEmpty()) {
                    Text("Запуск проекта:", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        runs.forEach { (label, command) -> AssistChip(onClick = { vm.termRun(command, 900, false) }, label = { Text("▶ $label") }) }
                        AssistChip(onClick = { vm.hostDraftFromCurrent(); onOpenHost() }, label = { Text("Хостить проект…") })
                    }
                }
            }
            if (vm.termBusy) {
                val prompt = TermHints.detectPrompt(vm.term.lastOrNull()?.out.orEmpty())
                if (prompt != null) {
                    val key = if (tty) "\r" else "\n"
                    Text(
                        when (prompt) {
                            TermHints.Prompt.YES_NO -> "Программа ждёт ответа: да или нет?"
                            TermHints.Prompt.ENTER -> "Программа просит нажать Enter."
                            TermHints.Prompt.PASSWORD -> "Программа просит пароль: введи его в поле ниже и нажми ↵. Он не показывается на экране."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (prompt == TermHints.Prompt.YES_NO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.termSend("y$key") }) { Text("Да") }
                        OutlinedButton(onClick = { vm.termSend("n$key") }) { Text("Нет") }
                    }
                    if (prompt == TermHints.Prompt.ENTER) Button(onClick = { vm.termSend(key) }) { Text("Enter") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val enter = if (tty) "\r" else "\n"
                    AssistChip(onClick = { vm.termSend(enter) }, label = { Text("Enter") })
                    AssistChip(onClick = { vm.termSend("\u001B[A") }, label = { Text("↑") })
                    AssistChip(onClick = { vm.termSend("\u001B[B") }, label = { Text("↓") })
                    AssistChip(onClick = { vm.termSend(" ") }, label = { Text("Пробел") })
                    AssistChip(onClick = { vm.termSend("\t") }, label = { Text("Tab") })
                    AssistChip(onClick = { vm.termSend("\u001B") }, label = { Text("Esc") })
                    AssistChip(onClick = { vm.termSend("\u0003") }, label = { Text("Ctrl+C") })
                    AssistChip(onClick = { vm.termSend("y$enter") }, label = { Text("y") })
                    AssistChip(onClick = { vm.termSend("n$enter") }, label = { Text("n") })
                }
            } else {
                val recent = vm.termHistory.takeLast(6).reversed()
                if (recent.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(selected = tty, onClick = { tty = !tty }, label = { Text("TTY") })
                    recent.forEach { AssistChip(onClick = { cmd = it }, label = { Text(it.take(28)) }) }
                } else Row {
                    androidx.compose.material3.FilterChip(selected = tty, onClick = { tty = !tty }, label = { Text("TTY (для интерактивных программ)") })
                }
            }
            if (!vm.termBusy && cmd.isNotBlank()) {
                val lines = remember(cmd, I18n.code) { TermGuide.explain(cmd) { I18n.t(it) } }
                val typo = remember(cmd) { TermGuide.didYouMean(cmd) }
                val sugg = remember(cmd, vm.termHistory.size) { TermGuide.suggest(cmd, vm.termHistory.toList()) }
                if (lines.isNotEmpty()) androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (TermGuide.isDangerous(cmd)) Text("Осторожно: " + TermGuide.dangerText(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (typo != null || sugg.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (typo != null) AssistChip(onClick = { cmd = typo }, label = { Text("Может, ты имел в виду: " + typo.take(24)) })
                    sugg.forEach { sg -> AssistChip(onClick = { cmd = sg }, label = { Text(sg.take(30)) }) }
                }
            }
            Text("~/" + vm.termRel, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            // Живая сессия: состояние (cd, переменные) живёт между командами; видна при включённом тумблере в настройках
            if (settings.shellSession) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.shellAlive) {
                        AssistChip(onClick = { vm.shellKill() }, label = { Text("● сессия активна — закрыть") })
                    } else {
                        AssistChip(onClick = { vm.shellStart() }, label = { Text("○ Живая сессия (sh)") })
                    }
                }
            }
            if (vm.shellAlive && vm.shellOut.isNotEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        vm.shellOut.takeLast(1200),
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.weight(0.4f).fillMaxWidth().horizontalScroll(rememberScrollState()),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cmd, onValueChange = { cmd = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (vm.termBusy) "ввод для программы" else "ls -la") }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                )
                if (vm.termBusy) {
                    Button(onClick = { vm.termSend(cmd + (if (tty) "\r" else "\n")); cmd = "" }) { Text("↵") }
                    OutlinedButton(onClick = { vm.termStop() }) { Text("Стоп") }
                } else if (vm.shellAlive) {
                    Button(onClick = { vm.shellExec(cmd); cmd = "" }, enabled = cmd.isNotBlank()) { Text("▶ в сессию") }
                } else Button(onClick = { runCmd(cmd) }, enabled = cmd.isNotBlank()) { Text("▶") }
            }
            BgPanel(vm)
        }
    }
    danger?.let { c ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { danger = null },
            title = { Text("Опасная команда") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(TermGuide.dangerText()); Text(c, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } },
            confirmButton = { TextButton(onClick = { danger = null; vm.termRun(c, 600, tty); cmd = "" }) { Text("Запустить") } },
            dismissButton = { TextButton(onClick = { danger = null }) { Text("Отмена") } },
        )
    }
    if (cheat) androidx.compose.material3.AlertDialog(
        onDismissRequest = { cheat = false },
        title = { Text("Шпаргалка по командам") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TermGuide.cheatSheet.forEach { (group, rows) ->
                    item { Text(group, style = MaterialTheme.typography.titleSmall) }
                    items(rows) { (c, what) ->
                        Column(Modifier.fillMaxWidth().clickable { cmd = c; cheat = false }) {
                            Text(c, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                            Text(what, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { cheat = false }) { Text("Закрыть") } },
    )
}

@Composable
private fun BgPanel(vm: ChatController) {
    var tick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    tick
    val all = vm.bgProcs.all()
    if (all.isEmpty()) return
    Text("Фоновые процессы (запущены агентом)", style = MaterialTheme.typography.titleSmall)
    all.forEach { e ->
        val alive = e.process.isAlive
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (alive) "● " else "○ ") + e.name + " · " + e.command.take(40), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { open = if (open == e.name) null else e.name }) { Text("Лог") }
                if (alive) TextButton(onClick = { runCatching { e.process.destroy() } }) { Text("Стоп") }
            }
            if (open == e.name) {
                val tail = synchronized(e.buffer) { e.buffer.toString().takeLast(3000) }
                Text(tail.ifEmpty { "(пока пусто)" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}
