package com.pocketcode

import java.io.File

/** Профиль удалённого сервера для SSH-инструмента. Пароль и ключ хранятся отдельно, в secrets.json. */
data class SshProfile(
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String = "root",
    val keyPath: String = "",
    val strictHostKey: Boolean = true,
)

object SshProfiles {
    fun validate(p: SshProfile): String? = when {
        p.name.isBlank() -> "Укажи имя подключения"
        p.host.isBlank() -> "Укажи адрес сервера"
        !p.host.matches(Regex("""[A-Za-z0-9._:\-]+""")) -> "Некорректный адрес сервера"
        p.port !in 1..65535 -> "Порт должен быть от 1 до 65535"
        p.user.isBlank() || !p.user.matches(Regex("""[A-Za-z0-9._\-]+""")) -> "Некорректное имя пользователя"
        p.keyPath.isNotEmpty() && !p.keyPath.matches(Regex("""[^\n\r'\\]+""")) -> "Некорректный путь к ключу"
        else -> null
    }

    /** Аргументы для ssh/scp. Никаких оболочек: пользовательские строки не попадают в shell. */
    fun sshArgs(p: SshProfile, command: String, identity: File?): List<String> = buildList {
        add("ssh")
        if (!p.strictHostKey) { add("-o"); add("StrictHostKeyChecking=no"); add("-o"); add("UserKnownHostsFile=/dev/null") }
        if (identity != null) { add("-i"); add(identity.absolutePath) }
        add("-p"); add(p.port.toString())
        add("-o"); add("BatchMode=yes")
        add("-o"); add("ConnectTimeout=15")
        add("${p.user}@${p.host}")
        add(command)
    }

    /** Строка для вывода в чат: без пароля и содержимого ключа. */
    fun describe(p: SshProfile, command: String): String = "SSH ${p.user}@${p.host}:${p.port} — ${command.take(120)}"
}
