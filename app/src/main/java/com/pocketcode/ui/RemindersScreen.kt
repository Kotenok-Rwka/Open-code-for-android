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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pocketcode.Reminders

/** Простые напоминания: текст + время, уведомление даже если приложение закрыто. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Reminders.load(ctx)) }
    var text by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("10") }
    fun refresh() { list = Reminders.load(ctx) }
    Scaffold(
        topBar = {
            AppTopBar(
                title = { Text("Напоминания") },
                navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            Arrangement.spacedBy(10.dp),
        ) {
            Text("Таймер и напоминания", style = MaterialTheme.typography.titleMedium)
            Text("Уведомление придёт, даже если PocketCode закрыт. Время считается от текущего момента.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, label = { Text("О чём напомнить") }, modifier = Modifier.fillMaxWidth())
            var phrase by remember { mutableStateOf("") }
            OutlinedTextField(phrase, { phrase = it }, label = { Text("Или напиши: через 2 часа проверить сборку") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = {
                com.pocketcode.Reminders.parseNatural(phrase)?.let { (what, at) ->
                    Reminders.add(ctx, what, at); phrase = ""; refresh()
                }
            }) { Text("Создать из фразы") }
            OutlinedTextField(minutes, { minutes = it }, label = { Text("Через сколько минут") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val m = minutes.toLongOrNull() ?: return@Button
                    if (text.isBlank() || m <= 0) return@Button
                    Reminders.add(ctx, text, System.currentTimeMillis() + m * 60_000L)
                    text = ""; refresh()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Добавить напоминание") }
            for (r in list.sortedBy { it.at }) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.at)) +
                                if (r.done) " · выполнено" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    TextButton(onClick = { Reminders.remove(ctx, r.id); refresh() }) { Text("✕") }
                }
            }
        }
    }
}
