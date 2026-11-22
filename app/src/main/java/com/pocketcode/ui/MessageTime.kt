package com.pocketcode.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Короткое время сообщения: HH:mm (или пусто для старых записей без таймштампа). */
object MessageTime {
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun format(ts: Long): String = if (ts <= 0L) "" else runCatching { fmt.format(Date(ts)) }.getOrDefault("")
}
