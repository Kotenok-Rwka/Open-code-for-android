package com.pocketcode

import com.pocketcode.video.BatchState
import com.pocketcode.video.GenHistoryItem
import com.pocketcode.video.MapKeyValue
import com.pocketcode.video.SubPos
import com.pocketcode.video.SubStyle
import com.pocketcode.video.SubTweak
import com.pocketcode.video.Transition
import com.pocketcode.video.VideoQuality
import com.pocketcode.video.VideoSettings
import com.pocketcode.video.VideoStore
import com.pocketcode.video.summary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSettingsTest {
    private val sample = VideoSettings(
        doCut = false, noiseDb = -40, minSilence = 1.0, doSubs = true, subStyle = SubStyle.NEON,
        subTweak = SubTweak("50E850", 1.25, SubPos.TOP), lang = "ru", transition = Transition.FLASH, quality = VideoQuality.HIGH,
        small = true, shorts = true, blurBg = false, enhance = true, finTitle = "Хук", finSec = 4.0, finBar = true,
        finSpeed = 1.25, finVol = 0.4, finDuck = false, presetId = "viral",
    )

    @Test
    fun settingsSurviveJson() {
        assertEquals(sample, VideoSettings.parse(sample.toJson().toString()))
        assertEquals(VideoSettings(), VideoSettings.parse(VideoSettings().toJson().toString()))
    }

    @Test
    fun brokenInputFallsBackToDefaults() {
        assertNull(VideoSettings.parse("не json"))
        assertNull(VideoSettings.parse(null))
        val s = VideoSettings.parse("{\"subStyle\":\"НЕТ\",\"noiseDb\":-999,\"finSpeed\":9,\"accent\":\"zz\",\"transition\":5}")!!
        assertEquals(SubStyle.CLASSIC, s.subStyle)
        assertEquals(-50, s.noiseDb)
        assertEquals(2.0, s.finSpeed, 0.0001)
        assertEquals("", s.subTweak.accent)
        assertEquals(Transition.ZOOM, s.transition)
    }

    @Test
    fun lastAndProjectsAreStoredPerVideo() {
        val st = VideoStore(MapKeyValue())
        assertNull(st.last())
        st.saveLast(sample)
        assertEquals(sample, st.last())
        st.saveProject("video/a.mp4", sample)
        st.saveProject("video/b.mp4", VideoSettings(doSubs = true))
        assertEquals(sample, st.project("video/a.mp4"))
        assertTrue(st.project("video/b.mp4")!!.doSubs)
        assertNull(st.project("video/c.mp4"))
        assertEquals(listOf("video/b.mp4", "video/a.mp4"), st.projects())
    }

    @Test
    fun oldProjectsAreForgotten() {
        val kv = MapKeyValue()
        val st = VideoStore(kv)
        for (i in 1..VideoStore.MAX_PROJECTS + 5) st.saveProject("video/$i.mp4", VideoSettings())
        assertEquals(VideoStore.MAX_PROJECTS, st.projects().size)
        assertNull(st.project("video/1.mp4"))
        assertTrue(st.project("video/${VideoStore.MAX_PROJECTS + 5}.mp4") != null)
        assertEquals(VideoStore.MAX_PROJECTS, kv.map.keys.count { it.startsWith("proj:") })
    }

    @Test
    fun userPresetsReplaceSameNameAndDelete() {
        val st = VideoStore(MapKeyValue())
        st.savePreset("  Мой клип ", sample)
        st.savePreset("Другой", VideoSettings())
        val l = st.savePreset("мой клип", VideoSettings(doSubs = true))
        assertEquals(listOf("Другой", "мой клип"), l.map { it.name })
        assertTrue(st.presets().last().settings.doSubs)
        assertNull(st.presets().first().settings.presetId)
        assertEquals(listOf("мой клип"), st.deletePreset("ДРУГОЙ").map { it.name })
        assertEquals(1, st.savePreset("   ", sample).size)
    }

    @Test
    fun historyKeepsLatestWithoutDuplicates() {
        val st = VideoStore(MapKeyValue())
        fun item(p: String, m: String = "m") = GenHistoryItem(p, "FAL", m, "9:16", 8, "720p", false)
        st.addHistory(item("один"))
        st.addHistory(item("два"))
        st.addHistory(item("один"))
        assertEquals(listOf("один", "два"), st.history().map { it.prompt })
        assertEquals(item("один"), st.history().first())
        for (i in 1..20) st.addHistory(item("p$i"))
        assertEquals(VideoStore.MAX_HISTORY, st.history().size)
        assertEquals("p20", st.history().first().prompt)
    }

    @Test
    fun batchResumesWhereItStopped() {
        val st = VideoStore(MapKeyValue())
        assertNull(st.batch())
        val b = BatchState(listOf("video/a.mp4", "video/b.mp4", "video/c.mp4"), sample, "video/music/m.mp3")
        assertEquals(3, b.remaining().size)
        val b2 = b.withDone("video/a.mp4", "video/a_cut.mp4").withFailed("video/b.mp4")
        st.saveBatch(b2)
        val back = st.batch()!!
        assertEquals(b2, back)
        assertEquals(listOf("video/c.mp4"), back.remaining())
        assertEquals(listOf("video/a_cut.mp4"), back.outs)
        assertTrue(!back.finished)
        assertTrue(back.withDone("video/c.mp4", null).finished)
        st.clearBatch()
        assertNull(st.batch())
    }

    @Test
    fun brokenBatchIsIgnored() {
        assertNull(BatchState.parse("не json"))
        assertNull(BatchState.parse("{\"items\":[]}"))
        assertNull(BatchState.parse(null))
    }

    @Test
    fun summaryListsEnabledSteps() {
        assertEquals(listOf("Без пауз", Transition.ZOOM.label), VideoSettings().summary())
        val s = sample.summary()
        assertTrue(SubStyle.NEON.label in s && "Shorts 9:16" in s && "Заголовок" in s && "Полоска прогресса" in s && "Ускорение" in s)
        assertTrue("Без пауз" !in s)
    }

    @Test
    fun forgetProjectAndHint() {
        val st = VideoStore(MapKeyValue())
        st.saveProject("video/a.mp4", sample)
        st.saveProject("video/b.mp4", sample)
        st.forgetProject("video/a.mp4")
        assertNull(st.project("video/a.mp4"))
        assertEquals(listOf("video/b.mp4"), st.projects())
        assertTrue(!st.hintSeen())
        st.markHintSeen()
        assertTrue(st.hintSeen())
    }
}
