package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Каталог доступных модели инструментов: что включено, плюс размышления. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    initial: ToolsConfig,
    vmReadOnly: Boolean = false,
    onReadOnlyChange: (Boolean) -> Unit = {},
    chatOnly: Boolean = false,
    onChatOnlyChange: (Boolean) -> Unit = {},
    linuxInstalled: Boolean,
    onSave: (ToolsConfig) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Инструменты модели") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var c by remember { mutableStateOf(initial) }
    fun update(block: (ToolsConfig) -> ToolsConfig) { c = block(c); onSave(c) }
    var group by remember { mutableStateOf("Все") }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        SectionHeader("Размышления", "Управляет ходом мыслей модели в живом статусе")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("Модель сначала обдумывает задачу и показывает ход мыслей в живом статусе. Можно выключить, чтобы получать ответ быстрее.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Включить размышления", modifier = Modifier.weight(1f))
                    Switch(checked = c.reasoning, onCheckedChange = { v -> update { it.copy(reasoning = v) } })
                }
                if (c.reasoning) {
                    Text("Глубина: ${c.reasoningDepth}", style = MaterialTheme.typography.bodySmall)
                    Slider(value = c.reasoningDepth.toFloat(), onValueChange = { v -> update { it.copy(reasoningDepth = v.toInt()) } }, valueRange = 0f..3f, steps = 2, modifier = Modifier.fillMaxWidth())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Чат без проекта")
                        Text("Модель отвечает без доступа к файлам, терминалу и телефону — только поиск в интернете и заметки.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = chatOnly, onCheckedChange = onChatOnlyChange)
                }
                Text("Протокол reasoning:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Авто", "openai" to "OpenAI", "gemini" to "Gemini", "anthropic" to "Claude", "deepseek" to "DeepSeek", "off" to "Выкл").forEach { (v, l) ->
                        FilterChip(selected = c.reasoningVendor == v, onClick = { update { it.copy(reasoningVendor = v) } }, label = { Text(l) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Только чтение")
                        Text("Агент сможет читать файлы и искать, но не сможет ничего менять или запускать команды.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = vmReadOnly, onCheckedChange = onReadOnlyChange)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Контроль качества после правок")
                        Text("Агент не может закончить задачу после правок без diagnostics, code_review и тестов; если проверка невозможна, он пишет «НЕ ПРОВЕРЕНО».", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = c.selfCheck, onCheckedChange = { v -> update { it.copy(selfCheck = v) } })
                }
                Text("Режим под силу модели:", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("auto" to "Обычный", "scaffold" to "Слабая модель", "focused" to "Большой проект").forEach { (v, l) ->
                        FilterChip(selected = c.agentMode == v, onClick = { update { it.copy(agentMode = v) } }, label = { Text(l) })
                    }
                }
                Text(
                    when (c.agentMode) {
                        "scaffold" -> "Жёсткие строительные леса: план до кода, один файл за раз, только реальные сигнатуры, ревью по чек-листу. Синтаксис проверяется автоматически после каждой правки, а код в ответе вместо файлов не принимается. Для простых/дешёвых моделей."
                        "focused" -> "Дисциплина контекста: навигация по карте и grep вместо чтения файлов, субагенты для обзора, минимальные диффы. Для моделей, слабых в больших проектах."
                        else -> "Стандартный системный промпт без дополнительных ограничений."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        SectionHeader("Доступные инструменты", "${ToolRegistry.all.size} инструментов. Отключённые не передаются модели в запросе.")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("Все") + ToolRegistry.groups()).forEach { g ->
                FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
            }
        }
        SectionHeader("Список", if (group == "Все") "Все группы" else group)
        ToolRegistry.all.filter { group == "Все" || it.group == group }.forEach { t ->
            val unavailable = t.requiresLinux && !linuxInstalled
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${t.title} · ${t.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(t.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (unavailable) Text("Нужна среда Linux", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        Switch(
                            checked = ToolRegistry.isEnabled(t.id, c) && !unavailable,
                            enabled = !unavailable,
                            onCheckedChange = { v ->
                                update { cfg -> cfg.copy(disabled = if (v) cfg.disabled - t.id else cfg.disabled + t.id) }
                            },
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = { update { it.copy(disabled = emptySet()) } }) { Text("Включить все") }
            androidx.compose.material3.OutlinedButton(onClick = { update { it.copy(disabled = ToolRegistry.all.map { t -> t.id }.toSet()) } }) { Text("Выключить все") }
        }
    }
}
