package com.pocketcode

import com.pocketcode.ui.OpenCodePerm
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodePermTest {
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun envFilesAreReadable() {
        for (all in listOf(true, false)) {
            val read = parse(OpenCodePerm.json(all, emptyList()))["read"]!!.jsonObject
            assertEquals("allow", read["*.env"]!!.jsonPrimitive.content)
            assertEquals("allow", read["*.env.*"]!!.jsonPrimitive.content)
            assertEquals("allow", read["*"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun broadRuleComesBeforeExceptions() {
        val keys = parse(OpenCodePerm.json(false, emptyList()))["read"]!!.jsonObject.keys.toList()
        assertEquals(listOf("*", "*.env", "*.env.*"), keys)
    }

    @Test
    fun allowAllKeepsExternalDirectoryOpen() {
        assertEquals(JsonPrimitive("allow"), parse(OpenCodePerm.json(true, emptyList()))["external_directory"])
    }

    @Test
    fun grantedPathsAreListedWithoutQuotes() {
        val ext = parse(OpenCodePerm.json(false, listOf("/sdcard/a\"b", "/tmp")))["external_directory"]!!.jsonObject
        assertEquals(setOf("/tmp", "/tmp/**", "/sdcard/ab"), ext.keys)
        assertFalse(OpenCodePerm.json(false, listOf("it's")).contains("'"))
        assertTrue(ext.values.all { it.jsonPrimitive.content == "allow" })
    }
}
