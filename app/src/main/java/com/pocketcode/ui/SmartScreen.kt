package com.pocketcode.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Toggle(title: String, about: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Все «умные» функции в одном месте, у каждой свой выключатель. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartScreen(vm: ChatController, onBack: () -> Unit, onOpenRoot: () -> Unit) {
    val sf = vm.smart
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Умные функции") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Каждую функцию можно включить или выключить отдельно. Изменения действуют сразу.", style = MaterialTheme.typography.bodyMedium)

            Group("Быстрые режимы") {
                var presetNote by remember { mutableStateOf<String?>(null) }
                Text("Один выбор меняет сразу несколько настроек. Потом любую можно поправить ниже.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = false, onClick = { presetNote = vm.applyPreset("eco") }, label = { Text("Экономно") })
                    FilterChip(selected = false, onClick = { presetNote = vm.applyPreset("max") }, label = { Text("Максимум") })
                    FilterChip(selected = false, onClick = { presetNote = vm.applyPreset("free") }, label = { Text("Бесплатно") })
                }
                presetNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }

            Group("Авто-режим и безопасность") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Авто-режим", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (vm.rootAuto) "Сейчас включён" else "Сейчас выключен. Включается на экране «Root и Android» после предупреждения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onOpenRoot) { Text("Открыть") }
                }
                Toggle(
                    "Предохранитель",
                    "Если за 10 минут нейросеть одобрила слишком много файловых действий, авто-режим сам выключается.",
                    sf.breakerOn,
                ) { sf.changeBreaker(it) }
                if (sf.breakerOn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (n in listOf(10, 20, 40)) {
                            FilterChip(selected = sf.breakerLimit == n, onClick = { sf.changeBreakerLimit(n) }, label = { Text("$n действий") })
                        }
                    }
                }
                Toggle(
                    "Показывать вердикт в чате",
                    "Когда нейросеть одобрила действие, в ленте появляется строка с причиной.",
                    sf.verdictNotes,
                ) { sf.changeVerdictNotes(it) }
                Toggle(
                    "Двойная проверка удаления",
                    "В авто-режиме удаление одобряют две нейросети. Если второй нет или она сомневается, решаешь ты. Нужен режим «Нейросеть».",
                    sf.doubleDelete,
                ) { sf.changeDoubleDelete(it) }
                if (sf.doubleDelete) {
                    Text("Вторая проверяющая нейросеть", style = MaterialTheme.typography.labelLarge)
                    if (vm.profiles.isEmpty()) {
                        Text("Нет профилей моделей. Добавь их в «Модели и ключи».", style = MaterialTheme.typography.bodySmall)
                    }
                    for (p in vm.profiles) {
                        FilterChip(
                            selected = sf.secondReviewer == p.id,
                            onClick = { sf.changeSecondReviewer(p.id) },
                            label = { Text("${p.label} (${p.model})") },
                        )
                    }
                }
                Toggle(
                    "Корзина вместо удаления",
                    "Команда rm у агента переносит файлы в папку .pocketcode-trash, а не стирает. Через 7 дней корзина чистится. Не действует на root и Python.",
                    sf.trashOn,
                ) { sf.changeTrash(it) }
            }

            Group("Откат и журнал") {
                Toggle(
                    "Откат правок агента",
                    "Перед записью файла запоминается прежний вид. Кнопка ниже возвращает файлы, изменённые в последнем ходе. Команды оболочки и Python не откатываются.",
                    sf.undoOn,
                ) { vm.changeUndo(it) }
                @Suppress("UNUSED_VARIABLE") val ver = vm.undoVersion
                val n = vm.undo.pendingFiles()
                OutlinedButton(onClick = { vm.rollbackLastTurn() }, enabled = sf.undoOn && n > 0, modifier = Modifier.fillMaxWidth()) {
                    Text(if (n > 0) "Откатить последний ход ($n файл.)" else "Откатывать пока нечего")
                }
                Toggle(
                    "Журнал авто-режима в файл",
                    "Что одобрила нейросеть, что спросили у тебя. Можно открыть и посмотреть, что агент делал, пока тебя не было.",
                    sf.logToFile,
                ) { sf.changeLogToFile(it) }
                var showLog by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showLog = true }, modifier = Modifier.weight(1f)) { Text("Открыть журнал") }
                    OutlinedButton(onClick = { vm.clearAutoLog() }, modifier = Modifier.weight(1f)) { Text("Очистить") }
                }
                if (showLog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showLog = false },
                        confirmButton = { TextButton(onClick = { showLog = false }) { Text("Закрыть") } },
                        title = { Text("Журнал авто-режима") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(vm.autoLogText().ifBlank { "Журнал пуст." }, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
            }

            Group("Модели и экономия") {
                Toggle(
                    "Автовыбор модели",
                    "Короткие вопросы без правок идут на модель-помощник, остальное на основную. Нужна заполненная модель-помощник в настройках.",
                    vm.autoRoute,
                ) { vm.setAutoRouteValue(it) }
                Toggle(
                    "Писать, какая модель ответила",
                    "В чате появляется пометка, когда ответ дала модель-помощник.",
                    sf.routeNotes,
                    enabled = vm.autoRoute,
                ) { sf.changeRouteNotes(it) }
                Toggle(
                    "Умное сокращение вывода",
                    "Длинный вывод команд режется с сохранением начала и конца, где обычно итог и ошибки. Выключено: сохраняется только начало.",
                    sf.smartTrim,
                ) { sf.changeSmartTrim(it) }
            }

            Group("Память") {
                Toggle(
                    "Карта проекта",
                    "В начале чата агенту выдаётся дерево файлов проекта, чтобы он не тратил шаги на поиск. Подхватывается при новом чате.",
                    sf.memoryOn,
                ) { sf.changeMemory(it) }
            }
        }
    }
}
