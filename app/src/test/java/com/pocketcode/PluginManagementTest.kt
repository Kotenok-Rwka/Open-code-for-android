package com.pocketcode

import com.pocketcode.agent.Extensions
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class PluginManagementTest {
    private lateinit var plugins: File
    private lateinit var ext: Extensions

    @Before
    fun setUp() {
        val base = java.nio.file.Files.createTempDirectory("pk-plugins").toFile()
        plugins = File(base, "plugins").apply { mkdirs() }
        ext = Extensions({ File(base, "project").apply { mkdirs() } }, File(base, "global").apply { mkdirs() }, plugins)
    }

    private fun addPlugin(folder: String, skill: String = "hello") {
        val dir = File(plugins, "$folder/skills/$skill").apply { mkdirs() }
        File(dir, "SKILL.md").writeText("---\nname: $skill\ndescription: test\n---\nBody of $folder.")
    }

    @Test
    fun disabledPluginCanBeEnabledAgain() {
        addPlugin("demo")
        assertTrue(ext.setPluginEnabled("demo", false))
        assertEquals(listOf("demo" to false), ext.plugins().map { it.name to it.enabled })
        assertTrue(File(plugins, ".disabled-demo").isDirectory)
        assertTrue(ext.skills(all = true).none { it.name == "hello" })

        assertTrue(ext.setPluginEnabled("demo", true))
        assertEquals(listOf("demo" to true), ext.plugins().map { it.name to it.enabled })
        assertTrue(File(plugins, "demo").isDirectory)
        assertFalse(File(plugins, ".disabled-demo").exists())
        assertTrue(ext.skills(all = true).any { it.name == "hello" })
    }

    @Test
    fun disabledPluginCanBeRemovedByPlainName() {
        addPlugin(".disabled-demo")
        assertTrue(ext.removePlugin("demo"))
        assertFalse(File(plugins, ".disabled-demo").exists())
        assertTrue(ext.plugins().isEmpty())
        assertFalse(ext.removePlugin("demo"))
    }

    @Test
    fun unknownPluginIsNotToggled() {
        assertFalse(ext.setPluginEnabled("missing", false))
        assertNull(ext.plugin("missing"))
    }

    @Test
    fun sourceIsRememberedAndFallsBackToGitConfig() {
        addPlugin("demo")
        assertNull(ext.pluginSource("demo"))
        ext.savePluginSource(File(plugins, "demo"), "https://github.com/a/demo")
        assertEquals("https://github.com/a/demo", ext.pluginSource("demo"))

        addPlugin("legacy")
        File(plugins, "legacy/.git").mkdirs()
        File(plugins, "legacy/.git/config").writeText("[remote \"origin\"]\n\turl = https://github.com/a/legacy.git\n")
        assertEquals("https://github.com/a/legacy.git", ext.pluginSource("legacy"))

        addPlugin("odd")
        ext.savePluginSource(File(plugins, "odd"), "file:///etc")
        assertNull(ext.pluginSource("odd"))
    }

    @Test
    fun updateReplacesFilesAndKeepsDisabledState() {
        addPlugin(".disabled-demo")
        ext.replacePlugin("demo", "https://github.com/a/demo") { dir ->
            File(dir, "skills/fresh").mkdirs()
            File(dir, "skills/fresh/SKILL.md").writeText("---\nname: fresh\ndescription: new\n---\nNew body.")
            File(dir, ".git").mkdirs()
        }
        val dir = File(plugins, ".disabled-demo")
        assertTrue(File(dir, "skills/fresh/SKILL.md").isFile)
        assertFalse(File(dir, "skills/hello").exists())
        assertFalse(File(dir, ".git").exists())
        assertEquals("https://github.com/a/demo", ext.pluginSource("demo"))
        assertEquals(listOf(".disabled-demo"), plugins.list()!!.toList())
    }

    @Test
    fun failedUpdateKeepsOldVersion() {
        addPlugin("demo")
        val failure = runCatching {
            ext.replacePlugin("demo", "https://github.com/a/demo") { dir ->
                dir.mkdirs()
                throw java.io.IOException("нет сети")
            }
        }
        assertEquals("нет сети", failure.exceptionOrNull()?.message)
        assertTrue(File(plugins, "demo/skills/hello/SKILL.md").isFile)
        assertEquals(listOf("demo"), plugins.list()!!.toList())
    }

    @Test
    fun inventoryIsEmptyWithoutExtensions() {
        assertNull(ext.inventory())
    }

    @Test
    fun inventoryNamesPluginAndItsSkillAndReachesThePrompt() {
        addPlugin("demo")
        val inv = ext.inventory()!!
        assertTrue(inv, inv.contains("demo"))
        assertTrue(inv, inv.contains("hello"))
        val prompt = com.pocketcode.agent.systemPrompt("/w", "sh", false, extensions = inv)
        assertTrue(prompt.contains(inv))
        assertFalse(com.pocketcode.agent.systemPrompt("/w", "sh", false).contains("Installed extensions"))
    }

    @Test
    fun agentModesAddTheirPromptSections() {
        val auto = com.pocketcode.agent.systemPrompt("/w", "sh", false)
        assertFalse(auto.contains("SCAFFOLD MODE"))
        assertFalse(auto.contains("FOCUSED MODE"))
        val scaffold = com.pocketcode.agent.systemPrompt("/w", "sh", false, agentMode = "scaffold")
        assertTrue(scaffold.contains("SCAFFOLD MODE"))
        assertTrue(scaffold.contains("ONE FILE AT A TIME"))
        assertFalse(scaffold.contains("FOCUSED MODE"))
        val focused = com.pocketcode.agent.systemPrompt("/w", "sh", false, agentMode = "focused")
        assertTrue(focused.contains("FOCUSED MODE"))
        assertTrue(focused.contains("NAVIGATE, DO NOT READ"))
        assertFalse(focused.contains("SCAFFOLD MODE"))
    }

    @Test
    fun appearanceTypographyAndShapesHelpers() {
        // scaled typography keeps base when nothing is set
        val base = androidx.compose.material3.Typography()
        val same = com.pocketcode.ui.styledTypography(base, null, 1f, false)
        for ((b, t) in listOf(same.bodyLarge!! to base.bodyLarge!!, same.bodyMedium!! to base.bodyMedium!!, same.titleLarge!! to base.titleLarge!!)) {
            org.junit.Assert.assertEquals(b.fontSize, t.fontSize)
        }
        // chat scale and reading mode enlarge only body styles
        val scaled = com.pocketcode.ui.styledTypography(base, null, 1.3f, true)
        assertTrue(scaled.bodyLarge!!.fontSize > base.bodyLarge!!.fontSize)
        assertTrue(scaled.bodyLarge!!.lineHeight > base.bodyLarge!!.lineHeight)
        org.junit.Assert.assertEquals(base.titleLarge!!.fontSize, scaled.titleLarge!!.fontSize)
        // shapes follow the configured radius (CornerSize renders as "CornerSize(size = 12.0.dp)")
        val shapes = com.pocketcode.ui.appearanceShapes(12)
        val large = shapes.large as androidx.compose.foundation.shape.RoundedCornerShape
        org.junit.Assert.assertEquals("CornerSize(size = 12.0.dp)", large.topStart.toString())
        val tiny = com.pocketcode.ui.appearanceShapes(0).large as androidx.compose.foundation.shape.RoundedCornerShape
        org.junit.Assert.assertEquals("CornerSize(size = 0.0.dp)", tiny.topStart.toString())
    }
}
