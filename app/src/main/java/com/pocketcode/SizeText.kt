package com.pocketcode

import java.util.Locale

/**
 * Размер в удобном виде: «830 КБ», «40.5 МБ», «1.2 ГБ» вместо байтов и «123456 КБ».
 * Один формат на всё приложение: прогресс скачивания, модели, обновления.
 */
fun formatSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b < 1024 -> "$b Б"
        b < 1024L * 1024 -> number(b / 1024.0) + " КБ"
        b < 1024L * 1024 * 1024 -> number(b / (1024.0 * 1024.0)) + " МБ"
        else -> number(b / (1024.0 * 1024.0 * 1024.0)) + " ГБ"
    }
}

/** Один знак после запятой, но без хвоста «,0»: 40 МБ читается лучше, чем 40.0 МБ. */
private fun number(v: Double): String = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
