// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

/** Шаги настройки помощника — по порядку, как в Mika. */
enum class SetupStep(val title: String) {
    DEV("Параметры разработчика"),
    USB("Отладка по USB"),
    A11Y("Доступность"),
    WIRELESS("Беспроводная отладка"),
    PAIR("Код сопряжения"),
    CONNECT("Подключение"),
}

enum class StepMark { DONE, CURRENT, TODO, FAILED, SKIPPED }
enum class SetupPhase { GUIDE, WORKING, SUCCESS, FAILED, WAIT, UNAVAILABLE }

/** Причина неудачи: используется, чтобы подсказать, какой шаг перепроверить. */
enum class ShellFailure {
    PAIRING_WINDOW_CLOSED, CONNECT_FAILED, UNKNOWN, USB_HELPER_STOPPED, ROOT_DENIED, WIRELESS_OFF, NOT_PAIRED, PORT_NOT_FOUND, TIMEOUT, UNSUPPORTED_OS,
}

sealed class ShellStatus {
    object Running : ShellStatus()
    object Connecting : ShellStatus()
    object WirelessOff : ShellStatus()
    object NotPaired : ShellStatus()
    data class Failed(val reason: ShellFailure) : ShellStatus()
    object Stopped : ShellStatus()
}

data class SetupInputs(
    val developerOptions: Boolean?,
    val usbDebugging: Boolean?,
    val accessibility: Boolean,
    val wirelessDebugging: Boolean?,
    val paired: Boolean,
)

data class SetupView(
    val phase: SetupPhase,
    val marks: Map<SetupStep, StepMark>,
    val step: SetupStep?,
    val failure: ShellFailure? = null,
    val revisit: SetupStep? = null,
    val hint: String = "",
)

object PhoneSetupFlow {
    private val REVISITABLE = setOf(SetupStep.DEV, SetupStep.USB, SetupStep.WIRELESS, SetupStep.A11Y)
    private val SWITCH_RELATED = setOf(ShellFailure.PAIRING_WINDOW_CLOSED, ShellFailure.CONNECT_FAILED, ShellFailure.UNKNOWN)

    fun revisit(step: SetupStep?, status: ShellStatus): SetupStep? {
        if (step == null || step !in REVISITABLE || status is ShellStatus.Connecting) return null
        return step
    }

    fun view(inputs: SetupInputs, status: ShellStatus, revisit: SetupStep? = null): SetupView {
        val marks = linkedMapOf<SetupStep, StepMark>()
        fun mark(step: SetupStep, m: StepMark) { marks[step] = m }
        if (status is ShellStatus.Running) {
            SetupStep.entries.forEach { mark(it, StepMark.DONE) }
            return SetupView(SetupPhase.SUCCESS, marks, null)
        }
        val r = revisit(SetupStep.WIRELESS.takeIf { revisit == SetupStep.WIRELESS } ?: revisit, status)
        if (status is ShellStatus.Connecting) {
            SetupStep.entries.forEach { mark(it, StepMark.DONE) }
            mark(SetupStep.CONNECT, StepMark.CURRENT)
            return SetupView(SetupPhase.WORKING, marks, SetupStep.CONNECT)
        }
        if (inputs.developerOptions != true) {
            mark(SetupStep.DEV, StepMark.CURRENT)
            return SetupView(SetupPhase.GUIDE, marks, SetupStep.DEV, hint = "Включи «Параметры разработчика» в настройках телефона.")
        }
        mark(SetupStep.DEV, StepMark.DONE)
        if (inputs.usbDebugging != true) {
            mark(SetupStep.USB, StepMark.CURRENT)
            return SetupView(SetupPhase.GUIDE, marks, SetupStep.USB, hint = "Включи отладку по USB (она нужна даже для Wi-Fi)." )
        }
        mark(SetupStep.USB, StepMark.DONE)
        if (!inputs.accessibility) {
            mark(SetupStep.A11Y, StepMark.CURRENT)
            return SetupView(SetupPhase.GUIDE, marks, SetupStep.A11Y, hint = "Включи службу доступности PocketCode — она читает экран и нажимает элементы.")
        }
        mark(SetupStep.A11Y, StepMark.SKIPPED)
        if (inputs.wirelessDebugging != true) {
            mark(SetupStep.WIRELESS, if (r == SetupStep.WIRELESS) StepMark.FAILED else StepMark.CURRENT)
            return SetupView(
                if (r == SetupStep.WIRELESS) SetupPhase.FAILED else SetupPhase.GUIDE,
                marks, SetupStep.WIRELESS,
                failure = if (r == SetupStep.WIRELESS) ShellFailure.WIRELESS_OFF else null,
                revisit = r,
                hint = "Включи беспроводную отладку в параметрах разработчика, затем вернись сюда.",
            )
        }
        mark(SetupStep.WIRELESS, StepMark.DONE)
        if (!inputs.paired) {
            mark(SetupStep.PAIR, StepMark.CURRENT)
            return SetupView(SetupPhase.GUIDE, marks, SetupStep.PAIR, hint = "Введи код сопряжения из параметров разработчика (окно живёт 30 секунд).")
        }
        mark(SetupStep.PAIR, StepMark.DONE)
        when (status) {
            is ShellStatus.Failed -> {
                mark(SetupStep.CONNECT, StepMark.FAILED)
                val back = if (status.reason in SWITCH_RELATED) SetupStep.WIRELESS else null
                return SetupView(
                    SetupPhase.FAILED, marks, SetupStep.CONNECT, failure = status.reason, revisit = back,
                    hint = when (status.reason) {
                        ShellFailure.PAIRING_WINDOW_CLOSED -> "Код сопряжения устарел. Открой pairing заново и введи свежий код."
                        ShellFailure.CONNECT_FAILED -> "Подключение не удалось. Проверь, что телефон и ПК в одной сети, и повтори."
                        ShellFailure.ROOT_DENIED -> "Менеджер root отказал в доступе. Разреши su для PocketCode."
                        else -> "Попробуй ещё раз; если не поможет — перепроверь беспроводную отладку."
                    },
                )
            }
            ShellStatus.WirelessOff -> return SetupView(SetupPhase.FAILED, marks, SetupStep.WIRELESS, ShellFailure.WIRELESS_OFF, SetupStep.WIRELESS, "Беспроводная отладка выключена.")
            ShellStatus.NotPaired -> return SetupView(SetupPhase.FAILED, marks, SetupStep.PAIR, ShellFailure.NOT_PAIRED, SetupStep.PAIR, "Сопряжение потеряно, введи код заново.")
            ShellStatus.Connecting -> return SetupView(SetupPhase.WORKING, marks, SetupStep.CONNECT)
            ShellStatus.Running -> return SetupView(SetupPhase.SUCCESS, marks, null)
            ShellStatus.Stopped -> {
                mark(SetupStep.CONNECT, StepMark.CURRENT)
                return SetupView(SetupPhase.WAIT, marks, SetupStep.CONNECT, hint = "Готово к запуску. Нажми «Подключить».")
            }
        }
    }
}
