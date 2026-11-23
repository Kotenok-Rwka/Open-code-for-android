package com.pocketcode.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.pocketcode.SyncConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoSyncScreen(
    vm: com.pocketcode.ui.ChatController,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.checkSyncServer(c.serverUrl, c.token) }) { Text("Проверить подключение") }
            OutlinedButton(onClick = { update { it.copy(serverUrl = "http://127.0.0.1:8765") } }) { Text("Этот телефон") }
            val scan = rememberQrScanner { raw ->
                val uri = runCatching { android.net.Uri.parse(raw) }.getOrNull()
                val url = uri?.getQueryParameter("url") ?: raw.substringBefore("?token=")
                val tok = uri?.getQueryParameter("token") ?: raw.substringAfter("?token=").takeIf { raw.contains("?token=") } ?: ""
                update { it.copy(serverUrl = url, token = tok) }
                vm.checkSyncServer(url, tok)
            }
            OutlinedButton(onClick = scan) { Text("Сканировать QR") }
        }
        OutlinedTextField(
            c.token, { v -> update { it.copy(token = v) } },
            label = { Text("Токен удалённого доступа") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            c.password, { v -> update { it.copy(password = v) } },
            label = { Text("Пароль E2E-шифрования (необязательно)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Только чаты", style = MaterialTheme.typography.bodyMedium)
                Text("Не синхронизировать файлы проекта.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = c.onlyChats, onCheckedChange = { v -> update { it.copy(onlyChats = v, onlyFiles = false) } })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Только файлы", style = MaterialTheme.typography.bodyMedium)
                Text("Не синхронизировать историю чата.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = c.onlyFiles, onCheckedChange = { v -> update { it.copy(onlyFiles = v, onlyChats = false) } })
        }
        androidx.compose.material3.HorizontalDivider()
        Text("Прямая синхронизация телефон ↔ телефон", style = MaterialTheme.typography.titleSmall)
        var peer by remember { mutableStateOf("") }
        val p2pIp = remember { com.pocketcode.P2PSync.localIp() }
        Text("IP этого телефона: ${p2pIp.ifBlank { "нет Wi-Fi" }} · порт ${com.pocketcode.P2PSync.PORT}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(peer, { peer = it }, label = { Text("IP второго телефона") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { com.pocketcode.P2PSync.start(); vm.syncCheck = "P2P-приём включён" }) { Text("Включить приём") }
            OutlinedButton(onClick = { com.pocketcode.P2PSync.stop(); vm.syncCheck = "P2P-приём выключен" }) { Text("Выключить") }
            Button(onClick = {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                    val payload = kotlinx.serialization.json.buildJsonObject {
                        put("version", kotlinx.serialization.json.JsonPrimitive(2))
                        put("device", kotlinx.serialization.json.JsonPrimitive(android.os.Build.MODEL))
                        put("text", kotlinx.serialization.json.JsonPrimitive(vm.lastUserText().orEmpty().take(2000)))
                    }.toString()
                    val r = com.pocketcode.P2PSync.send(peer.trim(), payload)
                    vm.syncCheck = r.fold({ "P2P: $it" }, { "P2P ошибка: ${it.message}" })
                }
            }, enabled = peer.isNotBlank()) { Text("Отправить") }
        }
        Text("Если пароль задан, данные шифруются на телефоне AES-256-GCM и ПК не видит их в открытом виде. Пароль должен совпадать на обоих устройствах.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Для работы через интернет (не только Wi-Fi): открой на ПК http://127.0.0.1:8765/api/sync/token, " +
                "скопируй токен сюда. Адрес ПК должен быть доступен извне — например туннель Cloudflare (cloudflared tunnel --url http://localhost:8765) " +
                "или HTTPS-прокси. Без токена удалённые подключения отклоняются.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            OutlinedButton(onClick = {
                val link = c.serverUrl.trim().trimEnd('/') + if (c.token.isBlank()) "" else "?token=" + c.token
                clip.setText(androidx.compose.ui.text.AnnotatedString(link))
            }) { Text("Скопировать ссылку") }
            val qr = remember(c.serverUrl, c.token) { QrCode.encode(c.serverUrl.trim().trimEnd('/') + if (c.token.isBlank()) "" else "?token=" + c.token) }
            if (qr != null) {
                androidx.compose.foundation.Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = "QR настройки синхронизации",
                    modifier = Modifier.size(140.dp),
                )
            }
        }
        Text(vm.syncCheck, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Проще всего: запусти компаньон на ПК, затем нажми «Проверить подключение» с адресом http://IP_ПК:8765. " +
                "IP ПК видно в его Wi-Fi-настройках.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Только Wi-Fi", style = MaterialTheme.typography.bodyMedium)
                Text("Выключи, чтобы синхронизироваться по мобильному интернету (для удалённого доступа).", style = MaterialTheme.typography.bodySmall)
            }
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
