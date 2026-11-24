package com.pocketcode.linux

import com.pocketcode.formatSize

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Скачивание GGUF в локальную папку модели с прогрессом и проверкой размера. */
class LocalModelDownloader(private val context: Context) {
    /** Эта же папка подключена в Linux как /root/.pocketcode/models (см. LinuxEnv.processBuilder). */
    val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun installed(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf", ignoreCase = true) }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun fileName(id: String, quant: String): String {
        val name = id.replace(':', '-').replace('/', '_')
        return "${name}-${quant}.gguf"
    }

    fun target(id: String, quant: String): File = File(dir, fileName(id, quant))

    fun sha256(file: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * Репозиторий GGUF на Hugging Face для id из каталога.
     * Названия проверены по API: у Qwen файлы лежат в репозиториях с «-Instruct», а не «-3B-GGUF».
     */
    fun huggingFaceRepo(id: String): String? {
        val slug = id.substringBefore(':').lowercase()
        val tag = id.substringAfter(':', "").uppercase()
        if (tag.isBlank()) return null
        return when {
            slug.contains("qwen2.5-coder") -> "Qwen/Qwen2.5-Coder-$tag-Instruct-GGUF"
            slug.contains("qwen2.5-vl") -> "unsloth/Qwen2.5-VL-$tag-Instruct-GGUF"
            slug.contains("llama3.2") -> "bartowski/Llama-3.2-$tag-Instruct-GGUF"
            slug.contains("llama3.1") -> "bartowski/Meta-Llama-3.1-$tag-Instruct-GGUF"
            slug.contains("mistral") -> "bartowski/Mistral-7B-Instruct-v0.3-GGUF"
            slug.contains("gemma3") -> "unsloth/gemma-3-$tag-it-GGUF"
            slug.contains("phi3.5") -> "bartowski/Phi-3.5-mini-instruct-GGUF"
            slug.contains("smollm2") -> "HuggingFaceTB/SmolLM2-$tag-Instruct-GGUF"
            slug.contains("codellama") -> "TheBloke/CodeLlama-$tag-Instruct-GGUF"
            slug.contains("deepseek-coder") -> "TheBloke/deepseek-coder-$tag-instruct-GGUF"
            slug.contains("llava") -> "mradermacher/llava-v1.6-mistral-7b-GGUF"
            slug.contains("qwq") -> "Qwen/QwQ-$tag-GGUF"
            else -> null
        }
    }

    /** Запасной прямой адрес, если список файлов репозитория получить не удалось. */
    fun huggingFaceUrl(id: String, quant: String = "Q4_K_M"): String? {
        val repo = huggingFaceRepo(id) ?: return null
        val guessed = repo.substringAfterLast('/').removeSuffix("-GGUF").lowercase() + "-" + quant.lowercase() + ".gguf"
        return "https://huggingface.co/$repo/resolve/main/$guessed"
    }

    /**
     * Находит настоящий файл модели в репозитории: имена у всех разные
     * (Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf, deepseek-coder-6.7b-instruct.Q4_K_M.gguf и т.д.),
     * поэтому угадывать имя нельзя — спрашиваем список файлов у Hugging Face.
     */
    suspend fun resolveUrl(repo: String, quant: String = "Q4_K_M"): String? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()
                .url("https://huggingface.co/api/models/$repo/tree/main")
                .header("User-Agent", "PocketCode")
                .build()
            val text = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: return@withContext null
            val files = Json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
                runCatching { el.jsonObject["path"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            }.filter { it.lowercase().endsWith(".gguf") && !it.lowercase().contains("mmproj") }
            if (files.isEmpty()) return@withContext null
            val q = quant.lowercase()
            val pick = files.firstOrNull { it.lowercase().contains(q) } ?: files.first()
            "https://huggingface.co/$repo/resolve/main/" + java.net.URLEncoder.encode(pick, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            null
        }
    }

    /** Call прерывает текущее соединение; следующий вызов download начнётся заново. */
    @Volatile
    private var currentCall: okhttp3.Call? = null

    val isDownloading: Boolean get() = currentCall != null

    fun cancel() {
        currentCall?.cancel()
        currentCall = null
    }

    suspend fun download(url: String, target: File, onProgress: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Нужен HTTPS-адрес модели" }
            require(!target.exists() || target.length() < 1024) { "Файл уже скачан: ${target.name}" }
            val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS).build()
            val tmp = File(dir, "." + target.name + ".part")
            val call = client.newCall(Request.Builder().url(url).header("User-Agent", "PocketCode").build())
            currentCall = call
            call.execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Пустой ответ")
                val total = body.contentLength()
                var done = 0L
                var last = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (call.isCanceled()) error("Скачивание отменено")
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - last > 1024 * 1024) {
                                last = done
                                onProgress(formatSize(done) + if (total > 0) " / " + formatSize(total) else "")
                            }
                        }
                    }
                }
            }
            require(tmp.length() > 1024 * 1024) { "Файл слишком маленький — похоже, скачивание не удалось" }
            if (target.exists()) target.delete()
            require(tmp.renameTo(target)) { "Не удалось переименовать файл модели" }
            target
        }.onFailure { runCatching { File(dir, "." + target.name + ".part").delete() } }
            .also { currentCall = null }
    }
}
