package com.pocketcode.agent

import com.pocketcode.llm.Block
import com.pocketcode.llm.LlmClient
import com.pocketcode.llm.Message
import com.pocketcode.llm.ToolSpec
import com.pocketcode.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

sealed class AgentEvent {
    /** Очередной кусок текста ответа (потоковый вывод). */
    data class TextDelta(val text: String) : AgentEvent()

    /** Текущий ответ модели закончился, следующий текст начнётся в новом блоке. */
    object TextEnd : AgentEvent()

    data class ToolCall(val name: String, val description: String) : AgentEvent()
    data class ToolResult(val name: String, val output: String, val isError: Boolean) : AgentEvent()
    data class Error(val message: String) : AgentEvent()

    /** Служебное сообщение в ленте, например «контекст сжат». */
    data class Notice(val text: String) : AgentEvent()
    object Done : AgentEvent()

    /** Достигнут лимит шагов за один запуск. */
    object StepLimit : AgentEvent()

    /** Расход токенов за один ответ модели. */
    data class Usage(val input: Int, val output: Int, val cached: Int, val viaFallback: Boolean = false) : AgentEvent()
}

private const val MAX_TOOL_OUTPUT = 30_000

/** Инструменты, которые меняют код: после них нужна проверка. */
private val CHANGE_TOOLS = setOf("write_file", "edit_file", "apply_patch")

/** Инструменты проверки: синтаксис, аудит, тесты. */
private val VERIFY_TOOLS = setOf("diagnostics", "code_review", "run_tests", "test")

private const val VERIFY_NUDGE =
    "[КОНТРОЛЬ КАЧЕСТВА] Ты изменил файлы, но не проверил их, и не сказал, что проверка невозможна. " +
        "Прежде чем отвечать пользователю: 1) вызови diagnostics для изменённых файлов (или code_review без аргументов), " +
        "2) исправь найденные ошибки и предупреждения безопасности, 3) запусти run_tests, если в проекте есть тесты. " +
        "Если проверка действительно невозможна в этом окружении — ответь и явно напиши строку «НЕ ПРОВЕРЕНО: <что именно и почему>». " +
        "Не выдумывай результаты проверок."
private val RETRY_DELAYS = intArrayOf(3, 8, 20, 45)

/**
 * Ошибка самого запроса (в том числе пропавшая подпись мысли Gemini), которая от повтора не пройдёт.
 * Такие ошибки шлюзы иногда присылают прямо в потоке ответа, и тогда они выглядят как обрыв связи: без этой проверки агент ждал бы
 * по 3, 8, 20 и 45 секунд и отправлял тот же запрос заново.
 */
internal fun isPermanentRequestError(message: String?): Boolean {
    val m = (message ?: return false).lowercase()
    return listOf("thought_signature", "invalid_argument", "invalid argument").any { m.contains(it) }
}

class Agent(
    llm: LlmClient,
    private val tools: List<Tool>,
    private val system: String,
    private val autoApprove: Boolean,
    /** Размер контекста модели в токенах: при заполнении ~70% старая часть истории сжимается. */
    contextLimit: Int,
    /** Спрашивает пользователя: (имя инструмента, описание) -> разрешено ли. */
    private val approve: suspend (String, String) -> Boolean,
    /** История диалога для модели; общая с хранилищем сессий. */
    val history: MutableList<Message> = mutableListOf(),
    /** Забирает уточнения, которые пользователь написал, пока агент работает. */
    private val pollNotes: () -> List<String> = { emptyList() },
    /** Шагов за запуск; 0 — без лимита. */
    private val maxSteps: Int = 0,
    private val hooks: AgentHooks = AgentHooks.NONE,
    /** Резервная модель: включается, если у основной кончилась квота, лимит запросов или ключ не принят. */
    private val fallbackLlm: LlmClient? = null,
    /** Дешёвая модель для сводок контекста. */
    helperLlm: LlmClient? = null,
    /** Эко-режим: старые выводы инструментов урезаются быстрее. */
    private val eco: Boolean = false,
    /** true — спрашивать (approve) даже при autoApprove: так авто-режим root может проверять файловые действия. */
    private val askEvenIfAuto: () -> Boolean = { false },
    /** true — длинный вывод режется с сохранением начала и конца, false — только начала. */
    private val keepTail: () -> Boolean = { true },
    /** true — после правок нельзя закончить без diagnostics/code_review/run_tests (контроль качества). */
    private val verifyChanges: () -> Boolean = { false },
) {
    private var llm: LlmClient = llm
    private var usedFallback = false

    /** Через сколько секунд повторить после временной ошибки; null — ошибка не временная. */
    private fun retryWaitSec(e: Exception, attempt: Int): Int? {
        if (attempt >= RETRY_DELAYS.size) return null
        val m = (e.message ?: "").lowercase()
        if (listOf("insufficient", "quota", "billing", "credit", "invalid api key", "incorrect api key", "unauthorized", "context", "too long").any { m.contains(it) }) return null
        if (isPermanentRequestError(e.message)) return null
        if (e is java.io.IOException && m.startsWith("http ")) {
            val code = m.removePrefix("http ").takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            return if (code in 500..599 || code == 408 || code == 429) RETRY_DELAYS[attempt] else null
        }
        return if (e is java.io.IOException) RETRY_DELAYS[attempt] else null
    }

    private fun looksLikeQuota(msg: String?): Boolean {
        val m = msg?.lowercase() ?: return false
        return listOf("429", "402", "401", "403", "529", "rate limit", "rate_limit", "quota", "insufficient", "too many requests",
            "overloaded", "credit", "billing", "invalid api key", "incorrect api key", "unauthorized").any { m.contains(it) }
    }

    private val compactor = Compactor(helperLlm ?: llm, contextLimit)
    private val byName = tools.associateBy { it.name }
    private val specs = tools.map { ToolSpec(it.name, it.description, it.schema) }

    /** Если прошлый запуск остановили посреди работы, закрываем висящие вызовы инструментов. */
    private fun repairHistory() {
        val last = history.lastOrNull() ?: return
        if (last.role != "assistant") return
        val pending = last.blocks.filterIsInstance<Block.ToolUse>()
        if (pending.isEmpty()) return
        history += Message(
            "user",
            pending.map { Block.ToolResult(it.id, "Прервано пользователем", true) },
        )
    }

    /** Добавляет уточнения пользователя к последнему сообщению пользователя (или отдельным сообщением). */
    private fun injectNotes(notes: List<String>) {
        if (notes.isEmpty()) return
        val extra = notes.map { Block.Text("[Уточнение от пользователя, пока ты работал]\n$it") }
        val last = history.lastOrNull()
        if (last != null && last.role == "user") history[history.lastIndex] = last.copy(blocks = last.blocks + extra)
        else history += Message("user", extra)
    }

    private fun mustAsk(tool: Tool, input: kotlinx.serialization.json.JsonObject, d: Decision): Boolean = when (d) {
        Decision.ASK -> true
        Decision.ALLOW -> tool.alwaysAsk
        else -> tool.needsApproval(input) && (tool.alwaysAsk || !autoApprove || askEvenIfAuto())
    }

    fun execute(text: String, images: List<Block.Image> = emptyList()): Flow<AgentEvent> = channelFlow {
        repairHistory()
        history += Message("user", images + Block.Text(text.ifBlank { "(см. изображение)" }))
        var steps = 0
        var overflowRetried = false
        var changedFiles = false
        var verified = false
        var nudges = 0

        suspend fun callLlm(): com.pocketcode.llm.LlmResponse {
            var attempt = 0
            while (true) {
                try {
                    return llm.complete(system, history, specs) { delta -> trySend(AgentEvent.TextDelta(delta)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val wait = retryWaitSec(e, attempt) ?: throw e
                    attempt++
                    send(AgentEvent.TextEnd)
                    send(AgentEvent.Notice("Связь с моделью прервалась (${(e.message ?: e.javaClass.simpleName).take(80)}). Повтор через $wait с (попытка $attempt из ${RETRY_DELAYS.size})."))
                    kotlinx.coroutines.delay(wait * 1000L)
                }
            }
        }

        while (true) {
            if (maxSteps > 0 && ++steps > maxSteps) {
                send(AgentEvent.StepLimit)
                break
            }
            injectNotes(pollNotes())
            if (eco) compactor.maskOld(history, keepRecent = 6, over = 1200, keep = 300) else compactor.maskOld(history)
            if (compactor.needsCompaction(history)) {
                try {
                    compactor.compact(history, force = false)?.let { send(AgentEvent.Notice(it)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    send(AgentEvent.Notice("Не удалось сжать контекст: ${e.message ?: e}"))
                }
            }
            val response = try {
                callLlm()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(AgentEvent.TextEnd)
                if (!usedFallback && fallbackLlm != null && looksLikeQuota(e.message)) {
                    usedFallback = true
                    llm = fallbackLlm
                    send(AgentEvent.Notice("Основной профиль не сработал (${(e.message ?: "").take(100)}). Перехожу на резервный."))
                    continue
                }
                if (!overflowRetried && Compactor.looksLikeOverflow(e.message)) {
                    // Модель отказалась из-за размера контекста: сжимаем принудительно и повторяем
                    overflowRetried = true
                    try {
                        val note = compactor.compact(history, force = true)
                        if (note != null) {
                            send(AgentEvent.Notice(note))
                            continue
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ce: Exception) {
                        send(AgentEvent.Notice("Не удалось сжать контекст: ${ce.message ?: ce}"))
                    }
                }
                send(AgentEvent.Error(e.message ?: e.toString()))
                break
            }
            overflowRetried = false
            if (response.inputTokens + response.outputTokens > 0) {
                send(AgentEvent.Usage(response.inputTokens, response.outputTokens, response.cachedTokens, usedFallback))
            }
            history += Message("assistant", response.blocks)
            send(AgentEvent.TextEnd)

            val calls = response.blocks.filterIsInstance<Block.ToolUse>()
            if (calls.isEmpty()) {
                val late = pollNotes()
                if (late.isEmpty()) {
                    // Контроль качества: правки без проверки не принимаем как готовый ответ.
                    val answer = response.blocks.filterIsInstance<Block.Text>().joinToString("\n") { it.text }
                    val unchecked = changedFiles && !verified && nudges < 2 && verifyChanges() &&
                        !answer.contains("НЕ ПРОВЕРЕНО", ignoreCase = true)
                    if (unchecked) {
                        nudges++
                        send(AgentEvent.Notice("Контроль качества: проверяю правки перед ответом (попытка $nudges из 2)."))
                        history += Message("user", listOf(Block.Text(VERIFY_NUDGE)))
                        continue
                    }
                    break
                }
                injectNotes(late)
                continue
            }

            val results = mutableListOf<Block>()
            // картинки, которые инструменты (например, скриншоты телефона) хотят показать модели; идут после результатов
            val toolImages = mutableListOf<Block>()
            for (call in calls) {
                val tool = byName[call.name]
                val description = tool?.describe(call.input) ?: call.input.toString().take(200)
                val decision = hooks.decide(call.name, call.input, description)
                send(AgentEvent.ToolCall(call.name, description))

                var output = ""
                var isError = false
                if (tool == null) {
                    output = "Неизвестный инструмент: ${call.name}"
                    isError = true
                } else if (hooks.planMode() && call.name !in PLAN_ALLOWED) {
                    output = "Режим плана: изменять и запускать пока нельзя. Исследуй (чтение, поиск), затем вызови present_plan и дождись одобрения."
                    isError = true
                } else if (decision == Decision.DENY) {
                    output = "Запрещено правилом пользователя в настройках. Не повторяй это, выбери другой путь или спроси пользователя."
                    isError = true
                } else if (mustAsk(tool, call.input, decision) && !approve(call.name, description)) {
                    output = "Пользователь отклонил это действие"
                    isError = true
                } else {
                    try {
                        hooks.before(tool, call.input)
                        output = withContext(Dispatchers.IO) { tool.run(call.input) }
                        hooks.after(tool, call.input, output, false)?.let { output += it }
                        (tool as? com.pocketcode.tools.ImageProducer)?.takeImages()?.let { toolImages += it }
                        if (call.name in CHANGE_TOOLS) changedFiles = true
                        if (call.name in VERIFY_TOOLS) verified = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        output = "Ошибка: ${e.message ?: e.toString()}"
                        isError = true
                    }
                }
                if (output.length > MAX_TOOL_OUTPUT) {
                    // Начало и конец важнее середины: в конце логов сборки и тестов обычно сама ошибка
                    if (keepTail()) {
                        val head = MAX_TOOL_OUTPUT * 6 / 10
                        val tail = MAX_TOOL_OUTPUT - head
                        output = output.take(head) + "\n... (вывод обрезан: пропущено ${output.length - MAX_TOOL_OUTPUT} символов из середины) ...\n" + output.takeLast(tail)
                    } else {
                        output = output.take(MAX_TOOL_OUTPUT) + "\n... (вывод обрезан)"
                    }
                }
                send(AgentEvent.ToolResult(call.name, output, isError))
                results += Block.ToolResult(call.id, output, isError)
            }
            history += Message("user", results + toolImages)
        }
        send(AgentEvent.Done)
    }.buffer(Channel.UNLIMITED)
}

fun systemPrompt(
    workspacePath: String,
    shellInfo: String,
    linux: Boolean,
    /** ALPINE или UBUNTU: от этого зависит пакетный менеджер и имена пакетов в инструкциях. */
    linuxFlavor: String = "",
    adbDevices: List<String>? = null,
    root: Boolean = false,
    instructions: String? = null,
    skills: List<Skill> = emptyList(),
    questionCards: Boolean = true,
    caveman: Boolean = false,
    reasoning: Boolean = true,
    reasoningDepth: Int = 1,
    selfCheck: Boolean = true,
): String {
    val ubuntu = com.pocketcode.linux.LinuxPkgs.isUbuntu(linuxFlavor)
    val linuxDistro = com.pocketcode.linux.LinuxPkgs.distroName(linuxFlavor)
    val linuxPkg = com.pocketcode.linux.LinuxPkgs.installHint(linuxFlavor)
    val linuxPip = com.pocketcode.linux.LinuxPkgs.pipHint(linuxFlavor)
    val linuxPkgs = com.pocketcode.linux.LinuxPkgs.packageNames(linuxFlavor)
    val linuxExtra = if (ubuntu) "Ubuntu uses apt/dpkg: apk flags and Alpine package names do not exist here."
        else "Alpine uses apk and busybox: apt commands and deb package names do not exist here."
    return """
You are PocketCode, a coding agent running inside an Android app on the user's phone. There is no PC:
everything happens on the device and through the network.

Workspace: the current project folder "$workspacePath"; all file tools work inside it and paths are relative to it.

SECURITY RULES (always):
- Never hardcode, print, log, or commit secrets, API keys, tokens, passwords or private keys. Use environment/settings and mask them.
- Never disable validation, authentication, TLS verification, permission checks, or sandboxing to make something "work".
- Never run destructive commands (rm -rf, git push --force, reset --hard, dd, mkfs) without explicit user approval; prefer trash/undo.
- Never exfiltrate files outside the workspace; only send to the user-specified endpoint.
- Validate all external input (type, length, range) and fail with a clear error; never trust a request field as the user's identity.
- SQL only with bound parameters, never string concatenation. Shell only with argument lists, never interpolated user text. HTML output escaped.
- Check authorization on the server for every user-specific read and write; derive the user from the session, never from an id sent by the client.
- Never swallow an error with an empty catch/except, and never turn a failed check into a success.
- If a task requires a secret the user did not provide, ask instead of inventing one.
- Prefer least privilege: read-only tools first; ask before writing outside the project.
- Before finishing, run code_review on the changed files and fix what it finds (injection, secrets, disabled TLS, wildcard CORS, unsafe deserialization, swallowed errors).
Tools: read_file, write_file, edit_file, list_dir, grep, bash, python, git.
- Read a file before editing it. Prefer edit_file for small changes; use write_file for new files.
- python runs an embedded Python 3.11 (standard library only, no pip, no stdin). ${if (linux) "Because bash is a real Linux here, prefer running `python3` through bash: it has pip and many libraries (install with `$linuxPip`)." else "bash has no python, so use this tool for scripts, tests and calculations."}
- bash runs in: $shellInfo
${if (linux) "- Linux notes: the sandbox is $linuxDistro, so use its package manager only: `$linuxPkg`. Useful package names here: $linuxPkgs. $linuxExtra Before relying on python3/node/git run `which python3 node git` and install what is missing. There is no systemd and no real root outside the proot sandbox. The project is mounted at /work and commands start there. The git tool runs the real git CLI, credentials are already configured." else "- Use the git tool for git (pure-Java implementation), not bash."}
${if (linux) "- Building Android apps: users do ask for APKs, so never refuse and never say a phone cannot build. Check `ls /opt/android-sdk`. If it is missing, ask the user to tap 'Установить Android SDK' in Project -> Terminal (it installs JDK 17, the SDK and the aapt2 check). With the SDK present run `export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk; ./gradlew assembleDebug --no-daemon` in the project. If Gradle fails on aapt2 (an x86_64 binary does not run on an ARM phone), do not retry in circles: build in the cloud. Add .github/workflows/android.yml (setup-java 17, ./gradlew assembleDebug, upload the APK as an artifact and as a GitHub Release), push it with the github tool, watch the run with the github tool, and give the user the Release link." else ""}
${if (adbDevices != null) "- adb controls other Android devices over ADB (Wi-Fi), like on a PC. Saved devices: " + (if (adbDevices.isEmpty()) "none (the user can add some on the Devices screen; you may also pass ip:port)" else adbDevices.joinToString("; ")) + ". Pass device by label or ip:port, never -s. Project files are at /work inside adb push/install paths. Read-only commands run without asking, everything else asks the user." else ""}
${if (root) "- root runs a command as root on THIS phone, outside the app sandbox. Every call needs the user's approval. Use it only when really necessary, never touch anything outside the task, and prefer the project folder tools otherwise." else ""}
- bg starts long-running processes (dev servers, watchers, long tests) in the background and lets you read their output; then use preview to show a local web page to the user. Never run a server with plain bash: it would block until timeout.
- code_search finds relevant files/definitions by meaning; diagnostics checks syntax of files you edited (run it after edits).
- code_review audits changed files for real holes: hardcoded secrets, SQL/shell/HTML injection, disabled TLS, wildcard CORS, unsafe deserialization, weak password hashes, empty catch and stubs instead of real code. Run it on every batch of edits.
- github works with GitHub directly through the API (needs the token from settings): read, create, change and delete files (file_put commits straight to the repo, no clone needed), branches, pull requests (create, merge), issues and CI checks/runs. By default it uses the current project's repo; pass repo=owner/name for any other repo the token can reach (action repos lists them). Without branch, commits go to the default branch. spawn starts parallel workers with their own context for independent parts of a big task (different files); collect them with worker_result before you finish.
- run_tests runs the project's tests and returns a compact failure summary. spawn with isolated=true gives a worker its own copy of the project (merged back afterwards).
- remember saves a short durable note about the project (decisions, how to run things, gotchas) that you will see at the start of future chats.
- todo keeps a visible checklist; use it for work with 3+ steps and update it as you go.
- In plan mode (the user message says so) you may only read and search; finish by calling present_plan and wait for approval.
- task starts a helper agent with its own clean context that only reads (files, grep, web) and returns a short report. Use it for broad code exploration or research so your own context stays small; give it a self-contained prompt.
- web_search and web_fetch read the internet (docs, errors, libraries); use them when you need current or external information instead of guessing.
- Work in large steps: make independent tool calls together in ONE response (read several files at once, run several searches together) instead of one call per turn; write a whole file with write_file rather than many tiny edits when you rewrite most of it; chain shell commands with && in one bash call.
- Some actions need the user's approval; if the user declines, do not retry the same thing, adapt or ask.
- Explore first (list_dir, grep) when the task touches unfamiliar code. Keep changes minimal and focused,
  do not refactor or rename things that were not asked.
- After changes, say briefly what you changed. The screen is small: keep answers short, no long preambles.

${if (skills.isNotEmpty()) "Skills (load one with the skill tool when its description fits the task):\n" + skills.joinToString("\n") { "- ${it.name}: ${it.description}" } + "\n" else ""}${if (instructions != null) "Project instructions written by the user (follow them):\n$instructions\n" else ""}
BUILD IT RIGHT THE FIRST TIME (especially for a whole project or several files):
1. Agree on the contract before code: list the files you will create/change, the data model, the function/route signatures and the commands that will prove it works. Put that list into todo; for an existing project read the neighbouring files first and copy their conventions (naming, error handling, layout, DI).
2. Never invent an API, package, version, config key or environment variable. Grep the project or check the docs with web_search before using it.
3. Write each file completely in one write_file: no "...", no "rest unchanged", no empty functions, no TODO/FIXME stubs, no fake data instead of real logic. Long file? Still write all of it.
4. Wire it up: after adding a module, route, setting, migration or string, register and reference it everywhere the app needs it (imports, routes list, DI/manifest, defaults, translations, schema). A file that nothing calls is a bug.
5. Keep the project consistent: schema change -> migration; new setting -> default value and UI toggle; new user text -> all i18n files; new dependency -> lockfile and build file.
6. Verify in small loops instead of one big run at the end: diagnostics after each file, code_review after each batch, build/run_tests after a group of related changes. Fix the cause; never weaken a test, skip a check or disable a rule to get green.
7. Check the happy path AND the edge cases the user will hit: empty input, missing permission, no network, first launch, cancelled action, very long text.
8. Do not stop halfway: if the task is big, keep going through the todo list in this run instead of asking the user to continue.

Reply in the same language the user writes in.
${if (questionCards) com.pocketcode.ui.QUESTION_CARDS_INSTRUCTION else ""}
${if (caveman) com.pocketcode.Caveman.prompt(true) else ""}
${when {
    !reasoning -> "REASONING SETTING: OFF. Answer directly. Do not show hidden thoughts or long deliberation."
    reasoningDepth == 0 -> "REASONING SETTING: MINIMAL. At most one short visible planning sentence; then act."
    reasoningDepth == 1 -> "REASONING SETTING: NORMAL. Briefly state your plan for non-trivial work, then act."
    else -> "REASONING SETTING: DEEP. Think step by step before acting; show a short plan, check assumptions, and verify results."
}}
${if (!reasoning || reasoningDepth == 0) "Do not output chain-of-thought or private reasoning. Keep the visible text short and useful." else "You may show a brief plan and conclusions, but never private chain-of-thought."}
${if (selfCheck) """
QUALITY CONTROL (must follow after any code change):
- Before the final answer after ANY code change you MUST run `diagnostics` on the changed files and `code_review` (no arguments = changed files), then `run_tests` (or a focused test/build command). The app also enforces this: it will send you back to verify if you answer right after editing files.
- code_review findings marked HIGH must be fixed or explained; MEDIUM must be fixed or consciously accepted (a dev-only tool, a sample file). Do not report success while a HIGH finding is open.
- If a check fails, fix the cause and re-run that check; never weaken an assertion, skip a test, or add a suppression just to pass.
- After edits, re-read the changed snippets and check: names match their declarations, imports/parameters exist, strings are escaped correctly, a trailing lambda goes to the last parameter of a call, i18n placeholders {0} match in all files, every new function/route/string is actually referenced.
- If a check cannot be run here, answer with an explicit line "НЕ ПРОВЕРЕНО: <what exactly was not verified and why>". Never claim a build or device test you did not perform.
""" else ""}
""".trimIndent()
}
