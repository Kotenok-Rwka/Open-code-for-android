package com.pocketcode

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Периодическая отправка снимка истории+файлов на ПК. */
class PcSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cfg = runCatching { Json.decodeFromString<SyncConfig>(SettingsStore(applicationContext).rawGet("syncConfig") ?: "{}") }
            .getOrDefault(SyncConfig())
        if (!SyncEngine.shouldRunPeriodic(cfg) || cfg.serverUrl.isBlank()) return@withContext Result.success()
        val store = SettingsStore(applicationContext)
        val seq = (store.rawGet("syncSeq")?.toLongOrNull() ?: 0L) + 1
        val root = File(store.loadProject().ifBlank { "main" })
        val raw = inputData.getString("messages").orEmpty()
        val msgs = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonArray }
            .getOrDefault(kotlinx.serialization.json.JsonArray(emptyList()))
        val body = SyncEngine.fullPacket(seq, android.os.Build.MODEL, store.loadProject(), msgs, root, cfg.includeFiles)
        val ok = SyncEngine.send(SyncEngine.endpoint(cfg), Secrets.mask(body)).isSuccess
        if (ok) store.rawPut("syncSeq", seq.toString())
        store.rawPut("syncLast", System.currentTimeMillis().toString())
        store.rawPut("syncStatus", if (ok) "OK" else "Нет связи с ПК")
        if (ok) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(ctx: Context, cfg: SyncConfig) {
            val wm = WorkManager.getInstance(ctx)
            if (!SyncEngine.shouldRunPeriodic(cfg)) { wm.cancelUniqueWork("pocketcode-sync"); return }
            val req = PeriodicWorkRequestBuilder<PcSyncWorker>(cfg.intervalMin.coerceIn(5, 720).toLong(), TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork("pocketcode-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }
}

