package com.pocketcode

import com.pocketcode.ui.TermGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TermGuideTest {
    @Test
    fun explainsCommandsAndFlags() {
        val e = TermGuide.explain("ls -la")
        assertEquals(1, e.size)
        assertTrue(e[0].contains("скрыт"))
        assertTrue(TermGuide.explain("git status")[0].contains("изменилось"))
    }

    @Test
    fun explainsOperators() {
        val e = TermGuide.explain("cd app && ls | grep kt")
        assertTrue(e.any { it.startsWith("&&") })
        assertTrue(e.any { it.startsWith("|:") })
    }

    @Test
    fun unknownCommandHasNoExplanation() {
        assertTrue(TermGuide.explain("zzzzz").isEmpty())
    }

    @Test
    fun detectsDangerousCommands() {
        assertTrue(TermGuide.isDangerous("rm -rf /"))
        assertTrue(TermGuide.isDangerous("rm -rf ~"))
        assertTrue(TermGuide.isDangerous("mkfs.ext4 /dev/sda"))
        assertTrue(TermGuide.isDangerous("dd if=a of=/dev/sda"))
        assertFalse(TermGuide.isDangerous("rm -rf build"))
        assertFalse(TermGuide.isDangerous("ls -la"))
    }

    @Test
    fun suggestsFromHistoryFirst() {
        val s = TermGuide.suggest("git c", listOf("git commit -m a", "ls"))
        assertEquals("git commit -m a", s[0])
        assertTrue(s.contains("git clone"))
        assertTrue(TermGuide.suggest("", listOf("ls")).isEmpty())
        assertTrue(TermGuide.suggest("pyth", emptyList()).contains("python3"))
    }

    @Test
    fun fixesTypos() {
        assertEquals("ls -la", TermGuide.didYouMean("sl -la"))
        assertEquals("git status", TermGuide.didYouMean("gti status"))
        assertNull(TermGuide.didYouMean("ls"))
        assertNull(TermGuide.didYouMean("./run.sh"))
        assertNull(TermGuide.didYouMean("qwertyuiop"))
    }

    @Test
    fun diagnosesByTextAndByCode() {
        assertTrue(TermGuide.diagnose("write error: No space left on device", 1)!!.contains("место"))
        assertTrue(TermGuide.diagnose("npm error ENOTEMPTY", 217)!!.contains("OpenCode"))
        assertTrue(TermGuide.diagnose("", 127)!!.contains("не найдена"))
        assertTrue(TermGuide.diagnose("", 137)!!.contains("памяти"))
        assertNull(TermGuide.diagnose("whatever", 0))
        assertNull(TermGuide.diagnose("whatever", 3))
        assertNull(TermGuide.diagnose("x", null))
    }

    @Test
    fun givesNextStepOnlyOnSuccess() {
        assertNotNull(TermGuide.nextStep("git init", 0))
        assertNull(TermGuide.nextStep("git init", 1))
        assertNull(TermGuide.nextStep("ls", 0))
    }

    @Test
    fun cheatSheetIsFilled() {
        assertTrue(TermGuide.cheatSheet.size >= 4)
        assertTrue(TermGuide.cheatSheet.all { it.second.isNotEmpty() })
    }
}
