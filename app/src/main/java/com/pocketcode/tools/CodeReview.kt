package com.pocketcode.tools

import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Детерминированный аудит изменённых файлов. Модель может «забыть» про безопасность, поэтому
 * дешёвые и однозначные правила проверяются кодом, а не просьбой в промпте:
 * секреты в коде, инъекции (SQL/шелл/HTML), отключённая проверка TLS, небезопасная
 * десериализация, заглушки вместо реализации и проглоченные ошибки.
 */
object CodeReview {

    enum class Severity { HIGH, MEDIUM, LOW }

    data class Finding(
        val path: String,
        val line: Int,
        val severity: Severity,
        val rule: String,
        val hint: String,
        val snippet: String,
    )

    private class Rule(
        val id: String,
        val severity: Severity,
        val hint: String,
        val pattern: Regex,
        /** Доп. условие по строке и расширению файла, чтобы не ловить ложные срабатывания. */
        val applies: (line: String, ext: String) -> Boolean = { _, _ -> true },
    )

    private val rules: List<Rule> = listOf(
        Rule(
            "hardcoded-secret", Severity.HIGH,
            "Секрет вписан в код. Вынеси в настройки или переменную окружения и не коммить.",
            Regex("""(?i)\b(api[_-]?key|apikey|secret|token|password|passwd|access[_-]?key|client[_-]?secret|private[_-]?key)\b\s*[:=]\s*["'][^"'\s]{8,}["']"""),
        ) { line, _ -> !looksLikePlaceholder(line) },
        Rule(
            "private-key", Severity.HIGH,
            "В файл попал закрытый ключ. Убери его из репозитория и перевыпусти.",
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
        ),
        Rule(
            "tls-disabled", Severity.HIGH,
            "Проверка TLS/сертификата отключена — это открывает перехват трафика. Включи проверку.",
            Regex("""(?i)verify\s*=\s*False|rejectUnauthorized\s*[:=]\s*false|InsecureSkipVerify\s*:\s*true|NODE_TLS_REJECT_UNAUTHORIZED\s*[:=]\s*["']?0|CERT_NONE"""),
        ),
        Rule(
            "dynamic-eval", Severity.HIGH,
            "eval/exec от строки — выполнение чужого кода. Используй разбор данных вместо вычисления строки.",
            Regex("""(?<![\w.$])(eval|exec)\s*\("""),
        ) { line, ext ->
            ext in setOf("js", "jsx", "ts", "tsx", "py", "lua", "php", "rb") &&
                !line.contains("child_process") && !line.contains("literal_eval")
        },
        Rule(
            "shell-injection", Severity.HIGH,
            "Команда собирается строкой или через shell — возможна инъекция. Передавай аргументы списком без shell.",
            Regex("""(?i)shell\s*=\s*True|os\.system\s*\(|os\.popen\s*\(|Runtime\.getRuntime\(\)\s*\.exec\s*\("""),
        ),
        Rule(
            "sql-concat", Severity.HIGH,
            "SQL собирается склейкой — это SQL-инъекция. Используй параметры/плейсхолдеры.",
            Regex("""(?i)\b(select|insert|update|delete)\b[^"'\n]{0,120}\b(from|into|set|values|where)\b[^"'\n]{0,120}["']\s*(\+|%|\.\s*format\s*\()"""),
        ),
        Rule(
            "sql-fstring", Severity.HIGH,
            "SQL собирается f-строкой — это SQL-инъекция. Передавай значения параметрами.",
            Regex("""(?i)[fr]?["'][^"'\n]*\b(select|insert|update|delete)\b[^"'\n]*\b(from|into|set|values)\b[^"'\n]*\{"""),
        ) { _, ext -> ext == "py" },
        Rule(
            "xss-html", Severity.HIGH,
            "Вставка непроверенного HTML (innerHTML/document.write) — XSS. Экранируй или используй textContent.",
            Regex("""(?i)innerHTML\s*=|dangerouslySetInnerHTML|document\.write\s*\("""),
        ),
        Rule(
            "unsafe-deserialize", Severity.HIGH,
            "Небезопасная десериализация данных извне — можно выполнить чужой код. Используй JSON и безопасный загрузчик.",
            Regex("""(?i)pickle\.loads?\s*\(|marshal\.loads\s*\(|yaml\.load\s*\(|ObjectInputStream"""),
        ) { line, _ -> !line.contains("SafeLoader") && !line.contains("safe_load") },
        Rule(
            "cors-wildcard", Severity.MEDIUM,
            "CORS открыт для всех источников — так теряется защита браузера. Укажи конкретные домены.",
            Regex("""(?i)Access-Control-Allow-Origin["']?\s*[,:]\s*["']\*|allow_origins\s*=\s*\[?\s*["']\*|@CrossOrigin\(\s*(origins\s*=\s*)?["']\*"""),
        ),
        Rule(
            "weak-password-hash", Severity.MEDIUM,
            "Слабый хеш для пароля. Нужен bcrypt/argon2/scrypt с солью.",
            Regex("""(?i)\b(md5|sha1)\s*\("""),
        ) { line, _ -> Regex("(?i)pass|парол").containsMatchIn(line) },
        Rule(
            "chmod-777", Severity.MEDIUM,
            "Права 777 открывают файл всем. Дай минимально нужные права (644/755).",
            Regex("""chmod\s+(-[a-zA-Z]+\s+)?777"""),
        ),
        Rule(
            "swallowed-error", Severity.MEDIUM,
            "Ошибка проглатывается пустым catch/except — сбой пройдёт незаметно. Обработай или пробрось.",
            Regex("""(?i)catch\s*(\([^)]*\))?\s*\{\s*\}|except[^:\n]*:\s*pass\b"""),
        ),
        Rule(
            "destructive-var", Severity.HIGH,
            "Удаление по переменной без проверки пути. Ограничь путь и проверь, что он не пустой.",
            Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*f?\s+["']?\$\{?\w+"""),
        ),
        // Обычные TODO/FIXME в комментариях не считаем ошибкой: их слишком много в любом живом коде
        // и агент начинал гонять себя по кругу. Ловим только реальные заглушки вместо реализации.
        Rule(
            "placeholder", Severity.MEDIUM,
            "Заглушка вместо кода: допиши реализацию, иначе проект соберётся, но не будет работать.",
            Regex("""(?i)\b(NotImplementedError|NotImplementedException|UnsupportedOperationException|your code here|implement me|остальная часть|остальной код)\b|TODO\s*\(\s*\)"""),
        ),
    )

    private val skippedDirs = setOf(".git", "node_modules", "build", "dist", "out", "target", ".gradle", "vendor", "__pycache__", ".venv", "venv")
    private val skippedExt = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "ico", "pdf", "zip", "gz", "tgz", "jar", "apk", "aab", "so", "dylib", "dll",
        "exe", "bin", "mp3", "mp4", "wav", "ogg", "ttf", "otf", "woff", "woff2", "class", "dex", "lock",
    )

    private val maxFileBytes = 400_000L
    private val maxFiles = 200
    private val maxFindings = 60

    /** Пустые обработчики, записанные в две строки: `catch (e) {\n}` и `except X:\n    pass`. */
    private val multiLineSwallowed = listOf(
        Regex("""catch\s*(\([^)]*\))?\s*\{\s*\}"""),
        Regex("""except[^:\n]*:\s*\n?\s*pass\b"""),
    )

    /** Ищет проблемы в тексте одного файла. */
    fun scanText(path: String, text: String): List<Finding> {
        val ext = path.substringAfterLast('.', "").lowercase()
        val out = mutableListOf<Finding>()
        text.lineSequence().forEachIndexed { index, raw ->
            if (raw.length > 2000) return@forEachIndexed
            val line = raw.trimEnd()
            if (line.isBlank()) return@forEachIndexed
            for (rule in rules) {
                if (!rule.pattern.containsMatchIn(line)) continue
                if (!rule.applies(line, ext)) continue
                if (out.size >= maxFindings) return out
                out += Finding(path, index + 1, rule.severity, rule.id, rule.hint, line.trim().take(160))
            }
        }
        // Многострочные пустые обработчики ошибок: одна находка на каждый случай.
        val swallowed = multiLineSwallowed.firstNotNullOfOrNull { it.find(text) }
        if (swallowed != null && out.none { it.rule == "swallowed-error" }) {
            val line = text.take(swallowed.range.first).count { it == '\n' } + 1
            val hint = rules.first { it.id == "swallowed-error" }
            out += Finding(path, line, hint.severity, hint.id, hint.hint, swallowed.value.replace("\n", " ").take(160))
        }
        return out
    }

    data class Report(val findings: List<Finding>, val problems: List<String>, val files: Int)

    /** Проверяет файлы (папки раскрываются) и возвращает находки в порядке важности. */
    fun scan(root: File, paths: List<String>): Report {
        val findings = mutableListOf<Finding>()
        val problems = mutableListOf<String>()
        val queue = ArrayDeque<File>()
        paths.forEach { p ->
            val f = if (p.startsWith("/")) File(p) else File(root, p)
            if (f.exists()) queue += f else problems += "$p: файла нет"
        }
        var scanned = 0
        while (queue.isNotEmpty() && scanned < maxFiles) {
            val f = queue.removeFirst()
            if (f.isDirectory) {
                if (f.name.lowercase() in skippedDirs) continue
                f.listFiles()?.sortedBy { it.name }?.take(200)?.forEach { queue += it }
                continue
            }
            if (!f.isFile) continue
            if (f.extension.lowercase() in skippedExt) continue
            if (f.length() > maxFileBytes) { problems += "${rel(root, f)}: слишком большой, пропущен"; continue }
            val text = runCatching { f.readText() }.getOrNull()
            if (text == null) { problems += "${rel(root, f)}: не текст, пропущен"; continue }
            if (text.contains('\u0000')) { problems += "${rel(root, f)}: двоичный, пропущен"; continue }
            scanned++
            findings += scanText(rel(root, f), text)
            if (findings.size >= maxFindings) break
        }
        val sorted = findings.sortedWith(compareBy({ it.severity.ordinal }, { it.path }, { it.line }))
        return Report(sorted, problems, scanned)
    }

    /** Готовый текст для модели: критичное всегда сверху, коротко, с адресами и подсказками. */
    fun format(findings: List<Finding>, problems: List<String> = emptyList(), scannedFiles: Int = findings.map { it.path }.distinct().size): String {
        val ordered = findings.sortedWith(compareBy({ it.severity.ordinal }, { it.path }, { it.line }))
        val high = ordered.count { it.severity == Severity.HIGH }
        val medium = ordered.count { it.severity == Severity.MEDIUM }
        val low = ordered.count { it.severity == Severity.LOW }
        val head = "Аудит кода: файлов $scannedFiles, критичных $high, средних $medium, подсказок $low."
        val body = ordered.take(maxFindings).joinToString("\n") {
            "[${it.severity}] ${it.path}:${it.line} ${it.rule} — ${it.hint}\n    ${it.snippet}"
        }
        val tail = buildString {
            if (ordered.size > maxFindings) append("\n... ещё ${ordered.size - maxFindings} находок не показаны")
            if (problems.isNotEmpty()) append("\n").append(problems.take(10).joinToString("\n"))
        }
        return when {
            ordered.isEmpty() && problems.isEmpty() -> "$head Проблем не найдено."
            ordered.isEmpty() -> "$head\n${problems.take(10).joinToString("\n")}"
            else -> "$head\n$body$tail"
        }
    }

    fun review(root: File, paths: List<String>): String {
        if (paths.isEmpty()) return "Аудит: нечего проверять — передай пути файлов."
        val report = scan(root, paths.distinct().take(maxFiles))
        return format(report.findings, report.problems, report.files)
    }

    private fun rel(root: File, f: File): String =
        runCatching { f.canonicalFile.relativeTo(root.canonicalFile).path }.getOrElse { f.name }

    private fun looksLikePlaceholder(line: String): Boolean {
        val low = line.lowercase()
        return listOf("your_", "your-", "example", "xxx", "changeme", "change_me", "placeholder", "dummy", "sample", "test_", "<", "\${", "%(", "env.", "settings.", "secret.").any { low.contains(it) }
    }
}

/** Инструмент `code_review`: аудит правок без запуска проекта. */
class ReviewTool(
    private val ws: Workspace,
    private val recent: () -> List<String> = { emptyList() },
) : Tool {
    override val name = "code_review"
    override val description =
        "Аудит кода на типовые дыры и заглушки: секреты в коде, SQL/шелл/HTML-инъекции, отключённый TLS, " +
            "небезопасная десериализация, слабые хеши паролей, права 777, пустые catch, заглушки. " +
            "Возвращает список file:line с подсказками. Вызывай после правок (без аргументов проверит изменённые файлы)."
    override val schema = objSchema(
        Prop("paths", "string", "Файлы или папки через запятую (по умолчанию — изменённые в этой сессии)", required = false),
    )
    override fun needsApproval(input: JsonObject) = false
    override fun describe(input: JsonObject) = "аудит " + (input.str("paths") ?: "изменённых файлов")

    override suspend fun run(input: JsonObject): String {
        val requested = input.str("paths")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val paths = if (requested.isNotEmpty()) requested else recent().distinct().takeLast(30)
        if (paths.isEmpty()) return "Аудит: изменённых файлов нет — передай paths."
        val resolved = paths.mapNotNull { p ->
            runCatching { ws.resolve(p).path }.getOrElse { return@mapNotNull null }
        }
        val outside = paths.filter { p -> runCatching { ws.resolve(p) }.isFailure }
        val text = CodeReview.review(ws.root, resolved)
        return if (outside.isEmpty()) text else text + "\nПропущено вне проекта: " + outside.joinToString(", ")
    }
}
