package com.pocketcode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class GitTab(val label: String) { CHANGES("Изменения"), COMMIT("Коммит"), REMOTE("Сервер"), GITHUB("GitHub") }

/** Удобная панель Git/GitHub: вкладки, быстрые действия и читаемый вывод. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GitScreen(vm: ChatController, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(GitTab.CHANGES) }
    var message by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var prTitle by remember { mutableStateOf("") }
    var prBody by remember { mutableStateOf("") }
    var issueTitle by remember { mutableStateOf("") }
    var issueBody by remember { mutableStateOf("") }
    var prNumber by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { vm.gitRun("status") }
    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Отменить все изменения?") },
            text = { Text("Несохранённые изменения в проекте будут потеряны без возможности вернуть.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.gitRun("reset_hard") }) { Text("Отменить") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Нет") } },
        )
    }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Git и GitHub") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
                actions = {
                    TextButton(onClick = { vm.gitRun("status") }, enabled = !vm.gitBusy) { Text("Обновить") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GitTab.entries.forEach { t ->
                    androidx.compose.material3.FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                }
            }
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (vm.gitBusy) Color(0xFFFFB300) else Color(0xFF4CAF50)))
                    Text(
                        "  ${vm.currentProject}  ·  " + if (vm.gitBusy) "выполняется…" else "готово",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { clipboard.setText(AnnotatedString(vm.gitOut)); }) { Text("Копировать") }
                }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    GitTab.CHANGES -> {
                        actionRow("Обзор", listOf(
                            "Статус" to { vm.gitRun("status") },
                            "Изменения" to { vm.gitRun("diff") },
                            "История" to { vm.gitRun("log", "count" to "20") },
                            "Ветки" to { vm.gitRun("branch") },
                        ), vm.gitBusy)
                        OutputCard(vm.gitOut, vm.gitBusy, "Здесь появится статус, diff или лог.")
                        Text("Быстрые действия", style = MaterialTheme.typography.titleSmall)
                        actionRow("", listOf(
                            "В индекс всё" to { vm.gitRun("add") },
                            "Отменить изменения" to { confirmReset = true },
                        ), vm.gitBusy)
                    }
                    GitTab.COMMIT -> {
                        OutlinedTextField(message, { message = it }, label = { Text("Сообщение коммита") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.gitRun("add") }, enabled = !vm.gitBusy) { Text("В индекс всё") }
                            Button(onClick = { vm.gitRun("commit", "message" to message); message = "" }, enabled = !vm.gitBusy && message.isNotBlank()) { Text("Закоммитить") }
                        }
                        OutputCard(vm.gitOut, vm.gitBusy, "Результат коммита появится здесь.")
                        Text("Ветка", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(branch, { branch = it }, label = { Text("Имя ветки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        actionRow("", listOf(
                            "Переключиться" to { vm.gitRun("checkout", "branch" to branch) },
                            "Создать и перейти" to { vm.gitRun("checkout", "branch" to branch, "create" to "true") },
                        ), vm.gitBusy || branch.isBlank())
                    }
                    GitTab.REMOTE -> {
                        actionRow("Синхронизация", listOf(
                            "Pull" to { vm.gitRun("pull") },
                            "Push" to { vm.gitRun("push") },
                        ), vm.gitBusy)
                        OutputCard(vm.gitOut, vm.gitBusy, "Результат pull/push появится здесь.")
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                                Text("Токен GitHub", style = MaterialTheme.typography.titleSmall)
                                Text("Берётся из настроек приложения. Без токена push и GitHub API не работают.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    GitTab.GITHUB -> {
                        actionRow("Просмотр", listOf(
                            "Pull Requests" to { vm.githubRun("pr_list") },
                            "Issues" to { vm.githubRun("issue_list") },
                            "Проверки CI" to { vm.githubRun("checks") },
                            "Репозитории" to { vm.githubRun("repos") },
                        ), vm.gitBusy)
                        OutlinedTextField(prTitle, { prTitle = it }, label = { Text("Заголовок Pull Request") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(prBody, { prBody = it }, label = { Text("Описание PR") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Button(onClick = { vm.githubRun("pr_create", "title" to prTitle, "body" to prBody); prTitle = ""; prBody = "" }, enabled = !vm.gitBusy && prTitle.isNotBlank()) {
                            Text("Создать Pull Request")
                        }
                        OutlinedTextField(issueTitle, { issueTitle = it }, label = { Text("Заголовок Issue") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(issueBody, { issueBody = it }, label = { Text("Описание Issue") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        Button(onClick = { vm.githubRun("issue_create", "title" to issueTitle, "body" to issueBody); issueTitle = ""; issueBody = "" }, enabled = !vm.gitBusy && issueTitle.isNotBlank()) {
                            Text("Создать Issue")
                        }
                        OutlinedTextField(prNumber, { prNumber = it }, label = { Text("Номер PR (для merge / checks)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        actionRow("", listOf(
                            "Объединить PR" to { vm.githubRun("pr_merge", "number" to prNumber) },
                            "Статус PR" to { vm.githubRun("pr_get", "number" to prNumber) },
                        ), vm.gitBusy || prNumber.isBlank())
                        OutputCard(vm.gitOut, vm.gitBusy, "Ответ GitHub появится здесь.")
                        Text("Сначала отправь ветку кнопкой Push.", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun actionRow(title: String, actions: List<Pair<String, () -> Unit>>, busy: Boolean) {
    if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { (label, run) -> OutlinedButton(onClick = run, enabled = !busy) { Text(label) } }
    }
}

@Composable
private fun OutputCard(text: String, busy: Boolean, empty: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (busy) "Выполняется…" else text.ifEmpty { empty },
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            if (busy) SkeletonRows(count = 2)
        }
    }
}
