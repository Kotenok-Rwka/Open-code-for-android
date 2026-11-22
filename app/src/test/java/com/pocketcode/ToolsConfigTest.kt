package com.pocketcode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsConfigTest {
    @Test
    fun catalogHasToolsAndGroups() {
        assertTrue(ToolRegistry.all.size >= 20)
        assertTrue(ToolRegistry.groups().contains("Файлы"))
        assertTrue(ToolRegistry.groups().contains("Сеть"))
        assertTrue(ToolRegistry.all.any { it.id == "bash" })
    }

    @Test
    fun disabledToolsAreHidden() {
        val c = ToolsConfig(disabled = setOf("bash", "ssh"))
        assertFalse(ToolRegistry.isEnabled("bash", c))
        assertFalse(ToolRegistry.isEnabled("ssh", c))
        assertTrue(ToolRegistry.isEnabled("read_file", c))
    }

    @Test
    fun reasoningConfigPersists() {
        val c = ToolsConfig(reasoning = false, reasoningDepth = 3)
        val json = kotlinx.serialization.json.Json.encodeToString(ToolsConfig.serializer(), c)
        val back = kotlinx.serialization.json.Json.decodeFromString<ToolsConfig>(json)
        assertEquals(c, back)
        assertFalse(back.reasoning)
    }
}
