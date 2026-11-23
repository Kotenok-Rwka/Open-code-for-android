package com.pocketcode

import com.pocketcode.ui.ChatImages
import com.pocketcode.ui.PluginSearch
import com.pocketcode.ui.StorageScanner
import com.pocketcode.ui.ToolsConfig
import com.pocketcode.ui.formatBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Простые тесты для новой логики, перенесённой по идеям Agora и AndroidHarness. */
class NewParityLogicTest {
    @Test
    fun findsGeneratedImagesInChatText() {
        val text = "Готово!\n![картинка](cat.png)\n<img src=\"logo.svg\" />\n[открыть](photo.jpeg)"
        assertEquals(listOf("cat.png", "logo.svg", "photo.jpeg"), ChatImages.find(text))
    }

    @Test
    fun findsGeneratedVideosInChatText() {
        val text = "Готово! Ролик сохранён как video/funny_cut_fx.mp4 в проекте."
        assertEquals(listOf("funny_cut_fx.mp4"), ChatImages.findVideos(text))
    }

    @Test
    fun ignoresRemoteImageLinks() {
        assertTrue(ChatImages.find("![x](https://example.com/a.png)").isEmpty())
    }

    @Test
    fun githubSearchHandlesNullFields() {
        val json = """{"items":[{"full_name":"org/repo","description":null,"language":null,"stargazers_count":1}]}"""
        val hits = PluginSearch.parse(json)
        assertEquals(1, hits.size)
        assertEquals("", hits[0].description)
        assertEquals("", hits[0].language)
    }

    @Test
    fun storageCountsNestedFilesOnce() {
        val base = Files.createTempDirectory("pk-storage").toFile()
        try {
            File(base, "a").mkdirs()
            File(base, "a/b").mkdirs()
            File(base, "a/b/x.txt").writeText("12345")
            val scan = StorageScanner.scan(base)
            assertEquals(5L, scan.rows.first { it.name == "Остальные данные" }.bytes)
            assertEquals(5L, scan.total)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun formatsSmallSizesWithoutRoundingToZero() {
        assertTrue(formatBytes(0).startsWith("0"))
        assertTrue(formatBytes(512).contains("Б"))
        assertTrue(formatBytes(2048).contains("КБ"))
    }

    @Test
    fun selfCheckEnabledByDefault() {
        assertTrue(ToolsConfig().selfCheck)
        assertTrue(!ToolsConfig(selfCheck = false).selfCheck)
    }

    @Test
    fun thinkingDepthIsBoundedBySliderRange() {
        val cfg = ToolsConfig(reasoningDepth = 3)
        assertEquals(3, cfg.reasoningDepth)
        assertTrue(cfg.reasoningDepth in 0..3)
    }
}
