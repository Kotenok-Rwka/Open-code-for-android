// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Служба помощника: живёт в процессе с правами shell (uid 2000), держит виртуальный экран агента,
 * запускает на нём приложения и внедряет ввод. Виртуальный экран и приложения на нём уничтожаются вместе с процессом,
 * поэтому служба должна быть постоянной, а не разовой командой.
 *
 * Скрытые API вызываются только рефлексией; если рефлексия не сработала, используется равнозначная shell-команда.
 */
class ShellService(
    /** Принимаем вызовы только от этого uid (приложения PocketCode). */
    private val allowedUid: Int,
) : IPocketShell.Stub() {
    private val context: Context = ShellContext.create()
    private val displays = HashMap<Int, VirtualDisplay>()

    // Нельзя использовать getSystemService: DisplayManager оттуда привязан к системному контексту, имя пакета не сойдётся с uid shell
    @SuppressLint("PrivateApi")
    private val displayManager: DisplayManager =
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)

    // Буфер обмена нужно открывать под именем пакета shell (иначе система покажет плашку «скопировано» на экране пользователя)
    private val clipboard = ShellClipboard(context)
    private val clipLock = Any()
    /** Не даём запуску двух задач пересечься: проверка занятости и сам запуск идут одним куском. */
    private val launchLock = Any()
    /** Буфер пользователя до [setClipText]; [clipSaved] == false — восстанавливать нечего. */
    private var savedClip: ClipData? = null
    private var clipSaved = false
    /** Последний текст, который положили мы; перед возвратом проверяем, что в буфере всё ещё наш текст. */
    private var ourText: String? = null

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    // ---------- Виртуальный экран ----------

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (!ShellPolicy.retryWithoutTrust(t)) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // У shell может не быть ADD_TRUSTED_DISPLAY: пробуем ещё раз без флагов доверенного экрана.
            // Тогда у экрана нет своего фокуса, и фокус основного экрана может задевать.
            Log.w(TAG, "createDisplay without trusted display flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "createDisplay failed", t2)
                -1
            }
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        val flags = ShellPolicy.displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("pocketcode-agent", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        setImePolicy(id)
        if (DisplayAwake.needed(flags, ShellPolicy.FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        return id
    }

    /** Экран в своей группе не должен засыпать, см. [DisplayAwake]. */
    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "pocketcode-display-awake").apply { isDaemon = true } }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    /** IPowerManager и его метод «действие пользователя по экрану»; нет — null (экран работает, но продлевать его некому). */
    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    /** Группа дисплеев, в которой экран (скрытое поле DisplayInfo.displayGroupId); не прочиталась — null. */
    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    /** Запускаем и вводим только на экране, который создала эта служба: ошибочный номер (например, основной экран) ничего не заденет. */
    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        val vd = synchronized(displays) { displays.remove(displayId) }
        vd?.release()
    }

    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            // WindowConfiguration.WINDOWING_MODE_FULLSCREEN = 1 (скрытая константа)
            wm.javaClass.getMethod("setWindowingMode", Int::class.java, Int::class.java).invoke(wm, displayId, 1)
        }.onFailure {
            Log.w(TAG, "setWindowingMode failed, falling back to wm", it)
            if (exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1") != 0) {
                Log.w(TAG, "could not set fullscreen windowing mode on display $displayId")
            }
        }
    }

    /** IWindowManager.setDisplayImePolicy: клавиатура не должна всплывать на основном экране пользователя. */
    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java).invoke(wm, displayId, ShellPolicy.IME_POLICY)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    // ---------- Запуск приложений ----------

    override fun startApp(displayId: Int, packageName: String): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            // Приложение, которым пользуются на основном экране, не трогаем
            if (tasksOn(Display.DEFAULT_DISPLAY).any { packageName in packagesOf(it) }) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            // Не можем разобрать, что на основном экране: лучше не запускать, чем помешать пользователю
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launch(intent, displayId)
    }

    override fun startView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        if (!ShellPolicy.safeView(action, uri)) return START_REFUSED
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        packageName?.let { intent.setPackage(it) }
        // Ссылки как в браузере отдаём только страницам, объявившим, что их можно открывать по ссылке
        if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
        val candidates = runCatching {
            context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
        }.getOrElse { return START_ERROR }
        if (candidates.isEmpty()) return START_UNRESOLVED
        try {
            val onMain = tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet()
            if (candidates.any { it in onMain }) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        launch(intent, displayId)
    }

    /** Запуск [intent] на экране [displayId] через ActivityTaskManager; при сбое рефлексии — `am start`. */
    private fun launch(intent: Intent, displayId: Int): Int {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val ok = try {
            val start = taskManager.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size == 12 }
            // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
            //  requestCode, flags, profilerInfo, options, userId)
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, USER_CURRENT,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
                ?: (listOf("-a", intent.action ?: Intent.ACTION_VIEW) +
                    listOfNotNull(intent.dataString?.let { "-d" }, intent.dataString) +
                    listOfNotNull(intent.`package`?.let { "-p" }, intent.`package`) +
                    (if (intent.categories?.contains(Intent.CATEGORY_BROWSABLE) == true) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
            exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
        }
        return if (ok) START_OK else START_ERROR
    }

    // ---------- Задачи ----------

    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    /** Пакеты задачи: верхняя и базовая активности. */
    private fun packagesOf(task: Any): Set<String> =
        listOf("topActivity", "baseActivity").mapNotNull { (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName }.toSet()

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    /** Задачи на всех экранах: перегрузка с номером экрана принимает INVALID_DISPLAY, то есть без фильтра по экрану. */
    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, MAX_TASKS, false, false)
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun task(taskId: Int): Any? {
        val idField = TASK_INFO.getField("taskId")
        return allTasks().firstOrNull { idField.getInt(it) == taskId }
    }

    override fun displayOfTask(taskId: Int): Int = try {
        task(taskId)?.let { TASK_INFO.getField("displayId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    override fun packageOfTask(taskId: Int): String? = try {
        task(taskId)?.let { t ->
            listOf("topActivity", "baseActivity").firstNotNullOfOrNull { (TASK_INFO.getField(it).get(t) as? ComponentName)?.packageName }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        null
    }

    /**
     * IActivityTaskManager.moveRootTaskToDisplay(taskId, displayId) — вызов за `am display move-stack`.
     * Переносим только на свой фоновый экран: чужие экраны (в том числе основной) помощник не трогает.
     */
    override fun moveTask(taskId: Int, displayId: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        return try {
            taskManager.javaClass.getMethod("moveRootTaskToDisplay", Int::class.java, Int::class.java)
                .invoke(taskManager, taskId, displayId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "moveRootTaskToDisplay unavailable, falling back to am", t)
            exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
        }
    }

    /** Журнал задач основного экрана: агент включает слежение перед действием, см. [MainScreenWatch]. Создаётся при первом обращении (поток, слушатель стека задач). */
    private val mainWatch by lazy { MainScreenWatch({ taskManager }, ::taskRows) }

    override fun watchMainScreen(windowMs: Int): Int = try {
        mainWatch.watch(windowMs)
    } catch (t: Throwable) {
        Log.e(TAG, "watchMainScreen failed", t)
        -1
    }

    override fun mainArrivals(sinceUptimeMs: Long): Bundle? = try {
        mainWatch.arrivals(sinceUptimeMs)
    } catch (t: Throwable) {
        Log.e(TAG, "mainArrivals failed", t)
        null
    }

    /** Все задачи в виде [MainScreenLog.Task]; если тип страницы не читается, считаем её страницей обычного приложения. */
    private fun taskRows(): List<MainScreenLog.Task> {
        val id = TASK_INFO.getField("taskId")
        val display = TASK_INFO.getField("displayId")
        val user = runCatching { TASK_INFO.getField("userId") }.getOrNull()
        val type = runCatching { TASK_INFO.getField("topActivityType") }.getOrNull()
        return allTasks().map { t ->
            val top = TASK_INFO.getField("topActivity").get(t) as? ComponentName
            MainScreenLog.Task(
                taskId = id.getInt(t),
                displayId = display.getInt(t),
                packageName = top?.packageName ?: (TASK_INFO.getField("baseActivity").get(t) as? ComponentName)?.packageName,
                userId = user?.getInt(t) ?: -1,
                topActivity = top?.className,
                standard = type?.getInt(t)?.let { it == ACTIVITY_TYPE_UNDEFINED || it == ACTIVITY_TYPE_STANDARD } ?: true,
            )
        }
    }

    /** Верхняя активность экрана в виде «пакет/класс» (ComponentName.flattenToShortString); не наш экран или пусто — null. */
    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.flattenToShortString()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "topActivity failed", t)
        null
    }

    /**
     * Закрывает приложения на экране [displayId]: `am force-stop` для каждого пакета из его задач.
     * Возвращает Bundle: "closed" — список пакетов через запятую, "failed" — число неудач.
     */
    override fun finishDisplay(displayId: Int): Bundle {
        val result = Bundle()
        if (!ownDisplay(displayId)) return result.apply { putString("closed", ""); putInt("failed", 0) }
        val packages = try {
            synchronized(launchLock) { tasksOn(displayId).flatMap(::packagesOf).distinct() }
        } catch (t: Throwable) {
            Log.e(TAG, "finishDisplay: cannot read tasks", t)
            return result.apply { putString("closed", ""); putInt("failed", 1) }
        }
        var failed = 0
        val closed = ArrayList<String>()
        for (pkg in packages) {
            if (pkg == ShellContext.SHELL_PACKAGE) continue
            if (exec("am", "force-stop", pkg) == 0) closed += pkg else failed++
        }
        return result.apply { putString("closed", closed.joinToString(",")); putInt("failed", failed) }
    }

    // ---------- Ввод ----------

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        // Только разрешённые клавиши: даже при ошибке вызывающего отсюда не уйдут громкость, питание и т.п.
        if (!ShellPolicy.keyAllowed(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return ShellPolicy.sendPair({ inject(down, displayId) }, { inject(up, displayId) }) {
            exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
        }
    }

    /** Ctrl+A / Ctrl+V на своём экране; результат — константы [AsciiTextKeys]. */
    override fun injectCtrlKey(displayId: Int, keyCode: Int): Int {
        if (!ownDisplay(displayId)) return AsciiTextKeys.NOT_SENT
        if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
            Log.w(TAG, "refused ctrl key $keyCode")
            return AsciiTextKeys.NOT_SENT
        }
        val now = SystemClock.uptimeMillis()
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        fun event(code: Int, action: Int, modifiers: Int) = KeyEvent(
            now, SystemClock.uptimeMillis(), action, code, 0, modifiers,
            VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        var modifierDown = false
        try {
            if (!inject(event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId)) return AsciiTextKeys.NOT_SENT
            modifierDown = true
            if (!inject(event(keyCode, KeyEvent.ACTION_DOWN, meta), displayId)) return AsciiTextKeys.NOT_SENT
            val up = event(keyCode, KeyEvent.ACTION_UP, meta)
            return if (inject(up, displayId) || inject(up, displayId)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
        } finally {
            if (modifierDown) {
                val up = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
                if (ownDisplay(displayId) && !inject(up, displayId) && !inject(up, displayId)) {
                    Log.w(TAG, "could not release Ctrl on display $displayId")
                }
            }
        }
    }

    @SuppressLint("DiscouragedPrivateApi")
    override fun injectAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
        if (displayId <= Display.DEFAULT_DISPLAY || !ownDisplay(displayId) || taskId < 0 || !AsciiTextKeys.supports(text)) {
            return AsciiTextKeys.NOT_SENT
        }
        val (events, targetUid) = runCatching {
            val task = tasksOn(displayId).firstOrNull() ?: return AsciiTextKeys.NOT_SENT
            val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
            if (TASK_INFO.getField("taskId").getInt(task) != taskId || top?.packageName != packageName) {
                return AsciiTextKeys.NOT_SENT
            }
            val uid = if (Build.VERSION.SDK_INT >= 33) {
                val user = TASK_INFO.getField("userId").getInt(task)
                val pm = context.packageManager
                (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                    .invoke(pm, packageName, 0, user) as Int).takeIf { it >= 0 } ?: return AsciiTextKeys.NOT_SENT
            } else null
            val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
                ?.takeIf { it.isNotEmpty() } ?: return AsciiTextKeys.NOT_SENT
            keys to uid
        }.getOrElse { return AsciiTextKeys.NOT_SENT }

        for (event in events) {
            if (!ownDisplay(displayId) || (targetUid == null && topTaskId(displayId) != taskId)) {
                return AsciiTextKeys.UNCONFIRMED
            }
            event.source = InputDevice.SOURCE_KEYBOARD
            val sent = if (targetUid != null) injectTo(event, displayId, targetUid)
            else inject(event, displayId, waitForFinish = true)
            // Сбой на середине мог оставить часть текста: другим путём не повторяем
            if (!sent) return AsciiTextKeys.UNCONFIRMED
        }
        return AsciiTextKeys.DISPATCHED
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            ShellPolicy.sendPair({ inject(down, displayId) }, { inject(up, displayId) }) {
                exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /**
     * Одно касание-штрих: нажатие, движение по точкам [times] и отпускание в последней точке; каждое событие
     * отправляется в своё время, поэтому приложение видит нужную скорость. Только на своём экране. Если нажатие
     * не прошло — false (вызывающий пробует другой путь); если сбой позже — всё равно отпускаем палец.
     */
    override fun injectStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
            times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
        ) return false
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
            val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
            return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) return false
        for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
        return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, ShellPolicy.injectMode(event is KeyEvent, waitForFinish)) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    /** Отправка только окну приложения с [targetUid] (перегрузка Android 13+); без неё не внедряем. */
    @SuppressLint("PrivateApi")
    private fun injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val manager = inputManager()
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
            .invoke(manager, event, ShellPolicy.INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
        false
    }

    /** С Android 14 внедрение живёт в InputManagerGlobal, раньше — в InputManager. */
    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    // ---------- Буфер обмена ----------

    override fun setClipText(text: String): Int = synchronized(clipLock) {
        try {
            if (clipSaved && !restoreClip()) return CLIP_UNREADABLE
            if (!clipSaved) {
                val prior = clipboard.primaryClip
                // Пустой результат не всегда значит «пусто»: система могла тихо запретить чтение
                if (prior == null && (clipboard.hasPrimaryClip() || clipboard.primaryClipDescription != null)) {
                    Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                    return CLIP_UNREADABLE
                }
                savedClip = prior
                clipSaved = true
            }
            val clip = ClipData.newPlainText(CLIP_LABEL, text)
            clip.description.extras = (clip.description.extras ?: android.os.PersistableBundle()).apply {
                putBoolean(EXTRA_IS_SENSITIVE, true)
                putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
            }
            clipboard.setPrimaryClip(clip)
            ourText = text
            // Система могла тихо отказать в записи: вставлять чужой буфер нельзя
            if (clipText() == text) CLIP_SET else CLIP_FAILED
        } catch (t: Throwable) {
            Log.w(TAG, "setClipText failed", t)
            CLIP_FAILED
        }
    }

    override fun restoreClip(): Boolean = synchronized(clipLock) {
        if (!clipSaved) return true
        val finished = runCatching {
            val prior = savedClip
            // Пользователь успел скопировать что-то другое — его новое содержимое не трогаем
            if (clipText() == ourText) {
                if (prior != null) clipboard.setPrimaryClip(prior) else clipboard.clearPrimaryClip()
            }
            clipText() != ourText
        }.onFailure { Log.w(TAG, "restoreClip failed", it) }.getOrDefault(false)
        if (finished) {
            savedClip = null
            clipSaved = false
            ourText = null
        }
        finished
    }

    private fun clipText(): String? = runCatching {
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }.getOrNull()

    // ---------- Системные команды и прочее ----------

    override fun listPackages(userId: Int): String? =
        sh("pm", "list", "packages", "--user", "$userId").takeIf { it.first == 0 }?.second

    override fun isSystemPackage(packageName: String): Boolean = try {
        val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
        flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun identity(): IntArray = intArrayOf(Process.myUid(), Process.myPid())

    override fun runSystemCommand(displayId: Int, command: String): Bundle {
        if (!ownDisplay(displayId)) return commandResult(-1, "Фоновый экран не создан.")
        return exec(command, displayId)
    }

    override fun runCommand(command: String): Bundle = exec(command, null)

    /** Включает экран; блокировку не снимает (пароль вводит только пользователь). */
    override fun wakeDevice(): Boolean = exec("input", "keyevent", "KEYCODE_WAKEUP") == 0

    /** Убирает свайп-блокировку, если пароля/PIN нет; с защищённой блокировкой остаётся на экране ввода. */
    override fun dismissDeviceKeyguard(): Boolean = exec("wm", "dismiss-keyguard") == 0

    private fun commandResult(code: Int, output: String) = Bundle().apply { putInt("exitCode", code); putString("output", output) }

    /** Запускает `sh -c` с правами shell, объединяет вывод, ограничивает время и длину; [displayId] попадает в POCKETCODE_DISPLAY_ID. */
    private fun exec(command: String, displayId: Int?): Bundle {
        if (command.isBlank() || command.length > 4_000) return commandResult(-1, "Недопустимая длина команды.")
        val process = try {
            ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true)
                .apply { if (displayId != null) environment()["POCKETCODE_DISPLAY_ID"] = displayId.toString() }.start()
        } catch (t: Throwable) {
            return commandResult(-1, t.message ?: "Команда не запустилась.")
        }
        val bytes = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < COMMAND_OUTPUT_MAX) bytes.write(buffer, 0, minOf(count, COMMAND_OUTPUT_MAX - bytes.size()))
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = process.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)
        val output = bytes.toString(Charsets.UTF_8.name()) + if (bytes.size() >= COMMAND_OUTPUT_MAX) "\n[вывод обрезан]" else ""
        return commandResult(if (finished) process.exitValue() else -1, output)
    }

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /** Выполняет команду, возвращает (код выхода, вывод); по таймауту — -1. Вывод читается в отдельном потоке. */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread { output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")) }
            .apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(READER_JOIN_MS)
        return p.exitValue() to output.get()
    }

    /** Pipe, чей читающий конец ушёл приложению: серверную копию закрываем после записи ответа, иначе fd утекает. */
    private val returnedPipes = ThreadLocal<ParcelFileDescriptor>()

    /**
     * PNG основного экрана: запускаем `screencap -p` и перекладываем его вывод в pipe. Большой PNG не помещается в Bundle,
     * поэтому отдаём читающий конец pipe. Только основной экран; экран агента снимается из его ImageReader.
     */
    override fun openScreencap(): ParcelFileDescriptor? = try {
        val pipe = ParcelFileDescriptor.createPipe()
        val process = ProcessBuilder("/system/bin/screencap", "-p")
            .redirectError(java.io.File("/dev/null")).start()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> process.inputStream.copyTo(out) }
            } catch (_: Throwable) {
                // приложение закрыло pipe или screencap упал: читающая сторона увидит обрыв
            } finally {
                process.destroy()
            }
        }.apply { isDaemon = true; start() }
        returnedPipes.set(pipe[0])
        pipe[0]
    } catch (t: Throwable) {
        Log.w(TAG, "openScreencap failed", t)
        null
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        // Процесс помощника может пережить приложение, а Binder мог попасть к чужому: вызывать методы может только приложение
        if (code in FIRST_CALL_TRANSACTION..LAST_CALL_TRANSACTION && Binder.getCallingUid() != allowedUid) {
            throw SecurityException("caller ${Binder.getCallingUid()} is not allowed")
        }
        try {
            return super.onTransact(code, data, reply, flags)
        } finally {
            returnedPipes.get()?.let { pipe ->
                returnedPipes.remove()
                runCatching { pipe.close() }
            }
        }
    }

    override fun releaseAll() {
        restoreClip()
        awake.stopAll()
        val all = synchronized(displays) { displays.values.toList().also { displays.clear() } }
        all.forEach { it.release() }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    companion object {
        private const val TAG = "PocketCodeShell"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        private const val STROKE_MAX_POINTS = 1_024
        private const val STROKE_MAX_MS = 10_000L
        private const val CMD_TIMEOUT_S = 10L
        private const val COMMAND_OUTPUT_MAX = 64 * 1024
        private const val READER_JOIN_MS = 1_000L

        // Коды результата startApp/startView
        const val START_OK = 0
        const val START_ERROR = 1
        const val START_NOT_FOUND = 2
        const val START_IN_USE_ON_MAIN = 3
        const val START_UNRESOLVED = 4
        const val START_REFUSED = 5

        // Коды результата setClipText
        const val CLIP_SET = 0
        const val CLIP_UNREADABLE = 1
        const val CLIP_FAILED = 2
        private const val CLIP_LABEL = "PocketCode"

        // SystemUI: буфер из shell с этой пометкой не показывает плашку «скопировано» на экране пользователя
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        private const val EXTRA_SUPPRESS_CLIPBOARD_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        // WindowConfiguration.ACTIVITY_TYPE_*: 0 — не определён, 1 — обычное приложение (остальное — рабочий стол, «недавние», ассистент)
        private const val ACTIVITY_TYPE_UNDEFINED = 0
        private const val ACTIVITY_TYPE_STANDARD = 1

        private const val USER_CURRENT = -2 // UserHandle.USER_CURRENT
        private const val VIRTUAL_KEYBOARD = -1 // KeyCharacterMap.VIRTUAL_KEYBOARD
    }
}

/** Результаты синхронного ввода клавишами и допустимый диапазон ASCII; управляющие символы не считаем текстом. */
internal object AsciiTextKeys {
    const val NOT_SENT = 0
    const val DISPATCHED = 1
    const val UNCONFIRMED = 2

    fun supports(text: String): Boolean = text.length in 1..256 && text.all { it in ' '..'~' }
}
