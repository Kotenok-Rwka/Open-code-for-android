// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import com.pocketcode.phone.a11y.ScreenText
import kotlinx.coroutines.delay

/**
 * Единая точка управления телефоном для инструментов агента. Сочетает службу доступности ([reader]) и помощника
 * ([input]): что умеет помощник — делает он (точнее и работает на фоновом экране), иначе остаётся служба доступности.
 */
class PhoneController(
    private val reader: PhoneScreenReader,
    private val input: PhoneInput,
    /** Запуск приложения без помощника (обычный Intent). */
    private val localLaunch: suspend (String) -> Boolean = { false },
    private val localApps: suspend () -> List<AppInfo> = { emptyList() },
) {
    /** Экран, которым управляет агент: 0 — основной, иначе фоновый. */
    @Volatile var displayId: Int = 0
        private set

    @Volatile var lastSnapshot: ScreenSnapshot? = null
        private set

    val ready: Boolean get() = reader.available || input.connected

    class Look(val text: String, val jpeg: ByteArray?)

    /** Что агенту доступно сейчас, для начала ответа и подсказки модели. */
    fun capabilities(): String = buildString {
        append(if (reader.available) "служба доступности: да" else "служба доступности: нет")
        append("; помощник: ").append(if (input.connected) "да" else "нет")
    }

    suspend fun look(withImage: Boolean = true, maxSide: Int = 1024): Look {
        val snap = reader.snapshot(displayId)
        lastSnapshot = snap
        val text = if (snap != null) {
            ScreenText.format(snap) + "\nКоординаты — в пикселях экрана ${snap.width}x${snap.height}; скриншот может быть уменьшен."
        } else if (!reader.available) {
            "Служба доступности выключена: дерево экрана недоступно. Попроси пользователя включить её в настройках режима."
        } else "Не удалось прочитать экран."
        val jpeg = if (!withImage) null else if (displayId != 0) input.hiddenScreenshot(maxSide)
        else reader.screenshot(0, maxSide) ?: input.screencap(maxSide)
        return Look(text, jpeg)
    }

    /** Ждёт, пока экран успокоится после действия. */
    suspend fun settle(ms: Long = 700) = delay(ms)

    fun nodeById(id: Int): UiNode? = lastSnapshot?.nodes?.firstOrNull { it.id == id }

    suspend fun tap(x: Int, y: Int): Boolean =
        if (input.connected) input.tap(displayId, x, y) else displayId == 0 && reader.gestureTap(x, y)

    suspend fun tapNode(id: Int): Boolean = reader.clickNode(id)

    suspend fun longPress(x: Int, y: Int): Boolean =
        if (input.connected) input.swipe(displayId, x, y, x, y, 700) else displayId == 0 && reader.gestureSwipe(x, y, x, y, 700)

    suspend fun longPressNode(id: Int): Boolean = reader.longClickNode(id)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val d = durationMs.coerceIn(50, 3000)
        return if (input.connected) input.swipe(displayId, x1, y1, x2, y2, d) else displayId == 0 && reader.gestureSwipe(x1, y1, x2, y2, d)
    }

    suspend fun scrollNode(id: Int, forward: Boolean): Boolean = reader.scrollNode(id, forward)

    /** Результат ввода текста: null — успех, иначе причина отказа. */
    suspend fun typeText(text: String, nodeId: Int?): String? {
        val node = nodeId?.let { nodeById(it) ?: return "Нет элемента с номером $it, сначала вызови phone_look." }
            ?: lastSnapshot?.nodes?.firstOrNull { it.focused && it.editable }
        if (node?.password == true) return "Это поле пароля: текст вводит только пользователь."
        if (nodeId != null) return if (reader.setNodeText(nodeId, text)) null else "Не удалось ввести текст в элемент $nodeId."
        if (input.connected) {
            val ok = if (PhoneKeys.isPlainAscii(text)) input.typeAscii(displayId, text) else input.pasteText(displayId, text)
            if (ok) return null
        }
        if (node != null && reader.setNodeText(node.id, text)) return null
        return "Не удалось ввести текст. Нажми на поле ввода и повтори или укажи его номер."
    }

    /** Нажатие кнопки по имени. Возвращает null при успехе, иначе причину. */
    suspend fun key(name: String): String? {
        if (PhoneSafety.keyBlocked(name)) return "Эту кнопку нажимать нельзя."
        val g = PhoneKeys.global(name)
        val code = PhoneKeys.code(name)
        if (g == null && code == null) return "Неизвестная кнопка «$name». Доступны: ${PhoneKeys.names.joinToString(", ")}."
        if (input.connected && code != null && input.key(displayId, code)) return null
        if (g != null && displayId == 0 && reader.global(g)) return null
        return "Не удалось нажать «$name»."
    }

    /** Находит пакет по имени пакета или названию приложения; null — не нашлось. */
    suspend fun resolveApp(query: String): AppInfo? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val apps = (if (input.connected) input.listApps() else emptyList()).ifEmpty { localApps() }
        return matchApp(apps, q)
    }

    suspend fun launch(query: String): String? {
        val app = resolveApp(query)
        val pkg = app?.pkg ?: query.trim().takeIf { it.contains('.') && !it.contains(' ') } ?: return "Приложение «$query» не найдено."
        val ok = if (input.connected) input.launchApp(displayId, pkg) else displayId == 0 && localLaunch(pkg)
        return if (ok) null else "Не удалось запустить $pkg."
    }

    suspend fun openUrl(url: String): String? =
        if (input.connected) { if (input.openUrl(displayId, url)) null else "Не удалось открыть ссылку." }
        else if (displayId == 0 && localLaunch(url)) null else "Не удалось открыть ссылку."

    suspend fun listApps(): List<AppInfo> =
        (if (input.connected) input.listApps() else emptyList()).ifEmpty { localApps() }

    /** Включает фоновый экран и переключает на него управление. Возвращает номер экрана или -1. */
    suspend fun openBackground(width: Int, height: Int, dpi: Int): Int {
        if (!input.connected) return -1
        val id = input.createHiddenDisplay(width, height, dpi)
        if (id > 0) displayId = id
        return id
    }

    suspend fun closeBackground() {
        displayId = 0
        if (input.connected) input.releaseHiddenDisplay()
    }

    companion object {
        /** Точное совпадение пакета или названия, иначе единственное приложение, чьё название содержит запрос; пользовательские важнее системных. */
        fun matchApp(apps: List<AppInfo>, query: String): AppInfo? {
            val q = query.trim().lowercase()
            apps.firstOrNull { it.pkg.lowercase() == q || it.label.lowercase() == q }?.let { return it }
            val part = apps.filter { it.label.lowercase().contains(q) }
            return part.firstOrNull { !it.system } ?: part.firstOrNull()
        }
    }
}
