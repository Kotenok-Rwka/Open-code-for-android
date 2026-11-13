// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {
    private fun has(flags: Int, f: Int) = flags and f != 0

    @Test fun trustedDisplayFlagsOnNewAndroid() {
        val f = ShellPolicy.displayFlags(34, trusted = true)
        assertTrue(has(f, ShellPolicy.FLAG_TRUSTED))
        assertTrue(has(f, ShellPolicy.FLAG_OWN_DISPLAY_GROUP))
        assertTrue(has(f, ShellPolicy.FLAG_OWN_FOCUS))
        assertTrue(has(f, ShellPolicy.FLAG_STEAL_TOP_FOCUS_DISABLED))
        assertTrue(has(f, ShellPolicy.FLAG_SUPPORTS_TOUCH))
    }

    @Test fun untrustedDisplayHasNoTrustedFlags() {
        val f = ShellPolicy.displayFlags(34, trusted = false)
        assertFalse(has(f, ShellPolicy.FLAG_TRUSTED))
        assertFalse(has(f, ShellPolicy.FLAG_OWN_DISPLAY_GROUP))
        assertFalse(has(f, ShellPolicy.FLAG_OWN_FOCUS))
        assertFalse(has(f, ShellPolicy.FLAG_ALWAYS_UNLOCKED))
        assertTrue(has(f, ShellPolicy.FLAG_TOUCH_FEEDBACK_DISABLED))
    }

    @Test fun oldAndroidHasOnlyBasicFlags() {
        val f = ShellPolicy.displayFlags(31, trusted = true)
        assertFalse(has(f, ShellPolicy.FLAG_TRUSTED))
        assertFalse(has(f, ShellPolicy.FLAG_TOUCH_FEEDBACK_DISABLED))
        assertTrue(has(f, ShellPolicy.FLAG_DESTROY_CONTENT_ON_REMOVAL))
    }

    @Test fun androidThirteenTrustedHasGroupButNoFocusFlags() {
        val f = ShellPolicy.displayFlags(33, trusted = true)
        assertTrue(has(f, ShellPolicy.FLAG_OWN_DISPLAY_GROUP))
        assertFalse(has(f, ShellPolicy.FLAG_OWN_FOCUS))
    }

    @Test fun injectModePicksByKindAndWait() {
        assertEquals(ShellPolicy.INJECT_MODE_WAIT_FOR_FINISH, ShellPolicy.injectMode(key = true, waitForFinish = true))
        assertEquals(ShellPolicy.INJECT_MODE_WAIT_FOR_RESULT, ShellPolicy.injectMode(key = true, waitForFinish = false))
        assertEquals(ShellPolicy.INJECT_MODE_ASYNC, ShellPolicy.injectMode(key = false, waitForFinish = false))
    }

    @Test fun sendPairFallsBackOnlyWhenDownFailed() {
        var fallbacks = 0
        assertTrue(ShellPolicy.sendPair({ false }, { error("no up") }) { fallbacks++; true })
        assertEquals(1, fallbacks)
        var ups = 0
        assertTrue(ShellPolicy.sendPair({ true }, { ups++; ups == 2 }) { error("no fallback") })
        assertEquals(2, ups)
        assertFalse(ShellPolicy.sendPair({ true }, { false }) { error("no fallback") })
    }

    @Test fun onlySecurityExceptionRetriesWithoutTrust() {
        assertTrue(ShellPolicy.retryWithoutTrust(SecurityException()))
        assertFalse(ShellPolicy.retryWithoutTrust(IllegalStateException()))
    }

    @Test fun keyWhitelistAllowsNavigationAndBlocksSystemKeys() {
        assertTrue(ShellPolicy.keyAllowed(4))   // BACK
        assertTrue(ShellPolicy.keyAllowed(66))  // ENTER
        assertTrue(ShellPolicy.keyAllowed(67))  // DEL
        assertFalse(ShellPolicy.keyAllowed(3))   // HOME
        assertFalse(ShellPolicy.keyAllowed(26))  // POWER
        assertFalse(ShellPolicy.keyAllowed(24))  // VOLUME_UP
        assertFalse(ShellPolicy.keyAllowed(187)) // APP_SWITCH
    }

    @Test fun safeViewAcceptsKnownSchemesOnly() {
        assertTrue(ShellPolicy.safeView(null, "https://example.com"))
        assertTrue(ShellPolicy.safeView("android.intent.action.VIEW", "HTTP://example.com"))
        assertTrue(ShellPolicy.safeView(null, "tel:+123"))
        assertFalse(ShellPolicy.safeView(null, "intent://x#Intent;end"))
        assertFalse(ShellPolicy.safeView(null, "file:///sdcard/a"))
        assertFalse(ShellPolicy.safeView(null, "javascript:alert(1)"))
        assertFalse(ShellPolicy.safeView("android.intent.action.DELETE", "https://example.com"))
        assertFalse(ShellPolicy.safeView(null, null))
        assertFalse(ShellPolicy.safeView(null, "no-scheme"))
    }

    @Test fun asciiTextSupportRules() {
        assertTrue(AsciiTextKeys.supports("Hello, world 123"))
        assertFalse(AsciiTextKeys.supports(""))
        assertFalse(AsciiTextKeys.supports("привет"))
        assertFalse(AsciiTextKeys.supports("line\nbreak"))
        assertFalse(AsciiTextKeys.supports("x".repeat(257)))
        assertTrue(AsciiTextKeys.supports("x".repeat(256)))
    }
}
