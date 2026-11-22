package com.pocketcode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    @Test
    fun modesGateCorrectly() {
        assertTrue(SyncEngine.shouldRunLive(SyncConfig(enabled = true, mode = "LIVE")))
        assertTrue(SyncEngine.shouldRunLive(SyncConfig(enabled = true, mode = "BOTH")))
        assertFalse(SyncEngine.shouldRunLive(SyncConfig(enabled = true, mode = "PERIODIC")))
        assertTrue(SyncEngine.shouldRunPeriodic(SyncConfig(enabled = true, mode = "BOTH")))
        assertFalse(SyncEngine.shouldRunPeriodic(SyncConfig(enabled = false)))
    }

    @Test
    fun endpointBuilt() {
        assertEquals("http://192.168.1.5:8765/api/sync/push", SyncEngine.endpoint(SyncConfig(serverUrl = "http://192.168.1.5:8765/")))
    }

    @Test
    fun eventPacketHasAllFields() {
        val json = Json.parseToJsonElement(SyncEngine.eventPacket(7, "Phone", "main", "message", "hello")).jsonObject
        assertEquals(2, json["v"]!!.jsonPrimitive.content.toInt())
        assertEquals("event", json["type"]!!.jsonPrimitive.content)
        assertEquals(7L, json["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals("message", json["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun fullPacketIncludesFiles() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        kotlin.io.path.createTempFile(dir.toPath(), "note", ".txt").toFile().writeText("hello")
        val msgs = Json.parseToJsonElement("""[{"role":"user","text":"hi"}]""").jsonArray
        val obj = Json.parseToJsonElement(SyncEngine.fullPacket(1, "Phone", "main", msgs, dir, true)).jsonObject
        assertEquals("full", obj["type"]!!.jsonPrimitive.content)
        assertEquals(1, obj["items"]!!.jsonArray.size)
        assertTrue(obj["files"]!!.jsonArray.size >= 1)
    }
}
