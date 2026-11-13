package com.pocketcode.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneTextTest {
    private val langs = listOf("en", "de", "es", "zh", "ar")

    private fun keys(lang: String): Set<String> =
        (Json.parseToJsonElement(File("src/main/assets/i18n/$lang.json").readText()) as JsonObject).keys

    @Test fun outcomeTextsAreTranslatedEverywhere() {
        val texts = listOf(
            PhoneText.outcome(StartOutcome.Started(HelperMode.ROOT)),
            PhoneText.outcome(StartOutcome.Started(HelperMode.WIRELESS_DEBUGGING)),
            PhoneText.outcome(StartOutcome.RootDenied),
            PhoneText.outcome(StartOutcome.WirelessDebuggingOff),
            PhoneText.outcome(StartOutcome.NeedsPairing),
            PhoneText.pairing(AdbResult.Ok),
        )
        for (l in langs) {
            val k = keys(l)
            for (t in texts) assertTrue("Нет перевода «$t» в $l", t in k)
        }
    }

    @Test fun templatesWithReasonExist() {
        val f = PhoneText.outcome(StartOutcome.Failed("X")).replace("X", "{0}")
        val p = PhoneText.pairing(AdbResult.Failed(AdbFailure.OTHER, "X")).replace("X", "{0}")
        for (l in langs) {
            val k = keys(l)
            assertTrue("Нет шаблона «$f» в $l", f in k)
            assertTrue("Нет шаблона «$p» в $l", p in k)
        }
    }

    @Test fun phoneScreenStringsAreTranslatedEverywhere() {
        val required = listOf(
            "Управление телефоном", "Подтверждения", "Каждое действие", "Только рискованные",
            "Служба доступности: включена", "Служба доступности: выключена", "Помощник не запущен",
            "Открыть настройки доступности", "Запустить помощника (root)", "Запустить помощника (без root)",
            "Сопряжение с отладкой по Wi-Fi", "Включить доступность", "Остановить помощника",
        )
        for (l in langs) {
            val k = keys(l)
            for (r in required) assertTrue("Нет перевода «$r» в $l", r in k)
        }
    }
}
