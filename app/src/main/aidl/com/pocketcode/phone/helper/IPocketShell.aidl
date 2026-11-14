// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

/**
 * Интерфейс помощника с правами shell (uid 2000). Помощник живёт в отдельном процессе,
 * держит фоновый экран агента и вводит события. Номера транзакций те же, что у Mika.
 */
interface IPocketShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    int startApp(int displayId, String packageName) = 3;
    int topTaskId(int displayId) = 4;
    /** Перенести корневую задачу на экран displayId (например, вернуть всплывшее на основной экран приложение на фоновый). */
    boolean moveTask(int taskId, int displayId) = 5;
    /** Экран, на котором находится задача; -1, если задачи уже нет. */
    int displayOfTask(int taskId) = 12;
    /** Пакет верхней страницы задачи; null, если задачи нет. */
    String packageOfTask(int taskId) = 13;
    /** Следить за основным экраном windowMs: какие задачи всплывают наверх (см. mainArrivals). Возвращает верхнюю задачу основного экрана, нет — -1. */
    int watchMainScreen(int windowMs) = 31;
    /** Задачи, всплывшие на основном экране после sinceUptimeMs (SystemClock.uptimeMillis), по порядку; поля — в MainScreenWatch.toBundle. Ошибка — null. */
    Bundle mainArrivals(long sinceUptimeMs) = 32;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    int startView(int displayId, String action, String uri, String packageName) = 17;
    int setClipText(String text) = 14;
    boolean restoreClip() = 15;
    int[] identity() = 21;
    void releaseAll() = 22;
    Bundle runSystemCommand(int displayId, String command) = 24;
    String topActivity(int displayId) = 25;
    int injectCtrlKey(int displayId, int keyCode) = 26;
    String listPackages(int userId) = 28;
    boolean isSystemPackage(String packageName) = 29;
    boolean injectStroke(int displayId, in int[] xs, in int[] ys, in long[] times) = 34;
    Bundle runCommand(String command) = 36;
    boolean wakeDevice() = 41;
    boolean dismissDeviceKeyguard() = 42;
    Bundle finishDisplay(int displayId) = 48;
    int injectAsciiText(int displayId, int taskId, String packageName, String text) = 50;
    /** PNG-скриншот основного экрана (вывод screencap) через pipe; читать до конца. null — не получилось. */
    ParcelFileDescriptor openScreencap() = 51;
    void destroy() = 16777114;
}
