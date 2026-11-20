package com.pocketcode

import com.pocketcode.ui.OcSessionPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcSessionPickTest {
    @Test
    fun takesFirstSessionFromList() {
        val raw = """[{"id":"ses_abc123","title":"Починить бота","time":{"updated":1}},{"id":"ses_old","title":"Старая"}]"""
        val f = OcSessionPick.latest(raw)!!
        assertEquals("ses_abc123", f.id)
        assertEquals("Починить бота", f.title)
    }

    @Test
    fun ignoresTerminalServiceLines() {
        val raw = "[{\"id\":\"ses_1x\",\"title\":\"\"}]\n[код выхода: 0]"
        val f = OcSessionPick.latest(raw)!!
        assertEquals("ses_1x", f.id)
        assertEquals("", f.title)
    }

    @Test
    fun returnsNullWhenNothingToTake() {
        assertNull(OcSessionPick.latest(""))
        assertNull(OcSessionPick.latest("[]"))
        assertNull(OcSessionPick.latest("opencode: command not found"))
        assertNull(OcSessionPick.latest("[{\"title\":\"без id\"}]"))
    }

    @Test
    fun rejectsIdsThatCouldBreakShellQuoting() {
        assertNull(OcSessionPick.latest("[{\"id\":\"ses_1'; rm -rf /\",\"title\":\"x\"}]"))
        assertNull(OcSessionPick.latest("[{\"id\":\"a b\",\"title\":\"x\"}]"))
    }

    @Test
    fun detectsMissingSession() {
        assertTrue(OcSessionPick.isMissing("NotFoundError: Session not found: ses_abc"))
        assertTrue(OcSessionPick.isMissing("Error: session ses_abc not found"))
        // неверная модель не должна сбрасывать беседу
        assertFalse(OcSessionPick.isMissing("ProviderModelNotFoundError: model x not found"))
        assertFalse(OcSessionPick.isMissing("ECONNRESET"))
    }
}
