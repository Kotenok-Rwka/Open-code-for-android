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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketcode.SyncConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSyncScreen(
    initial: SyncConfig,
    status: String,
    onSave: (SyncConfig) -> Unit,
    onSendNow: () -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Автосинхронизация с ПК") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var c by remember { mutableStateOf(initial) }
    fun update(block: (SyncConfig) -> SyncConfig) { c = block(c); onSave(c) }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        SectionHeader("Режим", "Отправлять события сразу, снимок по расписанию или и то и другое")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("LIVE" to "В реальном времени", "PERIODIC" to "По расписанию", "BOTH" to "Оба").forEach { (k, label) ->
                FilterChip(selected = c.mode == k, onClick = { update { it.copy(mode = k) } }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Включить", modifier = Modifier.weight(1f))
            Switch(checked = c.enabled, onCheckedChange = { v -> update { it.copy(enabled = v) } })
        }
        SectionHeader("Подключение", "Компаньон на ПК: python3 pocketcode_desktop.py --host 0.0.0.0")
        OutlinedTextField(c.serverUrl, { v -> update { it.copy(serverUrl = v) } }, label = { Text("Адрес ПК (http://IP:8765)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Только Wi-Fi", modifier = Modifier.weight(1f))
            Switch(checked = c.wifiOnly, onCheckedChange = { v -> update { it.copy(wifiOnly = v) } })
        }
        SectionHeader("Снимок по расписанию", "История чата и файлы проекта")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Интервал: ${c.intervalMin} мин", modifier = Modifier.weight(1f))
            Switch(checked = c.includeFiles, onCheckedChange = { v -> update { it.copy(includeFiles = v) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 30, 60).forEach { n ->
                FilterChip(selected = c.intervalMin == n, onClick = { update { it.copy(intervalMin = n) } }, label = { Text("$n мин") })
            }
        }
        Button(onClick = onSendNow, modifier = Modifier.fillMaxWidth()) { Text("Отправить сейчас") }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(4.dp)) {
                Text("Статус", style = MaterialTheme.typography.titleSmall)
                Text(status.ifBlank { "Ещё нет отправок" }, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Секреты маскируются до отправки. Файлы больше 2 МБ и служебные папки (.git, build) пропускаются.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
