package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DesktopSyncTest {
    @Test
    fun versionsAreUnbounded() {
        assertEquals(VersionUtil.V(4, 0, 1), VersionUtil.parse("4.0.1"))
        assertEquals(VersionUtil.V(12, 30, 5), VersionUtil.parse("12.30.5+meta"))
        assertTrue(VersionUtil.parse("4.2.6")!! > VersionUtil.parse("4.0.1")!!)
        assertEquals(4_002_006L, VersionUtil.parse("4.2.6")!!.code())
        assertEquals("0.6.200", VersionUtil.label("bogus", 200))
    }

    @Test
    fun syncFormatSurvivesRoundTrip() {
        val json = """{"version":1,"project":"main","items":[{"role":"user","text":"hi"},{"role":"assistant","text":"${"x".repeat(20000)}"}]}"""
        val items = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject["items"]!!.jsonArray
        assertEquals(2, items.size)
        val text = items[1].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(20000, text.length)
    }
}
