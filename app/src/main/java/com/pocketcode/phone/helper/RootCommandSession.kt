// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

/**
 * Надёжный запуск shell-команды через su: setsid отделяет помощника от сессии su,
 * а watcher гарантирует завершение группы процессов по закрытии stdin.
 * Ни один пользовательский текст не попадает в команду напрямую: только фиксированные пути и переменные.
 */
object RootCommandSession {
    private const val SYSTEM_SHELL = "/system/bin/sh"

    val androidScript: String = script(
        shell = SYSTEM_SHELL,
        setsid = listOf(listOf("/system/bin/setsid"), listOf("/system/bin/toybox", "setsid")),
        kill = listOf(listOf("/system/bin/kill"), listOf("kill"), listOf("/system/bin/toybox", "kill")),
    )

    internal fun script(shell: String, setsid: List<List<String>>, kill: List<List<String>>): String {
        require(setsid.all { it.isNotEmpty() } && kill.all { it.isNotEmpty() })
        val supervisor = """
            set +m
            group=${'$'}${'$'}
            exec 3<&0
            "${'$'}3" -c "${'$'}1" </dev/null 3<&- &
            child=${'$'}!
            (
                IFS= read -r stop <&3
                ${'$'}2 -s KILL -- "-${'$'}group" 2>/dev/null
            ) &
            watcher=${'$'}!
            exec 3<&-
            wait "${'$'}child"
            status=${'$'}?
            ${'$'}2 -s KILL -- "${'$'}watcher" 2>/dev/null
            wait "${'$'}watcher" 2>/dev/null
            exit "${'$'}status"
        """.trimIndent()
        val b = StringBuilder()
        b.append("pc_supervise='").append(supervisor).append("'; pc_kill=; ")
        kill.forEachIndexed { index, candidate ->
            val invocation = candidate.joinToString(" ")
            b.append(if (index == 0) "if " else "elif ")
                .append(available(candidate)).append(" && ")
                .append(invocation).append(" -s 0 -- \"${'$'}${'$'}\" >/dev/null 2>&1; then ")
                .append("pc_kill=\"").append(invocation).append("\"; ")
        }
        if (kill.isNotEmpty()) b.append("fi; ")
        if (setsid.isNotEmpty()) {
            b.append("if [ -n \"${'$'}pc_kill\" ]; then ")
            setsid.forEachIndexed { index, candidate ->
                val invocation = candidate.joinToString(" ")
                b.append(if (index == 0) "if " else "elif ")
                    .append(available(candidate)).append(" && ")
                    .append(invocation).append(" -w \"").append(shell).append("\" -c 'exit 0' </dev/null >/dev/null 2>&1; then ")
                    .append("exec ").append(invocation).append(" -w \"").append(shell)
                    .append("\" -c \"${'$'}pc_supervise\" pc-root \"${'$'}c\" \"${'$'}pc_kill\" \"").append(shell).append("\"; ")
            }
            b.append("fi; fi; ")
        }
        return b.toString()
    }

    private fun available(command: List<String>): String =
        if (command[0].startsWith('/')) "[ -x ${command[0]} ]"
        else "command -v ${command[0]} >/dev/null 2>&1"
}
