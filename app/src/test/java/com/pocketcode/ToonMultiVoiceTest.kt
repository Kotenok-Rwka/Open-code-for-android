package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToonMultiVoiceTest {
    @Test
    fun promptHonorsChoiceCountAndExtra() {
        val p2 = ToonPrompt.system(45, ToonFormat.CHOICE, null, null, 1, 2, "")
        val p5 = ToonPrompt.system(45, ToonFormat.CHOICE, null, null, 1, 5, "тон=жёсткий")
        assertTrue(p2.contains("ровно два варианта"))
        assertTrue(p5.contains("5 вариантами"))
        assertTrue(p5.contains("тон=жёсткий"))
    }

    @Test
    fun modesIncludeMulti() {
        assertTrue(ToonVoiceMode.values().any { it.name == "SYSTEM_MULTI" })
        assertTrue(ToonVoiceMode.values().any { it.label.contains("несколько", ignoreCase = true) })
    }
}
