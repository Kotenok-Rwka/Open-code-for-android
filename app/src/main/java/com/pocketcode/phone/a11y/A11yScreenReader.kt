// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pocketcode.phone.PRect
import com.pocketcode.phone.PhoneGlobal
import com.pocketcode.phone.PhoneScreenReader
import com.pocketcode.phone.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Прокси к текущему экземпляру [PocketAccessibilityService]: нет службы — функции возвращают null/false. */
object A11yScreenReader : PhoneScreenReader {
    private val service: PocketAccessibilityService? get() = PocketAccessibilityService.instance.value

    override val available: Boolean get() = service != null

    /** Узлы последнего снимка по номерам (индекс = номер - 1). */
    private val lock = Any()
    private var lastNodes: List<AccessibilityNodeInfo?> = emptyList()

    override suspend fun snapshot(displayId: Int): ScreenSnapshot? = withContext(Dispatchers.Default) {
        val svc = service ?: return@withContext null
        try {
            val (root, keyboard, size) = findRoot(svc, displayId) ?: return@withContext null
            val tree = NodeFlattener.flatten(toRaw(root, 0))
            synchronized(lock) { lastNodes = tree.sources.map { it as? AccessibilityNodeInfo } }
            val pkg = root.packageName?.toString().orEmpty()
            ScreenSnapshot(
                displayId = displayId, pkg = pkg,
                activity = if (pkg == PocketAccessibilityService.lastActivityPkg) PocketAccessibilityService.lastActivity else "",
                width = size.first, height = size.second, nodes = tree.nodes, keyboardShown = keyboard,
            )
        } catch (_: Exception) { null }
    }

    private data class Found(val root: AccessibilityNodeInfo, val keyboard: Boolean, val size: Pair<Int, Int>)

    private fun findRoot(svc: PocketAccessibilityService, displayId: Int): Found? {
        val windows: List<AccessibilityWindowInfo> =
            if (displayId == Display.DEFAULT_DISPLAY) svc.windows.orEmpty()
            else if (Build.VERSION.SDK_INT >= 30) svc.windowsOnAllDisplays.get(displayId).orEmpty()
            else emptyList()
        val keyboard = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val app = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val chosen = app.firstOrNull { it.isActive } ?: app.firstOrNull { it.isFocused } ?: app.firstOrNull()
        val root = chosen?.root ?: if (displayId == Display.DEFAULT_DISPLAY) svc.rootInActiveWindow else null
        root ?: return null
        val r = Rect()
        root.getBoundsInScreen(r)
        val res = svc.resources.displayMetrics
        val w = if (displayId == Display.DEFAULT_DISPLAY) res.widthPixels else r.right
        val h = if (displayId == Display.DEFAULT_DISPLAY) res.heightPixels else r.bottom
        return Found(root, keyboard, w to h)
    }

    private fun toRaw(n: AccessibilityNodeInfo, depth: Int): RawNode {
        val r = Rect()
        n.getBoundsInScreen(r)
        val kids = if (depth >= 40) emptyList() else (0 until n.childCount).mapNotNull { i ->
            try { n.getChild(i)?.let { toRaw(it, depth + 1) } } catch (_: Exception) { null }
        }
        return RawNode(
            cls = n.className?.toString().orEmpty(),
            text = n.text?.toString().orEmpty(),
            desc = n.contentDescription?.toString().orEmpty(),
            resId = n.viewIdResourceName.orEmpty(),
            bounds = PRect(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable, longClickable = n.isLongClickable, scrollable = n.isScrollable,
            editable = n.isEditable,
            checked = if (n.isCheckable) n.isChecked else null,
            focused = n.isFocused, enabled = n.isEnabled, password = n.isPassword,
            visible = n.isVisibleToUser, children = kids, source = n,
        )
    }

    private fun node(id: Int): AccessibilityNodeInfo? = synchronized(lock) { lastNodes.getOrNull(id - 1) }

    private fun fresh(id: Int): AccessibilityNodeInfo? {
        val n = node(id) ?: return null
        return if (n.refresh()) n else null
    }

    override suspend fun clickNode(id: Int): Boolean = withContext(Dispatchers.Default) {
        val n = fresh(id) ?: return@withContext false
        // если сам элемент не нажимается, ищем ближайшего нажимаемого предка
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
            cur = cur.parent
            depth++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        gestureTap(r.centerX(), r.centerY(), 50)
    }

    override suspend fun longClickNode(id: Int): Boolean = withContext(Dispatchers.Default) {
        val n = fresh(id) ?: return@withContext false
        if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return@withContext true
        val r = Rect()
        n.getBoundsInScreen(r)
        gestureTap(r.centerX(), r.centerY(), 600)
    }

    override suspend fun setNodeText(id: Int, text: String): Boolean = withContext(Dispatchers.Default) {
        val n = fresh(id) ?: return@withContext false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override suspend fun scrollNode(id: Int, forward: Boolean): Boolean = withContext(Dispatchers.Default) {
        val n = fresh(id) ?: return@withContext false
        n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    override suspend fun global(action: PhoneGlobal): Boolean {
        val svc = service ?: return false
        val code = when (action) {
            PhoneGlobal.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            PhoneGlobal.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            PhoneGlobal.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            PhoneGlobal.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            PhoneGlobal.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        }
        return try { svc.performGlobalAction(code) } catch (_: Exception) { false }
    }

    override suspend fun gestureTap(x: Int, y: Int, durationMs: Long): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(p, durationMs.coerceAtLeast(1))
    }

    override suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        return dispatch(p, durationMs.coerceAtLeast(1))
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean {
        val svc = service ?: return false
        return suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }
            val ok = try { svc.dispatchGesture(g, cb, null) } catch (_: Exception) { false }
            if (!ok && cont.isActive) cont.resume(false)
        }
    }

    override suspend fun screenshot(displayId: Int, maxSide: Int): ByteArray? {
        if (displayId != Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT < 30) return null
        val svc = service ?: return null
        var bmp = takeBitmap(svc)
        if (bmp == null) { delay(1100); bmp = takeBitmap(svc) } // система не даёт снимать чаще раза в секунду
        val shot = bmp ?: return null
        return withContext(Dispatchers.Default) { encodeJpeg(shot, maxSide) }
    }

    @SuppressLint("NewApi")
    private suspend fun takeBitmap(svc: PocketAccessibilityService): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val bmp = try {
                        val buf = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(buf, result.colorSpace)
                        val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        buf.close()
                        copy
                    } catch (_: Exception) { null }
                    if (cont.isActive) cont.resume(bmp)
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        } catch (_: Exception) { if (cont.isActive) cont.resume(null) }
    }

    /** Сжимает до JPEG, длинная сторона не больше [maxSide]. */
    internal fun encodeJpeg(src: Bitmap, maxSide: Int): ByteArray? = try {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true) else src
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
    } catch (_: Exception) { null }
}
