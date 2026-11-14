package com.pocketcode.phone

/*
 * Общий контракт режима «Управление телефоном».
 * Идея и часть реализации перенесены из проекта Mika (https://github.com/wangzan101/Mika,
 * Apache-2.0, Copyright 2026 wangzan101) и изменены для PocketCode.
 *
 * Схема: чтение экрана и «умные» действия даёт служба доступности ([PhoneScreenReader]),
 * точный ввод, запуск приложений, фоновый экран и системные команды даёт «помощник»
 * с правами shell ([PhoneInput]). Помощник запускается двумя способами: через беспроводную
 * отладку (без root) или через su (с root). Без помощника остаётся урезанный режим
 * только на службе доступности.
 */

/** Прямоугольник в пикселях экрана. Свой класс, чтобы тесты шли на обычной JVM без android.graphics. */
data class PRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Один элемент экрана из снимка. [id] — номер внутри снимка, начиная с 1; по нему агент ссылается на элемент. */
data class UiNode(
    val id: Int,
    /** Номер родителя; 0 — у корня. */
    val parent: Int,
    /** Короткое имя класса: Button, TextView, EditText… */
    val cls: String,
    val text: String,
    /** contentDescription. */
    val desc: String,
    /** Идентификатор ресурса без пакета, например "send_button"; пусто, если нет. */
    val resId: String,
    val bounds: PRect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    /** null — это не переключатель. */
    val checked: Boolean?,
    val focused: Boolean,
    val enabled: Boolean,
    /** Поле ввода пароля: текст не показываем модели, вводит только пользователь. */
    val password: Boolean = false,
)

/** Снимок одного экрана (основного или фонового). */
data class ScreenSnapshot(
    val displayId: Int,
    /** Пакет приложения на переднем плане этого экрана. */
    val pkg: String,
    /** Короткое имя активности или пусто, если неизвестно. */
    val activity: String,
    val width: Int,
    val height: Int,
    val nodes: List<UiNode>,
    val keyboardShown: Boolean,
)

/** Системные кнопки, которые умеет нажимать служба доступности. */
enum class PhoneGlobal { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS }

/** Результат shell-команды. */
data class ShellResult(val exitCode: Int, val output: String)

/** Установленное приложение для списка в инструменте. */
data class AppInfo(val pkg: String, val label: String, val system: Boolean)

/**
 * Чтение экрана и действия через службу доступности. Реализация — пакет com.pocketcode.phone.a11y.
 * Все функции безопасно вызывать из любого потока; если служба выключена, [available] == false,
 * а функции возвращают null/false и не бросают исключений.
 */
interface PhoneScreenReader {
    /** Что служба доступности видит по экранам (для диагностики, когда дерево пустое). */
    fun describeWindows(): String = ""

    /** Служба доступности включена и подключена. */
    val available: Boolean

    /** Снимок экрана [displayId] (0 — основной). null — не удалось прочитать. После вызова номера элементов действуют до следующего снимка. */
    suspend fun snapshot(displayId: Int): ScreenSnapshot?

    /** Скриншот экрана [displayId] в JPEG, длинная сторона не больше [maxSide]. null — не поддерживается или не вышло. */
    suspend fun screenshot(displayId: Int, maxSide: Int): ByteArray?

    suspend fun clickNode(id: Int): Boolean
    suspend fun longClickNode(id: Int): Boolean
    suspend fun setNodeText(id: Int, text: String): Boolean
    suspend fun scrollNode(id: Int, forward: Boolean): Boolean

    suspend fun global(action: PhoneGlobal): Boolean

    /** Жесты через dispatchGesture; работают только на основном экране. */
    suspend fun gestureTap(x: Int, y: Int, durationMs: Long = 50): Boolean
    suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
}

/**
 * Помощник с правами shell. Реализация — пакет com.pocketcode.phone.helper.
 * Все функции блокирующие внутри, но объявлены suspend и сами переключаются на IO.
 * Если помощник не подключён, [connected] == false, а функции возвращают -1/false/null и не бросают исключений.
 */
interface PhoneInput {
    val connected: Boolean

    suspend fun tap(displayId: Int, x: Int, y: Int): Boolean
    suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean
    suspend fun key(displayId: Int, keyCode: Int): Boolean

    /** Ввод ASCII-текста клавишами. Для остального текста — [pasteText]. */
    suspend fun typeAscii(displayId: Int, text: String): Boolean

    /** Положить текст в буфер обмена и вставить (Ctrl+V); буфер пользователя потом возвращается. */
    suspend fun pasteText(displayId: Int, text: String): Boolean

    /** Запустить приложение на экране [displayId]; true — запущено. */
    suspend fun launchApp(displayId: Int, pkg: String): Boolean
    suspend fun openUrl(displayId: Int, url: String): Boolean
    suspend fun listApps(): List<AppInfo>

    /** Создать фоновый экран; возвращает его displayId или -1. Повторный вызов возвращает уже созданный. */
    suspend fun createHiddenDisplay(width: Int, height: Int, dpi: Int): Int
    suspend fun releaseHiddenDisplay()

    /** Скриншот основного экрана командой screencap (JPEG), если служба доступности недоступна. */
    suspend fun screencap(maxSide: Int): ByteArray?

    /** Скриншот фонового экрана (JPEG) из его поверхности; null — экрана нет. */
    suspend fun hiddenScreenshot(maxSide: Int): ByteArray?

    suspend fun shell(command: String): ShellResult

    /** Включить нашу службу доступности в системных настройках (нужен shell-доступ к Settings.Secure). */
    suspend fun enableAccessibility(): Boolean
}

/** Откуда запущен помощник; нужно только для показа состояния. */
enum class HelperMode { NONE, ROOT, WIRELESS_DEBUGGING }

/** Состояние помощника для показа в настройках. */
data class HelperState(val connected: Boolean, val mode: HelperMode, val message: String)

/** Чем кончилась попытка запустить помощника. */
sealed class StartOutcome {
    data class Started(val mode: HelperMode) : StartOutcome()
    /** Нет su или менеджер root отказал. */
    object RootDenied : StartOutcome()
    /** Беспроводная отладка выключена или не найден порт подключения. */
    object WirelessDebuggingOff : StartOutcome()
    /** Приложение не сопряжено с отладкой по Wi-Fi (или сопряжение отозвано). */
    object NeedsPairing : StartOutcome()
    data class Failed(val reason: String) : StartOutcome()
}

/**
 * Управление помощником. Реализация — com.pocketcode.phone.helper.HelperHub (синглтон, HelperHub.get(context)).
 * Не бросает исключений: ошибки приходят как [StartOutcome.Failed].
 */
interface HelperManager {
    val input: PhoneInput
    val state: kotlinx.coroutines.flow.StateFlow<HelperState>

    /** Запуск через su (с root): без сопряжения и без беспроводной отладки. */
    suspend fun startViaRoot(): StartOutcome

    /** Запуск через беспроводную отладку (без root). Использует [WirelessAdb]. */
    suspend fun startViaWirelessDebugging(): StartOutcome

    /** Остановить помощника и убрать наши изменения (фоновый экран и т.п.). */
    suspend fun stop()
}

/** Причина неудачи при работе с беспроводной отладкой. */
enum class AdbFailure { WIRELESS_OFF, NOT_PAIRED, PORT_NOT_FOUND, REJECTED, TIMEOUT, UNSUPPORTED_OS, OTHER }

sealed class AdbResult {
    object Ok : AdbResult()
    data class Failed(val kind: AdbFailure, val reason: String) : AdbResult()
}

/**
 * Клиент adb внутри приложения: сопряжение по коду и подключение к adbd этого же телефона.
 * Реализация — com.pocketcode.phone.adb.AndroidWirelessAdb(context). Не бросает исключений.
 */
interface WirelessAdb {
    /** Сопряжение по шестизначному коду. [port] — порт из окна «Сопряжение»; null — найти службу сопряжения через NSD. */
    suspend fun pair(code: String, port: Int?): AdbResult

    /**
     * Подключиться к adbd (порт ищется сам), открыть службу exec: с [command], записать в неё [stdinLine],
     * дождаться [awaitHandoff] (true — помощник отдал Binder) и закрыть поток.
     */
    suspend fun startHelper(command: String, stdinLine: ByteArray, awaitHandoff: suspend () -> Boolean): AdbResult

    /** Забыть ключ сопряжения. */
    fun forgetPairing()
}

/**
 * Сопряжение через уведомление: окно «Сопряжение» в системных настройках закрывается, когда пользователь
 * уходит из него, поэтому код вводится в поле ввода уведомления. Реализация — com.pocketcode.phone.adb.NotificationPairingFlow.
 */
interface PairingFlow {
    /** Показать уведомление и открыть настройки разработчика; [onDone] вызывается один раз с итогом. */
    fun begin(onDone: (AdbResult) -> Unit)
    fun cancel()
}
