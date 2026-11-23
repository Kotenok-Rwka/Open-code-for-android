package com.pocketcode.tools

import java.io.File
import java.security.MessageDigest

/**
 * Безопасная работа с текстовыми файлами: идеи AndroidHarness, реализовано для PocketCode.
 * - atomic save через временный файл (не оборвётся посередине);
 * - отказ от записи не-UTF-8 (не испортим байты);
 * - история версий с хешем и восстановление.
 */
object SafeFiles {
    data class Version(val file: File, val hash: String, val at: Long)

    @Volatile
    private var rootOverride: File? = null

    /** Вызывается при старте приложения: история версий хранится вместе с данными, а не в /tmp. */
    fun init(baseDir: File) { rootOverride = File(baseDir, "file-history") }

    private val historyRoot: File
        get() = rootOverride ?: File(System.getProperty("java.io.tmpdir", "/tmp"), "pocketcode-file-history")

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun isBinary(file: File): Boolean {
        val bytes = file.readBytes()
        return bytes.any { it == 0.toByte() } || runCatching { String(bytes, Charsets.UTF_8) }.isFailure
    }

    /** Сохраняет файл через temp + rename. Возвращает true при успехе. */
    fun atomicWrite(file: File, content: String): Boolean {
        val parent = file.parentFile ?: return false
        parent.mkdirs()
        val tmp = File(parent, "." + file.name + ".pc-" + System.nanoTime() + ".tmp")
        return try {
            tmp.writeText(content, Charsets.UTF_8)
            if (file.exists() && !file.delete()) return false
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    /** Сохраняет предыдущее содержимое перед правкой. */
    fun rememberOriginal(file: File) {
        if (!file.isFile || isBinary(file)) return
        val content = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { return }
        val dir = File(historyRoot, file.absolutePath.hashCode().toString())
        dir.mkdirs()
        val entry = File(dir, "${System.currentTimeMillis()}.txt")
        entry.writeText(content, Charsets.UTF_8)
        File(dir, "hashes.txt").appendText("${entry.name} ${sha256(content)}\n")
        val all = dir.listFiles { f -> f.name.endsWith(".txt") && f.name != "hashes.txt" }
            ?.sortedByDescending { it.name }.orEmpty()
        all.drop(10).forEach { it.delete() }
    }

    fun history(file: File): List<Version> {
        val dir = File(historyRoot, file.absolutePath.hashCode().toString())
        return dir.listFiles { f -> f.name.endsWith(".txt") && f.name != "hashes.txt" }
            ?.sortedByDescending { it.name }
            ?.map { Version(it, sha256(it.readText(Charsets.UTF_8)), it.name.removeSuffix(".txt").toLongOrNull() ?: 0L) }
            ?: emptyList()
    }

    fun restore(file: File, version: File): Boolean = atomicWrite(file, version.readText(Charsets.UTF_8))
}
