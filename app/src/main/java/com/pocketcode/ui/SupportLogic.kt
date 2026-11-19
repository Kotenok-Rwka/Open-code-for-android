package com.pocketcode.ui

/** Ссылки экрана «Спасибо автору». Открываются только они: адрес проверяется перед запуском. */
object Support {
    const val CLOUDTIPS = "https://pay.cloudtips.ru/p/76bd7cea"
    const val CRYPTO = "https://t.me/send?start=IVbKweFfcV4w"
    const val TRIBUTE_APP = "https://t.me/tribute/app?startapp=dUqC"
    const val TRIBUTE_WEB = "https://web.tribute.tg/d/UqC"

    val ALL = listOf(CLOUDTIPS, CRYPTO, TRIBUTE_APP, TRIBUTE_WEB)

    private val hosts = setOf("pay.cloudtips.ru", "t.me", "web.tribute.tg")

    fun isAllowed(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        return u.scheme == "https" && u.host in hosts
    }
}
