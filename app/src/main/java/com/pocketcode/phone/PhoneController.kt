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
    /** Касания пользователя на основном экране: по ним отличаем приложение, которое открыл сам пользователь, от открытого действием агента. */
    private val userTaps: () -> List<MainScreenSpill.Tap> = { emptyList() },
    /** Пакеты, которые не переносим с основного экрана: сам PocketCode и приложение рабочего стола. */
    private val spillExcluded: () -> Set<String> = { emptySet() },
    /** Рабочие столы: возврат на них перед открытием приложения означает, что открыл пользователь. */
    private val homePackages: () -> Set<String> = { emptySet() },
    /** Время в миллисекундах от запуска системы (SystemClock.uptimeMillis): в той же шкале помощник ставит время записей. */
    private val clock: () -> Long = ::uptimeMs,
    /** Распознавание текста на картинке экрана (JPEG); null вместо функции — не поддерживается, null в ответе — не получилось. */
    private val ocr: (suspend (ByteArray) -> List<OcrLine>?)? = null,
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

    /** Скриншот управляемого экрана в JPEG с длинной стороной не больше [maxSide]. */
    private suspend fun shot(maxSide: Int): ByteArray? = if (displayId != 0) input.hiddenScreenshot(maxSide)
    else reader.screenshot(0, maxSide) ?: input.screencap(maxSide)

    /** Текст, распознанный по картинке экрана; null — не вышло. */
    suspend fun readScreenText(): List<OcrLine>? = try {
        val recognize = ocr
        if (recognize == null) null else shot(OCR_SIDE)?.let { recognize(it) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Ставит барабан выбора, к которому относится элемент [id], на значение [value]. Перетаскивание идёт тем же путём, что и свайпы
     * (помощник — на любом экране, иначе жест службы доступности — только на основном).
     */
    suspend fun setWheel(id: Int, value: String): WheelOutcome {
        val snap = lastSnapshot ?: return WheelOutcome(false, null, null, 0, "none", error = "ref_expired")
        val io = WheelIo(
            displayId = displayId, width = snap.width, height = snap.height,
            drag = { points, slow ->
                val a = points.first()
                val b = points.last()
                swipe(a.first, a.second, b.first, b.second, if (slow) WHEEL_DRAG_MS else WHEEL_SWIPE_MS)
            },
            ocr = { readScreenText() },
        )
        return reader.setWheel(id, value, io) ?: WheelOutcome(false, null, null, 0, "none", error = "not_supported")
    }

    suspend fun look(withImage: Boolean = true, maxSide: Int = 1024, ocrText: Boolean = false): Look {
        val snap = reader.snapshot(displayId)
        lastSnapshot = snap
        var text = if (snap != null) {
            ScreenText.format(snap) + "\nКоординаты — в пикселях экрана ${snap.width}x${snap.height}; скриншот может быть уменьшен."
        } else if (!reader.available) {
            "Служба доступности выключена: дерево экрана недоступно. Попроси пользователя включить её в настройках режима."
        } else "Не удалось прочитать экран $displayId. Видно службе доступности: ${reader.describeWindows()}."
        // экран почти без текста в дереве (игра, самописный интерфейс, барабаны): добавляем текст, распознанный по картинке
        if (snap != null && (ocrText || OcrText.needsOcr(snap.nodes.count { it.text.isNotBlank() || it.desc.isNotBlank() }))) {
            readScreenText()?.let { lines -> OcrText.format(lines, snap.width, snap.height).takeIf { it.isNotEmpty() }?.let { text += "\n" + it } }
        }
        val jpeg = if (!withImage) null else shot(maxSide)
        val diag = if (displayId != 0 && snap != null && snap.nodes.isEmpty()) "\nНа фоновом экране $displayId пока ничего нет: сначала запусти приложение (phone_app launch). Видно службе доступности: ${reader.describeWindows()}." else ""
        return Look(text + diag, jpeg)
    }

    private class SpillWatch(val since: Long, val topBefore: Int)

    @Volatile private var spillWatch: SpillWatch? = null

    /**
     * Вызывается перед действием на фоновом экране: помощник начинает следить за основным экраном, чтобы заметить приложение,
     * которое действие откроет не на фоновом, а на основном экране. На основном экране ничего не делаем.
     */
    suspend fun beginAction() {
        spillWatch = null
        if (displayId == 0 || !input.connected) return
        val since = clock()
        spillWatch = SpillWatch(since, input.watchMainScreen(SPILL_WINDOW_MS.toInt()))
    }

    /**
     * Вызывается после действия: если на основном экране всплыло приложение, открытое действием, переносит его на фоновый экран.
     * Возвращает примечание для модели (что произошло) или null, если ничего не всплыло.
     */
    suspend fun endAction(): String? {
        val w = spillWatch ?: return null
        spillWatch = null
        val target = displayId
        if (target == 0) return null
        val arrivals = input.mainArrivals(w.since)
        if (arrivals.isEmpty()) return null
        val verdicts = MainScreenSpill.decide(arrivals, w.since, SPILL_WINDOW_MS, w.topBefore, spillExcluded(), userTaps(), homePackages())
        val notes = ArrayList<String>()
        for (v in verdicts) {
            val pkg = v.arrival.packageName ?: continue
            when (v) {
                is MainScreenSpill.PullBack ->
                    if (input.moveTaskToHidden(v.arrival.taskId, target)) {
                        notes += "Приложение $pkg открылось на основном экране пользователя и перенесено на фоновый экран. Вызови phone_look ещё раз."
                    } else {
                        notes += "Приложение $pkg открылось на основном экране пользователя, перенести его на фоновый экран не удалось."
                    }
                is MainScreenSpill.InUse ->
                    notes += "Действие открыло $pkg на основном экране: пользователь им сейчас пользуется, я его не трогаю."
            }
        }
        return notes.takeIf { it.isNotEmpty() }?.joinToString("\n")
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

    /** [background] — сначала включить фоновый экран (если ещё не включён) и открыть приложение на нём. */
    suspend fun launch(query: String, background: Boolean = false): String? {
        if (background && displayId == 0 && openBackground(1080, 1920, 420) <= 0) {
            return "Фоновый экран недоступен: нужен помощник (root или беспроводная отладка)."
        }
        val app = resolveApp(query)
        val pkg = app?.pkg ?: query.trim().takeIf { it.contains('.') && !it.contains(' ') } ?: return "Приложение «$query» не найдено."
        // фоновый экран мог быть включён только что, уже после того, как инструмент вызвал beginAction: включаем слежение здесь
        if (spillWatch == null) beginAction()
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
        /** Сколько после начала действия смотрим, не всплыло ли приложение на основном экране. */
        const val SPILL_WINDOW_MS = 6_000L

        /** Длинная сторона картинки для распознавания текста: чем крупнее, тем точнее, на экранах телефонов уменьшения обычно нет. */
        const val OCR_SIDE = 2400

        /** Длительность медленного перетаскивания барабана: к отпусканию палец уже стоит, барабан не улетает. */
        const val WHEEL_DRAG_MS = 1_000L

        /** Быстрый свайп по барабану, когда медленное перетаскивание не сдвигает его. */
        const val WHEEL_SWIPE_MS = 400L

        /** Точное совпадение пакета или названия, иначе единственное приложение, чьё название содержит запрос; пользовательские важнее системных. */
        fun matchApp(apps: List<AppInfo>, query: String): AppInfo? {
            val q = query.trim().lowercase()
            apps.firstOrNull { it.pkg.lowercase() == q || it.label.lowercase() == q }?.let { return it }
            val part = apps.filter { it.label.lowercase().contains(q) }
            return part.firstOrNull { !it.system } ?: part.firstOrNull()
        }
    }
}

internal fun uptimeMs(): Long = try {
    android.os.SystemClock.uptimeMillis()
} catch (_: Throwable) {
    System.nanoTime() / 1_000_000
}
