package com.pocketcode.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/** Ссылки на картинки, которые модель вставляет в ответ (например, сгенерированный SVG/PNG). */
object ChatImages {
    private val LINK = Regex("""(?:src=["']?|\()([^\s"'<>()]+?\.(?:png|jpe?g|webp|svg))\b""", RegexOption.IGNORE_CASE)

    /** Возвращает уникальные имена файлов-картинок из текста: markdown-ссылки и HTML. */
    fun find(text: String): List<String> {
        val local = LINK.findAll(text).map { it.groupValues[1].trim('`').trim(',').trim(Char(41)) }
            .filter { raw -> !raw.contains("://") && !raw.startsWith("www.") }
        return local.map { it.substringAfterLast('/') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .toList()
    }

    /** Имена готовых видео в тексте ответа (мультики, монтаж, субтитры). */
    fun findVideos(text: String): List<String> = Regex("""([\w./\-]+?\.mp4)\b""", RegexOption.IGNORE_CASE)
        .findAll(text)
        .map { it.groupValues[1].trim().substringAfterLast('/') }
        .filter { it.isNotBlank() }
        .distinct()
        .take(6)
        .toList()

    fun resolve(projectRoot: File, name: String): File? {
        val direct = File(projectRoot, name)
        if (direct.isFile) return direct
        return projectRoot.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }
}

/** Готовое видео прямо в чате: превью по имени файла и кнопка «Поделиться». Файл остаётся в проекте. */
@Composable
fun InlineChatVideos(text: String, projectRoot: File, modifier: Modifier = Modifier) {
    val names = remember(text) { ChatImages.findVideos(text) }
    if (names.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        for (name in names) {
            val file = remember(name, projectRoot.absolutePath) { ChatImages.resolve(projectRoot, name) } ?: continue
            androidx.compose.material3.Card(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("🎬 ${file.name}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    Text(formatBytes(file.length()), style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", file,
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "video/mp4")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Открыть видео")) }
                        }) { Text("Открыть") }
                        androidx.compose.material3.TextButton(onClick = {
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, context.packageName + ".fileprovider", file,
                            )
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            runCatching { context.startActivity(android.content.Intent.createChooser(intent, "Поделиться видео")) }
                        }) { Text("Поделиться") }
                    }
                }
            }
        }
    }
}

/** Встраивает картинки прямо в сообщение: отдельной кнопки «Открыть» больше нет. */
@Composable
fun InlineChatImages(text: String, projectRoot: File, modifier: Modifier = Modifier) {
    val names = remember(text) { ChatImages.find(text) }
    if (names.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.fillMaxWidth().padding(top = 6.dp)) {
        for (name in names) {
            val file = remember(name, projectRoot.absolutePath) { ChatImages.resolve(projectRoot, name) }
            if (file == null) continue
            if (file.extension.equals("svg", ignoreCase = true)) {
                val svg = runCatching { file.readText() }.getOrNull()
                if (svg != null) {
                    val html = "<!doctype html><html><body style='margin:0;background:#151515'>" +
                        svg.replace("<svg", "<svg style='width:100%;height:auto'", ignoreCase = true) + "</body></html>"
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { android.webkit.WebView(context).apply { settings.javaScriptEnabled = false; settings.blockNetworkLoads = true } },
                        update = { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) },
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    )
                }
                continue
            }
            val bitmap = remember(file.absolutePath, file.lastModified()) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}
