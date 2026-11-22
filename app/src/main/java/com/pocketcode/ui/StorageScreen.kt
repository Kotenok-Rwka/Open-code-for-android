package com.pocketcode.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

data class StorageRow(val name: String, val path: String, val bytes: Long, val count: Int)
data class StorageScan(val total: Long, val rows: List<StorageRow>)

object StorageScanner {
    fun scan(base: File): StorageScan {
        val dirs = listOf(
            "Данные приложения" to base,
            "Плагины" to File(base, "plugins"),
            "Навыки" to File(base, ".pocketcode/skills"),
            "Рабочие области" to File(base, "workspace"),
            "Пакеты Linux" to File(base, "linux"),
            "Локальные модели" to File(base, "models"),
            "Кэш" to File(base, "cache"),
        )
        val rows = dirs.map { (name, dir) ->
            var bytes = 0L
            var count = 0
            fun walk(f: File) {
                if (!f.exists()) return
                if (f.isFile) { bytes += f.length(); count++; return }
                f.listFiles()?.forEach { walk(it) }
            }
            walk(dir)
            StorageRow(name, dir.absolutePath, bytes, count)
        }
        return StorageScan(rows.first().bytes, rows)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(onBack: () -> Unit) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Хранилище") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    val ctx = LocalContext.current
    var scan by remember { mutableStateOf(StorageScanner.scan(ctx.filesDir)) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("category") }
    var expanded by remember { mutableStateOf<String?>(null) }
    val rows = scan.rows.filter { it.name.contains(query, ignoreCase = true) || it.path.contains(query, ignoreCase = true) }
        .let { if (sort == "largest") it.sortedByDescending { r -> r.bytes } else it }
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Хранилище устройства", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("%.1f МБ".format(scan.total / 1024f / 1024f), style = MaterialTheme.typography.headlineMedium)
                Text("Измерено на этом устройстве", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { scan = StorageScanner.scan(ctx.filesDir) }) { Text("Пересканировать") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Поиск элементов или путей") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sort == "category", onClick = { sort = "category" }, label = { Text("Категории") })
            FilterChip(selected = sort == "largest", onClick = { sort = "largest" }, label = { Text("Крупнейшие") })
            FilterChip(selected = sort == "unknown", onClick = { sort = "unknown" }, label = { Text("Не измерено") })
        }
        rows.forEach { r ->
            Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == r.name) null else r.name }) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("%.1f МБ".format(r.bytes / 1024f / 1024f), style = MaterialTheme.typography.labelMedium)
                    }
                    Text("${r.count} элементов", style = MaterialTheme.typography.bodySmall)
                    if (expanded == r.name) Text(r.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
