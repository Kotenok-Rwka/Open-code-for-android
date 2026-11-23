package com.pocketcode.ui

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import com.pocketcode.LocalModelAdvice
import com.pocketcode.LocalModelCatalog

/** Каталог локальных моделей: фильтры, сортировка и подбор под железо телефона. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModelsCatalogScreen(
    vm: ChatController,
    onUse: (LocalModelAdvice) -> Unit,
    onInstall: (LocalModelAdvice) -> Unit,
    onBack: () -> Unit,
) = Scaffold(
    topBar = {
        AppTopBar(
            title = { Text("Локальные модели") },
            navigationIcon = { IconButton(onClick = onBack) { androidx.compose.material3.Icon(backIcon(), contentDescription = "Назад") } },
        )
    },
) { pad ->
    val context = LocalContext.current
    val device = remember { detectDevice(context) }
    var query by remember { mutableStateOf("") }
    var capability by remember { mutableStateOf<com.pocketcode.ModelCapability?>(null) }
    var sort by remember { mutableStateOf(com.pocketcode.LocalModelCatalog.SortBy.SUITABILITY) }
    var code by remember { mutableStateOf(true) }
    var vision by remember { mutableStateOf(false) }
    var fast by remember { mutableStateOf(false) }
    var limitRam by remember { mutableStateOf(false) }
    var modelUrl by remember { mutableStateOf("") }
    var externalUrl by remember { mutableStateOf("http://127.0.0.1:1234/v1") }

    val list = LocalModelCatalog.filter(
        query = query,
        capability = capability,
        maxRamGb = if (limitRam) device.totalRamGb else null,
        sortBy = sort,
        device = device,
        code = code, vision = vision, fast = fast,
    )
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(10.dp)) {
        Text("Подбор по железу", style = MaterialTheme.typography.titleMedium)
        Text("На устройстве ≈${device.totalRamGb} ГБ ОЗУ и ≈${device.freeStorageGb} ГБ свободно. Приоритеты влияют на порядок рекомендаций.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = code, onClick = { code = !code }, label = { Text("Код") })
            FilterChip(selected = vision, onClick = { vision = !vision }, label = { Text("Зрение") })
            FilterChip(selected = fast, onClick = { fast = !fast }, label = { Text("Быстрая") })
            FilterChip(selected = limitRam, onClick = { limitRam = !limitRam }, label = { Text("По ОЗУ") })
        }
        OutlinedTextField(query, { query = it }, label = { Text("Поиск модели") }, modifier = Modifier.fillMaxWidth())
        Text("Сортировка", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.pocketcode.LocalModelCatalog.SortBy.entries.forEach { s ->
                FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) })
            }
        }
        Text("Тип", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = capability == null, onClick = { capability = null }, label = { Text("Все") })
            com.pocketcode.ModelCapability.entries.forEach { c ->
                FilterChip(selected = capability == c, onClick = { capability = c }, label = { Text(c.label) })
            }
        }
        androidx.compose.material3.HorizontalDivider()
        SectionHeader("Локальный рантайм", "GGUF работает через llama.cpp/Ollama внутри Linux на телефоне — свои модели, без облака")
        OutlinedTextField(modelUrl, { modelUrl = it }, label = { Text("Своя ссылка на .gguf (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        var gpu by remember { mutableStateOf(vm.localGpuLayers.toString()) }
        var ctx by remember { mutableStateOf(vm.localContext.toString()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(gpu, { gpu = it }, label = { Text("GPU-слои (0 = CPU)") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(ctx, { ctx = it }, label = { Text("Контекст") }, modifier = Modifier.weight(1f), singleLine = true)
        }
        Button(onClick = {
            vm.setLocalSettings(vm.localPort, gpu.toIntOrNull() ?: 0, ctx.toIntOrNull() ?: 8192, vm.localModel)
        }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки сервера") }
        Text(vm.localRuntimeStatus, style = MaterialTheme.typography.bodySmall)
        val installed = remember(vm.localInstallStatus) { vm.localDownloader.installed() }
        if (installed.isNotEmpty()) {
            Text("Установленные модели:", style = MaterialTheme.typography.labelMedium)
            installed.forEach { f ->
                TextButton(onClick = { vm.setLocalSettings(vm.localPort, vm.localGpuLayers, vm.localContext, f.name) }) {
                    Text("• ${f.name} — ${f.length() / 1024 / 1024} МБ${if (vm.localModel == f.name) " ✓" else ""}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val log = vm.localServerLog()
        if (log.isNotBlank()) {
            Text("Лог сервера:", style = MaterialTheme.typography.labelMedium)
            Text(log.takeLast(1200), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.installLocalRuntime() }, enabled = !vm.localRuntimeBusy) { Text("Подготовить") }
            OutlinedButton(onClick = { vm.startLocalServer() }, enabled = !vm.localRuntimeBusy) { Text("Запустить сервер") }
            OutlinedButton(onClick = { vm.stopLocalServer() }) { Text("Остановить") }
            OutlinedButton(onClick = { vm.connectLocalEndpoint("http://127.0.0.1:8080/v1", "local-model") }) { Text("Подключить") }
        }
        Text("Внешний сервер (LM Studio/Ollama):", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(externalUrl, { externalUrl = it }, label = { Text("http://IP:Port/v1") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { vm.connectLocalEndpoint(externalUrl, "local-model") }) { Text("Подключить") }
        }
        Text("Найдено: ${list.size}", style = MaterialTheme.typography.bodySmall)
        list.forEach { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("★ ${m.quality}/10", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    val badge = LocalModelCatalog.badge(m)
                    if (badge.isNotBlank()) Text("🏆 ${I18n.t(badge)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    Text(m.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(m.note, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${m.paramsB} млрд параметров · от ${m.minRamGb} ГБ ОЗУ · ${m.contextK}k контекст · ${m.quant}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(m.capabilities.joinToString(" · ") { it.label }, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadLocalModel(m.id, m.quant, modelUrl) }) { Text("Скачать GGUF") }
                        TextButton(onClick = { onInstall(m) }) { Text("Как вручную") }
                        OutlinedButton(onClick = { onUse(m) }) { Text("Использовать") }
                    }
                }
            }
        }
        Text(
            "Рекомендации приблизительные: реальная скорость зависит от рантайма (LM Studio, llama.cpp, Ollama), квантования и GPU. " +
                "Модели с зрением требуют мультимодального рантайма; для кода лучше Qwen2.5 Coder, для слабых телефонов — 3B.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun detectDevice(context: Context): com.pocketcode.LocalModelCatalog.Device {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    val ramGb = (mem.totalMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(2)
    val freeGb = (mem.availMem / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
    val stat = android.os.StatFs(context.filesDir.path)
    val storageGb = (stat.availableBlocksLong * stat.blockSizeLong / (1024L * 1024L * 1024L)).toInt().coerceAtLeast(1)
    return com.pocketcode.LocalModelCatalog.Device(ramGb, minOf(freeGb, storageGb), android.os.Build.VERSION.SDK_INT)
}
