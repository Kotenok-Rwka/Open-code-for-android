// Основано на тестах Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import com.pocketcode.phone.MainScreenSpill.InUse
import com.pocketcode.phone.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val launchers = setOf("com.home")
    private val excluded = launchers + "com.pocketcode"

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = since + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, standard: Boolean = true, now: Int = MainScreenSpill.MAIN, user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(vararg a: MainScreenSpill.Arrival, topBefore: Int = 3, taps: List<MainScreenSpill.Tap> = emptyList()) =
        MainScreenSpill.decide(a.toList(), since, window, topBefore, excluded, taps, launchers)

    @Test fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test fun backgroundTaskBroughtToFrontIsPulledBackToo() {
        // система подняла уже существующую фоновую задачу приложения, новую не создавала
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test fun launcherOwnAppAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "com.pocketcode")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test fun taskMovedFromAnotherDisplayIsNotASpill() {
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        val other = MainScreenSpill.Tap(since + 400, "com.other")
        assertTrue(decide(arrival(at = since + 900), taps = listOf(other)).isEmpty())
        // нажатие до начала действия не считается
        assertEquals(1, decide(arrival(at = since + 900), taps = listOf(MainScreenSpill.Tap(since - 100, "com.other"))).size)
    }

    @Test fun returningHomeBeforeOpeningAnAppMeansTheUserOpenedIt() {
        val desktop = arrival(task = 2, pkg = "com.home", at = since + 200, from = MainScreenSpill.MAIN)
        val opened = arrival(at = since + 900)
        assertTrue(decide(desktop, opened).isEmpty())
        // страница самого PocketCode — не рабочий стол: настоящее всплытие агента не принимаем за действие пользователя
        val own = arrival(task = 2, pkg = "com.pocketcode", at = since + 200, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(opened)), decide(own, opened))
    }

    @Test fun userTappingThePopupItselfLeavesItThere() {
        val a = arrival(at = since + 900)
        assertEquals(listOf(InUse(a)), decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.chat"))))
        // нажатие в окне другого приложения: неясно, переносим как обычно
        assertEquals(1, decide(a, taps = listOf(MainScreenSpill.Tap(since + 1_500, "com.other"))).size)
    }

    @Test fun previousTopComingBackIsNotASpill() {
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test fun oneVerdictPerTaskInOrder() {
        val other = arrival(task = 8, pkg = "com.mail", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(other), PullBack(again)), decide(again, first, other))
    }
}
