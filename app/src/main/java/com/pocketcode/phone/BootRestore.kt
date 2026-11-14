// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.coroutines.delay

/** Что смотрим, решая, надо ли перезапускать помощника. */
data class RecoveryInputs(
    /** Ключ сопряжения с отладкой по Wi-Fi сохранён. */
    val paired: Boolean,
    /** Системный переключатель беспроводной отладки; null — прочитать не удалось. */
    val wirelessDebugging: Boolean?,
    /** Помощник уже подключён. */
    val connected: Boolean,
    /** Итог последней попытки запуска. */
    val last: StartOutcome?,
)

enum class StopReason { NotPaired, WirelessDebuggingOff, NeedsUser, OutOfAttempts }

sealed interface RecoveryDecision {
    object Healthy : RecoveryDecision
    /** Через [delayMs] попробовать подключиться ещё раз. */
    data class Try(val delayMs: Long) : RecoveryDecision
    data class Stop(val reason: StopReason) : RecoveryDecision
}

data class RecoveryOutcome(val recovered: Boolean, val attempts: Int, val stoppedBecause: StopReason?)

/**
 * Когда и сколько раз перезапускать помощника после перезагрузки. Пока ключ сопряжения есть, переключатель отладки не выключен
 * и сбой не требует действий пользователя, пробуем с нарастающей паузой (сеть Wi-Fi после загрузки поднимается не сразу). Чистая логика.
 */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** Первая попытка сразу, потом через 2, 4, 8, 16 секунд, не больше 30. */
    fun backoffMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (BASE_DELAY_MS shl (attempt - 1).coerceAtMost(20)).coerceAtMost(MAX_DELAY_MS)

    fun decide(i: RecoveryInputs, attempts: Int): RecoveryDecision = when {
        i.connected -> RecoveryDecision.Healthy
        !i.paired || i.last == StartOutcome.NeedsPairing -> RecoveryDecision.Stop(StopReason.NotPaired)
        i.wirelessDebugging == false -> RecoveryDecision.Stop(StopReason.WirelessDebuggingOff)
        i.last == StartOutcome.RootDenied -> RecoveryDecision.Stop(StopReason.NeedsUser)
        attempts >= MAX_ATTEMPTS -> RecoveryDecision.Stop(StopReason.OutOfAttempts)
        else -> RecoveryDecision.Try(backoffMs(attempts))
    }

    /** Повторяет попытки по [decide], пока помощник не подключится или не пора остановиться. [inputs] читается заново на каждом круге. */
    suspend fun recover(
        inputs: suspend () -> RecoveryInputs,
        attempt: suspend () -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): RecoveryOutcome {
        var attempts = 0
        while (true) {
            when (val d = decide(inputs(), attempts)) {
                RecoveryDecision.Healthy -> return RecoveryOutcome(true, attempts, null)
                is RecoveryDecision.Stop -> return RecoveryOutcome(false, attempts, d.reason)
                is RecoveryDecision.Try -> {
                    sleep(d.delayMs)
                    attempt()
                    attempts++
                }
            }
        }
    }
}

/** Нужно ли что-то делать после загрузки. */
object BootRestore {
    /** Беспроводная отладка появилась в Android 11. */
    const val MIN_SDK = 30
    const val MODE_ROOT = "ROOT"
    const val MODE_WIRELESS = "WIRELESS"

    enum class Gate { Act, Skip, PermissionMissing }

    /**
     * [mode] — как помощник запускали в прошлый раз (ROOT/WIRELESS), [holdsWrite] — есть ли у приложения право менять системные настройки.
     * Root-режиму оно не нужно: переключатель отладки не трогаем.
     */
    fun gate(sdkInt: Int, enabled: Boolean, mode: String?, holdsWrite: Boolean): Gate = when {
        !enabled || mode == null -> Gate.Skip
        mode == MODE_ROOT -> Gate.Act
        sdkInt < MIN_SDK -> Gate.Skip
        !holdsWrite -> Gate.PermissionMissing
        else -> Gate.Act
    }
}

/** Итог последнего автозапуска после загрузки: показывается в настройках. */
enum class RestoreOutcome { Restored, PermissionMissing, SettingUnavailable, NotRecovered, RootFailed }

/** Текст итога по-русски (интерфейс переводит его по таблицам). */
fun restoreText(name: String?): String? = when (name) {
    RestoreOutcome.Restored.name -> "Автозапуск: после перезагрузки помощник запущен."
    RestoreOutcome.PermissionMissing.name -> "Автозапуск: нет права менять системные настройки. Выдай его заново."
    RestoreOutcome.SettingUnavailable.name -> "Автозапуск: система не дала включить беспроводную отладку."
    RestoreOutcome.NotRecovered.name -> "Автозапуск: отладку включили, но помощник не подключился (нужна доверенная сеть Wi-Fi)."
    RestoreOutcome.RootFailed.name -> "Автозапуск: через root помощник не запустился."
    else -> null
}
