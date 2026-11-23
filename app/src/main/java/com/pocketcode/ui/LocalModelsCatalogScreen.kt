package com.pocketcode.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pocketcode.LocalModelAdvice
import com.pocketcode.LocalModelCatalog

/** Каталог локальных моделей: фильтры, сортировка, скачивание и запуск своего сервера. */
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
    var gpu by remember { mutableStateOf(vm.localGpuLayers.toString()) }
    var ctx by remember { mutableStateOf(vm.localContext.toString()) }
    var port by remember { mutableStateOf(vm.localPort.toString()) }

    val list = LocalModelCatalog.filter(
        query = query,
        capability = capability,
        maxRamGb = if (limitRam) device.totalRamGb else null,
        sortBy = sort,
        device = device,
        code = code, vision = vision, fast = fast,
    )
    Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), Arrangement.spacedBy(12.dp)) {
        Text("Подбор по железу", style = MaterialTheme.typography.titleMedium)
        Text("На устройстве ≈${device.totalRamGb} ГБ ОЗУ и ≈${device.freeStorageGb} ГБ свободно. Приоритеты влияют только на порядок рекомендаций.")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = code, onClick = { code = !code }, label = { Text("Код", maxLines = 1) })
            FilterChip(selected = vision, onClick = { vision = !vision }, label = { Text("Зрение", maxLines = 1) })
            FilterChip(selected = fast, onClick = { fast = !fast }, label = { Text("Быстрая", maxLines = 1) })
            FilterChip(selected = limitRam, onClick = { limitRam = !limitRam }, label = { Text("По ОЗУ", maxLines = 1) })
        }
        OutlinedTextField(query, { query = it }, label = { Text("Поиск модели") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Сортировка", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            com.pocketcode.LocalModelCatalog.SortBy.entries.forEach { s ->
                FilterChip(
                    selected = sort == s,
                    onClick = { sort = s },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1) },
                )
            }
        }
        Text("Тип", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = capability == null, onClick = { capability = null }, label = { Text("Все", maxLines = 1) })
            com.pocketcode.ModelCapability.entries.forEach { c ->
                FilterChip(selected = capability == c, onClick = { capability = c }, label = { Text(c.label, maxLines = 1) })
            }
        }

        androidx.compose.material3.HorizontalDivider()
        SectionHeader("Свой сервер (llama.cpp в Linux)", "Модель запускается на телефоне, без интернета")

        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(14.dp), Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    modelUrl, { modelUrl = it },
                    label = { Text("Своя ссылка на .gguf (необязательно)") },
                    placeholder = { Text("https://huggingface.co/…/model.gguf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port, { port = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Порт") },
                        singleLine = true,
                        isError = port.toIntOrNull()?.let { it !in 1024..65535 } ?: true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        gpu, { gpu = it.filter { ch -> ch.isDigit() } },
                        label = { Text("GPU-слои") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        ctx, { ctx = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Контекст") },
                        singleLine = true,
                        isError = ctx.toIntOrNull()?.let { it !in 512..131072 } ?: true,
                        modifier = Modifier.weight(1.2f),
                    )
                }
                Text(
                    "GPU-слои: 0 — только CPU (так работает встроенный llama.cpp). Контекст: 4096–8192 для телефона, больше — если хватает памяти.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        vm.setLocalSettings(
                            port.toIntOrNull() ?: vm.localPort,
                            gpu.toIntOrNull() ?: 0,
                            ctx.toIntOrNull() ?: 8192,
                            vm.localModel,
                        )
                    },
                    enabled = port.toIntOrNull() != null && ctx.toIntOrNull() != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сохранить настройки сервера") }
                if (vm.localRuntimeBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (vm.localRuntimeStatus.isNotBlank()) {
                    Text(vm.localRuntimeStatus, style = MaterialTheme.typography.bodySmall)
                }
                vm.localInstallStatus?.let {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        if (vm.localDownloader.isDownloading) TextButton(onClick = { vm.cancelLocalDownload() }) { Text("Отмена") }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.installLocalRuntime() }, enabled = !vm.localRuntimeBusy) { Text("Подготовить") }
                    Button(onClick = { vm.startLocalServer(vm.localPort) }, enabled = !vm.localRuntimeBusy) { Text("Запустить сервер") }
                    OutlinedButton(onClick = { vm.stopLocalServer() }, enabled = !vm.localRuntimeBusy) { Text("Остановить") }
                    OutlinedButton(
                        onClick = {
                            val model = vm.localModel.substringAfterLast('/').removeSuffix(".gguf").ifBlank { "local-model" }
                            vm.connectLocalEndpoint("http://127.0.0.1:${vm.localPort}/v1", model)
                        },
                        enabled = !vm.localRuntimeBusy,
                    ) { Text("Подключить") }
                }
            }
        }

        val installed = remember(vm.localInstallStatus, vm.localModel) { vm.localDownloader.installed() }
        if (installed.isNotEmpty()) {
            Text("Скачанные модели (${installed.size})", style = MaterialTheme.typography.labelMedium)
            installed.forEach { f ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        TextButton(onClick = { vm.setLocalSettings(vm.localPort, vm.localGpuLayers, vm.localContext, f.name) }, modifier = Modifier.weight(1f)) {
                            Text(
                                "• ${f.name} — ${f.length() / 1024 / 1024} МБ" + if (vm.localModel == f.name) "  ✓ выбрана" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        val log = vm.localServerLog()
        if (log.isNotBlank()) {
            Text("Лог сервера", style = MaterialTheme.typography.labelMedium)
            Text(log.takeLast(1200), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }

        SectionHeader("Внешний сервер (LM Studio / Ollama)", "Если запускать модель на телефоне не хочется")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                externalUrl, { externalUrl = it },
                label = { Text("http://IP:Port/v1") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connectLocalEndpoint(externalUrl, "local-model") }) { Text("Подключить") }
            OutlinedButton(onClick = { externalUrl = "http://127.0.0.1:1234/v1" }) { Text("LM Studio") }
            OutlinedButton(onClick = { externalUrl = "http://127.0.0.1:11434/v1" }) { Text("Ollama") }
        }

        androidx.compose.material3.HorizontalDivider()
        Text("Найдено: ${list.size}", style = MaterialTheme.typography.bodySmall)
        list.forEach { m ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp), Arrangement.spacedBy(6.dp)) {
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
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (vm.localRuntimeBusy && vm.localDownloader.isDownloading) {
                            Button(onClick = { vm.cancelLocalDownload() }) { Text("Отменить скачивание") }
                        } else {
                            Button(onClick = { vm.downloadLocalModel(m.id, m.quant, modelUrl) }, enabled = !vm.localRuntimeBusy) {
                                Text(if (vm.localRuntimeBusy) "Скачиваю…" else "Скачать GGUF")
                            }
                        }
                        OutlinedButton(onClick = { onUse(m) }) { Text("Использовать") }
                        TextButton(onClick = { onInstall(m) }) { Text("Как вручную") }
                    }
                }
            }
        }
        Text(
            "Рекомендации приблизительные: реальная скорость зависит от рантайма, квантования и памяти. " +
                "Для кода лучше Qwen2.5 Coder, для слабых телефонов — 3B. Скачанный файл остаётся в приложении и виден Linux-серверу.",
            style = MaterialTheme.typography.bodySmall,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
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
