package com.pocketcode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Настройки автоматической синхронизации с ПК. */
@Serializable
data class SyncConfig(
    val enabled: Boolean = false,
    val mode: String = "BOTH", // PERIODIC | LIVE | BOTH
    val intervalMin: Int = 15,
    val serverUrl: String = "http://192.168.1.10:8765",
    val includeFiles: Boolean = true,
    val wifiOnly: Boolean = true,
    /** Токен для удалённого доступа: передаётся в заголовке X-PocketCode-Token. */
    val token: String = "",
    /** Пароль E2E-шифрования; пусто — без шифрования. */
    val password: String = "",
    val onlyChats: Boolean = false,
    val onlyFiles: Boolean = false,
)

object SyncEngine {
    const val VERSION = 2
    private val SKIP_DIRS = setOf(".git", "build", ".gradle", "__pycache__", "node_modules", "dist", ".venv")
    const val MAX_FILE_BYTES = 2_000_000L

    fun endpoint(cfg: SyncConfig): String = cfg.serverUrl.trimEnd('/') + "/api/sync/push"

    fun eventPacket(seq: Long, device: String, project: String, kind: String, data: String): String =
        buildJsonObject {
            put("v", VERSION); put("type", "event"); put("seq", seq)
            put("device", device); put("project", project)
            put("ts", System.currentTimeMillis()); put("kind", kind); put("data", data)
        }.toString()

    fun fullPacket(seq: Long, device: String, project: String, messages: kotlinx.serialization.json.JsonArray, root: File, includeFiles: Boolean): String {
        val files = mutableListOf<Pair<String, String>>()
        if (includeFiles && root.isDirectory) {
            root.walkTopDown().onEnter { it == root || it.name !in SKIP_DIRS }.forEach { f ->
                if (f.isFile && f.length() <= MAX_FILE_BYTES && files.size < 100) {
                    val content = runCatching { f.readText() }.getOrNull() ?: return@forEach
                    files += f.relativeTo(root).invariantSeparatorsPath to content
                }
            }
        }
        val obj = kotlinx.serialization.json.buildJsonObject {
            put("v", VERSION); put("type", "full"); put("seq", seq)
            put("device", device); put("project", project); put("ts", System.currentTimeMillis())
            put("items", kotlinx.serialization.json.JsonArray(messages.toList()))
            put("files", kotlinx.serialization.json.JsonArray(files.map { (path, text) ->
                kotlinx.serialization.json.buildJsonObject { put("path", path); put("text", text) }
            }))
        }
        return obj.toString()
    }

    /** Отправка с backoff; ошибка не роняет вызывающий код. Токен нужен при доступе не из домашней сети. */
    fun send(url: String, body: String, token: String = "", password: String = ""): Result<String> = runCatching {
        val out = SyncCrypto.encrypt(body, password)
        val req = okhttp3.Request.Builder().url(url)
            .apply { if (token.isNotBlank()) header("X-PocketCode-Token", token) }
            .post(out.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        okhttp3.OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
            .newCall(req).execute().use { resp -> resp.body?.string().orEmpty() }
    }

    fun shouldRunLive(cfg: SyncConfig): Boolean = cfg.enabled && (cfg.mode == "LIVE" || cfg.mode == "BOTH")
    fun shouldRunPeriodic(cfg: SyncConfig): Boolean = cfg.enabled && (cfg.mode == "PERIODIC" || cfg.mode == "BOTH")
}
