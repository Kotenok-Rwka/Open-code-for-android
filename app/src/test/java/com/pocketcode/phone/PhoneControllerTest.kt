// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControllerTest {
    private fun node(id: Int, text: String = "", editable: Boolean = false, focused: Boolean = false, password: Boolean = false) =
        UiNode(id, 0, "TextView", text, "", "", PRect(0, 0, 10, 10), true, false, false, editable, null, focused, true, password)

    private class FakeReader(var snap: ScreenSnapshot?, override var available: Boolean = true) : PhoneScreenReader {
        val log = ArrayList<String>()
        override suspend fun snapshot(displayId: Int) = snap
        override suspend fun screenshot(displayId: Int, maxSide: Int): ByteArray? = null
        override suspend fun clickNode(id: Int) = true.also { log += "click$id" }
        override suspend fun longClickNode(id: Int) = true
        override suspend fun setNodeText(id: Int, text: String) = true.also { log += "set$id=$text" }
        override suspend fun scrollNode(id: Int, forward: Boolean) = true
        override suspend fun global(action: PhoneGlobal) = true.also { log += "global$action" }
        override suspend fun gestureTap(x: Int, y: Int, durationMs: Long) = true.also { log += "gtap$x,$y" }
        override suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true.also { log += "gswipe" }
    }

    private class FakeInput(override var connected: Boolean, val apps: List<AppInfo> = emptyList()) : PhoneInput {
        val log = ArrayList<String>()
        override suspend fun tap(displayId: Int, x: Int, y: Int) = true.also { log += "tap$displayId:$x,$y" }
        override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true.also { log += "swipe" }
        override suspend fun key(displayId: Int, keyCode: Int) = true.also { log += "key$keyCode" }
        override suspend fun typeAscii(displayId: Int, text: String) = true.also { log += "ascii:$text" }
        override suspend fun pasteText(displayId: Int, text: String) = true.also { log += "paste:$text" }
        override suspend fun launchApp(displayId: Int, pkg: String) = true.also { log += "launch$displayId:$pkg" }
        override suspend fun openUrl(displayId: Int, url: String) = true
        override suspend fun listApps() = apps
        override suspend fun createHiddenDisplay(width: Int, height: Int, dpi: Int) = 5
        override suspend fun releaseHiddenDisplay() { log += "release" }
        override suspend fun screencap(maxSide: Int): ByteArray? = null
        override suspend fun hiddenScreenshot(maxSide: Int): ByteArray? = null
        override suspend fun shell(command: String) = ShellResult(0, "")
        override suspend fun enableAccessibility() = true
    }

    private fun snap(vararg n: UiNode) = ScreenSnapshot(0, "com.x", "Main", 1080, 2400, n.toList(), false)

    @Test fun tapUsesHelperWhenConnected() = runBlocking {
        val inp = FakeInput(true); val rd = FakeReader(null)
        assertTrue(PhoneController(rd, inp).tap(5, 6))
        assertEquals(listOf("tap0:5,6"), inp.log)
        assertTrue(rd.log.isEmpty())
    }

    @Test fun tapFallsBackToGesture() = runBlocking {
        val inp = FakeInput(false); val rd = FakeReader(null)
        assertTrue(PhoneController(rd, inp).tap(5, 6))
        assertEquals(listOf("gtap5,6"), rd.log)
    }

    @Test fun passwordFieldIsRefused() = runBlocking {
        val rd = FakeReader(snap(node(1, editable = true, password = true)))
        val c = PhoneController(rd, FakeInput(true))
        c.look(withImage = false)
        assertNotNull(c.typeText("secret", 1))
        assertTrue(rd.log.isEmpty())
    }

    @Test fun focusedPasswordFieldIsRefusedWithoutNode() = runBlocking {
        val rd = FakeReader(snap(node(1, editable = true, focused = true, password = true)))
        val inp = FakeInput(true)
        val c = PhoneController(rd, inp)
        c.look(withImage = false)
        assertNotNull(c.typeText("x", null))
        assertTrue(inp.log.isEmpty())
    }

    @Test fun typeChoosesAsciiOrPaste() = runBlocking {
        val inp = FakeInput(true)
        val c = PhoneController(FakeReader(null), inp)
        assertNull(c.typeText("abc 1", null))
        assertNull(c.typeText("привет", null))
        assertEquals(listOf("ascii:abc 1", "paste:привет"), inp.log)
    }

    @Test fun typeWithoutHelperUsesFocusedNode() = runBlocking {
        val rd = FakeReader(snap(node(3, editable = true, focused = true)))
        val c = PhoneController(rd, FakeInput(false))
        c.look(withImage = false)
        assertNull(c.typeText("hi", null))
        assertEquals(listOf("set3=hi"), rd.log)
    }

    @Test fun unknownNodeRejected() = runBlocking {
        val c = PhoneController(FakeReader(snap(node(1))), FakeInput(true))
        c.look(withImage = false)
        assertNotNull(c.typeText("a", 9))
    }

    @Test fun keys() = runBlocking {
        val inp = FakeInput(true); val rd = FakeReader(null)
        val c = PhoneController(rd, inp)
        assertNull(c.key("back"))
        assertNull(c.key("notifications"))
        assertNotNull(c.key("power"))
        assertNotNull(c.key("nonsense"))
        assertEquals(listOf("key4"), inp.log)
        assertEquals(listOf("globalNOTIFICATIONS"), rd.log)
    }

    @Test fun launchResolvesLabelAndBackgroundDisplay() = runBlocking {
        val apps = listOf(AppInfo("com.a.settings", "Настройки", true), AppInfo("com.b.chat", "Chat", false))
        val inp = FakeInput(true, apps)
        val c = PhoneController(FakeReader(null), inp)
        assertNull(c.launch("настройки"))
        assertEquals(5, c.openBackground(1080, 1920, 420))
        assertNull(c.launch("com.b.chat"))
        assertNotNull(c.launch("несуществующее"))
        c.closeBackground()
        assertEquals(0, c.displayId)
        assertEquals(listOf("launch0:com.a.settings", "launch5:com.b.chat", "release"), inp.log)
    }

    @Test fun backgroundNeedsHelper() = runBlocking {
        assertEquals(-1, PhoneController(FakeReader(null), FakeInput(false)).openBackground(1, 1, 1))
    }

    @Test fun matchAppPrefersUserApps() {
        val apps = listOf(AppInfo("sys.cam", "Камера", true), AppInfo("my.cam", "Камера Pro", false))
        assertEquals("sys.cam", PhoneController.matchApp(apps, "камера")?.pkg)
        assertEquals("my.cam", PhoneController.matchApp(apps, "pro")?.pkg)
        assertNull(PhoneController.matchApp(apps, "карта"))
    }

    @Test fun lookTextWithoutService() = runBlocking {
        val r = PhoneController(FakeReader(null, available = false), FakeInput(false)).look(withImage = false)
        assertTrue(r.text.contains("Служба доступности выключена"))
        assertFalse(PhoneController(FakeReader(null, available = false), FakeInput(false)).ready)
    }
}
