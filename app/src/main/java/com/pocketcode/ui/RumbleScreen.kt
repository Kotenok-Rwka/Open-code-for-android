package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RumbleScreen(initial: Rumble.Config, onSave: (Rumble.Config) -> Unit, onBack: () -> Unit) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Тактильные подсказки") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(initial) }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Тактильные импульсы, когда чат думает, вызывает инструменты и завершает ответ", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Вибрация", style = MaterialTheme.typography.titleSmall)
                        Text("Чувствуйте импульсы при размышлении, вызове инструмента, написании ответа.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = c.enabled, onCheckedChange = { c = c.copy(enabled = it); onSave(c) })
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("Где вибрирует", style = MaterialTheme.typography.titleSmall)
                toggleRow("Чат — импульсы во время ходов в чате", c.inChat) { c = c.copy(inChat = it); onSave(c) }
                toggleRow("Код — когда агент запускает инструменты", c.inCode) { c = c.copy(inCode = it); onSave(c) }
                Button(onClick = { Rumble.pulse(ctx, Rumble.Kind.TOOL, c.copy(enabled = true)) }) { Text("Попробовать") }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("Вибрация во время ответа", style = MaterialTheme.typography.titleSmall)
                Text("Плавная вибрация, пока приходит ответ в чате.", style = MaterialTheme.typography.bodySmall)
                toggleRow("Включить", c.onAnswer) { c = c.copy(onAnswer = it); onSave(c) }
                Button(onClick = { Rumble.pulse(ctx, Rumble.Kind.ANSWER, c.copy(enabled = true)) }) { Text("Почувствовать поток") }
                Text("На этом телефоне может отсутствовать расширенная отдача — используются обычные импульсы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
