// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val ours = AccessibilityList.OUR_SERVICE

    @Test fun parseHandlesEmptyAndNullLiteral() {
        assertEquals(emptyList<String>(), AccessibilityList.parse(null))
        assertEquals(emptyList<String>(), AccessibilityList.parse(""))
        assertEquals(emptyList<String>(), AccessibilityList.parse("null\n"))
        assertEquals(listOf("a/.B", "c/d.E"), AccessibilityList.parse(" a/.B : c/d.E \n"))
    }

    @Test fun sameTreatsShortAndFullClassNamesAsEqual() {
        assertTrue(AccessibilityList.same("p/.S", "p/p.S"))
        assertFalse(AccessibilityList.same("p/.S", "q/q.S"))
        assertFalse(AccessibilityList.same("p/.S", "p/.T"))
    }

    @Test fun withAddsOnlyWhenMissing() {
        assertEquals(ours, AccessibilityList.with(null, ours))
        assertEquals("x/.Y:$ours", AccessibilityList.with("x/.Y", ours))
        assertEquals("x/.Y:$ours", AccessibilityList.with("x/.Y:$ours", ours))
    }

    @Test fun containsFindsOurService() {
        assertTrue(AccessibilityList.contains("a/.B:$ours", ours))
        assertFalse(AccessibilityList.contains("a/.B", ours))
        assertFalse(AccessibilityList.contains("null", ours))
    }

    @Test fun enableCommandsKeepOtherServicesAndSetFlag() {
        val cmds = AccessibilityList.enableCommands("x/.Y", ours)
        assertEquals(2, cmds.size)
        assertEquals("settings put secure enabled_accessibility_services 'x/.Y:$ours'", cmds[0])
        assertEquals("settings put secure accessibility_enabled 1", cmds[1])
    }

    @Test(expected = IllegalArgumentException::class)
    fun enableCommandsRejectDangerousServiceName() {
        AccessibilityList.enableCommands(null, "a/b; reboot")
    }
}
