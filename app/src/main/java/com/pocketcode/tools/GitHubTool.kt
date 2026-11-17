package com.pocketcode.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Ошибка GitHub API с кодом ответа. */
class GitHubError(val code: Int, message: String) : IllegalStateException(message)

/**
 * GitHub API: файлы, ветки, pull request, issues, статус проверок.
 * Работает с репозиторием текущего проекта (remote origin) или с любым другим, который доступен токену (параметр repo).
 * Токен берётся из настроек; какие репозитории ему доступны, решается при создании токена на GitHub.
 */
class GitHubTool(private val ws: Workspace, private val token: () -> String) : Tool {
    override val name = "github"
    override val description =
        "GitHub API. По умолчанию — репозиторий текущего проекта (remote origin); для любого другого репозитория, " +
            "выданного токену, укажи repo (владелец/имя или ссылка). action: " +
            "repos (список доступных репозиториев), " +
            "tree (path? — папка, ref?), file_get (path, ref?, save_to? — сохранить в проект), " +
            "file_put (path, content ИЛИ from_file из проекта, message?, branch? — создаёт или обновляет файл коммитом), " +
            "file_delete (path, message?, branch?), branch_list, branch_create (branch, ref? — от какой ветки), " +
            "commits (ref?), runs (ref? — запуски GitHub Actions), " +
            "pr_create (title, body, head?, base?), pr_list, pr_get (number), pr_merge (number, merge_method?), " +
            "issue_list, issue_get (number), issue_create (title, body), issue_comment (number, body), issue_close (number), " +
            "checks (ref? — ветка или коммит). " +
            "Файлы на GitHub меняются прямо через API, клонировать репозиторий не нужно. Если branch не указана, изменения идут в основную ветку. " +
            "Перед pr_create в текущем проекте ветку нужно отправить (git push). Токену для записи нужно право Contents: Read and write."
    override val schema = objSchema(
        Prop("action", "string", "Действие"),
        Prop("repo", "string", "Репозиторий: владелец/имя или ссылка на GitHub (по умолчанию — репозиторий текущего проекта)", required = false),
        Prop("path", "string", "Путь к файлу или папке внутри репозитория", required = false),
        Prop("content", "string", "Новое содержимое файла (текст целиком)", required = false),
        Prop("from_file", "string", "Файл из текущего проекта, который нужно загрузить в репозиторий (вместо content, подходит для двоичных)", required = false),
        Prop("save_to", "string", "Куда в текущем проекте сохранить скачанный файл", required = false),
        Prop("message", "string", "Сообщение коммита", required = false),
        Prop("branch", "string", "Ветка: куда коммитить или имя новой ветки", required = false),
        Prop("merge_method", "string", "Способ слияния PR: merge, squash или rebase", required = false),
        Prop("title", "string", "Заголовок", required = false),
        Prop("body", "string", "Текст", required = false),
        Prop("head", "string", "Ветка с изменениями (по умолчанию текущая)", required = false),
        Prop("base", "string", "Целевая ветка (по умолчанию основная)", required = false),
        Prop("number", "integer", "Номер PR или issue", required = false),
        Prop("ref", "string", "Ветка или коммит (для tree, file_get, commits, runs, checks; для branch_create — от какой ветки)", required = false),
    )

    private val writes = setOf(
        "pr_create", "issue_create", "issue_comment",
        "file_put", "file_delete", "branch_create", "pr_merge", "issue_close",
    )
    override fun needsApproval(input: JsonObject) = input.str("action") in writes
    override fun describe(input: JsonObject): String {
        val what = input.str("path") ?: input.str("title") ?: input.str("branch") ?: input.int("number")?.let { "#$it" } ?: ""
        val where = input.str("repo")?.let { " [$it]" } ?: ""
        return "github ${input.str("action")}$where $what".trim()
    }

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private fun originRepo(): String {
        val cfg = File(ws.root, ".git/config")
        require(cfg.isFile) { "В проекте нет git-репозитория: укажи repo (владелец/имя)" }
        val url = Regex("""\[remote "origin"][^\[]*?url\s*=\s*(\S+)""").find(cfg.readText())?.groupValues?.get(1)
            ?: throw IllegalStateException("У репозитория нет remote origin: укажи repo (владелец/имя)")
        return GitHubRefs.repoName(url) ?: throw IllegalStateException("origin ($url) — не GitHub")
    }

    /** Репозиторий для вызова и признак, что он указан явно (а не взят из текущего проекта). */
    private fun target(input: JsonObject): Pair<String, Boolean> {
        val given = input.str("repo")?.takeIf { it.isNotBlank() }
        if (given == null) return Pair(originRepo(), false)
        val name = GitHubRefs.repoName(given)
            ?: throw IllegalArgumentException("repo: нужен вид владелец/имя или ссылка на GitHub, получено «$given»")
        return Pair(name, true)
    }

    private fun branch(): String {
        val head = File(ws.root, ".git/HEAD").takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return head.removePrefix("ref: refs/heads/").takeIf { head.startsWith("ref:") } ?: throw IllegalStateException("Не на ветке (detached HEAD)")
    }

    private fun call(method: String, path: String, body: JsonObject? = null): JsonElement {
        val t = token()
        require(t.isNotBlank()) { "Нужен токен GitHub: добавь его в настройках" }
        val rb = Request.Builder().url("https://api.github.com$path")
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        rb.method(method, body?.toString()?.toRequestBody("application/json".toMediaType()))
        http.newCall(rb.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { (Json.parseToJsonElement(text).jsonObject["message"] as JsonPrimitive).content }.getOrNull() ?: text.take(300)
                val hint = if (r.code == 401 || r.code == 403) " (проверь токен: права и выданные ему репозитории)" else ""
                throw GitHubError(r.code, "GitHub ${r.code}: $msg$hint")
            }
            return if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text)
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun defaultBranch(repo: String) = call("GET", "/repos/$repo").jsonObject.s("default_branch")

    /** Содержимое пути (файл — объект, папка — массив) или null, если такого пути нет. */
    private fun contents(repo: String, path: String, ref: String?): JsonElement? {
        val p = GitHubRefs.encodePath(path)
        val q = if (ref.isNullOrBlank()) "" else "?ref=" + GitHubRefs.enc(ref)
        val tail = if (p.isEmpty()) "" else "/$p"
        return try {
            call("GET", "/repos/$repo/contents$tail$q")
        } catch (e: GitHubError) {
            if (e.code == 404) null else throw e
        }
    }

    private fun readRepoFile(repo: String, path: String, ref: String?): ByteArray {
        val c = contents(repo, path, ref) ?: throw IllegalStateException("Файл не найден: $path")
        require(c !is JsonArray) { "$path — это папка, используй tree" }
        val o = c.jsonObject
        require(o.s("type") == "file") { "$path — не обычный файл (${o.s("type")})" }
        if (o.s("size") == "0") return ByteArray(0)
        val inline = o.s("content")
        if (inline.isNotEmpty() && o.s("encoding") == "base64") return Base64.getMimeDecoder().decode(inline)
        val blob = call("GET", "/repos/$repo/git/blobs/${o.s("sha")}").jsonObject
        return Base64.getMimeDecoder().decode(blob.s("content"))
    }

    private fun listRepos(): String {
        val arr = call("GET", "/user/repos?per_page=50&sort=pushed") as JsonArray
        return arr.joinToString("\n") {
            val o = it.jsonObject
            val push = ((o["permissions"] as? JsonObject)?.get("push") as? JsonPrimitive)?.contentOrNull == "true"
            val priv = if (o.s("private") == "true") " [приватный]" else ""
            o.s("full_name") + priv + (if (push) " [запись]" else " [чтение]")
        }.ifEmpty { "Токену не выдан ни один репозиторий." }
    }

    private fun inBranch(branch: String?) = if (branch == null) "" else " в ветке $branch"

    override suspend fun run(input: JsonObject): String = withContext(Dispatchers.IO) {
        val action = input.need("action")
        if (action == "repos") return@withContext listRepos()
        val (repo, remote) = target(input)
        when (action) {
            "tree" -> {
                val path = GitHubRefs.cleanPath(input.str("path") ?: "")
                val c = contents(repo, path, input.str("ref")) ?: throw IllegalStateException("Не найдено: " + path.ifEmpty { "/" })
                if (c is JsonArray) {
                    c.joinToString("\n") {
                        val o = it.jsonObject
                        val type = o.s("type")
                        val mark = if (type == "dir") "[папка] " else ""
                        val size = if (type == "file") " (" + o.s("size") + " Б)" else ""
                        mark + o.s("path") + size
                    }.ifEmpty { "Папка пуста." }
                } else {
                    "Это файл, а не папка: $path. Используй file_get."
                }
            }
            "file_get" -> {
                val path = GitHubRefs.cleanPath(input.need("path"))
                require(path.isNotEmpty()) { "Нужен path" }
                val bytes = readRepoFile(repo, path, input.str("ref"))
                val save = input.str("save_to")?.takeIf { it.isNotBlank() }
                if (save != null) {
                    val f = ws.resolve(save)
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                    "Файл $path сохранён в проект: ${ws.rel(f)} (${bytes.size} Б)"
                } else {
                    require(!GitHubRefs.isBinary(bytes)) { "Файл двоичный (${bytes.size} Б): укажи save_to, чтобы сохранить его в проект" }
                    val text = String(bytes, Charsets.UTF_8)
                    if (text.length > MAX_TEXT) text.take(MAX_TEXT) + "\n… (обрезано, всего ${text.length} символов; целиком — через save_to)" else text
                }
            }
            "file_put" -> {
                val path = GitHubRefs.cleanPath(input.need("path"))
                require(path.isNotEmpty()) { "Нужен path" }
                val fromFile = input.str("from_file")?.takeIf { it.isNotBlank() }
                val bytes: ByteArray = if (fromFile != null) {
                    val f = ws.resolve(fromFile)
                    require(f.isFile) { "Нет файла в проекте: $fromFile" }
                    f.readBytes()
                } else {
                    val text = input.str("content") ?: throw IllegalArgumentException("Нужен content или from_file")
                    text.toByteArray(Charsets.UTF_8)
                }
                require(bytes.size <= MAX_UPLOAD) { "Файл слишком большой для загрузки через API: ${bytes.size} Б (максимум $MAX_UPLOAD)" }
                val branch = input.str("branch")?.takeIf { it.isNotBlank() }
                val existing = contents(repo, path, branch)
                require(existing !is JsonArray) { "$path — это папка, а не файл" }
                val oldSha = existing?.jsonObject?.s("sha")?.takeIf { it.isNotEmpty() }
                if (oldSha != null && oldSha == GitHubRefs.blobSha(bytes)) {
                    "Без изменений: $path${inBranch(branch)} уже такой же."
                } else {
                    val msg = input.str("message")?.takeIf { it.isNotBlank() } ?: (if (oldSha == null) "Add $path" else "Update $path")
                    val r = call("PUT", "/repos/$repo/contents/${GitHubRefs.encodePath(path)}", buildJsonObject {
                        put("message", msg)
                        put("content", Base64.getEncoder().encodeToString(bytes))
                        if (oldSha != null) put("sha", oldSha)
                        if (branch != null) put("branch", branch)
                    }).jsonObject
                    val commit = (r["commit"] as? JsonObject)?.s("sha").orEmpty().take(7)
                    val verb = if (oldSha == null) "Создан" else "Обновлён"
                    "$verb файл $path${inBranch(branch)} (коммит $commit)"
                }
            }
            "file_delete" -> {
                val path = GitHubRefs.cleanPath(input.need("path"))
                require(path.isNotEmpty()) { "Нужен path" }
                val branch = input.str("branch")?.takeIf { it.isNotBlank() }
                val existing = contents(repo, path, branch) ?: throw IllegalStateException("Файл не найден: $path")
                require(existing !is JsonArray) { "$path — это папка: удалить можно только файл" }
                val sha = existing.jsonObject.s("sha")
                val msg = input.str("message")?.takeIf { it.isNotBlank() } ?: "Delete $path"
                call("DELETE", "/repos/$repo/contents/${GitHubRefs.encodePath(path)}", buildJsonObject {
                    put("message", msg)
                    put("sha", sha)
                    if (branch != null) put("branch", branch)
                })
                "Удалён файл $path${inBranch(branch)}"
            }
            "branch_list" -> (call("GET", "/repos/$repo/branches?per_page=50") as JsonArray)
                .joinToString("\n") { it.jsonObject.s("name") }.ifEmpty { "Веток нет." }
            "branch_create" -> {
                val name = input.need("branch").trim()
                require(name.isNotEmpty()) { "Нужно имя ветки (branch)" }
                val from = input.str("ref")?.takeIf { it.isNotBlank() } ?: defaultBranch(repo)
                val sha = (call("GET", "/repos/$repo/branches/${GitHubRefs.encodePath(from)}").jsonObject["commit"] as? JsonObject)?.s("sha").orEmpty()
                check(sha.isNotEmpty()) { "Не удалось найти ветку $from" }
                call("POST", "/repos/$repo/git/refs", buildJsonObject {
                    put("ref", "refs/heads/$name")
                    put("sha", sha)
                })
                "Ветка $name создана из $from (${sha.take(7)})"
            }
            "commits" -> {
                val ref = input.str("ref")?.takeIf { it.isNotBlank() }
                val q = "per_page=15" + (if (ref != null) "&sha=" + GitHubRefs.enc(ref) else "")
                (call("GET", "/repos/$repo/commits?$q") as JsonArray).joinToString("\n") {
                    val o = it.jsonObject
                    val c = (o["commit"] as? JsonObject) ?: JsonObject(emptyMap())
                    val author = (c["author"] as? JsonObject)?.s("name").orEmpty()
                    o.s("sha").take(7) + " " + c.s("message").lineSequence().first() + " (" + author + ")"
                }.ifEmpty { "Коммитов нет." }
            }
            "runs" -> {
                val ref = input.str("ref")?.takeIf { it.isNotBlank() }
                val q = "per_page=10" + (if (ref != null) "&branch=" + GitHubRefs.enc(ref) else "")
                val runs = call("GET", "/repos/$repo/actions/runs?$q").jsonObject["workflow_runs"]?.jsonArray ?: JsonArray(emptyList())
                if (runs.isEmpty()) "Запусков Actions нет."
                else runs.joinToString("\n") {
                    val o = it.jsonObject
                    "${o.s("name")} [${o.s("status")} ${o.s("conclusion")}] ${o.s("head_branch")} ${o.s("html_url")}"
                }
            }
            "pr_create" -> {
                val head = input.str("head") ?: (if (remote) throw IllegalArgumentException("Нужен head — ветка с изменениями") else branch())
                val base = input.str("base") ?: defaultBranch(repo)
                val r = call("POST", "/repos/$repo/pulls", buildJsonObject {
                    put("title", input.need("title")); put("body", input.str("body") ?: "")
                    put("head", head); put("base", base)
                }).jsonObject
                "PR создан: ${r.s("html_url")}"
            }
            "pr_list" -> (call("GET", "/repos/$repo/pulls?state=open&per_page=15") as JsonArray).joinToString("\n") {
                val o = it.jsonObject; "#${(o["number"] as JsonPrimitive).content} ${o.s("title")} (${o.s("html_url")})"
            }.ifEmpty { "Открытых PR нет." }
            "pr_get" -> call("GET", "/repos/$repo/pulls/${input.int("number") ?: error("Нужен number")}").jsonObject.let {
                "#${(it["number"] as JsonPrimitive).content} ${it.s("title")} [${it.s("state")}]\n${it.s("html_url")}\n\n${it.s("body").take(3000)}"
            }
            "pr_merge" -> {
                val n = input.int("number") ?: error("Нужен number")
                val r = call("PUT", "/repos/$repo/pulls/$n/merge", buildJsonObject {
                    put("merge_method", GitHubRefs.mergeMethod(input.str("merge_method")))
                    input.str("title")?.let { put("commit_title", it) }
                    input.str("body")?.let { put("commit_message", it) }
                }).jsonObject
                "PR #$n: ${r.s("message")}"
            }
            "issue_list" -> (call("GET", "/repos/$repo/issues?state=open&per_page=20") as JsonArray)
                .filter { (it.jsonObject["pull_request"]) == null }.joinToString("\n") {
                    val o = it.jsonObject; "#${(o["number"] as JsonPrimitive).content} ${o.s("title")}"
                }.ifEmpty { "Открытых issues нет." }
            "issue_get" -> {
                val n = input.int("number") ?: error("Нужен number")
                val o = call("GET", "/repos/$repo/issues/$n").jsonObject
                val comments = (call("GET", "/repos/$repo/issues/$n/comments?per_page=10") as JsonArray)
                    .joinToString("\n---\n") { c -> c.jsonObject["user"]!!.jsonObject.s("login") + ": " + c.jsonObject.s("body").take(800) }
                "#$n ${o.s("title")} [${o.s("state")}]\n${o.s("body").take(3000)}\n\nКомментарии:\n$comments"
            }
            "issue_create" -> call("POST", "/repos/$repo/issues", buildJsonObject {
                put("title", input.need("title")); put("body", input.str("body") ?: "")
            }).jsonObject.let { "Issue создан: ${it.s("html_url")}" }
            "issue_comment" -> call("POST", "/repos/$repo/issues/${input.int("number") ?: error("Нужен number")}/comments",
                buildJsonObject { put("body", input.need("body")) }).jsonObject.let { "Комментарий добавлен: ${it.s("html_url")}" }
            "issue_close" -> {
                val n = input.int("number") ?: error("Нужен number")
                call("PATCH", "/repos/$repo/issues/$n", buildJsonObject { put("state", "closed") })
                "#$n закрыт"
            }
            "checks" -> {
                val ref = input.str("ref") ?: (if (remote) defaultBranch(repo) else branch())
                val runs = call("GET", "/repos/$repo/commits/$ref/check-runs?per_page=30").jsonObject["check_runs"]?.jsonArray ?: JsonArray(emptyList())
                if (runs.isEmpty()) "Проверок нет."
                else runs.joinToString("\n") { val o = it.jsonObject; "${o.s("name")}: ${o.s("status")} ${o.s("conclusion")}" }
            }
            else -> throw IllegalArgumentException("Неизвестное действие: $action")
        }
    }

    private companion object {
        const val MAX_TEXT = 60_000
        const val MAX_UPLOAD = 10 * 1024 * 1024
    }
}
