package com.pocketcode.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketcode.CodeSearch
import com.pocketcode.SnippetLib
import com.pocketcode.EditorStats
import com.pocketcode.TaskDedup
import com.pocketcode.VersionCompare
import com.pocketcode.Progress
import com.pocketcode.RuDate
import com.pocketcode.Settings
import com.pocketcode.TechDebt
import com.pocketcode.Complexity
import com.pocketcode.VersionBump
import com.pocketcode.Changelog
import com.pocketcode.CoverageGaps
import com.pocketcode.Outline

/** Экран «Инструменты проекта»: рабочие утилиты из Batch15–18. */
@Composable
fun ProjectToolsScreen(onBack: () -> Unit, settings: Settings = Settings()) {
    var result by remember { mutableStateOf("") }

    fun run(label: String, value: String) { result = "$label\n\n$value" }

    Scaffold(topBar = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Инструменты проекта", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            Arrangement.spacedBy(14.dp),
        ) {
            if (result.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Результат", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer { Text(result, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            // 209. Поиск по коду
            var query by remember { mutableStateOf("") }
            OutlinedTextField(query, { query = it }, label = { Text("Поиск по тексту/коду") },
                modifier = Modifier.fillMaxWidth(), trailingIcon = {
                    TextButton(onClick = {
                        val src = result
                        val hits = CodeSearch.search(src, query)
                        run("Поиск «$query»", if (hits.isEmpty()) "нет совпадений"
                        else hits.joinToString("\n") { h -> "${h.line}: ${h.text.trim()}" })
                    }) { Text("Найти") }
                })

            // 210. Сниппеты
            var snip by remember { mutableStateOf("") }
            OutlinedTextField(snip, { snip = it }, label = { Text("Сниппет: название; код; тег") },
                modifier = Modifier.fillMaxWidth(), trailingIcon = {
                    TextButton(onClick = {
                        val parts = snip.split(";").map { it.trim() }
                        if (parts.size >= 2) {
                            val s = SnippetLib.add(parts[0], parts[1], parts.drop(2))
                            run("Сниппет добавлен", "#${s.id} ${s.title}\n${s.body}\nтеги: ${s.tags}")
                        }
                    }) { Text("+") }
                })
            TextButton(onClick = {
                run("Сниппеты", SnippetLib.all().joinToString("\n\n") { "#${it.id} ${it.title} [${it.tags}]\n${it.body}" })
            }) { Text("Показать все сниппеты") }

            // 221. Дедуп задач
            var tasks by remember { mutableStateOf("") }
            OutlinedTextField(tasks, { tasks = it }, label = { Text("Задачи, по одной в строке") },
                modifier = Modifier.fillMaxWidth(), trailingIcon = {
                    TextButton(onClick = {
                        val kept = TaskDedup.keepUnique(tasks.split("\n"))
                        run("Дедуп задач", "осталось ${kept.size} из ${tasks.split("\n").size}:\n" + kept.joinToString("\n"))
                    }) { Text("Дедуп") }
                })

            // 218. Сравнение версий
            var verA by remember { mutableStateOf("") }
            var verB by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(verA, { verA = it }, label = { Text("Версия A") }, modifier = Modifier.weight(1f))
                OutlinedTextField(verB, { verB = it }, label = { Text("B") }, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = {
                val c = VersionCompare.compare(verA, verB)
                val verdict = if (c > 0) "$verA новее" else if (c < 0) "$verB новее" else "равны"
                run("Сравнение версий", verdict)
            }) { Text("Сравнить версии") }

            // 224. Русская дата
            TextButton(onClick = {
                val now = java.util.Calendar.getInstance()
                val d = RuDate.formatDate(now.get(java.util.Calendar.DAY_OF_MONTH), now.get(java.util.Calendar.MONTH) + 1)
                run("Дата сегодня", d)
            }) { Text("Дата сегодня по-русски") }

            // 230. Прогресс
            var done by remember { mutableStateOf("") }
            var total by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(done, { done = it }, label = { Text("Сделано") }, modifier = Modifier.weight(1f))
                OutlinedTextField(total, { total = it }, label = { Text("Всего") }, modifier = Modifier.weight(1f))
            }
            TextButton(onClick = {
                val d = done.toIntOrNull() ?: 0
                val t = total.toIntOrNull() ?: 0
                run("Прогресс", "${Progress.bar(d, t)} ${Progress.percent(d, t)}%")
            }) { Text("Показать прогресс") }

            // 216. Раскрытие табов
            TextButton(onClick = {
                val expanded = EditorStats.expandTabs(result.ifEmpty { "вставь текст в результат выше" })
                run("Табы → пробелы", expanded)
            }) { Text("Табы → пробелы в результате") }

            // Batch 18: техдолг (переключается в настройках)
            if (settings.todoScan) {
                var code by remember { mutableStateOf("") }
                OutlinedTextField(code, { code = it }, label = { Text("Вставь код для поиска TODO/FIXME") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val items = TechDebt.scan(code)
                    run("Техдолг", if (items.isEmpty()) "меток не найдено"
                        else items.joinToString("\n") { "${it.line}: [${it.kind}] ${it.text}" })
                }) { Text("Найти TODO/FIXME") }
            }

            // Batch 18: сложность (переключается в настройках)
            if (settings.secretGate || settings.todoScan) {
                TextButton(onClick = {
                    val cc = Complexity.ofBody(result.ifEmpty { "if (a) b else c" })
                    run("Сложность", "cc=$cc — ${Complexity.label(cc)}")
                }) { Text("Сложность текста в результате") }
            }

            // Batch 18: версия +1
            var ver by remember { mutableStateOf("1.0.0") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(ver, { ver = it }, label = { Text("Версия") }, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("major", "minor", "patch").forEach { part ->
                    TextButton(onClick = {
                        run("Bump $part", VersionBump.bump(ver, part) ?: "не разобрать версию")
                    }) { Text("+$part") }
                }
            }

            // Batch 18: outline
            TextButton(onClick = {
                val syms = Outline.scan(result.ifEmpty { "class A {\n    fun b() {}\n}" })
                run("Оглавление", if (syms.isEmpty()) "символов не найдено"
                    else syms.joinToString("\n") { "${it.line}: ${it.kind} ${it.name}" })
            }) { Text("Оглавление кода в результате") }

            // Batch 18: покрытие тестами
            var files by remember { mutableStateOf("") }
            OutlinedTextField(files, { files = it }, label = { Text("Файлы проекта, по одному в строке") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                val gaps = CoverageGaps.untested(files.lines().filter { it.isNotBlank() })
                run("Без тестов", if (gaps.isEmpty()) "у всех модулей есть тесты" else gaps.joinToString("\n"))
            }) { Text("Какие модули без тестов") }

            // Batch 18: changelog
            var subjects by remember { mutableStateOf("") }
            OutlinedTextField(subjects, { subjects = it }, label = { Text("Темы коммитов, по одной в строке") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                val g = Changelog.group(subjects.lines().filter { it.isNotBlank() })
                run("Changelog", Changelog.markdown(g))
            }) { Text("Собрать changelog") }
        }
    }
}
