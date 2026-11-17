package com.pocketcode.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Проект, который пользователь хочет запускать на телефоне (бот, сервер и т.п.). */
@Serializable
data class HostApp(
    val id: String,
    val name: String,
    val project: String,
    val command: String,
    /** true — работать фоном (в службе); false — только пока приложение открыто. */
    val background: Boolean = true,
    /** true — запускать только в окне времени startMin..endMin (минуты от полуночи). */
    val useWindow: Boolean = false,
    val startMin: Int = 8 * 60,
    val endMin: Int = 23 * 60,
    val autoRestart: Boolean = true,
    /** Хостинг включён: запускать по расписанию. */
    val enabled: Boolean = false,
)

/** Чистая логика хостинга: расписание, перезапуск, подбор команды. Без Android, поэтому её проверяют unit-тесты. */
object HostLogic {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HostApp.serializer())

    fun encode(list: List<HostApp>): String = json.encodeToString(serializer, list)

    fun decode(text: String?): List<HostApp> {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString(serializer, t) }.getOrDefault(emptyList())
    }

    /** Попадает ли время now в окно start..end. Окно может переходить через полночь; start == end — весь день. */
    fun inWindow(now: Int, start: Int, end: Int): Boolean = when {
        start == end -> true
        start < end -> now >= start && now < end
        else -> now >= start || now < end
    }

    fun nowMinutes(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    /** Должен ли проект работать прямо сейчас. manual — пользователь нажал «Запустить сейчас». */
    fun wanted(app: HostApp, nowMin: Int, appOpen: Boolean, manual: Boolean): Boolean {
        if (!app.background && !appOpen) return false
        if (manual) return true
        if (!app.enabled) return false
        return !app.useWindow || inWindow(nowMin, app.startMin, app.endMin)
    }

    /** Пауза перед перезапуском после падения: 5, 10, 20 … секунд, не больше 5 минут. */
    fun restartDelaySec(attempt: Int): Int {
        if (attempt <= 0) return 0
        return (5 shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(300)
    }

    fun fmtTime(min: Int): String {
        val m = min.coerceIn(0, 24 * 60 - 1)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** «22:30», «7:05», «7.05» → минуты от полуночи; иначе null. */
    fun parseTime(text: String): Int? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        return h * 60 + mi
    }

    fun fmtDuration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h ${(s % 3600) / 60}m"
            else -> "${s / 86400}d ${(s % 86400) / 3600}h"
        }
    }

    /**
     * Подбирает команду запуска по файлам в корне проекта. files — имена файлов и папок в корне,
     * hasNpmStart — есть ли скрипт start в package.json, linux — запуск внутри Alpine (там pip просит флаг).
     */
    fun guessCommand(files: Set<String>, hasNpmStart: Boolean, linux: Boolean): String? {
        val pipFlag = if (linux) " --break-system-packages" else ""
        if ("package.json" in files) {
            if (hasNpmStart) return "npm install && npm start"
            for (m in listOf("bot.js", "index.js", "server.js", "app.js", "main.js")) {
                if (m in files) return "npm install && node $m"
            }
        }
        for (m in listOf("bot.py", "main.py", "app.py", "run.py")) {
            if (m in files) {
                return if ("requirements.txt" in files) "pip install -q$pipFlag -r requirements.txt && python3 $m" else "python3 $m"
            }
        }
        for (m in listOf("bot.js", "index.js", "server.js", "app.js", "main.js")) {
            if (m in files) return "node $m"
        }
        return null
    }

    /** Есть ли скрипт start в тексте package.json. */
    fun hasNpmStart(packageJson: String): Boolean =
        Regex(""""scripts"\s*:\s*\{[^}]*"start"\s*:""").containsMatchIn(packageJson)
}
