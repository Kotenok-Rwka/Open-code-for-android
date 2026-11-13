// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeTest {
    @Test
    fun normalizesSpacesDashesAndFullWidthDigits() {
        assertEquals("123456", PairingCode.normalize("123456"))
        assertEquals("123456", PairingCode.normalize(" 123 456 "))
        assertEquals("123456", PairingCode.normalize("123-456"))
        assertEquals("123456", PairingCode.normalize("123–456"))
        assertEquals("123456", PairingCode.normalize("１２３４５６"))
    }

    @Test
    fun rejectsAnythingButSixDigits() {
        assertNull(PairingCode.normalize("12345"))
        assertNull(PairingCode.normalize("1234567"))
        assertNull(PairingCode.normalize("12a456"))
        assertNull(PairingCode.normalize(""))
    }

    @Test
    fun portMustBeInRange() {
        assertEquals(37145, PairingCode.port(" 37145 "))
        assertNull(PairingCode.port("0"))
        assertNull(PairingCode.port("65536"))
        assertNull(PairingCode.port("abc"))
    }
}
