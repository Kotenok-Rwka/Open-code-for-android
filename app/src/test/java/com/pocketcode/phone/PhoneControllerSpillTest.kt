// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Защита от «всплытия» приложений на основной экран: слежение до действия и возврат задачи на фоновый экран после него. */
class PhoneControllerSpillTest {
    private class Reader : PhoneScreenReader {
        override var available: Boolean = true
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

    private class Input(
        override var connected: Boolean = true,
        val top: Int = 3,
        val arrivals: List<MainScreenSpill.Arrival> = emptyList(),
        val moveOk: Boolean = true,
    ) : PhoneInput {
        val log = ArrayList<String>()
        override suspend fun tap(displayId: Int, x: Int, y: Int) = true
        override suspend fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = true
        override suspend fun key(displayId: Int, keyCode: Int) = true
        override suspend fun typeAscii(displayId: Int, text: String) = true
        override suspend fun pasteText(displayId: Int, text: String) = true
        override suspend fun launchApp(displayId: Int, pkg: String) = true.also { log += "launch$displayId:$pkg" }
        override suspend fun openUrl(displayId: Int, url: String) = true
        override suspend fun listApps(): List<AppInfo> = emptyList()
        override suspend fun createHiddenDisplay(width: Int, height: Int, dpi: Int) = 5
        override suspend fun releaseHiddenDisplay() {}
        override suspend fun screencap(maxSide: Int): ByteArray? = null
        override suspend fun hiddenScreenshot(maxSide: Int): ByteArray? = null
        override suspend fun shell(command: String) = ShellResult(0, "")
        override suspend fun enableAccessibility() = true
        override suspend fun watchMainScreen(windowMs: Int): Int = top.also { log += "watch$windowMs" }
        override suspend fun mainArrivals(sinceUptimeMs: Long): List<MainScreenSpill.Arrival> = arrivals.also { log += "arrivals$sinceUptimeMs" }
        override suspend fun moveTaskToHidden(taskId: Int, displayId: Int) = moveOk.also { log += "move$taskId->$displayId" }
    }

    private val start = 1_000L

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = start + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, now: Int = MainScreenSpill.MAIN,
    ) = MainScreenSpill.Arrival(task, pkg, 0, at, from, sameTask, true, now)

    private fun controller(
        input: Input,
        taps: List<MainScreenSpill.Tap> = emptyList(),
        excluded: Set<String> = setOf("com.pocketcode", "com.home"),
    ) = PhoneController(Reader(), input, userTaps = { taps }, spillExcluded = { excluded }, homePackages = { setOf("com.home") }, clock = { start })

    @Test fun appOpenedOnTheMainScreenIsMovedToTheBackgroundDisplay() = runBlocking {
        val inp = Input(arrivals = listOf(arrival()))
        val c = controller(inp)
        assertEquals(5, c.openBackground(1080, 1920, 420))
        c.beginAction()
        val note = c.endAction()
        assertNotNull(note)
        assertTrue(note!!.contains("com.chat") && note.contains("перенесено"))
        assertEquals(listOf("watch6000", "arrivals$start", "move7->5"), inp.log)
    }

    @Test fun nothingIsWatchedOnTheMainScreen() = runBlocking {
        val inp = Input(arrivals = listOf(arrival()))
        val c = controller(inp)
        c.beginAction()
        assertNull(c.endAction())
        assertTrue(inp.log.isEmpty())
    }

    @Test fun noWatchWithoutHelper() = runBlocking {
        val inp = Input(connected = false)
        val c = controller(inp)
        c.beginAction()
        assertNull(c.endAction())
        assertTrue(inp.log.isEmpty())
    }

    @Test fun userTapMeansTheUserOpenedTheApp() = runBlocking {
        val inp = Input(arrivals = listOf(arrival(at = start + 900)))
        val c = controller(inp, taps = listOf(MainScreenSpill.Tap(start + 400, "com.other")))
        c.openBackground(1080, 1920, 420)
        c.beginAction()
        assertNull(c.endAction())
        assertTrue(inp.log.none { it.startsWith("move") })
    }

    @Test fun ownAppAndLauncherAreNeverMoved() = runBlocking {
        val inp = Input(arrivals = listOf(arrival(pkg = "com.pocketcode"), arrival(task = 8, pkg = "com.home")))
        val c = controller(inp)
        c.openBackground(1080, 1920, 420)
        c.beginAction()
        assertNull(c.endAction())
        assertTrue(inp.log.none { it.startsWith("move") })
    }

    @Test fun appTheUserIsUsingIsReportedNotMoved() = runBlocking {
        val inp = Input(top = 7, arrivals = listOf(arrival(task = 7, sameTask = true, from = MainScreenSpill.MAIN)))
        val c = controller(inp)
        c.openBackground(1080, 1920, 420)
        c.beginAction()
        val note = c.endAction()
        assertTrue(note!!.contains("не трогаю"))
        assertTrue(inp.log.none { it.startsWith("move") })
    }

    @Test fun failedMoveIsReported() = runBlocking {
        val inp = Input(arrivals = listOf(arrival()), moveOk = false)
        val c = controller(inp)
        c.openBackground(1080, 1920, 420)
        c.beginAction()
        assertTrue(c.endAction()!!.contains("не удалось"))
    }

    @Test fun launchInBackgroundArmsTheWatch() = runBlocking {
        val inp = Input(arrivals = listOf(arrival()))
        val c = controller(inp)
        // инструмент вызывает beginAction ещё на основном экране, фоновый включается уже внутри launch
        c.beginAction()
        assertNull(c.launch("com.chat", background = true))
        assertNotNull(c.endAction())
        assertEquals(listOf("watch6000", "launch5:com.chat", "arrivals$start", "move7->5"), inp.log)
    }

    @Test fun oldHelperWithoutSupportDoesNothing() = runBlocking {
        val inp = Input(top = -1, arrivals = emptyList())
        val c = controller(inp)
        c.openBackground(1080, 1920, 420)
        c.beginAction()
        assertNull(c.endAction())
    }
}
