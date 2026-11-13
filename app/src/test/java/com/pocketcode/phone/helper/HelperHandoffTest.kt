// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperHandoffTest {
    private class MemStore : NonceStore {
        var slots = NonceSlots()
        override fun load() = slots
        override fun save(slots: NonceSlots) { this.slots = slots }
    }

    @Test fun commandPassesNonceViaStdinNotArguments() {
        val c = HelperHandoff.command("/data/app/x/base.apk", "com.pocketcode", 10123, "abc")
        assertTrue(c.startsWith("read n;"))
        assertTrue(c.contains("--nice-name='com.pocketcode:helper'"))
        assertTrue(c.contains(HelperHandoff.HELPER_CLASS))
        assertTrue(c.contains("setsid app_process"))
        assertTrue(c.contains("10123"))
    }

    @Test fun rootCommandPrintsUidFirst() {
        assertTrue(HelperHandoff.rootCommand("/a.apk", "p", 1, "x").startsWith("id -u; read n;"))
    }

    @Test fun quoteEscapesSingleQuotes() {
        assertEquals("'a'\\''b'", HelperHandoff.quote("a'b"))
    }

    @Test fun stdinLineEndsWithNewline() {
        assertEquals("n1\n", String(HelperHandoff.stdinLine("n1")))
    }

    @Test fun certPinSortsAndHashes() {
        assertNull(HelperHandoff.certPin(null))
        assertNull(HelperHandoff.certPin(emptyList()))
        val one = HelperHandoff.certPin(listOf(byteArrayOf(1)))!!
        assertEquals(64, one.length)
        val two = HelperHandoff.certPin(listOf(byteArrayOf(1), byteArrayOf(2)))!!
        assertEquals(two, HelperHandoff.certPin(listOf(byteArrayOf(2), byteArrayOf(1))))
        assertTrue(two.contains(","))
    }

    @Test fun callerClasses() {
        assertEquals(HelperHandoff.CallerClass.Root, HelperHandoff.callerClass(0))
        assertEquals(HelperHandoff.CallerClass.Shell, HelperHandoff.callerClass(2000))
        assertEquals(HelperHandoff.CallerClass.ShellOtherUser, HelperHandoff.callerClass(1_002_000))
        assertEquals(HelperHandoff.CallerClass.OrdinaryApp, HelperHandoff.callerClass(10_100))
        assertEquals(HelperHandoff.CallerClass.Other, HelperHandoff.callerClass(1000))
    }

    @Test fun checkRejectsInOrder() {
        val ok = { uid: Int, ruid: Int, apk: String?, b: Boolean ->
            HelperHandoff.check(uid, 5, ruid, 5, apk, "/a.apk", b)
        }
        assertEquals(HelperHandoff.Reject.NotShell, ok(10_001, 10_001, "/a.apk", true))
        assertEquals(HelperHandoff.Reject.IdMismatch, ok(2000, 1, "/a.apk", true))
        assertEquals(HelperHandoff.Reject.StaleApk, ok(2000, 2000, "/old.apk", true))
        assertEquals(HelperHandoff.Reject.NoBinder, ok(2000, 2000, "/a.apk", false))
        assertNull(ok(2000, 2000, "/a.apk", true))
    }

    @Test fun diagnosticsHideUidExceptClass() {
        val d = HandoffDiagnostics()
        d.reject(HelperHandoff.Reject.NotShell, 10_200)
        assertEquals("NotShell(caller=OrdinaryApp)", d.lastRejection)
        d.reject(HelperHandoff.Reject.BadNonce, 2000)
        assertEquals("BadNonce", d.lastRejection)
        d.clear()
        assertNull(d.lastRejection)
    }

    @Test fun launchNonceIsOneShotAndIssuesResume() {
        var now = 0L
        val store = MemStore()
        val n = HandoffNonces(store, { now }, { "r${store.slots.hashCode()}" })
        val launch = n.issueLaunch()
        assertTrue(n.matches(launch))
        val next = n.accept(launch)
        assertNotNull(next)
        assertFalse(n.matches(launch))
        assertTrue(n.matches(next))
    }

    @Test fun launchNonceExpires() {
        var now = 0L
        val store = MemStore()
        var i = 0
        val n = HandoffNonces(store, { now }, { "n${i++}" })
        val launch = n.issueLaunch()
        now = HandoffNonces.LAUNCH_TTL_MS + 1
        assertFalse(n.matches(launch))
        assertNull(n.accept(launch))
    }

    @Test fun clearInvalidatesAndCountsResets() {
        var now = 0L
        var i = 0
        val n = HandoffNonces(MemStore(), { now }, { "n${i++}" })
        val launch = n.issueLaunch()
        assertEquals(0, n.resets.value)
        n.clear()
        assertEquals(1, n.resets.value)
        assertFalse(n.matches(launch))
        assertFalse(n.matches(null))
    }

    @Test fun randomNonceIsLongHexAndUnique() {
        val a = HandoffNonces.randomNonce()
        val b = HandoffNonces.randomNonce()
        assertEquals(64, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertFalse(a == b)
    }
}
