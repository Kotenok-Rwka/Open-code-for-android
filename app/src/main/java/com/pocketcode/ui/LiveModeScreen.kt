package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveModeScreen(
    initial: LiveModeConfig,
    profiles: List<Pair<String, String>>,
    onSave: (LiveModeConfig) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Живой режим") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var config by remember { mutableStateOf(initial) }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Голосовое планирование без рук на быстрой модели", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(8.dp)) {
                Text("Живой режим", style = MaterialTheme.typography.titleSmall)
                Text("Обдумывайте идеи вслух, не нажимая отправку. Ответы читаются вслух, а весь разговор сохраняется в чате, чтобы агент мог действовать позже.", style = MaterialTheme.typography.bodyMedium)
                Text("Экспериментально", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Включить", modifier = Modifier.weight(1f))
                    Switch(checked = config.enabled, onCheckedChange = { config = config.copy(enabled = it); onSave(config) })
                }
            }
        }
        Text("Быстрая лёгкая модель делает голосовые ответы оперативными. Основной агент сохраняет свою модель, поэтому запущенная задача не страдает.")
        Text("Модель живого режима", style = MaterialTheme.typography.titleSmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = config.modelId.isEmpty(), onClick = { config = config.copy(modelId = ""); onSave(config) }, label = { Text("Модель чата") })
            profiles.forEach { (id, label) ->
                FilterChip(selected = config.modelId == id, onClick = { config = config.copy(modelId = id); onSave(config) }, label = { Text(label) })
            }
        }
        OutlinedTextField(config.modelId, { config = config.copy(modelId = it); onSave(config) }, label = { Text("id модели (или пусто — как в чате)") }, modifier = Modifier.fillMaxWidth())
        Text("Что может живой режим", style = MaterialTheme.typography.titleMedium)
        Text("Живой режим сам никогда не редактирует файлы и не выполняет команды. Чтобы действовать, он передаёт план основному агенту, который работает с обычными разрешениями и подтверждениями этого чата.")
        LiveModeScope.entries.forEach { scope ->
            Card(
                Modifier.fillMaxWidth().clickable { config = config.copy(scope = scope); onSave(config) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = config.scope == scope, onClick = { config = config.copy(scope = scope); onSave(config) })
                        Text(scope.label, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(scope.description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
