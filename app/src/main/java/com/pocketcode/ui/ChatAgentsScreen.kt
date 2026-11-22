package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ChatAgent(val id: String, val name: String, val goal: String, val system: String = "")

/** Агенты Chat: сохраняют цель и системную инструкцию, запускают новый чат одним нажатием. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatAgentsScreen(
    initial: List<ChatAgent>,
    onSave: (List<ChatAgent>) -> Unit,
    onStart: (ChatAgent) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Агенты Chat") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var agents by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var system by remember { mutableStateOf("") }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Помощник для каждой задачи", style = MaterialTheme.typography.titleMedium)
        Text("Создай напарника для учёбы, письма или исследований. Задай ему цель и начни разговор. Изменения применяются к новым разговорам.")
        for (a in agents) {
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    Text(a.goal, style = MaterialTheme.typography.bodySmall)
                    Row {
                        androidx.compose.material3.TextButton(onClick = { onStart(a) }) { Text("Начать чат") }
                        androidx.compose.material3.TextButton(onClick = { agents = agents.filterNot { it.id == a.id }; onSave(agents) }) { Text("Удалить") }
                    }
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("Имя агента") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(goal, { goal = it }, label = { Text("Цель") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(system, { system = it }, label = { Text("Инструкция (необязательно)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            if (name.isBlank() || goal.isBlank()) return@Button
            val a = ChatAgent(java.util.UUID.randomUUID().toString(), name.trim(), goal.trim(), system.trim())
            agents = agents + a
            onSave(agents)
            name = ""; goal = ""; system = ""
        }) { Text("Создать агента") }
    }
}
