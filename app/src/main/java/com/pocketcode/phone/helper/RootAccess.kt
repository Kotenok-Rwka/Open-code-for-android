// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Ответ su на телефоне с root. [Granted] — согласился и выполнил команду под нужным uid; [Denied] — отказал
 * (или su вышел, не выполнив команду); [NoAnswer] — за отведённое время не ответил (окно запроса никто не нажал или
 * менеджер root завис); [WrongUid] — согласился, но не переключился на нужного пользователя.
 */
enum class SuAnswer { Granted, Denied, NoAnswer, WrongUid }

/** Поиск su и разбор его ответа: смотрим только на возможности (есть ли файл, какой uid получился), а не на то, чей это root. */
object SuProbe {
    /** Привычные места su: каталоги из PATH и места, куда root-решения кладут su. Остальные из PATH тоже проверяются. */
    val DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin", "/odm/bin", "/debug_ramdisk")

    /** Полный путь к первому существующему su или null. [path] — переменная PATH, [exists] проверяет файл (в тестах подменяется). */
    fun find(path: String?, exists: (String) -> Boolean): String? =
        (path.orEmpty().split(':').map { it.trim().trimEnd('/') }.filter { it.startsWith("/") } + DIRS)
            .distinct().map { "$it/su" }.firstOrNull(exists)

    private val UID = Regex("""uid=(\d+)""")

    /** uid из вывода `id` или `id -u`; не нашли — null. Перед ответом su мог напечатать подсказки, поэтому ищем по строкам. */
    fun uid(output: String): Int? =
        UID.find(output)?.groupValues?.get(1)?.toIntOrNull()
            ?: output.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    /**
     * Ответ после запуска `su … -c id`. [output] — вывод su (пусто, если вышел молча); [timedOut] — время вышло;
     * [expectUid] — нужный uid. Другой прочитанный uid — [SuAnswer.WrongUid]; uid не прочитан — отказ.
     */
    fun answer(output: String, timedOut: Boolean, expectUid: Int): SuAnswer {
        val uid = uid(output)
        return when {
            uid == expectUid -> SuAnswer.Granted
            uid != null -> SuAnswer.WrongUid
            timedOut -> SuAnswer.NoAnswer
            else -> SuAnswer.Denied
        }
    }

    /** Аргументы для запуска от root: `su -c <команда>` (понимают все варианты su). */
    fun rootArgs(su: String, command: String): List<String> = listOf(su, "-c", command)

    /**
     * Аргументы для запуска от shell (uid 2000): `su 2000 -c <команда>`. Пользователь пишется перед опциями;
     * su, который не умеет переключаться, выдаст другой uid (см. [answer]).
     */
    fun shellArgs(su: String, command: String): List<String> = listOf(su, HelperHandoff.SHELL_UID.toString(), "-c", command)
}

/**
 * su на этом телефоне: найти, спросить, запустить процесс. Все методы блокирующие (su может показывать окно запроса
 * и ждать пользователя), вызывать только с потока ввода-вывода.
 */
object RootShell {
    /** Полный путь к su; нет — null. */
    fun su(): String? = SuProbe.find(System.getenv("PATH")) { runCatching { File(it).exists() }.getOrDefault(false) }

    /**
     * Спрашивает su один раз: запускает `id` от [uid] (0 — root, 2000 — shell) и ждёт не дольше [timeoutMs]
     * (первый раз появится окно запроса, пользователю нужно время). su не найден — null.
     */
    fun ask(uid: Int, timeoutMs: Long): SuAnswer? {
        val su = su() ?: return null
        val args = if (uid == 0) SuProbe.rootArgs(su, "id") else SuProbe.shellArgs(su, "id")
        val p = runCatching { start(args) }.getOrElse { return SuAnswer.Denied }
        val out = collect(p.inputStream)
        val exited = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!exited) p.destroyForcibly()
        val text = runCatching { out.get(1, TimeUnit.SECONDS) }.getOrDefault("")
        return SuProbe.answer(text, timedOut = !exited, expectUid = uid)
    }

    /** Запускает su-процесс; stderr сливается в stdout (при отказе su объясняет причину там). */
    fun start(args: List<String>): Process = ProcessBuilder(args).redirectErrorStream(true).start()

    /**
     * Читает вывод процесса, пока не встретится строка с uid (возвращает его), поток закончится (null: su вышел,
     * не выполнив команду) или пройдёт [timeoutMs] (бросает java.util.concurrent.TimeoutException; процесс завершает вызывающий).
     */
    fun readUid(p: Process, timeoutMs: Long): Int? {
        val f = onThread {
            val r = p.inputStream.bufferedReader()
            generateSequence { runCatching { r.readLine() }.getOrNull() }.firstNotNullOfOrNull(SuProbe::uid)
        }
        return f.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Блокирующее чтение в отдельном потоке (общий пул не занимаем). */
    fun <T> onThread(block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
        Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, "pocketcode-su").apply { isDaemon = true }.start()
    }

    /** Читает [stream] до конца в другом потоке (не больше [max] байт, остальное отбрасывается). */
    fun collect(stream: InputStream, max: Int = 16 * 1024): CompletableFuture<String> = onThread {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        runCatching {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (buf.size() < max) buf.write(chunk, 0, minOf(n, max - buf.size()))
            }
        }
        buf.toString(Charsets.UTF_8.name())
    }
}
