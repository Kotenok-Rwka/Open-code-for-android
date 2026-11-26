package com.pocketcode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Выбор языка. При первом запуске ([first] = true) заранее выбран язык системы, но спрашиваем всё равно.
 * Языки показаны своими названиями, чтобы их можно было найти на любом языке.
 */
@Composable
fun LanguagePicker(first: Boolean, onDone: () -> Unit) {
    val langs = remember { I18n.available }
    var sel by remember { mutableStateOf(if (first) I18n.systemDefault() else I18n.code) }
    var onlineOn by remember { mutableStateOf(I18n.onlineStatus()) }
    var engine by remember { mutableStateOf("google") }
    var apiKey by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("https://translate.disroot.org") }
    androidx.compose.runtime.LaunchedEffect(onlineOn) {
        if (onlineOn) {
            engine = I18n.engine()
            apiKey = I18n.apiKey()
            server = I18n.serverUrl()
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(if (first) "Language · Язык · اللغة" else "Язык", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (first) "Choose the app language. You can change it later in Settings." else "Выберите язык приложения.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                for (l in langs) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = sel == l.code, role = Role.RadioButton, onClick = { sel = l.code }).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = sel == l.code, onClick = { sel = l.code })
                        androidx.compose.material3.Text(l.native, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            if (sel !in setOf("ru", "en", "ar", "es", "de", "zh", "tr", "fr", "it", "pt", "hi", "id", "bn")) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = onlineOn, onCheckedChange = { onlineOn = it })
                    Column(Modifier.padding(start = 8.dp)) {
                        androidx.compose.material3.Text("Онлайн-переводчик", style = MaterialTheme.typography.titleSmall)
                        androidx.compose.material3.Text(
                            "Переводить интерфейс на лету и сохранять перевод на устройстве",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onlineOn) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        var expJson by remember { mutableStateOf("") }
                        var ovRu by remember { mutableStateOf("") }
                        var ovTr by remember { mutableStateOf("") }
                        var msg by remember { mutableStateOf("") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedButton(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(I18n.exportOnlineCache()))
                                msg = "Переводы скопированы в буфер обмена"
                            }) { Text("Экспорт переводов") }
                            androidx.compose.material3.OutlinedButton(onClick = {
                                val n = I18n.importOnlineCache(expJson)
                                msg = if (n > 0) "Импортировано строк: $n" else "В вставленном тексте нет переводов"
                                if (n > 0) expJson = ""
                            }) { Text("Импорт переводов") }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = expJson,
                            onValueChange = { expJson = it },
                            label = { Text("JSON с переводами (вставьте сюда для импорта)") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.OutlinedTextField(
                                value = ovRu,
                                onValueChange = { ovRu = it },
                                label = { Text("Русская строка") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = ovTr,
                                onValueChange = { ovTr = it },
                                label = { Text("Правильный перевод") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    I18n.overrideTranslation(ovRu.trim(), ovTr.trim())
                                    msg = "Исправление сохранено"; ovRu = ""; ovTr = ""
                                },
                                enabled = ovRu.isNotBlank() && ovTr.isNotBlank(),
                            ) { Text("Сохранить исправление") }
                            if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val engineNames = mapOf("google" to "Google", "deepl" to "DeepL", "libre" to "LibreTranslate")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            engineNames.forEach { (id, name) ->
                                androidx.compose.material3.FilterChip(
                                    selected = engine == id,
                                    onClick = { engine = id },
                                    label = { androidx.compose.material3.Text(name) },
                                )
                            }
                        }
                        if (engine == "deepl") {
                            androidx.compose.material3.OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { androidx.compose.material3.Text("Ключ DeepL API (auth_key)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        if (engine == "libre") {
                            androidx.compose.material3.OutlinedTextField(
                                value = server,
                                onValueChange = { server = it },
                                label = { androidx.compose.material3.Text("Адрес сервера LibreTranslate") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            androidx.compose.material3.OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { androidx.compose.material3.Text("API-ключ (если требуется)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                    }
                }
            }
            Button(onClick = {
                I18n.choose(sel)
                I18n.setOnlineEnabled(onlineOn)
                I18n.setEngine(engine)
                I18n.setApiKey(apiKey.trim())
                I18n.setServerUrl(server)
                onDone()
            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                androidx.compose.material3.Text(
                    when (sel) { "ru" -> "Продолжить"; "ar" -> "متابعة"; "es" -> "Continuar"; "de" -> "Weiter"; "zh" -> "继续"; "tr" -> "Devam"; "uk" -> "Продовжити"; else -> "Continue" },
                )
            }
        }
    }
}
