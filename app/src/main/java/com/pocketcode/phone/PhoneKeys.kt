package com.pocketcode.phone

/** Имена кнопок для инструмента phone_key. Числа — KeyEvent.KEYCODE_*; записаны числами, чтобы код проверялся обычными тестами. */
object PhoneKeys {
    private val codes = mapOf(
        "enter" to 66, "delete" to 67, "backspace" to 67, "tab" to 61, "escape" to 111, "search" to 84, "menu" to 82,
        "up" to 19, "down" to 20, "left" to 21, "right" to 22, "volume_up" to 24, "volume_down" to 25,
        "page_up" to 92, "page_down" to 93, "space" to 62,
    )
    private val globals = mapOf(
        "back" to PhoneGlobal.BACK, "home" to PhoneGlobal.HOME, "recents" to PhoneGlobal.RECENTS,
        "notifications" to PhoneGlobal.NOTIFICATIONS, "quick_settings" to PhoneGlobal.QUICK_SETTINGS,
    )
    private val globalCodes = mapOf(PhoneGlobal.BACK to 4, PhoneGlobal.HOME to 3, PhoneGlobal.RECENTS to 187)

    val names: List<String> get() = (globals.keys + codes.keys).toList()

    fun global(name: String): PhoneGlobal? = globals[name.trim().lowercase()]

    /** Код клавиши для помощника; null — такой кнопки нет. Системные кнопки тоже имеют коды (кроме шторки). */
    fun code(name: String): Int? {
        val n = name.trim().lowercase()
        return codes[n] ?: globals[n]?.let { globalCodes[it] }
    }

    /** Только ASCII без управляющих символов (кроме перевода строки): такой текст можно вводить клавишами. */
    fun isPlainAscii(text: String): Boolean = text.all { it.code in 32..126 }
}
