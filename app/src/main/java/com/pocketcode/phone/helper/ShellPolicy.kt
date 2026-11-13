// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

/**
 * Чистая логика решений помощника без обращений к Android API (проверяется обычными тестами на JVM):
 * флаги виртуального экрана, способ внедрения событий, разрешённые клавиши, допустимые ссылки.
 */
internal object ShellPolicy {
    // InputManager.INJECT_INPUT_EVENT_MODE_*
    const val INJECT_MODE_ASYNC = 0
    const val INJECT_MODE_WAIT_FOR_RESULT = 1
    const val INJECT_MODE_WAIT_FOR_FINISH = 2

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (часть — скрытые константы)
    const val FLAG_PUBLIC = 1 shl 0
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val FLAG_OWN_FOCUS = 1 shl 14
    const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0: клавиатура появляется на самом виртуальном экране
    const val IME_POLICY = 0

    private const val SDK_TIRAMISU = 33
    private const val SDK_UPSIDE_DOWN_CAKE = 34

    /**
     * Флаги виртуального экрана. При [trusted] == false убираем флаги доверенного экрана: у shell на некоторых
     * системах нет ADD_TRUSTED_DISPLAY, и система отклоняет их с SecurityException.
     */
    fun displayFlags(sdk: Int, trusted: Boolean): Int {
        var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (sdk >= SDK_TIRAMISU) {
            flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
            if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
        }
        // Со своим фокусом, но без STEAL_TOP_FOCUS_DISABLED, запуск приложения на экране агента отнимал бы фокус у основного экрана
        if (sdk >= SDK_UPSIDE_DOWN_CAKE && trusted) {
            flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    /**
     * Способ внедрения. Клавиши ждут решения системы (WAIT_FOR_RESULT): если на экране нет окна для клавиши, внедрение
     * вернёт сбой и вызывающий уйдёт на shell-команду. Касания не ждём (ASYNC): штрих отправляется по времени.
     */
    fun injectMode(key: Boolean, waitForFinish: Boolean): Int = when {
        waitForFinish -> INJECT_MODE_WAIT_FOR_FINISH
        key -> INJECT_MODE_WAIT_FOR_RESULT
        else -> INJECT_MODE_ASYNC
    }

    /**
     * Пара «нажатие/отпускание»: к запасному [fallback] (shell-команде) идём только если не прошло само нажатие;
     * после доставленного нажатия повторять команду было бы лишним кликом, поэтому только досылаем отпускание.
     */
    inline fun sendPair(down: () -> Boolean, up: () -> Boolean, fallback: () -> Boolean): Boolean {
        if (!down()) return fallback()
        return up() || up()
    }

    /** Только нехватка прав (SecurityException) оправдывает повтор без флагов доверенного экрана. */
    fun retryWithoutTrust(t: Throwable): Boolean = t is SecurityException

    // KeyEvent.KEYCODE_*: навигация и редактирование
    private val ALLOWED_KEYS = setOf(
        4,   // BACK
        66,  // ENTER
        67,  // DEL
        112, // FORWARD_DEL
        61,  // TAB
        62,  // SPACE
        111, // ESCAPE
        84,  // SEARCH
        19, 20, 21, 22, 23, // DPAD_UP/DOWN/LEFT/RIGHT/CENTER
        122, 123, // MOVE_HOME, MOVE_END
        92, 93,   // PAGE_UP, PAGE_DOWN
    )

    /** Разрешённые клавиши: навигация и редактирование. Громкость, питание, Home и подобное не внедряем. */
    fun keyAllowed(keyCode: Int): Boolean = keyCode in ALLOWED_KEYS

    private val VIEW_SCHEMES = setOf("http", "https", "tel", "mailto", "geo", "sms", "smsto")
    private const val ACTION_VIEW = "android.intent.action.VIEW"

    /** Разрешены только безопасные для фонового экрана ссылки и действия (только VIEW и известные схемы). */
    fun safeView(action: String?, uri: String?): Boolean {
        if (uri == null) return false
        if (action != null && action != ACTION_VIEW) return false
        val colon = uri.indexOf(':')
        if (colon <= 0) return false
        return uri.substring(0, colon).lowercase() in VIEW_SCHEMES
    }
}
