package com.pocketcode.host

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pocketcode.Secrets
import com.pocketcode.SettingsStore
import com.pocketcode.service.HostService
import com.pocketcode.tools.BgProcesses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Хостинг проектов на телефоне: запускает команды проектов (боты, серверы) по выбору пользователя
 * либо фоном (в службе), либо только пока приложение открыто, и только в заданное время.
 * Упавший процесс перезапускается с растущей паузой.
 */
class HostManager(
    private val app: Application,
    /** Заготовка процесса в той же среде, что и терминал (Linux или Android sh), в папке проекта. */
    private val builder: (String, File) -> ProcessBuilder,
    private val projectDir: (String) -> File,
    /** Открыто ли приложение прямо сейчас. */
    private val appOpen: () -> Boolean,
) {
    private val store = SettingsStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val procs = BgProcesses()
    private val rt = ConcurrentHashMap<String, Rt>()
    private var ticker: Job? = null
    private var serviceOn = false

    var apps by mutableStateOf(HostLogic.decode(store.rawGet("hostApps")))
        private set

    /** Заготовка для окна «Добавить»: терминал кладёт сюда проект и команду. */
    var draft by mutableStateOf<HostApp?>(null)

    private class Rt {
        @Volatile var manual = false
        @Volatile var halted = false
        @Volatile var attempts = 0
        @Volatile var nextAt = 0L
        @Volatile var startedAt = 0L
        @Volatile var error = ""
    }

    init {
        if (apps.any { it.enabled }) ensureStarted()
    }

    fun newId(): String = System.currentTimeMillis().toString()

    private fun persist(list: List<HostApp>) {
        apps = list
        store.rawPut("hostApps", HostLogic.encode(list))
    }

    /** Запускает цикл проверки (раз в 15 секунд), если он ещё не идёт. */
    fun ensureStarted() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(15_000)
            }
        }
    }

    private fun kick() {
        ensureStarted()
        scope.launch { runCatching { tick() } }
    }

    fun save(a: HostApp) {
        val list = apps.toMutableList()
        val i = list.indexOfFirst { it.id == a.id }
        if (i >= 0) list[i] = a else list += a
        persist(list)
        rt.getOrPut(a.id) { Rt() }.halted = false
        kick()
    }

    fun remove(id: String) {
        stopProcess(id)
        persist(apps.filter { it.id != id })
        rt.remove(id)
        kick()
    }

    fun setEnabled(id: String, on: Boolean) {
        persist(apps.map { if (it.id == id) it.copy(enabled = on) else it })
        rt[id]?.halted = false
        kick()
    }

    /** «Запустить сейчас»: работает, пока пользователь не остановит (расписание не учитывается). */
    fun runNow(id: String) {
        val r = rt.getOrPut(id) { Rt() }
        r.manual = true
        r.halted = false
        r.error = ""
        r.attempts = 0
        r.nextAt = 0L
        r.startedAt = 0L
        kick()
    }

    /** Остановить процесс; до смены расписания или нового запуска он сам не поднимется. */
    fun stop(id: String) {
        val r = rt.getOrPut(id) { Rt() }
        r.manual = false
        r.halted = true
        stopProcess(id)
    }

    /** Остановить всё и выключить хостинг (кнопка «Стоп» в уведомлении). */
    fun stopAll() {
        persist(apps.map { it.copy(enabled = false) })
        rt.values.forEach { it.manual = false }
        scope.launch(Dispatchers.IO) { runCatching { procs.stopAll() } }
        kick()
    }

    private fun stopProcess(id: String) {
        scope.launch(Dispatchers.IO) { runCatching { procs.stop("host-$id") } }
    }

    fun isRunning(id: String): Boolean = procs.get("host-$id")?.process?.isAlive == true

    fun log(id: String, max: Int = 4000): String {
        val e = procs.get("host-$id") ?: return ""
        val text = synchronized(e.buffer) { e.buffer.toString() }
        return Secrets.mask(text.takeLast(max))
    }

    /** Короткое описание состояния для экрана. */
    fun statusText(a: HostApp): String {
        val r = rt[a.id]
        val e = procs.get("host-${a.id}")
        val alive = e?.process?.isAlive == true
        val error = r?.error.orEmpty()
        val code = e?.finishedCode
        val now = System.currentTimeMillis()
        return when {
            alive -> "Работает " + HostLogic.fmtDuration(now - (r?.startedAt ?: now))
            error.isNotEmpty() -> "Не удалось запустить: $error"
            r != null && r.halted && code != null -> "Завершился, код $code"
            r != null && r.nextAt > now && code != null -> "Перезапуск через ${((r.nextAt - now) / 1000) + 1} с, код выхода $code"
            !a.background && !appOpen() && (a.enabled || r?.manual == true) -> "Ждёт, пока приложение откроют"
            a.enabled && a.useWindow && !HostLogic.inWindow(HostLogic.nowMinutes(), a.startMin, a.endMin) ->
                "Ждёт окна ${HostLogic.fmtTime(a.startMin)}–${HostLogic.fmtTime(a.endMin)}"
            a.enabled || r?.manual == true -> "Запускается…"
            else -> "Выключен"
        }
    }

    @Synchronized
    private fun tick() {
        val now = HostLogic.nowMinutes()
        val ts = System.currentTimeMillis()
        val open = appOpen()
        for (a in apps) {
            val r = rt.getOrPut(a.id) { Rt() }
            val key = "host-" + a.id
            val e = procs.get(key)
            val alive = e?.process?.isAlive == true
            if (!HostLogic.wanted(a, now, open, r.manual)) {
                r.halted = false
                r.attempts = 0
                r.nextAt = 0L
                r.startedAt = 0L
                r.error = ""
                if (alive) runCatching { procs.stop(key) }
                continue
            }
            if (alive || r.halted) continue
            if (e != null && r.startedAt > 0L) {
                // процесс завершился сам
                if (!a.autoRestart) {
                    r.halted = true
                    continue
                }
                if (r.nextAt == 0L) {
                    if (ts - r.startedAt > 60_000L) r.attempts = 0
                    r.attempts++
                    r.nextAt = ts + HostLogic.restartDelaySec(r.attempts) * 1000L
                }
                if (ts < r.nextAt) continue
            }
            startProcess(a, r, key, ts)
        }
        syncService()
    }

    private fun startProcess(a: HostApp, r: Rt, key: String, ts: Long) {
        try {
            val dir = projectDir(a.project)
            require(dir.isDirectory) { "нет папки проекта «${a.project}»" }
            procs.start(key, a.command, builder(a.command, dir))
            r.startedAt = ts
            r.nextAt = 0L
            r.error = ""
        } catch (x: Throwable) {
            r.error = x.message ?: x.toString()
            r.halted = true
        }
    }

    /** Служба нужна, пока есть проекты для работы в фоне; при их отсутствии уведомление пропадает. */
    private fun syncService() {
        val bg = apps.filter { it.background && (it.enabled || rt[it.id]?.manual == true) }
        if (bg.isNotEmpty()) {
            val running = bg.count { isRunning(it.id) }
            val text = "Работает проектов: $running из ${bg.size}"
            if (!serviceOn) {
                serviceOn = true
                HostService.start(app, text)
            } else {
                HostService.update(app, text)
            }
        } else if (serviceOn) {
            serviceOn = false
            HostService.stop(app)
        }
    }
}
