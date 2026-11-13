package com.pocketcode.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSafetyTest {
    @Test fun riskyLabels() {
        assertTrue(PhoneSafety.riskyLabel("Оплатить заказ"))
        assertTrue(PhoneSafety.riskyLabel("", "Delete account"))
        assertTrue(PhoneSafety.riskyLabel("Allow"))
        assertFalse(PhoneSafety.riskyLabel("Назад", "Поиск"))
    }

    @Test fun urls() {
        assertTrue(PhoneSafety.riskyUrl("tel:112"))
        assertTrue(PhoneSafety.riskyUrl(" SMS:123"))
        assertFalse(PhoneSafety.riskyUrl("https://example.com"))
    }

    @Test fun confirmModes() {
        assertTrue(PhoneSafety.needsConfirm(PhoneConfirm.EVERY, readOnly = false, risky = false))
        assertFalse(PhoneSafety.needsConfirm(PhoneConfirm.EVERY, readOnly = true, risky = true))
        assertFalse(PhoneSafety.needsConfirm(PhoneConfirm.RISKY, readOnly = false, risky = false))
        assertTrue(PhoneSafety.needsConfirm(PhoneConfirm.RISKY, readOnly = false, risky = true))
    }

    @Test fun parseModeDefaultsToEvery() {
        assertEquals(PhoneConfirm.RISKY, PhoneConfirm.parse("RISKY"))
        assertEquals(PhoneConfirm.EVERY, PhoneConfirm.parse(null))
        assertEquals(PhoneConfirm.EVERY, PhoneConfirm.parse("junk"))
    }

    @Test fun keys() {
        assertEquals(4, PhoneKeys.code("BACK"))
        assertNull(PhoneKeys.code("notifications"))
        assertEquals(PhoneGlobal.NOTIFICATIONS, PhoneKeys.global("notifications"))
        assertTrue(PhoneKeys.isPlainAscii("abc 123"))
        assertFalse(PhoneKeys.isPlainAscii("привет"))
        assertFalse(PhoneKeys.isPlainAscii("a\nb"))
        assertTrue(PhoneSafety.keyBlocked(" Power "))
    }
}
