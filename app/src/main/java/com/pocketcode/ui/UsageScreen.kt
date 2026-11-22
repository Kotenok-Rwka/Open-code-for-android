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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Сводка использования: токены, стоимость, запросы и изменения кода ИИ. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(vm: ChatController, onBack: () -> Unit) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Использование") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    var tab by remember { mutableStateOf(0) }
    val today = vm.usageToday()
    val total = vm.usageTotal()
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Активность моделей и изменения кода ИИ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("Изменения кода ИИ", style = MaterialTheme.typography.titleSmall)
                Text("За всё время учтено правок: ${vm.changes.size}. Текущая сессия: +${vm.changes.size} / −0 строк.")
                Text("Код и конфигурация · итоги сохраняются после удаления чата", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("Распределение токенов", style = MaterialTheme.typography.titleSmall)
                tokenRow("Новый ввод", today[0]); tokenRow("Вывод", today[1])
                tokenRow("Чтение кэша", today[2]); tokenRow("Запись кэша", today.getOrNull(3) ?: 0L)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
                Text("По моделям", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Токены", "Стоимость", "Запросы").forEachIndexed { i, label ->
                        FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                    }
                }
                val profiles = vm.profiles
                if (profiles.isEmpty()) Text("За этот период нет датированной активности. Более ранние данные доступны за всё время.")
                profiles.forEach { p ->
                    val arr = vm.usageMonth(p.id)
                    val value = when (tab) {
                        0 -> "${arr[0] + arr[1]} ток."
                        1 -> if (vm.settings.priceIn > 0 || vm.settings.priceOut > 0) "≈ \$%.2f".format(vm.costOf(arr[0], arr[1])) else "—"
                        else -> "${arr[3]} запросов"
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(p.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(value, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text("Всего: ${total[0] + total[1]} токенов, ${total[3]} запросов.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun tokenRow(label: String, value: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value.toString(), style = MaterialTheme.typography.labelMedium)
    }
}
