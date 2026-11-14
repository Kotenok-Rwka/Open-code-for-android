// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRestoreTest {
    @Test fun gateSkipsWhenDisabledOrNeverStarted() {
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(34, false, BootRestore.MODE_WIRELESS, true))
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(34, true, null, true))
    }

    @Test fun rootModeNeedsNoWritePermission() {
        assertEquals(BootRestore.Gate.Act, BootRestore.gate(34, true, BootRestore.MODE_ROOT, false))
        assertEquals(BootRestore.Gate.Act, BootRestore.gate(26, true, BootRestore.MODE_ROOT, false))
    }

    @Test fun wirelessModeNeedsAndroid11AndWritePermission() {
        assertEquals(BootRestore.Gate.Skip, BootRestore.gate(29, true, BootRestore.MODE_WIRELESS, true))
        assertEquals(BootRestore.Gate.PermissionMissing, BootRestore.gate(34, true, BootRestore.MODE_WIRELESS, false))
        assertEquals(BootRestore.Gate.Act, BootRestore.gate(30, true, BootRestore.MODE_WIRELESS, true))
    }

    @Test fun backoffGrowsAndIsCapped() {
        assertEquals(0L, RecoveryPolicy.backoffMs(0))
        assertEquals(2_000L, RecoveryPolicy.backoffMs(1))
        assertEquals(4_000L, RecoveryPolicy.backoffMs(2))
        assertEquals(16_000L, RecoveryPolicy.backoffMs(4))
        assertEquals(30_000L, RecoveryPolicy.backoffMs(5))
        assertEquals(30_000L, RecoveryPolicy.backoffMs(100))
    }

    private fun inputs(
        paired: Boolean = true, wifi: Boolean? = true, connected: Boolean = false, last: StartOutcome? = null,
    ) = RecoveryInputs(paired, wifi, connected, last)

    @Test fun decisions() {
        assertEquals(RecoveryDecision.Healthy, RecoveryPolicy.decide(inputs(connected = true), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.NotPaired), RecoveryPolicy.decide(inputs(paired = false), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.NotPaired), RecoveryPolicy.decide(inputs(last = StartOutcome.NeedsPairing), 1))
        assertEquals(RecoveryDecision.Stop(StopReason.WirelessDebuggingOff), RecoveryPolicy.decide(inputs(wifi = false), 0))
        assertEquals(RecoveryDecision.Stop(StopReason.NeedsUser), RecoveryPolicy.decide(inputs(last = StartOutcome.RootDenied), 1))
        assertEquals(RecoveryDecision.Stop(StopReason.OutOfAttempts), RecoveryPolicy.decide(inputs(), RecoveryPolicy.MAX_ATTEMPTS))
        assertEquals(RecoveryDecision.Try(0L), RecoveryPolicy.decide(inputs(), 0))
        // переключатель не прочитался: пробуем
        assertEquals(RecoveryDecision.Try(2_000L), RecoveryPolicy.decide(inputs(wifi = null), 1))
    }

    @Test fun recoverStopsWhenConnected() = runBlocking {
        var connected = false
        var tries = 0
        val sleeps = ArrayList<Long>()
        val out = RecoveryPolicy.recover(
            inputs = { inputs(connected = connected) },
            attempt = { tries++; if (tries == 3) connected = true },
            sleep = { sleeps += it },
        )
        assertTrue(out.recovered)
        assertEquals(3, out.attempts)
        assertEquals(listOf(0L, 2_000L, 4_000L), sleeps)
    }

    @Test fun recoverGivesUpAfterMaxAttempts() = runBlocking {
        var tries = 0
        val out = RecoveryPolicy.recover({ inputs() }, { tries++ }, { })
        assertFalse(out.recovered)
        assertEquals(RecoveryPolicy.MAX_ATTEMPTS, tries)
        assertEquals(StopReason.OutOfAttempts, out.stoppedBecause)
    }

    @Test fun recoverStopsWhenTheSwitchIsTurnedBack() = runBlocking {
        var tries = 0
        val out = RecoveryPolicy.recover({ inputs(wifi = tries < 2) }, { tries++ }, { })
        assertFalse(out.recovered)
        assertEquals(StopReason.WirelessDebuggingOff, out.stoppedBecause)
        assertEquals(2, tries)
    }

    @Test fun everyOutcomeHasText() {
        for (o in RestoreOutcome.values()) assertNotNull(o.name, restoreText(o.name))
        assertNull(restoreText(null))
        assertNull(restoreText("что-то другое"))
    }
}
