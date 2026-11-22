package com.pocketcode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSettingsTest {
    @Test
    fun liveModeScopes() {
        assertEquals(3, LiveModeScope.entries.size)
        assertTrue(LiveMode.systemPrompt(LiveModeScope.READ).contains("Never edit"))
        assertFalse(LiveMode.canStart(LiveModeConfig(enabled = false, modelId = "fast")))
        assertFalse(LiveMode.canStart(LiveModeConfig(enabled = true, modelId = "")))
        assertTrue(LiveMode.canStart(LiveModeConfig(enabled = true, modelId = "fast")))
    }

    @Test
    fun rumbleConfigDefaults() {
        val c = Rumble.Config()
        assertTrue(c.enabled)
        assertTrue(c.inChat)
        assertTrue(c.onAnswer)
        assertEquals(3, Rumble.Kind.entries.size)
    }

    @Test
    fun storageCategories() {
        val scan = com.pocketcode.ui.StorageScanner.scan(java.io.File("/nonexistent-pocketcode-test"))
        assertEquals(7, scan.rows.size)
        assertTrue(scan.rows.any { it.name.contains("Навыки") })
        assertTrue(scan.total >= 0)
    }
}
