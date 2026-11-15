// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Запуск приложения по умолчанию идёт на фоновый экран, если помощник есть. */
class PhoneControllerLaunchTest {
    private class Reader : PhoneScreenReader {
        override val available = true
        override suspend fun snapshot(displayId: Int): ScreenSnapshot? = null
        override suspend fun screenshot(displayId: Int, maxSide: Int): ByteArray? = null
        override suspend fun clickNode(id: Int) = true
        override suspend fun longClickNode(id: Int) = true
        override suspend fun setNodeText(id: Int, text: String) = true
        override suspend fun scrollNode(id: Int, forward: Boolean) = true
        override suspend fun global(action: PhoneGlobal) = true
        override suspend fun gestureTap(x: Int, y: Int, durationMs: Long) = true
        override suspend fun gestureSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
    }

    private class Input(override val connected: Boolean, val display: Int) : PhoneInput {
        val launches = ArrayList<String>()
        override suspend fun tap(displayId: Int, x: Int, y: Int) = true
        override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
        override suspend fun key(displayId: Int, keyCode: Int) = true
        override suspend fun typeAscii(displayId: Int, text: String) = true
        override suspend fun pasteText(displayId: Int, text: String) = true
        override suspend fun launchApp(displayId: Int, pkg: String) = true.also { launches += "$displayId:$pkg" }
        override suspend fun openUrl(displayId: Int, url: String) = true
        override suspend fun listApps(): List<AppInfo> = emptyList()
        override suspend fun createHiddenDisplay(width: Int, height: Int, dpi: Int) = display
        override suspend fun releaseHiddenDisplay() {}
        override suspend fun screencap(maxSide: Int): ByteArray? = null
        override suspend fun hiddenScreenshot(maxSide: Int): ByteArray? = null
        override suspend fun shell(command: String) = ShellResult(0, "")
        override suspend fun enableAccessibility() = true
    }

    @Test fun withHelperTheAppOpensOnTheBackgroundDisplay() = runBlocking {
        val inp = Input(true, 5)
        val c = PhoneController(Reader(), inp)
        assertNull(c.launchAuto("org.exteragram.messenger"))
        assertEquals(5, c.displayId)
        assertEquals(listOf("5:org.exteragram.messenger"), inp.launches)
    }

    @Test fun backgroundIsNotRecreatedOnTheNextLaunch() = runBlocking {
        val inp = Input(true, 5)
        val c = PhoneController(Reader(), inp)
        c.launchAuto("com.a.b")
        c.launchAuto("com.c.d")
        assertEquals(listOf("5:com.a.b", "5:com.c.d"), inp.launches)
    }

    @Test fun whenTheDisplayCannotBeCreatedTheAppOpensOnTheMainScreen() = runBlocking {
        val inp = Input(true, -1)
        val c = PhoneController(Reader(), inp)
        assertNull(c.launchAuto("com.a.b"))
        assertEquals(0, c.displayId)
        assertEquals(listOf("0:com.a.b"), inp.launches)
    }

    @Test fun explicitMainScreenLaunchStaysOnTheMainScreen() = runBlocking {
        val inp = Input(true, 5)
        val c = PhoneController(Reader(), inp)
        assertNull(c.launch("com.a.b", false))
        assertEquals(listOf("0:com.a.b"), inp.launches)
    }

    @Test fun withoutHelperNothingIsCreated() = runBlocking {
        val inp = Input(false, 5)
        val c = PhoneController(Reader(), inp, localLaunch = { true })
        assertNull(c.launchAuto("com.a.b"))
        assertEquals(0, c.displayId)
        assertEquals(emptyList<String>(), inp.launches)
    }
}
