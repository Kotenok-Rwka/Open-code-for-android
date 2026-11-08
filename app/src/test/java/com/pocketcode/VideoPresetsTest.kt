package com.pocketcode

import com.pocketcode.video.SubStyle
import com.pocketcode.video.VideoPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPresetsTest {
    @Test
    fun idsAreUniqueAndLookupWorks() {
        val ids = VideoPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertNotNull(VideoPresets.byId("viral"))
        assertNull(VideoPresets.byId("нет такого"))
        assertNull(VideoPresets.byId(null))
    }

    @Test
    fun valuesMatchWhatTheScreenCanSelect() {
        val silences = setOf(0.4, 0.6, 1.0, 1.5)
        for (p in VideoPresets.all) {
            assertTrue("${p.id}: порог паузы", p.minSilence in silences)
            assertTrue("${p.id}: чувствительность", p.noiseDb in -50..-25)
            assertTrue("${p.id}: нужен хотя бы один шаг монтажа", p.cut || p.subs != null)
            assertTrue("${p.id}: описание", p.about.isNotBlank() && p.label.isNotBlank())
        }
    }

    @Test
    fun tagsDescribeTheSettings() {
        val v = VideoPresets.byId("viral")!!
        assertTrue("Без пауз" in v.tags())
        assertTrue(SubStyle.POP.label in v.tags())
        assertTrue("Shorts 9:16" in v.tags())
        val clean = VideoPresets.byId("clean")!!
        assertEquals(listOf("Без пауз", clean.transition.label, "Чистый звук"), clean.tags())
    }

    @Test
    fun everySubtitleStyleAppearsInSomePreset() {
        val used = VideoPresets.all.mapNotNull { it.subs }.toSet()
        assertTrue(SubStyle.values().all { it in used })
    }
}
