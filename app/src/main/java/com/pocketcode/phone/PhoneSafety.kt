package com.pocketcode.phone

/** Режим подтверждений для действий агента на телефоне. */
enum class PhoneConfirm {
    /** Спрашивать перед каждым действием (кроме чтения экрана и ожидания). */
    EVERY,
    /** Спрашивать только перед рискованными действиями: платежи, отправка, удаление и т.п. */
    RISKY;

    companion object {
        fun parse(s: String?): PhoneConfirm = values().firstOrNull { it.name == s } ?: EVERY
    }
}

/** Что делает действие; по нему решается, нужно ли подтверждение. */
object PhoneSafety {
    private val riskyWords = listOf(
        "оплат", "купить", "куплю", "заплат", "платёж", "платеж", "перевест", "перевод", "отправить деньги", "заказать", "оформить заказ",
        "удалить", "удаление", "стереть", "очистить", "сбросить", "форматир", "отключить защиту", "подтвердить", "разрешить",
        "pay", "buy", "purchase", "checkout", "order now", "transfer", "send money", "delete", "remove", "erase", "factory reset",
        "wipe", "confirm", "allow", "grant", "uninstall", "удалить аккаунт", "delete account",
    )
    private val riskySchemes = listOf("tel:", "sms:", "smsto:", "mailto:")
    private val blockedKeys = setOf("power")

    // Поля ввода, куда агент может ввести сообщение/поиск/платёж, — рискованные.
    private val riskyInputKeys = listOf(
        "message", "search", "query", "chat", "comment", "caption", "sms", "amount",
        "pay", "transfer", "email", "phone_number", "сообщени", "поиск", "сумма", "перевод",
    )

    /** Текст элемента похож на платёж, удаление или выдачу разрешения. */
    fun riskyLabel(vararg parts: String): Boolean {
        val s = parts.joinToString(" ").lowercase()
        return riskyWords.any { s.contains(it) }
    }

    fun riskyUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return riskySchemes.any { u.startsWith(it) }
    }

    fun riskyNode(n: UiNode?): Boolean {
        if (n == null) return false
        if (riskyLabel(n.text, n.desc, n.resId)) return true
        if (n.editable) {
            val s = (n.resId + " " + n.text).lowercase()
            if (riskyInputKeys.any { s.contains(it) }) return true
        }
        return false
    }

    /** Кнопки, которые агенту нажимать нельзя. */
    fun keyBlocked(name: String): Boolean = name.trim().lowercase() in blockedKeys

    /** Подтверждать ли действие: [risky] — по тексту элемента или адресу; [mode] — настройка пользователя. */
    fun needsConfirm(mode: PhoneConfirm, readOnly: Boolean, risky: Boolean): Boolean =
        !readOnly && (mode == PhoneConfirm.EVERY || risky)
}
