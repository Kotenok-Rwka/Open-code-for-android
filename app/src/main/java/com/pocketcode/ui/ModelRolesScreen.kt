package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Настройки отдельных моделей планирования/выполнения и 9Router. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelRolesScreen(
    profiles: List<String>,
    planning: String,
    execution: String,
    routerUrl: String,
    routerKey: String,
    onSaveRoles: (String, String) -> Unit,
    onSaveRouter: (String, String) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Роли моделей и роутер") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var plan by remember { mutableStateOf(planning) }
    var exec by remember { mutableStateOf(execution) }
    var url by remember { mutableStateOf(routerUrl) }
    var key by remember { mutableStateOf(routerKey) }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Отдельные модели планирования и выполнения", style = MaterialTheme.typography.titleMedium)
        Text("В режиме плана агент только читает код и составляет план; выбранная модель выполнения применяет изменения после одобрения. Пустое значение — использовать текущую модель.")
        Text("Модель планирования")
        OutlinedTextField(plan, { plan = it }, label = { Text("id профиля планирования") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(exec, { exec = it }, label = { Text("id профиля выполнения") }, modifier = Modifier.fillMaxWidth())
        Text("Доступные профили: ${profiles.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onSaveRoles(plan.trim(), exec.trim()) }) { Text("Сохранить роли") }
        Text("9Router / локальный маршрутизатор", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text("9Router — локальный роутер моделей: ставится в Linux-окружении и поддерживает OpenAI-совместимый endpoint. Укажи его адрес, и список моделей будет доступен приложению.")
        OutlinedTextField(url, { url = it }, label = { Text("http://127.0.0.1:... ") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(key, { key = it }, label = { Text("Ключ роутера (если нужен)") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onSaveRouter(url, key) }) { Text("Сохранить роутер") }
    }
}
