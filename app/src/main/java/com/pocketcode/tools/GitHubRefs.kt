package com.pocketcode.tools

import java.net.URLEncoder
import java.security.MessageDigest

/** Чистые помощники для GitHubTool: без сети и Android, поэтому их проверяют unit-тесты. */
object GitHubRefs {
    private val urlForm = Regex("""github\.com[:/]+([^/\s]+)/([^/\s]+?)(?:\.git)?/?$""")
    private val plainForm = Regex("""^([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\.git)?$""")

    /** «владелец/имя» из ссылки на GitHub (https или ssh) или из записи «владелец/имя»; иначе null. */
    fun repoName(text: String): String? {
        val t = text.trim()
        val m = urlForm.find(t) ?: plainForm.find(t) ?: return null
        return m.groupValues[1] + "/" + m.groupValues[2]
    }

    /** Адрес ведёт на GitHub по https: только такому серверу можно отправлять токен GitHub. */
    fun isGitHubHttps(scheme: String?, host: String?): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return h == "github.com" || h.endsWith(".github.com")
    }

    /** Кодирует одну часть адреса: пробелы — %20, а не «+». */
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Путь внутри репозитория без лишних «/» и «.»; «..» запрещены. */
    fun cleanPath(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Путь не должен содержать «..»: $path" }
        return parts.joinToString("/")
    }

    /** Путь для адреса API: каждая часть кодируется, «/» остаются. */
    fun encodePath(path: String): String = cleanPath(path).split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    fun mergeMethod(value: String?): String {
        val m = value?.trim()?.lowercase().orEmpty().ifEmpty { "merge" }
        require(m == "merge" || m == "squash" || m == "rebase") { "merge_method: merge, squash или rebase" }
        return m
    }

    fun isBinary(bytes: ByteArray): Boolean {
        for (i in 0 until minOf(bytes.size, 1024)) if (bytes[i] == 0.toByte()) return true
        return false
    }

    /** Git-хеш содержимого файла (как `git hash-object`): по нему видно, что файл на GitHub уже такой же. */
    fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(("blob " + bytes.size + "\u0000").toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
