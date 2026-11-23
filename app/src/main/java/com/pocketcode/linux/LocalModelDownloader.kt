package com.pocketcode.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Скачивание GGUF в локальную папку модели с прогрессом и проверкой размера. */
class LocalModelDownloader(private val context: Context) {
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

    /** URL GGUF по id модели из каталога (популярные репозитории). */
    fun huggingFaceUrl(id: String, quant: String = "Q4_K_M"): String? {
        val slug = id.substringBefore(':')
        val repo = when {
            slug.contains("qwen2.5-coder", true) -> "Qwen/Qwen2.5-Coder-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("qwen2.5-vl", true) -> "Qwen/Qwen2.5-VL-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("llama3.2", true) -> "bartowski/Llama-3.2-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("llama3.1", true) -> "bartowski/Meta-Llama-3.1-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("mistral", true) -> "bartowski/Mistral-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("gemma3", true) -> "bartowski/gemma-3-${id.substringAfter(':').uppercase()}-it-GGUF"
            slug.contains("phi3.5", true) -> "bartowski/Phi-3.5-mini-instruct-GGUF"
            slug.contains("smollm2", true) -> "HuggingFaceTB/SmolLM2-${id.substringAfter(':').uppercase()}-Instruct-GGUF"
            slug.contains("codellama", true) -> "TheBloke/CodeLlama-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("deepseek-coder", true) -> "TheBloke/DeepSeek-Coder-${id.substringAfter(':').uppercase()}-GGUF"
            slug.contains("llava", true) -> "bartowski/llava-v1.6-${id.substringAfter(':').uppercase()}-GGUF"
            else -> return null
        }
        val file = fileName(id, quant)
        return "https://huggingface.co/$repo/resolve/main/$file"
    }

    suspend fun download(url: String, target: File, onProgress: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(url.startsWith("https://")) { "Нужен HTTPS-адрес модели" }
            require(!target.exists() || target.length() < 1024) { "Файл уже скачан: ${target.name}" }
            val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS).build()
            val tmp = File(dir, "." + target.name + ".part")
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Пустой ответ")
                val total = body.contentLength()
                var done = 0L
                var last = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - last > 1024 * 1024) {
                                last = done
                                onProgress("${done / 1024 / 1024} МБ" + if (total > 0) " / ${total / 1024 / 1024} МБ" else "")
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
    }
}
