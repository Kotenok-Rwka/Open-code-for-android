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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Экран локальных виджетов: включает/выключает панели и открывает их в PreviewScreen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalhostWidgetsScreen(
    initial: List<LocalhostWidget>,
    onSave: (List<LocalhostWidget>) -> Unit,
    onOpen: (String, String) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Локальные виджеты") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var widgets by remember { mutableStateOf(initial.ifEmpty { LocalhostWidgets.defaults() }) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Панели на localhost", style = MaterialTheme.typography.titleMedium)
        Text("Виджеты открываются во встроенном просмотрщике. Допустимы только localhost и локальная сеть.")
        widgets.forEachIndexed { i, w ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, style = MaterialTheme.typography.bodyMedium)
                    Text(w.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = w.enabled, onCheckedChange = { v -> widgets = widgets.mapIndexed { j, x -> if (j == i) x.copy(enabled = v) else x }; onSave(widgets) })
                TextButton(onClick = { onOpen(w.url, w.name) }) { Text("Открыть") }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("Адрес http://...") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            if (!LocalhostWidgets.isLocal(url)) error = "Разрешены только localhost и локальная сеть"
            else if (name.isBlank()) error = "Укажи название"
            else {
                widgets = widgets + LocalhostWidget(java.util.UUID.randomUUID().toString(), name.trim(), url.trim())
                onSave(widgets); name = ""; url = ""; error = null
            }
        }) { Text("Добавить виджет") }
    }
}
