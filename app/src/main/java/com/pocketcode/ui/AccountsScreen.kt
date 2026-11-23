package com.pocketcode.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.pocketcode.accounts.AccountKind
import com.pocketcode.accounts.AccountStore

/** Подключение аккаунтов: токен можно вставить вручную или открыть сайт сервиса. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(vm: ChatController, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var accountError by remember { mutableStateOf<String?>(null) }
    tick.toString()
    Scaffold(topBar = {
        AppTopBar(title = { Text("Аккаунты сервисов") }, navigationIcon = {
            IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(12.dp)) {
            Text("Подключи сервис через токен аккаунта или открой его сайт. Токен хранится только на телефоне и подставляется в запросы как Bearer. Файлы проекта остаются на телефоне: модель видит их через инструменты PocketCode (read_file/write_file/bash) и сама решает, что прочитать и изменить — сервис получает только текст запроса и нужные фрагменты, а не весь телефон.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Card(
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
            ) {
                Text(
                    "Бесплатные тарифы: важные ограничения",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
                Text(
                    "• ChatGPT Free: обычный вход в чат НЕ даёт доступа к API для агента. Нужен план с доступом к API или ключ API. " +
                        "Если «Проверить» пишет HTTP 401/403 — это ограничение тарифа, а не ошибка PocketCode.\n" +
                        "• Gemini: бесплатный ключ Google AI Studio работает, но имеет дневной лимит запросов.\n" +
                        "• Claude Free: обычный вход в claude.ai не работает с API; нужен ключ из console.anthropic.com (там есть бесплатные лимиты для нового аккаунта).\n" +
                        "• Бесплатные OpenAI-совместимые сервисы (OpenRouter :free, Groq, NVIDIA) подключаются в «Моделях и ключах» по шаблону.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                Text(
                    "Всегда нажимай «Проверить» после вставки токена — так сразу видно, будет ли работать агент.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            accountError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            AccountKind.values().forEach { kind ->
                val connected = AccountStore.token(ctx, kind) != null
                androidx.compose.material3.Card(
                    Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(kind.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (connected) "✓ Подключён ${AccountStore.email(ctx, kind).orEmpty()}" else "Не подключён",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    var token by remember { mutableStateOf("") }
                    OutlinedTextField(token, { token = it }, label = { Text("Токен / ключ аккаунта") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (kind == AccountKind.CHATGPT) {
                            Button(onClick = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    val r = runCatching { com.pocketcode.accounts.ChatGptOAuth.signIn(ctx) }
                                    r.onSuccess { t ->
                                        vm.connectLocalEndpoint("https://api.openai.com/v1", "gpt-4o", t)
                                        accountError = null; tick++
                                    }.onFailure { e -> accountError = "Вход не удался: ${e.message}" }
                                }
                            }) { Text("Войти через браузер") }
                        }
                        TextButton(onClick = {
                            if (token.isBlank()) return@TextButton
                            val base = when (kind) {
                                AccountKind.CHATGPT -> "https://api.openai.com/v1"
                                AccountKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
                                else -> "https://api.openai.com/v1"
                            }
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                val r = com.pocketcode.accounts.AccountChecker.check(base, token.trim(), if (kind == AccountKind.GEMINI) "gemini-2.5-flash" else "gpt-4o")
                                accountError = (if (r.ok) "✓ " else "✗ ") + r.message
                            }
                        }) { Text("Проверить") }
                        Button(onClick = {
                            if (token.isNotBlank()) {
                                AccountStore.save(ctx, kind, token.trim())
                                if (kind == AccountKind.CHATGPT || kind == AccountKind.GEMINI) vm.connectLocalEndpoint(when (kind) {
                                    AccountKind.CHATGPT -> "https://api.openai.com/v1"
                                    AccountKind.CLAUDE -> "https://api.anthropic.com/v1"
                                    AccountKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
                                    else -> "https://api.openai.com/v1"
                                }, when (kind) {
                                    AccountKind.CHATGPT -> "gpt-4o"
                                    AccountKind.GEMINI -> "gemini-2.5-flash"
                                    else -> kind.name.lowercase()
                                }, token.trim())
                                token = ""; tick++
                            }
                        }) { Text("Подключить") }
                        if (kind == AccountKind.CHATGPT && connected) {
                            TextButton(onClick = {
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    val list = runCatching { com.pocketcode.accounts.ChatGptOAuth.models(ctx) }.getOrDefault(emptyList())
                                    accountError = if (list.isEmpty()) "Не удалось получить список моделей" else "Модели: " + list.take(12).joinToString(", ")
                                }
                            }) { Text("Модели аккаунта") }
                        }
                        TextButton(onClick = {
                            runCatching {
                                ctx.startActivity(Intent(android.content.Intent.ACTION_VIEW, Uri.parse(kind.site)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }) { Text("Открыть сайт") }
                        if (connected) TextButton(onClick = { AccountStore.remove(ctx, kind); tick++ }) { Text("Отключить") }
                    }
                }
                }
            }
            Text("Важно: ChatGPT и Gemini говорят по OpenAI-совместимому протоколу — их токены работают сразу. Claude использует другой протокол: для него нужен API-ключ из console.anthropic.com (кнопка «Открыть сайт»). Обычный вход в claude.ai не даёт доступа API — это ограничение сервиса, а не PocketCode. Остальные сервисы можно подключить через OpenAI-совместимый адрес вручную.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
