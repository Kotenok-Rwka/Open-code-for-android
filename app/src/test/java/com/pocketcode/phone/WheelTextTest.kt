// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelTextTest {
    @Test fun reachedMentionsOldAndNewValue() {
        val t = WheelText.result(WheelOutcome(true, "09", "07", 3, "actions"), "9")
        assertEquals("Барабан установлен на «09» (было «07»).", t)
    }

    @Test fun reachedWithoutChangeHasNoOldValue() {
        assertEquals("Барабан установлен на «9».", WheelText.result(WheelOutcome(true, "9", "9", 0, "none"), "9"))
    }

    @Test fun failuresExplainTheReasonAndShowTheCurrentValue() {
        val t = WheelText.result(WheelOutcome(false, "12", "12", 5, "drag", "at_end"), "75")
        assertTrue(t.contains("«75»") && t.contains("Сейчас показано: «12»"))
        assertTrue(WheelText.result(WheelOutcome(false, null, null, 0, "none", "not_a_wheel"), "1").contains("не барабан"))
        assertTrue(WheelText.result(WheelOutcome(false, null, null, 0, "none", "ref_expired"), "1").contains("phone_look"))
        assertTrue(WheelText.result(WheelOutcome(false, null, null, 0, "none", "not_supported"), "1").contains("служба доступности"))
        assertTrue(WheelText.result(WheelOutcome(false, null, null, 0, "drag", "not_moving"), "1").contains("не двигается"))
        assertTrue(WheelText.result(WheelOutcome(false, "3", null, 1, "drag", "weird"), "1").contains("Не получилось"))
    }
}
