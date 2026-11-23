package com.pocketcode

import com.pocketcode.tools.SafeFiles
import com.pocketcode.ui.RichBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NewToolsLogicTest {
    @Test
    fun atomicWriteKeepsContent() {
        val dir = Files.createTempDirectory("pk-atomic").toFile()
        val f = File(dir, "a.txt")
        assertTrue(SafeFiles.atomicWrite(f, "hello"))
        assertEquals("hello", f.readText())
        assertTrue(SafeFiles.atomicWrite(f, "world"))
        assertEquals("world", f.readText())
        f.deleteRecursively()
    }

    @Test
    fun sha256IsStable() {
        val a = SafeFiles.sha256("abc")
        assertEquals(a, SafeFiles.sha256("abc"))
        assertFalse(a == SafeFiles.sha256("abd"))
    }

    @Test
    fun recognizesRichBlocks() {
        assertTrue(RichBlocks.isRich("mermaid", "graph TD\nA-->B"))
        assertTrue(RichBlocks.isRich("latex", "x^2"))
        assertFalse(RichBlocks.isRich("kotlin", "fun main()"))
    }

    @Test
    fun mermaidHtmlHasNoScripts() {
        val html = RichBlocks.html("mermaid", "A-->B")
        assertTrue(html.contains("A"))
        assertFalse(html.contains("<script"))
    }
}
