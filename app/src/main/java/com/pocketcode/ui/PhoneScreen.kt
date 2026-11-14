// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketcode.phone.PhoneConfirm

/** Отдельный экран настройки режима «Управление телефоном»: три шага и состояние подключения. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScreen(vm: ChatController, onBack: () -> Unit, onToChat: () -> Unit) {
    val pm = vm.phoneMode
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Управление телефоном") },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Агент сможет смотреть на экран и управлять этим телефоном: нажимать, листать, вводить текст, открывать приложения, " +
                    "в том числе на отдельном фоновом экране. Идея и часть кода взяты из проекта Mika (Apache-2.0).",
                style = MaterialTheme.typography.bodySmall,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (pm.accessibilityOn) "✓ Служба доступности включена" else "✗ Служба доступности выключена", style = MaterialTheme.typography.bodyMedium)
                    Text(if (pm.helper.connected) "✓ " + pm.helper.message else "✗ Помощник не запущен", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (pm.accessibilityOn) "Агент уже может читать экран и нажимать. Помощник добавляет точный ввод и фоновый экран."
                        else "Без службы доступности агент ничего не увидит. Начни с шага 1.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Шаг 1. Служба доступности", style = MaterialTheme.typography.titleMedium)
            Text("Нужна, чтобы агент читал экран. В списке найди PocketCode и включи переключатель.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { pm.openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки доступности") }

            Text("Шаг 2. Помощник (по желанию)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Даёт точный ввод, запуск приложений и фоновый экран. С root: одна кнопка. Без root: беспроводная отладка Android 11+, " +
                    "сопряжение по коду нужно один раз.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { pm.startRoot() }, enabled = !pm.busy, modifier = Modifier.fillMaxWidth()) { Text("Запустить помощника (root)") }
            Text("Без root:", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { pm.beginPairing() }, enabled = !pm.busy, modifier = Modifier.fillMaxWidth()) { Text("Сопряжение с отладкой по Wi-Fi") }
            OutlinedButton(onClick = { pm.startWireless() }, enabled = !pm.busy, modifier = Modifier.fillMaxWidth()) { Text("Запустить помощника (без root)") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pm.enableAccessibilityViaHelper() }, enabled = !pm.busy && pm.helper.connected) { Text("Включить доступность") }
                OutlinedButton(onClick = { pm.stopHelper() }, enabled = !pm.busy && pm.helper.connected) { Text("Остановить помощника") }
            }
            pm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Шаг 3. Подтверждения", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pm.confirm == PhoneConfirm.EVERY, onClick = { pm.chooseConfirm(PhoneConfirm.EVERY) }, label = { Text("Каждое действие") })
                FilterChip(selected = pm.confirm == PhoneConfirm.RISKY, onClick = { pm.chooseConfirm(PhoneConfirm.RISKY) }, label = { Text("Только рискованные") })
            }
            Text(
                "Рискованные: оплата, удаление, выдача разрешений, звонки и сообщения. Поля паролей агент не заполняет никогда.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("Скриншоты для модели", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = pm.screenshots, onClick = { pm.chooseScreenshots(true) }, label = { Text("Со скриншотами") })
                FilterChip(selected = !pm.screenshots, onClick = { pm.chooseScreenshots(false) }, label = { Text("Только текст") })
            }
            Text(
                "Со скриншотами нужна модель, которая понимает изображения. «Только текст» работает с любой моделью: " +
                    "агент читает дерево экрана (подписи, кнопки, поля, координаты), картинки не отправляются и расходуется меньше токенов.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = { pm.chooseEnabled(true); vm.openCodeMode = false; vm.planMode = false; onToChat() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Включить режим и перейти в чат") }
            if (pm.enabled) {
                OutlinedButton(onClick = { pm.chooseEnabled(false) }, modifier = Modifier.fillMaxWidth()) { Text("Выключить режим") }
            }
        }
    }
}
