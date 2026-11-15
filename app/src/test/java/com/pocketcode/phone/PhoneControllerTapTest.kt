package com.pocketcode.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Нажатия по элементу не должны попадать на основной экран, когда агент работает на фоновом; перенос приложения на фоновый экран. */
class PhoneControllerTapTest {
    private fun node(id: Int) = UiNode(
        id, 0, "Button", "b$id", "", "", PRect(0, id * 10, 100, id * 10 + 10),
        clickable = true, longClickable = false, scrollable = false, editable = false, checked = null, focused = false, enabled = true,
    )

    private class Reader(val click: Boolean, val longClick: Boolean = false) : PhoneScreenReader {
        val gestures = ArrayList<String>()
        override val available = true
        override suspend fun snapshot(displayId: Int) = ScreenSnapshot(displayId, "com.app", "", 1080, 1920, listOf(UiNode(
            1, 0, "Button", "b1", "", "", PRect(0, 10, 100, 20),
            clickable = true, longClickable = false, scrollable = false, editable = false, checked = null, focused = false, enabled = true,
        ), UiNode(2, 0, "TextView", "t", "", "", PRect(0, 0, 10, 10), clickable = false, longClickable = false, scrollable = false, editable = false, checked = null, focused = false, enabled = true),
            UiNode(3, 0, "TextView", "u", "", "", PRect(0, 0, 10, 10), clickable = false, longClickable = false, scrollable = false, editable = false, checked = null, focused = false, enabled = true)), false)
        override suspend fun screenshot(displayId: Int, maxSide: Int): ByteArray? = null
        override suspend fun clickNode(id: Int) = click
        override suspend fun longClickNode(id: Int) = longClick
        override suspend fun setNodeText(id: Int, text: String) = true
        override suspend fun scrollNode(id: Int, forward: Boolean) = true
        override suspend fun global(action: PhoneGlobal) = true
        override suspend fun gestureTap(x: Int, y: Int, durationMs: Long) = true.also { gestures += "tap:$x,$y" }
        override suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true.also { gestures += "swipe:$x1,$y1,$durationMs" }
    }

    private class Input(override val connected: Boolean, val moved: Boolean = true) : PhoneInput {
        val taps = ArrayList<String>()
        val swipes = ArrayList<String>()
        val moves = ArrayList<String>()
        override suspend fun tap(displayId: Int, x: Int, y: Int) = true.also { taps += "$displayId:$x,$y" }
        override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true.also { swipes += "$displayId:$x1,$y1,$durationMs" }
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
        override suspend fun moveAppToHidden(pkg: String, displayId: Int) = moved.also { moves += "$displayId:$pkg" }
    }

    @Test fun failedNodeClickOnTheBackgroundScreenTapsThereNotOnTheMainOne() = runBlocking {
        val reader = Reader(click = false)
        val inp = Input(true)
        val c = PhoneController(reader, inp)
        c.openBackground(1080, 1920, 420)
        c.look(withImage = false)
        assertTrue(c.tapNode(1))
        assertEquals(listOf("5:50,15"), inp.taps)
        assertEquals(emptyList<String>(), reader.gestures)
    }

    @Test fun nodeClickThatWorksNeedsNoFallback() = runBlocking {
        val reader = Reader(click = true)
        val inp = Input(true)
        val c = PhoneController(reader, inp)
        c.look(withImage = false)
        assertTrue(c.tapNode(1))
        assertEquals(emptyList<String>(), inp.taps)
    }

    @Test fun withoutHelperTheFallbackIsAGestureOnTheMainScreenOnly() = runBlocking {
        val reader = Reader(click = false)
        val c = PhoneController(reader, Input(false))
        c.look(withImage = false)
        assertTrue(c.tapNode(1))
        assertEquals(listOf("tap:50,15"), reader.gestures)
    }

    @Test fun staleNodeIsNotTapped() = runBlocking {
        val c = PhoneController(Reader(click = false), Input(true))
        c.look(withImage = false)
        assertEquals(false, c.tapNode(99))
    }

    @Test fun longPressFallbackStaysOnTheBackgroundScreen() = runBlocking {
        val reader = Reader(click = false, longClick = false)
        val inp = Input(true)
        val c = PhoneController(reader, inp)
        c.openBackground(1080, 1920, 420)
        c.look(withImage = false)
        assertTrue(c.longPressNode(1))
        assertEquals(listOf("5:50,15,700"), inp.swipes)
        assertEquals(emptyList<String>(), reader.gestures)
    }

    @Test fun appIsMovedToTheBackgroundScreen() = runBlocking {
        val inp = Input(true)
        val c = PhoneController(Reader(true), inp)
        assertNull(c.moveToBackground("org.exteragram.messenger"))
        assertEquals(5, c.displayId)
        assertEquals(listOf("5:org.exteragram.messenger"), inp.moves)
    }

    @Test fun moveReportsReasons() = runBlocking {
        assertTrue(PhoneController(Reader(true), Input(false)).moveToBackground("a.b")!!.contains("нужен помощник"))
        assertTrue(PhoneController(Reader(true), Input(true, moved = false)).moveToBackground("a.b")!!.contains("Не удалось перенести"))
        assertTrue(PhoneController(Reader(true), Input(true)).moveToBackground("Нет такого")!!.contains("не найдено"))
    }
}
