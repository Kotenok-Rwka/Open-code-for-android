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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.provider.Settings

/** Один экран со всеми разрешениями и понятным статусом. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityCenterScreen(vm: ChatController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Scaffold(topBar = {
        AppTopBar(title = { Text("Центр безопасности") }, navigationIcon = {
            IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
            tick.toString(); // перерисовка при возврате с экрана настроек
            val overlay = Settings.canDrawOverlays(ctx)
            val notifications = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = overlay, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text("Окна поверх приложений")
                    Text(if (overlay) "Включено: подтверждения появятся поверх экрана" else "Нужно для подтверждений в режиме телефона", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = {
                    runCatching {
                        ctx.startActivity(android.content.Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Включить") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = notifications, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text("Уведомления")
                    Text(if (notifications) "Включены" else "Нужны для напоминаний и фоновых задач", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = vm.phoneMode.enabled, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text("Служба доступности")
                    Text(if (vm.phoneMode.enabled) "Включена" else "Нужна для управления телефоном", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = vm.linuxInstalled, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text("Linux-окружение")
                    Text(if (vm.linuxInstalled) "Установлено" else "Нужно для bash, Python и локальной модели", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Режим «Только чтение»: ${if (vm.readOnlyMode) "вкл" else "выкл"} · Чат без проекта: ${if (vm.chatOnlyMode) "вкл" else "выкл"}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { vm.setReadOnly(!vm.readOnlyMode); tick++ }, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.readOnlyMode) "Выключить «Только чтение»" else "Включить «Только чтение»")
            }
        }
    }
}
