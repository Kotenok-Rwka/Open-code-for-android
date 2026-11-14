// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Барабаны выбора и распознавание текста в контроллере (с подставными читателем и вводом). */
class PhoneControllerWheelTest {
    private fun node(id: Int, text: String = "") = UiNode(
        id, 0, "TextView", text, "", "", PRect(0, id * 10, 100, id * 10 + 10),
        clickable = false, longClickable = false, scrollable = false, editable = false, checked = null, focused = false, enabled = true,
    )

    private class Reader(val nodes: List<UiNode>, val wheel: WheelOutcome? = null) : PhoneScreenReader {
        var shots = 0
        var io: WheelIo? = null
        override val available = true
        override suspend fun snapshot(displayId: Int) = ScreenSnapshot(displayId, "com.app", "", 1080, 2400, nodes, false)
        override suspend fun screenshot(displayId: Int, maxSide: Int): ByteArray? = byteArrayOf(1, 2, 3).also { shots++ }
        override suspend fun clickNode(id: Int) = true
        override suspend fun longClickNode(id: Int) = true
        override suspend fun setNodeText(id: Int, text: String) = true
        override suspend fun scrollNode(id: Int, forward: Boolean) = true
        override suspend fun global(action: PhoneGlobal) = true
        override suspend fun gestureTap(x: Int, y: Int, durationMs: Long) = true
        override suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
        override suspend fun setWheel(id: Int, value: String, io: WheelIo): WheelOutcome? = wheel.also { this.io = io }
    }

    private class Input : PhoneInput {
        val swipes = ArrayList<List<Int>>()
        override val connected = true
        override suspend fun tap(displayId: Int, x: Int, y: Int) = true
        override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true.also { swipes += listOf(x1, y1, x2, y2, durationMs.toInt()) }
        override suspend fun key(displayId: Int, keyCode: Int) = true
        override suspend fun typeAscii(displayId: Int, text: String) = true
        override suspend fun pasteText(displayId: Int, text: String) = true
        override suspend fun launchApp(displayId: Int, pkg: String) = true
        override suspend fun openUrl(displayId: Int, url: String) = true
        override suspend fun listApps(): List<AppInfo> = emptyList()
        override suspend fun createHiddenDisplay(width: Int, height: Int, dpi: Int) = 5
        override suspend fun releaseHiddenDisplay() {}
        override suspend fun screencap(maxSide: Int): ByteArray? = null
        override suspend fun hiddenScreenshot(maxSide: Int): ByteArray? = null
        override suspend fun shell(command: String) = ShellResult(0, "")
        override suspend fun enableAccessibility() = true
    }

    private val ocrLine = OcrLine("Хм Hello", listOf(500, 500, 500, 520))

    @Test fun sparseTreeGetsRecognizedText() = runBlocking {
        val c = PhoneController(Reader(listOf(node(1))), Input(), ocr = { listOf(ocrLine) })
        val text = c.look(withImage = false).text
        assertTrue(text, text.contains("Текст на экране") && text.contains("«Хм Hello»"))
    }

    @Test fun richTreeIsNotRecognizedUnlessAsked() = runBlocking {
        val nodes = (1..5).map { node(it, "t$it") }
        var calls = 0
        val c = PhoneController(Reader(nodes), Input(), ocr = { calls++; listOf(ocrLine) })
        assertFalse(c.look(withImage = false).text.contains("Текст на экране"))
        assertEquals(0, calls)
        assertTrue(c.look(withImage = false, ocrText = true).text.contains("Текст на экране"))
        assertEquals(1, calls)
    }

    @Test fun withoutRecognizerNothingIsAddedAndNoExtraScreenshot() = runBlocking {
        val reader = Reader(listOf(node(1)))
        val c = PhoneController(reader, Input())
        assertFalse(c.look(withImage = false).text.contains("Текст на экране"))
        assertEquals(0, reader.shots)
    }

    @Test fun recognizerFailureDoesNotBreakLook() = runBlocking {
        val c = PhoneController(Reader(listOf(node(1))), Input(), ocr = { throw IllegalStateException("boom") })
        assertFalse(c.look(withImage = false).text.contains("Текст на экране"))
    }

    @Test fun wheelNeedsAFreshSnapshot() = runBlocking {
        val c = PhoneController(Reader(emptyList()), Input())
        assertEquals("ref_expired", c.setWheel(1, "9").error)
    }

    @Test fun wheelResultComesFromTheReaderAndDragGoesThroughInput() = runBlocking {
        val reader = Reader(listOf(node(1)), WheelOutcome(true, "9", "7", 2, "drag"))
        val inp = Input()
        val c = PhoneController(reader, inp)
        c.look(withImage = false)
        val o = c.setWheel(1, "9")
        assertTrue(o.reached)
        val io = reader.io!!
        assertEquals(1080, io.width)
        assertEquals(2400, io.height)
        assertTrue(io.drag(listOf(540 to 1500, 540 to 1100), true))
        assertTrue(io.drag(listOf(540 to 1500, 540 to 1100), false))
        assertEquals(listOf(540, 1500, 540, 1100, 1000), inp.swipes[0])
        assertEquals(listOf(540, 1500, 540, 1100, 400), inp.swipes[1])
        assertNull(io.ocr())
    }

    @Test fun unsupportedReaderReportsIt() = runBlocking {
        val reader = Reader(listOf(node(1)), null)
        val c = PhoneController(reader, Input())
        c.look(withImage = false)
        assertEquals("not_supported", c.setWheel(1, "9").error)
    }
}
