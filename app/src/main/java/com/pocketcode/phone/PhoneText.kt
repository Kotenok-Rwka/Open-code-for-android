package com.pocketcode.phone

/** Тексты для интерфейса настроек режима «Управление телефоном». Русские: интерфейс переводит их по таблицам в assets/i18n. */
object PhoneText {
    fun outcome(o: StartOutcome): String = when (o) {
        is StartOutcome.Started -> if (o.mode == HelperMode.ROOT) "Помощник запущен через root." else "Помощник запущен через беспроводную отладку."
        StartOutcome.RootDenied -> "Root недоступен: нет su или менеджер root отказал."
        StartOutcome.WirelessDebuggingOff -> "Беспроводная отладка выключена. Включи её в параметрах разработчика (нужна сеть Wi-Fi) и повтори."
        StartOutcome.NeedsPairing -> "Нужно сопряжение с отладкой по Wi-Fi. Нажми «Сопряжение с отладкой по Wi-Fi»."
        is StartOutcome.Failed -> "Не удалось запустить помощника: " + o.reason
    }

    fun pairing(r: AdbResult): String = when (r) {
        AdbResult.Ok -> "Сопряжение выполнено. Теперь можно запускать помощника."
        is AdbResult.Failed -> "Сопряжение не удалось: " + r.reason
    }
}
