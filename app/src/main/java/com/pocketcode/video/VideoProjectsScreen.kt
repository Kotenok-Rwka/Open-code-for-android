package com.pocketcode.video

import com.pocketcode.ui.backIcon
import com.pocketcode.ui.AppTopBar
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketcode.ui.AppIcons
import com.pocketcode.ui.Text
import java.io.File

/** Список видео с сохранёнными настройками: открыть и продолжить или забыть. Недавние сверху. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoProjectsScreen(store: VideoStore, v: VideoPipeline, onBack: () -> Unit, onOpen: (String) -> Unit) {
    BackHandler { onBack() }
    var list by remember { mutableStateOf(store.projects()) }
    Scaffold(topBar = {
        AppTopBar(
            title = { Text("Мои проекты", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(backIcon(), contentDescription = "Назад") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Для каждого видео помнятся его настройки: стиль, субтитры, отделка. Откройте проект, чтобы продолжить с того же места.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (list.isEmpty()) Text("Пока пусто: проекты появятся, когда вы откроете видео.", style = MaterialTheme.typography.bodyMedium)
            list.forEach { rel ->
                val exists = v.fileExists(rel)
                val s = store.project(rel)
                SoftCard {
                    Text(File(rel).name, style = MaterialTheme.typography.titleSmall)
                    if (!exists) {
                        Text("Файла больше нет в проекте", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else if (s != null) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.summary().forEach { tag -> AssistChip(onClick = {}, enabled = false, label = { Text(tag) }) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (exists) Button(enabled = v.ready && !v.busy, onClick = { onOpen(rel) }) { Text("Открыть") }
                        OutlinedButton(onClick = { store.forgetProject(rel); list = store.projects() }) { Text("Забыть") }
                    }
                }
            }
        }
    }
}
