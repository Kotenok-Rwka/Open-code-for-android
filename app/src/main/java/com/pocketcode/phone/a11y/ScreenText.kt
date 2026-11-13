// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.a11y

import com.pocketcode.phone.ScreenSnapshot
import com.pocketcode.phone.UiNode

/** Текстовое представление снимка экрана для модели. */
object ScreenText {
    private const val MAX_TEXT = 120

    private fun clip(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length > MAX_TEXT) one.take(MAX_TEXT) + "…" else one
    }

    fun line(n: UiNode): String {
        val flags = ArrayList<String>()
        if (n.clickable) flags += "нажимается"
        if (n.longClickable) flags += "долгое нажатие"
        if (n.scrollable) flags += "прокручивается"
        if (n.editable) flags += if (n.password) "пароль (вводит только пользователь)" else "поле ввода"
        n.checked?.let { flags += if (it) "включено" else "выключено" }
        if (n.focused) flags += "в фокусе"
        if (!n.enabled) flags += "недоступно"
        val sb = StringBuilder()
        sb.append('[').append(n.id).append("] ").append(n.cls)
        if (n.text.isNotBlank()) sb.append(" \"").append(clip(n.text)).append('"')
        if (n.desc.isNotBlank()) sb.append(" (").append(clip(n.desc)).append(')')
        if (n.resId.isNotEmpty()) sb.append(" #").append(n.resId)
        sb.append(" @").append(n.bounds.centerX).append(',').append(n.bounds.centerY)
        if (flags.isNotEmpty()) sb.append(" — ").append(flags.joinToString(", "))
        return sb.toString()
    }

    fun format(s: ScreenSnapshot, maxNodes: Int = 150): String {
        val sb = StringBuilder()
        sb.append("Экран ").append(s.displayId).append(": ").append(s.pkg.ifEmpty { "?" })
        if (s.activity.isNotEmpty()) sb.append('/').append(s.activity)
        sb.append(", ").append(s.width).append('x').append(s.height)
        if (s.keyboardShown) sb.append(", клавиатура открыта")
        sb.append('\n')
        val shown = s.nodes.take(maxNodes)
        for (n in shown) sb.append(line(n)).append('\n')
        if (s.nodes.size > shown.size) sb.append("… ещё элементов: ").append(s.nodes.size - shown.size).append('\n')
        if (s.nodes.isEmpty()) sb.append("(элементов нет — возможно, экран закрыт для чтения; используйте скриншот)\n")
        return sb.toString().trimEnd()
    }
}
