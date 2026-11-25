package com.pocketcode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Сводка ответов ПК для панели фич; чистая логика, покрыта юнит-тестом. */
object FeaturesSummary {
    fun cron(json: JSONObject?): String =
        json?.optJSONArray("tasks")?.let { "${it.length()} задач" } ?: "нет связи"

    fun count(json: JSONObject?, key: String, suffix: String = ""): String {
        val n = json?.optInt(key, -1) ?: -1
        return if (n >= 0) "$n$suffix" else "нет связи"
    }
}

/** Панель фич ПК на телефоне: cron, заметки, краши, здоровье проекта, метрики API, экспорт. */
@Composable
fun FeaturesScreen(chat: ChatController) {
    val cfg = chat.syncConfig
    val base = cfg.serverUrl.trim().trimEnd('/')
    val scope = rememberCoroutineScope()

    var cron by remember { mutableStateOf("—") }
    var notes by remember { mutableStateOf("—") }
    var crashes by remember { mutableStateOf("—") }
    var health by remember { mutableStateOf("—") }
    var metrics by remember { mutableStateOf("—") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val client = remember {
        OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }

    fun post(path: String): Pair<Boolean, JSONObject?> = runCatching {
        val req = Request.Builder().url(base + path)
            .apply { if (cfg.token.isNotBlank()) header("X-PocketCode-Token", cfg.token) }
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r -> Pair(r.isSuccessful, JSONObject(r.body?.string() ?: "{}")) }
    }.getOrDefault(Pair(false, null))

    fun refresh() {
        if (base.isBlank()) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val c = FeaturesSummary.cron(post("/api/cron/list").second)
            val n = FeaturesSummary.count(post("/api/notes/list").second, "total")
            val k = FeaturesSummary.count(post("/api/crash/list").second, "total")
            val h = FeaturesSummary.count(post("/api/health/report").second, "score", " / 100")
            val m = FeaturesSummary.count(post("/api/metrics/report").second, "total", " вызовов")
            withContext(Dispatchers.Main) {
                cron = c; notes = n; crashes = k; health = h; metrics = m; busy = false
            }
        }
    }

    LaunchedEffect(base) { refresh() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Фичи ПК", style = MaterialTheme.typography.headlineSmall)
            if (base.isBlank()) {
                Text("Сначала подключись к ПК на экране синхронизации.", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { FeatureCard("Cron-задачи", cron, "повторяющиеся задачи по расписанию") }
        item { FeatureCard("Заметки проекта", notes, "мини-CMS с тегами и поиском") }
        item { FeatureCard("Журнал крашей", crashes, "ошибки агента с группировкой") }
        item { FeatureCard("Здоровье проекта", health, "целостность, мёртвый код, диск") }
        item { FeatureCard("Метрики API", metrics, "медленные маршруты и доля ошибок") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { refresh() }, enabled = !busy && base.isNotBlank()) {
                    Text(if (busy) "…" else "Обновить")
                }
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val ok = post("/api/export/full").first
                        withContext(Dispatchers.Main) {
                            status = if (ok) "Архив создан на ПК" else "Не удалось создать архив"
                        }
                    }
                }, enabled = base.isNotBlank()) { Text("Экспорт") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FeatureCard(title: String, value: String, subtitle: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
