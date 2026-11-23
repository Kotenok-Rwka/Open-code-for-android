package com.pocketcode

import com.pocketcode.tools.lineDiff
import com.pocketcode.ui.*
import com.pocketcode.agent.Extensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    @Test
    fun lineDiffShowsAddedAndRemoved() {
        val d = lineDiff("a\nb\n", "a\nc\n")
        assertTrue(d.contains("- b"))
        assertTrue(d.contains("+ c"))
    }

    @Test
    fun syncUrlBuildsWithToken() {
        val url = "http://192.168.1.10:8765".trimEnd('/') + "?token=abc"
        assertEquals("http://192.168.1.10:8765?token=abc", url)
    }
}

class RemindersParseTest {
    @Test
    fun parsesNaturalReminder() {
        val r = Reminders.parseNatural("напомни через 2 часа проверить сборку")
        assertNotNull(r)
        assertEquals("проверить сборку", r!!.first)
        assertTrue(r.second > System.currentTimeMillis())
    }
}

class LearnContentExtraTest {
    @Test
    fun newLessonsAndFaqPresent() {
        assertTrue(com.pocketcode.ui.EXTRA_LESSONS.size >= 5)
        assertTrue(com.pocketcode.ui.EXTRA_FAQ.any { it.lesson == "gguf" })
    }
}

class SyncCryptoTest {
    @Test
    fun encryptDecryptRoundTrip() {
        val enc = com.pocketcode.SyncCrypto.encrypt("secret payload", "pass123")
        assertTrue(com.pocketcode.SyncCrypto.isEncrypted(enc))
        assertEquals("secret payload", com.pocketcode.SyncCrypto.decrypt(enc, "pass123"))
    }
    @Test
    fun wrongPasswordDoesNotCrash() {
        val enc = com.pocketcode.SyncCrypto.encrypt("secret", "a")
        assertEquals(enc, com.pocketcode.SyncCrypto.decrypt(enc, "b"))
    }
}

class ExtensionsSkillTest {
    @Test
    fun loadsLowercaseSkillMdAndSkipsDisabledPlugin() {
        val base = java.nio.file.Files.createTempDirectory("pk-skills").toFile()
        val project = java.io.File(base, "project").apply { mkdirs() }
        val global = java.io.File(base, "global").apply { mkdirs() }
        val plugins = java.io.File(base, "plugins").apply { mkdirs() }
        val plugin = java.io.File(plugins, "demo").apply { mkdirs() }
        java.io.File(plugin, "skills").mkdirs()
        val dir = java.io.File(plugin, "skills/hello").apply { mkdirs() }
        java.io.File(dir, "skill.md").writeText("---\nname: hello\ndescription: test\n---\nDo the thing.")
        java.io.File(plugins, ".disabled-off").mkdirs()
        val ext = Extensions({ project }, global, plugins)
        val skills = ext.skills(all = true)
        assertTrue(skills.any { it.name == "hello" })
        assertTrue(skills.none { it.source.contains("disabled") })
    }
}

class ReasoningSpecTest {
    @Test
    fun sendsNativeFields() {
        val openai = com.pocketcode.llm.ReasoningSpec(true, 2, "openai").fields("gpt-5", "https://api.openai.com/v1")
        assertEquals("high", openai["reasoning_effort"])
        val gemini = com.pocketcode.llm.ReasoningSpec(true, 1, "gemini").fields("gemini-2.5", "https://generativelanguage.googleapis.com/v1beta/openai")
        assertTrue(gemini.containsKey("thinkingConfig"))
        assertTrue(com.pocketcode.llm.ReasoningSpec(true, 2, "anthropic").fields("claude", "https://api.anthropic.com").isEmpty())
    }
    @Test
    fun detectsSupportedModels() {
        assertTrue(com.pocketcode.llm.ReasoningSpec(true, 1).supported("gpt-5", "https://api.openai.com/v1"))
        assertTrue(com.pocketcode.llm.ReasoningSpec(true, 1).supported("gemini-2.5-flash", "https://generativelanguage.googleapis.com/v1beta/openai"))
    }
}

class ReasoningUniversalTest {
    @Test
    fun sendsToAnyCompatibleProvider() {
        val spec = com.pocketcode.llm.ReasoningSpec(true, 2, "auto")
        assertTrue(spec.sendsNative("some-unknown-model", "https://custom.example/v1"))
        assertTrue(spec.fields("some-model", "https://custom.example/v1").containsKey("reasoning_effort"))
    }
}

class CapabilitySummaryTest {
    @Test
    fun formatsSummary() {
        val s = com.pocketcode.llm.ModelCapabilityInfo("m", true, true, false, true).summary
        assertTrue(s.contains("reasoning=да"))
        assertTrue(s.contains("tools=да"))
    }
}
