package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pocketcode.SshProfile
import com.pocketcode.SshProfiles

/** Экран подключений к локальным моделям и удалённым серверам. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    templates: List<String>,
    sshProfiles: List<SshProfile>,
    onSaveSsh: (SshProfile, String) -> Unit,
    onDeleteSsh: (String) -> Unit,
    onBack: () -> Unit,
) = ConnectionsPage(onBack = onBack) {
    Text("Локальные модели", style = MaterialTheme.typography.titleMedium)
    Text(
        "LM Studio, llama.cpp, LocalAI и vLLM подключаются как OpenAI-совместимый провайдер: " +
            "в настройках моделей выбери нужный шаблон и укажи адрес компьютера в Wi-Fi (например http://192.168.1.10:1234/v1). " +
            "Список моделей подтянется автоматически. Для GGUF прямо на телефоне нужен внешний рантайм — " +
            "приложение управляет им через локальный сервер, а не эмулирует его само.",
    )
    for (t in templates) Text("• $t", style = MaterialTheme.typography.bodySmall)
    Text("SSH-подключения", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var user by remember { mutableStateOf("root") }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    for (p in sshProfiles) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${p.name}: ${p.user}@${p.host}:${p.port}", modifier = Modifier.padding(vertical = 6.dp))
            TextButton(onClick = { onDeleteSsh(p.name) }) { Text("Удалить") }
        }
    }
    OutlinedTextField(name, { name = it }, label = { Text("Имя подключения") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(host, { host = it }, label = { Text("Адрес сервера") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(port, { port = it }, label = { Text("Порт") }, modifier = Modifier.weight(0.35f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(user, { user = it }, label = { Text("Пользователь") }, modifier = Modifier.weight(0.65f))
    }
    OutlinedTextField(key, { key = it }, label = { Text("Путь к приватному ключу (необязательно)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(password, { password = it }, label = { Text("Пароль (необязательно)") }, modifier = Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = {
        val p = SshProfile(name.trim(), host.trim(), port.toIntOrNull() ?: 22, user.trim(), key.trim())
        error = SshProfiles.validate(p)
        if (error == null) { onSaveSsh(p, password); name = ""; host = ""; password = "" }
    }) { Text("Сохранить подключение") }
    Text(
        "Пароль хранится в защищённом хранилище приложения. Для входа по паролю в Linux-окружении нужен sshpass " +
            "(apk add openssh-client sshpass). По ключу работает обычный ssh — безопаснее.",
        style = MaterialTheme.typography.bodySmall,
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsPage(title: String = "Подключения", onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text(title) },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}
