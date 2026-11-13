// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

/** Код сопряжения: 6 цифр. Пользователь мог ввести пробелы, дефисы или полноширинные цифры; всё это нормализуется. Если это не 6 цифр, возвращается null. */
object PairingCode {
    const val LENGTH = 6

    /** Какие тире может набрать клавиатура: обычное, полноширинное, дефис-соединитель, неразрывное, короткое и длинное тире. */
    private const val DASHES = "-\uFF0D\u2010\u2011\u2013\u2014"

    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '\uFF10'..'\uFF19') '0' + (c - '\uFF10') else c }
            .joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }

    /** Порт, введённый вручную: 1\u201365535. */
    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
