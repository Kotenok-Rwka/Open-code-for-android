package com.pocketcode.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Подпись профиля из строки «Название (id)». */
private fun profileLabel(entry: String): String = entry.substringBeforeLast(" (", entry)

/** Идентификатор профиля из строки «Название (id)»; без скобок считается, что это уже id. */
private fun profileId(entry: String): String =
    entry.substringAfterLast(" (", "").removeSuffix(")").ifBlank { entry }

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
    var manual by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        Arrangement.spacedBy(12.dp),
    ) {
        Text("Отдельные модели планирования и выполнения", style = MaterialTheme.typography.titleMedium)
        Text(
            "В режиме плана агент только читает код и составляет план; выбранная модель выполнения применяет изменения после одобрения. " +
                "Если ничего не выбрано, используется текущая модель профиля.",
        )

        RoleCard(
            title = "Модель планирования",
            hint = "Читает код и предлагает план, ничего не меняя",
            profiles = profiles,
            selected = plan,
            onSelect = { plan = it },
        )
        RoleCard(
            title = "Модель выполнения",
            hint = "Применяет правки после одобрения плана",
            profiles = profiles,
            selected = exec,
            onSelect = { exec = it },
        )

        TextButton(onClick = { manual = !manual }) { Text(if (manual) "Скрыть ручной ввод id" else "Ввести id профиля вручную") }
        if (manual) {
            OutlinedTextField(plan, { plan = it }, label = { Text("id профиля планирования") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(exec, { exec = it }, label = { Text("id профиля выполнения") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        if (profiles.isNotEmpty()) {
            Text("Доступные профили", style = MaterialTheme.typography.labelMedium)
            Text(
                profiles.joinToString("\n") { "• ${profileLabel(it)} — ${profileId(it)}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = { onSaveRoles(plan.trim(), exec.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить роли") }

        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text("9Router / локальный маршрутизатор", style = MaterialTheme.typography.titleMedium)
        Text(
            "9Router — локальный роутер моделей: ставится в Linux-окружении и поддерживает OpenAI-совместимый endpoint. " +
                "Укажи его адрес, и список моделей будет доступен приложению.",
        )
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    url, { url = it },
                    label = { Text("Адрес роутера") },
                    placeholder = { Text("http://127.0.0.1:20128/v1") },
                    singleLine = true,
                    isError = url.isNotBlank() && !url.trim().startsWith("http"),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(key, { key = it }, label = { Text("Ключ роутера (если нужен)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSaveRouter(url.trim(), key.trim()) }, enabled = url.isBlank() || url.trim().startsWith("http")) { Text("Сохранить роутер") }
                    OutlinedButton(onClick = { url = "http://127.0.0.1:20128/v1" }) { Text("Адрес по умолчанию") }
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoleCard(
    title: String,
    hint: String,
    profiles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = selected.isBlank(),
                    onClick = { onSelect("") },
                    label = { Text("Текущая модель", maxLines = 1) },
                )
                profiles.forEach { entry ->
                    val id = profileId(entry)
                    FilterChip(
                        selected = selected == id,
                        onClick = { onSelect(id) },
                        label = { Text(profileLabel(entry), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    )
                }
            }
            Text(
                if (selected.isBlank()) "Сейчас: текущая модель" else "Сейчас: " + (profiles.firstOrNull { profileId(it) == selected }?.let { profileLabel(it) } ?: selected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
