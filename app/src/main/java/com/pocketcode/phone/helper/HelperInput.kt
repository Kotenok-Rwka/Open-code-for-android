// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.view.KeyEvent
import com.pocketcode.phone.AppInfo
import com.pocketcode.phone.PhoneInput
import com.pocketcode.phone.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Реализация [PhoneInput] поверх Binder помощника. Все вызовы выполняются на потоке ввода-вывода; если помощник не
 * подключён или Binder умер, возвращаются -1/false/null и исключений наружу нет. Фоновый экран — [AgentDisplay],
 * создаётся здесь, но существует, только пока жив тот Binder, с которым он создан.
 */
internal class HelperInput(
    private val app: Context,
    private val shellOf: () -> IPocketShell?,
) : PhoneInput {
    private val lock = Any()
    private var hidden: AgentDisplay? = null
    private var hiddenShell: IPocketShell? = null

    override val connected: Boolean get() = shellOf() != null

    /** Выполняет [block] с интерфейсом помощника; нет помощника или связь оборвалась — [default]. */
    private suspend fun <T> io(default: T, block: (IPocketShell) -> T): T = withContext(Dispatchers.IO) {
        val s = shellOf() ?: return@withContext default
        try {
            block(s)
        } catch (e: RemoteException) {
            default
        } catch (e: SecurityException) {
            default
        } catch (e: RuntimeException) {
            default
        }
    }

    /** Номер фонового экрана, если он создан и помощник тот же. */
    private fun hiddenId(): Int? = synchronized(lock) {
        hidden?.takeIf { hiddenShell === shellOf() }?.displayId
    }

    override suspend fun tap(displayId: Int, x: Int, y: Int): Boolean = io(false) { it.injectTap(displayId, x, y) }

    override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = io(false) { s ->
        val own = hiddenId() == displayId
        if (own) {
            val plan = StrokePlan.of(x1, y1, x2, y2, durationMs)
            if (s.injectStroke(displayId, plan.xs, plan.ys, plan.times)) return@io true
        }
        val d = StrokePlan.clampDuration(durationMs)
        s.runCommand("input -d $displayId swipe $x1 $y1 $x2 $y2 $d").getInt("exitCode", -1) == 0
    }

    override suspend fun key(displayId: Int, keyCode: Int): Boolean = io(false) { it.injectKey(displayId, keyCode) }

    override suspend fun typeAscii(displayId: Int, text: String): Boolean = io(false) { s ->
        if (!AsciiTextKeys.supports(text)) return@io false
        if (hiddenId() == displayId) {
            val taskId = s.topTaskId(displayId)
            val pkg = s.topActivity(displayId)?.substringBefore('/').orEmpty()
            if (taskId < 0 || pkg.isEmpty()) return@io false
            s.injectAsciiText(displayId, taskId, pkg, text) == AsciiTextKeys.DISPATCHED
        } else {
            // `input text`: пробел кодируется как %s; сам знак % не поддерживаем (вызывающий вставит текст через буфер)
            if (text.contains('%')) return@io false
            val arg = HelperHandoff.quote(text.replace(" ", "%s"))
            s.runCommand("input -d $displayId text $arg").getInt("exitCode", -1) == 0
        }
    }

    override suspend fun pasteText(displayId: Int, text: String): Boolean = io(false) { s ->
        if (s.setClipText(text) != ShellService.CLIP_SET) return@io false
        try {
            val sent = if (hiddenId() == displayId) {
                s.injectCtrlKey(displayId, KeyEvent.KEYCODE_V) == AsciiTextKeys.DISPATCHED
            } else {
                // Ctrl+V как комбинация клавиш (Android 12+)
                s.runCommand("input -d $displayId keycombination ${KeyEvent.KEYCODE_CTRL_LEFT} ${KeyEvent.KEYCODE_V}")
                    .getInt("exitCode", -1) == 0
            }
            // даём приложению время вставить, прежде чем вернуть пользователю его буфер
            if (sent) Thread.sleep(PASTE_SETTLE_MS)
            sent
        } finally {
            s.restoreClip()
        }
    }

    override suspend fun launchApp(displayId: Int, pkg: String): Boolean = io(false) { s ->
        if (!PACKAGE_NAME.matches(pkg)) return@io false
        if (hiddenId() == displayId) s.startApp(displayId, pkg) == ShellService.START_OK
        else s.runCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1").getInt("exitCode", -1) == 0
    }

    override suspend fun openUrl(displayId: Int, url: String): Boolean = io(false) { s ->
        val lower = url.lowercase()
        if (!(lower.startsWith("http://") || lower.startsWith("https://")) || url.length > 2_000) return@io false
        if (hiddenId() == displayId) s.startView(displayId, null, url, null) == ShellService.START_OK
        else s.runCommand("am start -a android.intent.action.VIEW -d ${HelperHandoff.quote(url)}").getInt("exitCode", -1) == 0
    }

    override suspend fun listApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = app.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .map {
                    val system = it.activityInfo.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString(), system)
                }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    override suspend fun createHiddenDisplay(width: Int, height: Int, dpi: Int): Int = withContext(Dispatchers.IO) {
        val s = shellOf() ?: return@withContext -1
        synchronized(lock) {
            hidden?.let { if (hiddenShell === s) return@withContext it.displayId }
            // прежний экран остался от умершего помощника: освобождаем свою сторону
            hidden?.let { runCatching { it.release() } }
            hidden = null
            hiddenShell = null
        }
        try {
            val dm = app.getSystemService(DisplayManager::class.java)
            val display = AgentDisplay(s, width, height, dpi, dm)
            synchronized(lock) {
                hidden = display
                hiddenShell = s
            }
            display.displayId
        } catch (t: Throwable) {
            -1
        }
    }

    override suspend fun releaseHiddenDisplay() {
        withContext(Dispatchers.IO) {
            val d = synchronized(lock) { hidden.also { hidden = null; hiddenShell = null } }
            runCatching { d?.release() }
        }
    }

    override suspend fun screencap(maxSide: Int): ByteArray? = withContext(Dispatchers.IO) {
        val s = shellOf() ?: return@withContext null
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = s.openScreencap() ?: return@withContext null
            val bitmap = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            try {
                scaledJpeg(bitmap, maxSide)
            } finally {
                bitmap.recycle()
            }
        } catch (e: RemoteException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { pfd?.close() }
        }
    }

    override suspend fun hiddenScreenshot(maxSide: Int): ByteArray? = withContext(Dispatchers.IO) {
        val d = synchronized(lock) { hidden?.takeIf { hiddenShell === shellOf() } } ?: return@withContext null
        d.latestFrameJpeg(maxSide)
    }

    override suspend fun shell(command: String): ShellResult = withContext(Dispatchers.IO) {
        val s = shellOf() ?: return@withContext ShellResult(-1, "Помощник не запущен.")
        try {
            val r = s.runCommand(command)
            ShellResult(r.getInt("exitCode", -1), r.getString("output").orEmpty())
        } catch (e: RemoteException) {
            ShellResult(-1, "Связь с помощником потеряна.")
        } catch (e: RuntimeException) {
            ShellResult(-1, "Не удалось выполнить команду.")
        }
    }

    override suspend fun enableAccessibility(): Boolean = io(false) { s ->
        val current = s.runCommand("settings get secure enabled_accessibility_services").getString("output")
        AccessibilityList.enableCommands(current, AccessibilityList.OUR_SERVICE)
            .all { s.runCommand(it).getInt("exitCode", -1) == 0 }
    }

    private companion object {
        const val PASTE_SETTLE_MS = 250L
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        /** Уменьшает [bitmap] так, чтобы длинная сторона была не больше [maxSide], и кодирует в JPEG. */
        fun scaledJpeg(bitmap: Bitmap, maxSide: Int, quality: Int = 70): ByteArray {
            val longSide = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longSide > maxSide && maxSide > 0) {
                val k = maxSide.toFloat() / longSide
                Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * k).toInt()), maxOf(1, (bitmap.height * k).toInt()), true)
            } else bitmap
            return try {
                ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }
}

/** План штриха: точки и время для injectStroke. Чистая логика, проверяется на JVM. */
internal class StrokePlan(val xs: IntArray, val ys: IntArray, val times: LongArray) {
    companion object {
        const val MIN_MS = 50L
        const val MAX_MS = 10_000L
        private const val MAX_STEPS = 60

        fun clampDuration(durationMs: Long): Long = durationMs.coerceIn(MIN_MS, MAX_MS)

        /** Равномерный штрих из ~одной точки на 16 мс; всегда не меньше 2 точек, время начинается с 0 и не убывает. */
        fun of(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): StrokePlan {
            val d = clampDuration(durationMs)
            val steps = (d / 16).toInt().coerceIn(2, MAX_STEPS)
            val n = steps + 1
            val xs = IntArray(n) { x1 + ((x2 - x1).toLong() * it / steps).toInt() }
            val ys = IntArray(n) { y1 + ((y2 - y1).toLong() * it / steps).toInt() }
            val times = LongArray(n) { d * it / steps }
            return StrokePlan(xs, ys, times)
        }
    }
}
