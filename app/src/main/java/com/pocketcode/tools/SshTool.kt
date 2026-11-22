package com.pocketcode.tools

import com.pocketcode.SshProfile
import com.pocketcode.SshProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File


/**
 * Удалённый сервер через ssh из Linux-окружения (openssh-client ставится вместе с инструментами).
 * Поддерживаются ключ и пароль, но пароль передаётся только через sshpass, если он установлен.
 * Команда не interpreter-escape: она отправляется серверу как есть, как обычный ssh <host> '<cmd>'.
 */
class SshTool(
    private val shell: Shell,
    private val profiles: () -> Map<String, SshProfile>,
    private val secret: (String) -> String?,
    private val workspace: Workspace? = null,
) : Tool {
    override val name = "ssh"
    override val description =
        "Выполняет команду на удалённом сервере по SSH. Параметры: profile (имя), command. " +
            "Для загрузки файла используй upload с локальным и удалённым путём, для скачивания — download."
    override val schema = objSchema(
        Prop("profile", "string", "Имя сохранённого SSH-подключения"),
        Prop("command", "string", "Команда на удалённом сервере", required = false),
        Prop("upload", "string", "Локальный файл для загрузки (scp)", required = false),
        Prop("download", "string", "Удалённый файл для скачивания в рабочую папку", required = false),
        Prop("remote_path", "string", "Удалённый путь для upload", required = false),
        Prop("password", "string", "Пароль, если не сохранён в профиле", required = false),
    )

    override fun needsApproval(input: JsonObject): Boolean = true
    override val alwaysAsk: Boolean get() = true

    override fun describe(input: JsonObject): String {
        val p = profiles()[input.str("profile") ?: ""]
        val action = input.str("command")?.let { "команда $it" }
            ?: input.str("upload")?.let { "загрузка $it" }
            ?: input.str("download")?.let { "скачивание $it" }
            ?: "действие"
        return if (p == null) "SSH: неизвестный профиль" else SshProfiles.describe(p, action)
    }

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val name = input.need("profile")
        val p = profiles()[name] ?: throw IllegalArgumentException("Нет SSH-подключения «$name». Сначала сохрани его в настройках.")
        val error = SshProfiles.validate(p)
        require(error == null) { error ?: "" }

        val password = input.str("password")?.takeIf { it.isNotEmpty() } ?: secret(name).orEmpty()
        val keyFile = p.keyPath.takeIf { it.isNotEmpty() }?.let { File(it) }
        if (keyFile != null) require(keyFile.isFile) { "Файл ключа не найден: ${p.keyPath}" }
        val result = when {
            input.str("upload") != null -> {
                val local = input.need("upload").let { f -> workspace?.resolve(f) ?: File(f) }
                require(local.isFile) { "Файл не найден: ${local.path}" }
                val remote = input.need("remote_path")
                require(remote.matches(Regex("""[^\n\r'\\]+"""))) { "Некорректный удалённый путь" }
                shell.command(scpArgs(p, keyFile, local.absolutePath, "${p.user}@${p.host}:$remote"), password)
            }
            input.str("download") != null -> {
                val remote = input.need("download")
                require(remote.matches(Regex("""[^\n\r'\\]+"""))) { "Некорректный удалённый путь" }
                val localDir = (workspace?.root ?: File("/work")).canonicalFile
                shell.command(scpArgs(p, keyFile, "${p.user}@${p.host}:$remote", localDir.absolutePath), password)
            }
            else -> {
                val cmd = input.need("command")
                shell.command(SshProfiles.sshArgs(p, cmd, keyFile), password)
            }
        }
        if (password.isNotEmpty()) result.replace(password, "***") else result
    }

    private fun scpArgs(p: SshProfile, key: File?, from: String, to: String): List<String> = buildList {
        add("scp")
        if (!p.strictHostKey) { add("-o"); add("StrictHostKeyChecking=no"); add("-o"); add("UserKnownHostsFile=/dev/null") }
        if (key != null) { add("-i"); add(key.absolutePath) }
        add("-P"); add(p.port.toString())
        add("-o"); add("BatchMode=yes")
        add("-o"); add("ConnectTimeout=15")
        add(from); add(to)
    }
}

/** Добавляет sshpass только когда задан пароль; без пароля работает обычный ssh с ключом. */
private suspend fun Shell.command(args: List<String>, password: String): String {
    if (password.isEmpty()) return run(args.joinToString(" ") { quote(it) }, File("/"), 120)
    require(run("command -v sshpass >/dev/null 2>&1; echo \$?", File("/"), 15).trim() == "0") {
        "Для пароля нужен sshpass. Выполни в Linux: apk add openssh-client sshpass"
    }
    return run("sshpass -p " + quote(password) + " " + args.joinToString(" ") { quote(it) }, File("/"), 120)
}

private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
