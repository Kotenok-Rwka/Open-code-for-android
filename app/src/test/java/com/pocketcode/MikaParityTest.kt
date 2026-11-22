package com.pocketcode.phone

import com.pocketcode.phone.helper.HelperHandoff
import com.pocketcode.phone.helper.RootCommandSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка соответствия перенесённой логики Mika (Apache-2.0). */
class MikaParityTest {
    @Test
    fun setupFlowHasAllSixSteps() {
        assertEquals(6, SetupStep.entries.size)
        assertEquals("Параметры разработчика", SetupStep.DEV.title)
        val view = PhoneSetupFlow.view(
            SetupInputs(false, null, false, null, false),
            ShellStatus.Stopped,
        )
        assertEquals(SetupPhase.GUIDE, view.phase)
        assertEquals(SetupStep.DEV, view.step)
    }

    @Test
    fun failurePointsToRevisit() {
        val view = PhoneSetupFlow.view(
            SetupInputs(true, true, true, true, true),
            ShellStatus.Failed(ShellFailure.PAIRING_WINDOW_CLOSED),
        )
        assertEquals(SetupPhase.FAILED, view.phase)
        assertEquals(SetupStep.WIRELESS, view.revisit)
        assertTrue(view.hint.contains("код"))
    }

    @Test
    fun runningMeansAllDone() {
        val view = PhoneSetupFlow.view(
            SetupInputs(true, true, true, true, true),
            ShellStatus.Running,
        )
        assertEquals(SetupPhase.SUCCESS, view.phase)
        assertTrue(view.marks.values.all { it == StepMark.DONE })
    }

    @Test
    fun rootCommandUsesSupervisorAndSetsid() {
        val cmd = HelperHandoff.rootCommand("/data/app/base.apk", "com.pocketcode", 10123, "pin")
        assertTrue(cmd.contains("pc_supervise="))
        assertTrue(cmd.contains("pc_kill="))
        assertTrue(cmd.contains("setsid"))
        assertTrue(cmd.contains("id -u;"))
        // Пароль (nonce) не должен попадать в командную строку
        assertTrue(cmd.contains("read n;"))
        assertTrue(cmd.contains("/system/bin/sh -c"))
        assertTrue(RootCommandSession.script("/system/bin/sh", listOf(listOf("/system/bin/setsid")), listOf(listOf("kill"))).contains("watch"))
    }
}
