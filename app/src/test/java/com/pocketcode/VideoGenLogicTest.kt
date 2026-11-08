package com.pocketcode

import com.pocketcode.video.GenPresets
import com.pocketcode.video.GenProvider
import com.pocketcode.video.GenRequest
import com.pocketcode.video.GeminiVideo
import com.pocketcode.video.apiErrorText
import com.pocketcode.video.buildGenInput
import com.pocketcode.video.buildGeminiBody
import com.pocketcode.video.buildReplicateBody
import com.pocketcode.video.explainGenHttp
import com.pocketcode.video.findVideoUrl
import com.pocketcode.video.genElapsed
import com.pocketcode.video.genFileName
import com.pocketcode.video.geminiFileId
import com.pocketcode.video.parseFalStatus
import com.pocketcode.video.parseFalSubmit
import com.pocketcode.video.parseGeminiVideo
import com.pocketcode.video.parseReplicate
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VideoGenLogicTest {
    @Test
    fun seedanceInputUsesStringDuration() {
        val o = buildGenInput(GenPresets.SEEDANCE_FAL, GenRequest("кот", "9:16", 8, "720p", true))
        assertEquals("кот", o["prompt"]!!.jsonPrimitive.content)
        assertEquals("9:16", o["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("8", o["duration"]!!.jsonPrimitive.content)
        assertTrue(o["duration"]!!.jsonPrimitive.isString)
        assertEquals("true", o["generate_audio"]!!.jsonPrimitive.content)
    }

    @Test
    fun unsupportedValuesFallBackToDefaults() {
        val o = buildGenInput(GenPresets.WAN_FAL, GenRequest("x", "21:9", 7, "4k"))
        assertEquals("16:9", o["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("5", o["duration"]!!.jsonPrimitive.content)
        assertFalse(o["duration"]!!.jsonPrimitive.isString)
        assertEquals("720p", o["resolution"]!!.jsonPrimitive.content)
    }

    @Test
    fun extraOverridesAndBadExtraFails() {
        val o = buildGenInput(GenPresets.WAN_FAL, GenRequest("x", extra = "{\"seed\": 42, \"resolution\": \"480p\"}"))
        assertEquals("42", o["seed"]!!.jsonPrimitive.content)
        assertEquals("480p", o["resolution"]!!.jsonPrimitive.content)
        try {
            buildGenInput(GenPresets.WAN_FAL, GenRequest("x", extra = "не json"))
            fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("JSON"))
        }
    }

    @Test
    fun customModelSendsOnlyPrompt() {
        val p = GenPresets.resolve(GenProvider.FAL, "vendor/some-model")
        assertTrue(p.custom)
        val o = buildGenInput(p, GenRequest("привет"))
        assertEquals(setOf("prompt"), o.keys)
        assertEquals(GenPresets.SEEDANCE_FAL, GenPresets.resolve(GenProvider.FAL, " "))
    }

    @Test
    fun replicateBodyWrapsInput() {
        val b = buildReplicateBody(GenPresets.SEEDANCE_REPLICATE, GenRequest("море", "1:1", 10))
        val i = b["input"]!!.jsonObject
        assertEquals("1:1", i["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("10", i["duration"]!!.jsonPrimitive.content)
    }

    @Test
    fun geminiBody() {
        val b = buildGeminiBody(GenPresets.OMNI_GEMINI, GenRequest("закат", "9:16", resolution = "1080p"))
        assertEquals("gemini-omni-1.1-flash", b["model"]!!.jsonPrimitive.content)
        val f = b["response_format"]!!.jsonObject
        assertEquals("9:16", f["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("1080p", f["resolution"]!!.jsonPrimitive.content)
    }

    @Test
    fun findsVideoUrlInDifferentShapes() {
        assertEquals("https://a/v.mp4", findVideoUrl("{\"video\":{\"url\":\"https://a/v.mp4\",\"content_type\":\"video/mp4\"}}"))
        assertEquals("https://b/x.mp4", findVideoUrl("\"https://b/x.mp4\""))
        assertEquals("https://c/o.mp4", findVideoUrl("{\"output\":[\"https://c/o.mp4\"]}"))
        assertEquals("https://d/v.webm", findVideoUrl("{\"thumb\":\"https://d/t.jpg\",\"file\":\"https://d/v.webm\"}"))
        assertNull(findVideoUrl("{\"a\":1}"))
        assertNull(findVideoUrl("не json"))
    }

    @Test
    fun falSubmitAndStatus() {
        val s = parseFalSubmit("{\"request_id\":\"r1\"}", "bytedance/seedance-2.0/text-to-video")
        assertEquals("https://queue.fal.run/bytedance/seedance-2.0/requests/r1/status", s.statusUrl)
        assertEquals("https://queue.fal.run/bytedance/seedance-2.0/requests/r1", s.responseUrl)
        val s2 = parseFalSubmit("{\"request_id\":\"r2\",\"status_url\":\"https://x/s\",\"response_url\":\"https://x/r\"}", "a/b")
        assertEquals("https://x/s", s2.statusUrl)
        val st = parseFalStatus("{\"status\":\"in_queue\",\"queue_position\":3}")
        assertEquals("IN_QUEUE", st.status)
        assertEquals(3, st.queuePosition)
        assertNull(st.error)
    }

    @Test
    fun replicateParse() {
        val p = parseReplicate("{\"id\":\"p1\",\"status\":\"Succeeded\",\"output\":\"https://r/o.mp4\",\"urls\":{\"get\":\"https://g\"}}")
        assertEquals("succeeded", p.status)
        assertEquals("https://g", p.getUrl)
        assertEquals("https://r/o.mp4", findVideoUrl(p.output))
        val f = parseReplicate("{\"id\":\"p2\",\"status\":\"failed\",\"error\":\"NSFW\"}")
        assertEquals("NSFW", f.error)
        assertEquals("https://api.replicate.com/v1/predictions/p2/cancel", f.cancelUrl)
    }

    @Test
    fun geminiVideoParse() {
        val inline = parseGeminiVideo("{\"steps\":[{\"content\":[{\"type\":\"video\",\"mime_type\":\"video/mp4\",\"data\":\"AAAA\"}]}]}")
        assertTrue(inline is GeminiVideo.Inline)
        val uri = parseGeminiVideo("{\"x\":{\"type\":\"video\",\"uri\":\"https://g/v1beta/files/abc123:download?alt=media\"}}")
        assertTrue(uri is GeminiVideo.Uri)
        assertEquals("abc123", geminiFileId((uri as GeminiVideo.Uri).uri))
        assertNull(parseGeminiVideo("{\"text\":\"нет\"}"))
    }

    @Test
    fun errorTexts() {
        assertEquals("Bad key", apiErrorText("{\"error\":{\"message\":\"Bad key\"}}"))
        assertEquals("слабый", apiErrorText("{\"detail\":\"слабый\"}"))
        assertEquals("plain text", apiErrorText("plain   text"))
        assertTrue(explainGenHttp(GenProvider.FAL, 401, "{}").contains("Ключ"))
        assertTrue(explainGenHttp(GenProvider.REPLICATE, 402, "").contains("средств"))
        assertTrue(explainGenHttp(GenProvider.GEMINI, 503, "").contains("недоступен"))
    }

    @Test
    fun fileNameAndElapsed() {
        assertEquals("gen_1_Кот_на_луне.mp4", genFileName("  Кот, на луне!! ", "1"))
        assertEquals("gen_1_video.mp4", genFileName("!!!", "1"))
        assertEquals("1:05", genElapsed(65))
    }
}
