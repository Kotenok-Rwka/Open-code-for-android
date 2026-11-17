package com.pocketcode.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallExtrasTest {
    private fun sig(v: String) = buildJsonObject { putJsonObject("extra_content") { putJsonObject("google") { put("thought_signature", v) } } }
    private fun call(id: String, extra: JsonObject? = null) = Block.ToolUse(id, "phone_tap", JsonObject(emptyMap()), extra)
    private fun signatureOf(e: JsonObject?) =
        e?.get("extra_content")?.jsonObject?.get("google")?.jsonObject?.get("thought_signature")?.jsonPrimitive?.content

    @Test fun callExtraKeepsOnlyUnknownFields() {
        val c = buildJsonObject {
            put("index", 0); put("id", "a"); put("type", "function")
            putJsonObject("function") { put("name", "x") }
            putJsonObject("extra_content") { putJsonObject("google") { put("thought_signature", "S") } }
        }
        assertEquals(setOf("extra_content"), ToolCallExtras.callExtra(c)!!.keys)
        assertNull(ToolCallExtras.callExtra(buildJsonObject { put("id", "a") }))
    }

    @Test fun functionExtraKeepsOnlyUnknownFields() {
        val f = buildJsonObject { put("name", "x"); put("arguments", "{}"); put("foo", 1) }
        assertEquals(setOf("foo"), ToolCallExtras.functionExtra(f)!!.keys)
        assertNull(ToolCallExtras.functionExtra(buildJsonObject { put("name", "x") }))
        assertNull(ToolCallExtras.functionExtra(null))
    }

    @Test fun mergeReplacesAndKeeps() {
        val a = buildJsonObject { put("a", 1) }
        val b = buildJsonObject { put("b", 2) }
        assertEquals(a, ToolCallExtras.merge(a, null))
        assertEquals(b, ToolCallExtras.merge(null, b))
        assertEquals(setOf("a", "b"), ToolCallExtras.merge(a, b)!!.keys)
    }

    @Test fun nonGeminiUnchanged() {
        val calls = listOf(call("1"), call("2"))
        assertEquals(listOf<JsonObject?>(null, null), ToolCallExtras.forSending(calls, "gpt-4o"))
    }

    @Test fun geminiWithoutSignatureGetsDummyOnFirstCallOnly() {
        val r = ToolCallExtras.forSending(listOf(call("1"), call("2")), "google/Gemini-3-pro")
        assertEquals(ToolCallExtras.DUMMY_SIGNATURE, signatureOf(r[0]))
        assertNull(r[1])
    }

    @Test fun existingSignatureIsPreserved() {
        val real = sig("REAL")
        val r = ToolCallExtras.forSending(listOf(call("1", real), call("2")), "gemini-3-flash")
        assertEquals("REAL", signatureOf(r[0]))
        assertNull(r[1])
    }

    @Test fun emptyCalls() {
        assertTrue(ToolCallExtras.forSending(emptyList(), "gemini-3").isEmpty())
        assertFalse(ToolCallExtras.hasSignature(null))
    }

    @Test fun toolUseRoundTripAndOldJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val original = Block.ToolUse("1", "t", JsonObject(emptyMap()), sig("S"), null)
        val back = json.decodeFromString(Block.serializer(), json.encodeToString(Block.serializer(), original)) as Block.ToolUse
        assertEquals("S", signatureOf(back.extra))
        val old = """{"type":"tool_use","id":"1","name":"t","input":{}}"""
        val parsed = runCatching { json.decodeFromString(Block.serializer(), old) }.getOrNull()
        if (parsed != null) assertNull((parsed as Block.ToolUse).extra)
    }
}
