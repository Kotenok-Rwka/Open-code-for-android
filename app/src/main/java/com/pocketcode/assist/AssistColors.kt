package com.pocketcode.assist

/** Цвета окна ассистента (числа в формате ARGB). Считаются без Android, чтобы их можно было проверить тестом. */
class AssistColors(val dark: Boolean, amoled: Boolean, val accent: Int) {
    val scrim: Int = argb(0x73000000)
    val surface: Int = when {
        !dark -> argb(0xFFFFFFFF)
        amoled -> argb(0xFF000000)
        else -> argb(0xFF1B1E26)
    }
    val surfaceHigh: Int = if (dark) argb(if (amoled) 0xFF15171C else 0xFF272B35) else argb(0xFFF0F2F7)
    val text: Int = if (dark) argb(0xFFE7E9EE) else argb(0xFF16181D)
    val textDim: Int = if (dark) argb(0xFFA3A9B6) else argb(0xFF5B6270)
    val outline: Int = if (dark) argb(0x2EFFFFFF) else argb(0x1F000000)
    val danger: Int = if (dark) argb(0xFFFF8A80) else argb(0xFFC62828)
    val onAccent: Int = onColor(accent)
    val accentSoft: Int = withAlpha(accent, 0x2B)

    companion object {
        private fun argb(v: Long): Int = v.toInt()

        /** Тот же цвет с другой прозрачностью (0..255). */
        fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

        /** Читаемый цвет текста поверх [bg]: тёмный на светлом фоне и белый на тёмном. */
        fun onColor(bg: Int): Int {
            val r = (bg shr 16) and 0xFF
            val g = (bg shr 8) and 0xFF
            val b = bg and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            return if (lum > 0.6) argb(0xFF101216) else argb(0xFFFFFFFF)
        }
    }
}
