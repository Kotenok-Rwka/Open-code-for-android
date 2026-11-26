package com.pocketcode

/** Смена версии: bump major/minor/patch + строка версии. */
object VersionBump {
    data class SemVer(val major: Int, val minor: Int, val patch: Int) {
        override fun toString(): String = "$major.$minor.$patch"
    }

    fun parse(s: String): SemVer? {
        val parts = s.trim().split(".")
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.size == 2) return SemVer(nums[0], nums[1], 0)
        return SemVer(nums[0], nums[1], nums[2])
    }

    fun bump(current: String, part: String): String? {
        val v = parse(current) ?: return null
        return when (part) {
            "major" -> SemVer(v.major + 1, 0, 0)
            "minor" -> SemVer(v.major, v.minor + 1, 0)
            "patch" -> SemVer(v.major, v.minor, v.patch + 1)
            else -> null
        }.toString()
    }
}

/** Changelog из списка тем коммитов: группировка фичи/фиксы/прочее. */
object Changelog {
    data class Groups(val features: List<String>, val fixes: List<String>, val other: List<String>)

    fun group(subjects: List<String>): Groups {
        val f = subjects.filter { it.trim().lowercase().startsWith("feat") || it.trim().startsWith("Добав") || it.trim().startsWith("+") }
        val x = subjects.filter { it.trim().lowercase().startsWith("fix") || it.trim().startsWith("Исправ") }
        val o = subjects.filter { it !in f && it !in x }
        return Groups(f, x, o)
    }

    fun markdown(groups: Groups, title: String = "Что нового"): String = buildString {
        appendLine("# $title")
        if (groups.features.isNotEmpty()) {
            appendLine(); appendLine("## Новое")
            groups.features.forEach { appendLine("- $it") }
        }
        if (groups.fixes.isNotEmpty()) {
            appendLine(); appendLine("## Исправления")
            groups.fixes.forEach { appendLine("- $it") }
        }
        if (groups.other.isNotEmpty()) {
            appendLine(); appendLine("## Прочее")
            groups.other.forEach { appendLine("- $it") }
        }
    }.trimEnd() + "\n"
}

/** Техдолг: TODO/FIXME/HACK из строк кода. */
object TechDebt {
    data class Item(val kind: String, val line: Int, val text: String)

    private val rx = Regex("""\b(TODO|FIXME|HACK|XXX)\b[:\s]?(.{0,120})""")

    fun scanLine(lineNo: Int, line: String): Item? {
        val m = rx.find(line) ?: return null
        return Item(m.groupValues[1], lineNo, m.groupValues[2].trim())
    }

    fun scan(source: String): List<Item> =
        source.lines().mapIndexedNotNull { i, ln -> scanLine(i + 1, ln) }
}

/** Сложность функции (приближённо): 1 + число ветвлений. */
object Complexity {
    private val branches = Regex("""\b(if|elif|for|while|when|catch|and|or|\?\:)\b|\?\?|\?:|&&|\|\|""")

    fun ofBody(body: String): Int = 1 + branches.findAll(body).count()

    fun label(cc: Int): String = when {
        cc <= 5 -> "простая"
        cc <= 10 -> "средняя"
        cc <= 20 -> "сложная"
        else -> "очень сложная"
    }
}

/** Мёртвый код: имена, объявленные, но не использованные вне объявления. */
object DeadCode {
    fun find(declarations: Map<String, List<String>>): List<String> {
        val dead = mutableListOf<String>()
        for ((name, lines) in declarations) {
            val used = lines.any { ln ->
                val body = ln.substringAfter(":", "").trim()
                !body.startsWith("fun $name") && !body.startsWith("class $name") &&
                    !body.startsWith("object $name") && Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(body)
            }
            if (!used) dead.add(name)
        }
        return dead
    }
}

/** Покрытие тестами: модули без пары Test. */
object CoverageGaps {
    fun untested(files: List<String>): List<String> {
        val names = files.map { it.substringAfterLast('/') }.toSet()
        return files.filter { f ->
            val name = f.substringAfterLast('/')
            val stem = name.substringBeforeLast('.')
            !(stem.startsWith("test_") || stem.endsWith("Test") || name.endsWith("Test.kt")) &&
                when (name.substringAfterLast('.')) {
                    "py" -> ("test_$stem.py") !in names
                    "kt", "java" -> ("${stem}Test." + name.substringAfterLast('.')) !in names
                    else -> false
                }
        }
    }
}

/** Переключатели функций проекта: сохранение и чтение набора id. */
object FeatureFlags {
    fun parse(saved: String, defaults: Map<String, Boolean>): Map<String, Boolean> {
        val on = saved.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val off = on.filter { it.startsWith("-") }.map { it.removePrefix("-") }.toSet()
        val explicitOn = on.filter { !it.startsWith("-") }.toSet()
        return defaults.mapValues { (id, def) ->
            when (id) {
                in off -> false
                in explicitOn -> true
                else -> def
            }
        }
    }

    fun serialize(enabled: Map<String, Boolean>): String =
        enabled.map { (id, on) -> if (on) id else "-$id" }.sorted().joinToString(",")

    fun isEnabled(state: Map<String, Boolean>, id: String): Boolean = state[id] ?: false
}

/** Экспорт проекта: манифест файлов + размеры для переноса. */
object ProjectExport {
    data class Manifest(val files: List<String>, val totalBytes: Long)

    fun manifest(pathsToSize: Map<String, Long>, skip: Set<String> = setOf(".git", "build", "__pycache__", ".gradle", ".idea")): Manifest {
        val kept = pathsToSize.filter { (path, _) -> skip.none { s -> path == s || path.startsWith("$s/") } }
        return Manifest(kept.keys.sorted(), kept.values.sum())
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f МБ".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1f КБ".format(bytes / 1024.0)
        else -> "$bytes Б"
    }
}

/** Outline: классы и функции из кода Kotlin/Python с номерами строк. */
object Outline {
    data class Symbol(val line: Int, val kind: String, val name: String, val indent: Int)

    private val rx = Regex("""^(\s*)(?:class|fun|def|object|interface)\s+(\w+)""")

    fun scan(source: String): List<Symbol> =
        source.lines().mapIndexedNotNull { i, ln ->
            rx.find(ln)?.let { m ->
                val raw = ln.trimStart().substringBefore(' ')
                val kind = when (raw) {
                    "class", "fun", "def", "object", "interface" -> raw
                    else -> "class"
                }
                Symbol(i + 1, kind, m.groupValues[2], m.groupValues[1].length)
            }
        }
}

/** Голосовой ввод: черновик задачи из сырого текста. */
object VoiceTask {
    fun draft(raw: String, now: String): String {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ""
        return "[голос · $now] ${text.replaceFirstChar { it.uppercase() }}"
    }
}
