package com.pocketcode.assist

/** Чистые функции голосового ввода: язык распознавания, тексты ошибок и выбор службы распознавания. */
object AssistVoice {
    /** Язык распознавания: язык системы, если он совпадает с языком приложения, иначе язык приложения. */
    fun languageTag(appLang: String, systemTag: String): String {
        val sysLang = systemTag.substringBefore('-').lowercase()
        if (sysLang == appLang) return systemTag
        return when (appLang) {
            "ru" -> "ru-RU"
            "en" -> "en-US"
            "de" -> "de-DE"
            "es" -> "es-ES"
            "zh" -> "zh-CN"
            "ar" -> "ar-SA"
            "tr" -> "tr-TR"
            "uk" -> "uk-UA"
            else -> systemTag
        }
    }

    /** Текст по коду ошибки SpeechRecognizer (числа взяты из его констант ERROR_*). */
    fun errorText(code: Int): String = when (code) {
        1, 2 -> "Нет связи: не удалось распознать речь"
        3 -> "Не удалось записать звук"
        4 -> "Служба распознавания речи не ответила"
        5 -> "Распознавание речи сейчас недоступно"
        6 -> "Ничего не слышно. Скажи ещё раз"
        7 -> "Не расслышал. Скажи ещё раз"
        8 -> "Распознавание занято, попробуй через секунду"
        9 -> "Нет доступа к микрофону"
        else -> "Не удалось распознать речь"
    }

    /**
     * Выбирает службу распознавания речи из установленных (имена вида «пакет/класс»). Когда PocketCode назначен ассистентом,
     * система делает его же службой распознавания по умолчанию, поэтому свою службу пропускаем: иначе запрос шёл бы по кругу.
     */
    fun chooseRecognizer(
        candidates: List<String>,
        configured: String?,
        ownPackage: String,
        preferredPackage: String = "com.google.android.googlequicksearchbox",
    ): String? {
        val usable = candidates.filter { it.substringBefore('/') != ownPackage }
        if (configured != null && configured in usable) return configured
        usable.firstOrNull { it.substringBefore('/') == preferredPackage }?.let { return it }
        return usable.firstOrNull()
    }
}
