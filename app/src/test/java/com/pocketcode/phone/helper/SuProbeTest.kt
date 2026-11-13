// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuProbeTest {
    @Test fun findPrefersPathThenKnownDirs() {
        val existing = setOf("/odd/bin/su", "/system/xbin/su")
        assertEquals("/odd/bin/su", SuProbe.find("/usr/bin:/odd/bin/") { it in existing })
        assertEquals("/system/xbin/su", SuProbe.find(null) { it in existing })
        assertNull(SuProbe.find("/usr/bin") { false })
    }

    @Test fun findIgnoresRelativePathEntries() {
        assertNull(SuProbe.find("bin:./x") { it == "bin/su" || it == "./x/su" })
    }

    @Test fun uidReadsIdOutputAndPlainNumber() {
        assertEquals(2000, SuProbe.uid("uid=2000(shell) gid=2000(shell) groups=2000"))
        assertEquals(0, SuProbe.uid("warning: something\nuid=0(root) gid=0(root)"))
        assertEquals(2000, SuProbe.uid("noise\n2000\n"))
        assertNull(SuProbe.uid("Permission denied"))
        assertNull(SuProbe.uid(""))
    }

    @Test fun answerClassifiesOutcomes() {
        assertEquals(SuAnswer.Granted, SuProbe.answer("uid=2000(shell)", false, 2000))
        assertEquals(SuAnswer.WrongUid, SuProbe.answer("uid=0(root)", false, 2000))
        assertEquals(SuAnswer.NoAnswer, SuProbe.answer("", true, 2000))
        assertEquals(SuAnswer.Denied, SuProbe.answer("denied", false, 2000))
        assertEquals(SuAnswer.Denied, SuProbe.answer("", false, 0))
    }

    @Test fun argumentsPutUserBeforeCommand() {
        assertEquals(listOf("/su", "-c", "id"), SuProbe.rootArgs("/su", "id"))
        assertEquals(listOf("/su", "2000", "-c", "id"), SuProbe.shellArgs("/su", "id"))
    }
}
