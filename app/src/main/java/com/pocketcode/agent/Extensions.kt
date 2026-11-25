package com.pocketcode.agent

import java.io.File

data class Skill(val name: String, val description: String, val dir: File, val source: String)
data class Command(val name: String, val description: String, val file: File, val source: String)

/** Установленный плагин: имя без служебной приставки, включён ли он и его папка на диске. */
data class PluginInfo(val name: String, val enabled: Boolean, val dir: File)

private const val DISABLED_PREFIX = ".disabled-"
private const val OLD_PREFIX = ".old-"
private const val SOURCE_FILE = ".pocketcode-source"

private data class Parsed(val meta: Map<String, String>, val body: String)

private fun parseFrontmatter(text: String): Parsed {
    val t = text.replace("\r\n", "\n")
    if (!t.startsWith("---\n")) return Parsed(emptyMap(), t)
    val end = t.indexOf("\n---", 4)
    if (end < 0) return Parsed(emptyMap(), t)
    val meta = t.substring(4, end).lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim().trim('"', '\'')
    }.toMap()
    val body = t.substring(end + 4).removePrefix("\n").trimStart('\n')
    return Parsed(meta, body)
}

/**
 * Инструкции проекта, навыки и пользовательские команды.
 * Источники: папки .pocketcode и .claude проекта, общая папка приложения и установленные плагины.
 */
class Extensions(
    private val projectRoot: () -> File,
    val globalDir: File,
    val pluginsDir: File,
) {
    /** Имена выключенных навыков: агент их не видит, но в списке они остаются. */
    var disabled: () -> Set<String> = { emptySet() }

    private fun roots(): List<Pair<String, File>> {
        val p = projectRoot()
        val list = mutableListOf(
            "проект" to File(p, ".pocketcode"),
            "проект" to File(p, ".claude"),
            "общие" to globalDir,
        )
        plugins().filter { it.enabled }.forEach { list += "плагин ${it.name}" to it.dir }
        return list
    }

    /** Все установленные плагины, включённые и выключенные. Выключенный лежит в папке «.disabled-имя». */
    fun plugins(): List<PluginInfo> =
        (pluginsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(OLD_PREFIX) } ?: emptyArray())
            .map { PluginInfo(it.name.removePrefix(DISABLED_PREFIX), !it.name.startsWith(DISABLED_PREFIX), it) }
            .filter { it.name.isNotEmpty() }
            .sortedWith(compareBy<PluginInfo> { it.name }.thenByDescending { it.enabled })
            .distinctBy { it.name }

    fun plugin(name: String): PluginInfo? {
        val plain = name.removePrefix(DISABLED_PREFIX)
        return plugins().firstOrNull { it.name == plain }
    }

    /** Включает или выключает плагин переименованием папки. Возвращает false, если плагина нет или переименовать не удалось. */
    fun setPluginEnabled(name: String, enabled: Boolean): Boolean {
        val p = plugin(name) ?: return false
        if (p.enabled == enabled) return true
        val target = File(pluginsDir, if (enabled) p.name else DISABLED_PREFIX + p.name)
        return !target.exists() && p.dir.renameTo(target)
    }

    fun removePlugin(name: String): Boolean {
        val p = plugin(name) ?: return false
        return p.dir.deleteRecursively()
    }

    /** Запоминает адрес, с которого поставлен плагин: по нему работает «Обновить». */
    fun savePluginSource(dir: File, url: String) {
        File(dir, SOURCE_FILE).writeText(url.trim() + "\n")
    }

    /** Адрес плагина: из сохранённого файла, а у плагинов старых версий — из .git/config, если он остался. */
    fun pluginSource(name: String): String? {
        val dir = plugin(name)?.dir ?: return null
        val saved = File(dir, SOURCE_FILE).takeIf { it.isFile }?.let { f -> runCatching { f.readText().trim() }.getOrNull() }
        val url = saved?.takeIf { it.isNotEmpty() } ?: File(dir, ".git/config").takeIf { it.isFile }?.let { f ->
            runCatching {
                f.readLines().firstOrNull { it.trim().startsWith("url =") }?.substringAfter("url =")?.trim()
            }.getOrNull()
        }
        return url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    /**
     * Обновление плагина: [fetch] скачивает новую версию во временную папку, и только после
     * успеха она заменяет старую. При ошибке старая версия остаётся на месте. Состояние
     * «выключен» сохраняется.
     */
    fun replacePlugin(name: String, url: String, fetch: (File) -> Unit) {
        val p = plugin(name) ?: throw IllegalArgumentException("Плагин «$name» не найден")
        val stamp = System.currentTimeMillis()
        val fresh = File(pluginsDir, "$OLD_PREFIX${p.name}-new-$stamp")
        val backup = File(pluginsDir, "$OLD_PREFIX${p.name}-$stamp")
        try {
            fetch(fresh)
            File(fresh, ".git").deleteRecursively()
            savePluginSource(fresh, url)
            if (!p.dir.renameTo(backup)) throw java.io.IOException("не удалось убрать старую версию")
            if (!fresh.renameTo(p.dir)) {
                backup.renameTo(p.dir)
                throw java.io.IOException("не удалось поставить новую версию")
            }
            backup.deleteRecursively()
        } finally {
            fresh.deleteRecursively()
        }
    }

    /**
     * Короткая сводка, что нашлось в скачанном плагине: сколько навыков, команд и есть ли инструкции
     * (AGENTS.md/CLAUDE.md). Для сообщения после установки — чтобы пользователь видел, чем плагин полезен.
     */
    fun pluginSummary(root: File): String {
        val sk = runCatching {
            val dirs = mutableListOf<File>()
            File(root, "skills").listFiles()?.filter { it.isDirectory }?.let { dirs += it }
            root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.equals("SKILL.md", true) && it.parentFile != root }
                .forEach { if (it.parentFile !in dirs) dirs += it.parentFile!! }
            if (File(root, "SKILL.md").isFile || File(root, "skill.md").isFile) dirs += root
            dirs.distinct().count { d -> listOf(File(d, "SKILL.md"), File(d, "skill.md")).any { it.isFile } }
        }.getOrDefault(0)
        val cm = runCatching {
            root.walkTopDown().maxDepth(4)
                .filter { it.isDirectory && it.name.equals("commands", true) }
                .sumOf { d -> (d.listFiles()?.count { it.isFile && it.name.endsWith(".md") } ?: 0) }
        }.getOrDefault(0)
        val hasInstructions = listOf("AGENTS.md", "CLAUDE.md").any { File(root, it).isFile }
        return listOfNotNull(
            if (sk > 0) "$sk навык(ов)" else null,
            if (cm > 0) "$cm команд(а)" else null,
            if (hasInstructions && sk == 0) "инструкции в AGENTS/CLAUDE.md" else null,
        ).joinToString(", ").ifEmpty { "навыков и команд не найдено" }
    }

    fun skills(all: Boolean = false): List<Skill> {
        val out = LinkedHashMap<String, Skill>()
        for ((src, root) in roots()) {
            val candidates = mutableListOf<File>()
            File(root, "skills").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.let { candidates += it }
            runCatching {
                root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.equals("SKILL.md", true) && it.parentFile != root }
                    .forEach { if (it.parentFile !in candidates) candidates += it.parentFile!! }
            }
            if (File(root, "SKILL.md").isFile || File(root, "skill.md").isFile) candidates += root
            for (dir in candidates) {
                val f = listOf(File(dir, "SKILL.md"), File(dir, "skill.md")).firstOrNull { it.isFile } ?: continue
                val parsed = runCatching { parseFrontmatter(f.readText()) }.getOrNull() ?: continue
                val name = (parsed.meta["name"] ?: dir.name).trim().replace(' ', '-')
                if (name.isEmpty() || name in out) continue
                if (!all && name in disabled()) continue
                val desc = parsed.meta["description"]?.takeIf { it.isNotBlank() }
                    ?: parsed.body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(160).orEmpty()
                out[name] = Skill(name, desc, dir, src)
            }
            // Репозиторий без SKILL.md, но с AGENTS.md/CLAUDE.md в корне: корень сам становится навыком,
            // чтобы скачанный плагин с одними инструкциями не пропадал зря.
            if (out.values.none { it.source == src }) {
                val f = listOf("AGENTS.md", "CLAUDE.md").map { File(root, it) }.firstOrNull { it.isFile }
                if (f != null) {
                    val parsed = runCatching { parseFrontmatter(f.readText()) }.getOrNull() ?: Parsed(emptyMap(), f.readText())
                    val name = (parsed.meta["name"] ?: root.name).trim().replace(' ', '-')
                    if (name.isNotEmpty() && name !in out && (all || name !in disabled())) {
                        val desc = parsed.meta["description"]?.takeIf { it.isNotBlank() }
                            ?: parsed.body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(160).orEmpty()
                        out[name] = Skill(name, desc, root, src)
                    }
                }
            }
        }
        return out.values.toList()
    }

    fun commands(): List<Command> {
        val out = LinkedHashMap<String, Command>()
        for ((src, root) in roots()) {
            val dirs = mutableListOf<File>()
            File(root, "commands").takeIf { it.isDirectory }?.let { dirs += it }
            File(root, ".claude/commands").takeIf { it.isDirectory }?.let { dirs += it }
            runCatching {
                root.walkTopDown().maxDepth(4)
                    .filter { it.isDirectory && it.name.equals("commands", true) && it !in dirs }
                    .forEach { dirs += it }
            }
            for (d in dirs) d.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.sortedBy { it.name }?.forEach { f ->
                val name = f.name.removeSuffix(".md").lowercase().replace(' ', '-')
                if (name in out) return@forEach
                val parsed = runCatching { parseFrontmatter(f.readText()) }.getOrNull() ?: return@forEach
                val desc = parsed.meta["description"]?.takeIf { it.isNotBlank() }
                    ?: parsed.body.lines().firstOrNull { it.isNotBlank() }?.trim('#', ' ')?.take(100).orEmpty()
                out[name] = Command(name, desc, f, src)
            }
        }
        return out.values.toList()
    }

    /**
     * Сводка для системного промпта: какие плагины стоят и что они дают, какие команды через слэш
     * есть у пользователя и что выключено. null — расширений нет вовсе.
     */
    fun inventory(): String? {
        val allPlugins = plugins()
        val allSkills = skills(all = true)
        val cmds = commands()
        val off = disabled()
        val lines = mutableListOf<String>()
        allPlugins.filter { it.enabled }.forEach { p ->
            val src = "плагин ${p.name}"
            val sk = allSkills.filter { it.source == src }.map { it.name }
            val cm = cmds.filter { it.source == src }.map { "/" + it.name }
            val gives = listOfNotNull(
                sk.takeIf { it.isNotEmpty() }?.let { "skills: " + it.take(20).joinToString(", ") },
                cm.takeIf { it.isNotEmpty() }?.let { "commands: " + it.take(20).joinToString(", ") },
            ).joinToString("; ").ifEmpty { "no skills or commands found in it" }
            lines += "- Plugin ${p.name} (on): $gives"
        }
        allPlugins.filter { !it.enabled }.takeIf { it.isNotEmpty() }?.let { list ->
            lines += "- Plugins turned off (their skills and commands do not work until the user turns them on): " +
                list.joinToString(", ") { it.name }
        }
        if (cmds.isNotEmpty()) {
            lines += "- Slash commands the user can type in the chat (you cannot run them yourself, suggest them when they fit):"
            cmds.take(40).forEach { c ->
                lines += "  /${c.name}" + (if (c.description.isNotBlank()) ": ${c.description.take(100)}" else "") + " [${c.source}]"
            }
            if (cmds.size > 40) lines += "  and ${cmds.size - 40} more"
        }
        allSkills.filter { it.name in off }.takeIf { it.isNotEmpty() }?.let { list ->
            lines += "- Skills turned off by the user (the skill tool will not load them; say so if the task needs one): " +
                list.take(40).joinToString(", ") { it.name }
        }
        if (lines.isEmpty()) return null
        return "Installed extensions (the user manages them in the app: Агент → Навыки, команды, MCP). " +
            "When asked which plugins, skills or commands exist, answer from this list and the Skills list, do not guess:\n" +
            lines.joinToString("\n")
    }

    /** Текст навыка: SKILL.md и небольшие текстовые файлы рядом. */
    fun readSkill(skill: Skill): String {
        val sb = StringBuilder()
        val main = listOf(File(skill.dir, "SKILL.md"), File(skill.dir, "skill.md")).firstOrNull { it.isFile }
        if (main != null) sb.append(parseFrontmatter(main.readText()).body.trim())
        val extra = skill.dir.walkTopDown().maxDepth(3)
            .filter { it.isFile && !it.name.equals("SKILL.md", true) && it.extension.lowercase() in setOf("md", "txt", "json", "yaml", "yml", "py", "sh", "js") }
            .sortedBy { it.path }.take(15).toList()
        var budget = 20_000
        for (f in extra) {
            if (budget <= 0) break
            val t = runCatching { f.readText().take(budget) }.getOrNull() ?: continue
            sb.append("\n\n--- файл навыка: ").append(f.relativeTo(skill.dir).path).append(" ---\n").append(t)
            budget -= t.length
        }
        return sb.toString()
    }

    /** Раскрывает «/имя аргументы» в текст команды или возвращает null, если такой команды нет. */
    fun expand(input: String): String? {
        if (!input.startsWith("/")) return null
        val name = input.substring(1).substringBefore(' ').substringBefore('\n').lowercase()
        val args = input.substring(1 + name.length).trim()
        val cmd = commands().firstOrNull { it.name == name } ?: return null
        val body = parseFrontmatter(cmd.file.readText()).body.trim()
        return if (body.contains("\$ARGUMENTS")) body.replace("\$ARGUMENTS", args)
        else if (args.isEmpty()) body else "$body\n\n$args"
    }

    /** Заметки агента о проекте (.pocketcode/NOTES.md): решения, договорённости, подводные камни. */
    fun projectNotes(): String? {
        val f = File(projectRoot(), ".pocketcode/NOTES.md")
        if (!f.isFile) return null
        return runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.takeLast(4000)
    }

    fun addNote(note: String) {
        val f = File(projectRoot(), ".pocketcode/NOTES.md")
        f.parentFile?.mkdirs()
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        f.appendText("- ($date) ${note.trim().replace('\n', ' ')}\n")
    }

    /** AGENTS.md (или CLAUDE.md) из корня проекта. */
    fun projectInstructions(): String? {
        val root = projectRoot()
        val parts = listOf("AGENTS.md", "CLAUDE.md", ".pocketcode/AGENTS.md").mapNotNull { n ->
            File(root, n).takeIf { it.isFile }?.let { f -> runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { n to it } }
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n") { (n, t) -> "[$n]\n" + t.take(10_000) }
    }
}
