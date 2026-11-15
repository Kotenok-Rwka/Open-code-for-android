package com.pocketcode.assist

/** Подсказки, где в настройках телефона выбрать ассистента: у разных прошивок пункты называются по-разному. */
object AssistHints {
    /** Путь в настройках для производителя телефона ([manufacturer] — Build.MANUFACTURER). */
    fun settingsPath(manufacturer: String): String {
        val m = manufacturer.trim().lowercase()
        return when {
            m.contains("samsung") -> "Настройки → Приложения → Выбор приложений по умолчанию → Цифровой помощник → Приложение цифрового помощника → PocketCode"
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> "Настройки → Приложения → Приложения по умолчанию → Помощник → PocketCode"
            m.contains("huawei") || m.contains("honor") -> "Настройки → Приложения → Приложения по умолчанию → Помощник → PocketCode"
            else -> "Настройки → Приложения → Приложения по умолчанию → Цифровой помощник → Приложение цифрового помощника → PocketCode"
        }
    }
}
