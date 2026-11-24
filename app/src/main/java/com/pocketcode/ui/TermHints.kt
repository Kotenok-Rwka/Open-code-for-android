package com.pocketcode.ui

import java.io.File

/** Чистая логика «умного» терминала: вопросы программы, подсказки по ошибкам, команда cd. Без Android. */
object TermHints {
    enum class Prompt { YES_NO, ENTER, PASSWORD }

    private val passwordRx = Regex("""(?iu)(password|passphrase|пароль)[^\n]{0,24}:\s*$""")
    private val yesNoRx = Regex("""(?iu)[\[(](y(es)?/n(o)?|д(а)?/н(ет)?)[\])]""")
    private val enterRx = Regex("""(?iu)(press (enter|return|any key)|hit enter|нажмите\s+(enter|любую клавишу)|нажми\s+(enter|любую клавишу))""")

    private fun lastLine(out: String): String = out.trimEnd().lines().lastOrNull().orEmpty().trim()

    /** Похоже ли, что запущенная программа ждёт ответа (по последней строке вывода). */
    fun detectPrompt(out: String): Prompt? {
        val last = lastLine(out)
        if (last.isEmpty()) return null
        return when {
            passwordRx.containsMatchIn(last) -> Prompt.PASSWORD
            yesNoRx.containsMatchIn(last) -> Prompt.YES_NO
            enterRx.containsMatchIn(last) -> Prompt.ENTER
            else -> null
        }
    }

    /** Какой ответ выбран по умолчанию: заглавная Y в [Y/n] — да, заглавная N в [y/N] — нет, иначе null. */
    fun yesNoDefault(out: String): Boolean? {
        val m = Regex("""[\[(]([Yy](?:es)?)/([Nn](?:o)?)[\])]""").find(lastLine(out)) ?: return null
        val yes = m.groupValues[1]
        val no = m.groupValues[2]
        return when {
            yes.first() == 'Y' && no.first() == 'n' -> true
            yes.first() == 'y' && no.first() == 'N' -> false
            else -> null
        }
    }

    /** Код выхода из хвоста вывода терминала вида «[код выхода: 1]». */
    fun parseExitCode(out: String): Int? =
        Regex("""\[код выхода: (-?\d+)]""").findAll(out).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

    private val pyNames = mapOf(
        "PIL" to "pillow", "yaml" to "pyyaml", "bs4" to "beautifulsoup4", "dotenv" to "python-dotenv",
        "telegram" to "python-telegram-bot", "cv2" to "opencv-python-headless", "sklearn" to "scikit-learn",
        "dateutil" to "python-dateutil", "serial" to "pyserial", "Crypto" to "pycryptodome", "jwt" to "pyjwt",
    )

    private val apkNames = mapOf(
        "python" to "python3", "pip" to "py3-pip", "pip3" to "py3-pip", "node" to "nodejs", "gcc" to "build-base",
        "g++" to "build-base", "cc" to "build-base", "java" to "openjdk17", "javac" to "openjdk17", "rustc" to "rust",
        "cmake" to "cmake", "make" to "make",
    )

    /** Команды, которые могут исправить ошибку в выводе (не больше трёх). linux — запуск внутри Alpine. */
    fun fixes(cmd: String, out: String, linux: Boolean, flavor: String = ""): List<String> {
        val res = LinkedHashSet<String>()
        val ubuntu = flavor == com.pocketcode.linux.RootFsChoice.UBUNTU.name
        // В Ubuntu 22.04 pip старый и флаг --break-system-packages не знает.
        val pipFlag = if (linux && !ubuntu) " --break-system-packages" else ""

        Regex("""No module named ['"]([A-Za-z0-9_.\-]+)['"]""").find(out)?.let {
            val mod = it.groupValues[1].substringBefore('.')
            res += "pip install$pipFlag ${pyNames[mod] ?: mod}"
        }
        Regex("""Cannot find module '([^']+)'""").find(out)?.let {
            val raw = it.groupValues[1]
            if (!raw.startsWith(".") && !raw.startsWith("/")) {
                val parts = raw.split('/')
                val pkg = if (raw.startsWith("@") && parts.size >= 2) parts[0] + "/" + parts[1] else parts[0]
                res += "npm install $pkg"
            }
        }
        if (out.contains("ENOENT") && out.contains("package.json")) res += "npm init -y"
        if (linux) {
            Regex("""(?m)^(?:sh: |/bin/sh: \d+: |bash: )?([A-Za-z0-9_.+\-]+): (?:command )?not found""").find(out)?.let {
                val name = it.groupValues[1]
                val pkg = com.pocketcode.linux.LinuxPkgs.packageFor(flavor, apkNames[name] ?: name)
                res += if (ubuntu) "apt-get install -y $pkg" else "apk add $pkg"
            }
        }
        Regex("""(?:sh|bash|/bin/sh)(?:: \d+)?: (\.?/[^\s:]+): [Pp]ermission denied""").find(out)?.let {
            res += "chmod +x ${it.groupValues[1]}"
        }
        if (out.contains("EADDRINUSE") || out.contains("Address already in use")) res += "ps"
        return res.take(3)
    }

    /** Текст для ассистента: что запускали, чем кончилось и просьба разобраться. */
    fun explainPrompt(cmd: String, out: String, code: Int?): String {
        val tail = out.trim().takeLast(3000)
        val codeText = if (code != null) " с кодом $code" else ""
        return "Команда в терминале завершилась с ошибкой$codeText:\n\$ $cmd\n\n$tail\n\nОбъясни причину и, если нужно, исправь проект."
    }

    sealed class Cd {
        object NotCd : Cd()
        class Ok(val dir: File) : Cd()
        class Fail(val msg: String) : Cd()
    }

    /**
     * Разбирает отдельную команду cd. root — корень проекта, cur — текущая папка терминала.
     * Выходить за корень проекта нельзя. «/work/…» понимается как путь внутри проекта (так он выглядит в Linux).
     */
    fun parseCd(root: File, cur: File, cmd: String): Cd {
        val c = cmd.trim()
        if (c != "cd" && !c.startsWith("cd ")) return Cd.NotCd
        val arg = c.removePrefix("cd").trim().trim('"', '\'')
        if (arg.any { it in "&|;<>\$`" }) return Cd.NotCd
        val base = root.canonicalFile
        val target = when {
            arg.isEmpty() || arg == "~" -> base
            arg == "/work" -> base
            arg.startsWith("/work/") -> File(base, arg.removePrefix("/work/"))
            arg.startsWith("~/") -> File(base, arg.removePrefix("~/"))
            arg.startsWith("/") -> File(arg)
            else -> File(cur, arg)
        }.canonicalFile
        val inside = target.path == base.path || target.path.startsWith(base.path + File.separator)
        return when {
            !inside -> Cd.Fail("cd: $arg — выход за пределы проекта запрещён")
            !target.isDirectory -> Cd.Fail("cd: $arg — нет такой папки")
            else -> Cd.Ok(target)
        }
    }

    /** Путь папки относительно корня проекта; пустая строка — сам корень. */
    fun relPath(root: File, dir: File): String =
        runCatching { dir.canonicalFile.relativeTo(root.canonicalFile).path }.getOrDefault("").let { if (it == ".") "" else it }
}
