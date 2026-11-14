// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * Системные переключатели, которые нужны для беспроводной отладки, и Intent-ы для перехода на нужные страницы настроек.
 * У пунктов «Отладка по USB» и «Беспроводная отладка» нет публичной точки входа: открываем «Для разработчиков» и, как это делают оригинальные настройки,
 * подсвечиваем нужную строку; телефоны, не знающие этот параметр, просто останавливаются вверху раздела.
 */
object DeveloperSettings {
    /** Глобальная настройка оригинальной системы; публичной константы нет, читаем по имени. */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    /** Ключ строки «Беспроводная отладка» в оригинальных настройках разработчика (для подсветки). */
    private const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /**
     * Беспроводная отладка включена? null — настройка не читается или никогда не записывалась (тогда не блокируем попытку подключения).
     * Оригинальная система с Android 11 при загрузке всегда записывает эту настройку как 0 или 1.
     */
    fun wirelessDebugging(cr: ContentResolver): Boolean? =
        runCatching { Settings.Global.getString(cr, ADB_WIFI_ENABLED) }.getOrNull()?.let { it == "1" }

    /**
     * Включает беспроводную отладку записью в системные настройки (нужно право WRITE_SECURE_SETTINGS).
     * true — записали и прочитали обратно единицу; отказ системы или откат значения дают false.
     */
    fun turnOnWirelessDebugging(cr: ContentResolver): Boolean =
        runCatching { Settings.Global.putInt(cr, ADB_WIFI_ENABLED, 1) && wirelessDebugging(cr) == true }.getOrDefault(false)

    /** Страница «Для разработчиков» (с запасным вариантом — общие настройки). */
    fun developerOptionsPage(): List<Intent> =
        listOf(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS), Intent(Settings.ACTION_SETTINGS))

    /** Страница беспроводной отладки: «Для разработчиков» с подсвеченной строкой, затем запасные варианты. */
    fun wirelessDebuggingPage(): List<Intent> {
        val highlight = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY) })
        return listOf(highlight) + developerOptionsPage()
    }
}
