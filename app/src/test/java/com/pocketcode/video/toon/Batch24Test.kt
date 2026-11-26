package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch24Test {

    @Test
    fun eyeOpenSmoothAndFullRange() {
        // между морганиями глаз полностью открыт
        assertEquals(1f, ToonAnim.eyeOpen(1.0), 0.001f)
        // в середине закрытия — почти закрыт, и значения непрерывны
        val mid = ToonAnim.eyeOpen(3.4 - 0.12)
        val before = ToonAnim.eyeOpen(3.4 - 0.18)
        val after = ToonAnim.eyeOpen(3.4 - 0.06)
        assertTrue(mid in 0f..1f)
        assertTrue(before > mid && mid < after) // минимум внутри фазы — гладко
        assertTrue(mid < 0.35f)
        // после фазы снова открыты (следующий цикл)
        assertEquals(1f, ToonAnim.eyeOpen(3.6), 0.001f)
        assertEquals(1f, ToonAnim.eyeOpen(1.0, 0.0))
    }

    @Test
    fun breathAndTiltSmoothBounded() {
        for (k in 0..40) {
            val t = k / 2.0
            val b = ToonAnim.breath(t, true, 0.5f)
            assertTrue(b in -4.5f..4.5f)
            val tilt = ToonAnim.headTilt(t, ToonMood.CALM)
            assertTrue(tilt in -3.5f..3.5f)
            // соседние значения близки — без рывков
            assertTrue(abs(ToonAnim.breath(t + 0.03, true, 0.5f) - b) < 1.5f)
        }
        assertTrue(ToonAnim.headTilt(1.0, ToonMood.WOW) != 0f || ToonAnim.headTilt(1.3, ToonMood.WOW) != 0f)
    }

    @Test
    fun armsGestureAndHoldingCup() {
        val idle = ToonAnim.arms(2.0, false, 0f, false)
        val talk = ToonAnim.arms(2.0, true, 0.8f, false)
        // говорящий машет сильнее, чем спокоен слушатель
        assertTrue(abs(talk[0]) > abs(idle[0]) || abs(talk[2]) > abs(idle[2]))
        // держащий: правое плечо поднято (отрицательный угол), локоть сильно согнут
        val hold = ToonAnim.arms(2.0, false, 0f, true)
        assertTrue(hold[2] < -80f)
        assertTrue(hold[3] > 80f)
        val pose = ToonAnim.pose(1.0, true, 0.6f, ToonMood.CALM, true)
        assertTrue(pose.holdsProp)
        assertTrue(pose.eyeOpen in 0f..1f)
        assertEquals(4, pose.arms.size)
        assertNotEquals(ToonPose(1f, 0f, 0f, floatArrayOf(0f, 0f, 0f, 0f), false), pose)
    }

    @Test
    fun propsParsePlacementAndSlots() {
        val props = ToonProps.parse("КОМНАТА: стол слева, окно по центру, картина над окном, чашка справа")
        // «над окном» — окно не совпадает ни с одним суффиксом → слово неизвестно, пропущено;
        // картина с «над» — на стене
        assertTrue(props.any { it.kind == ToonProp.Kind.TABLE && it.x == 0.16f })
        assertTrue(props.any { it.kind == ToonProp.Kind.WINDOW && it.x == 0.5f })
        assertTrue(props.any { it.kind == ToonProp.Kind.PICTURE && it.y == 0.24f })
        assertTrue(props.any { it.kind == ToonProp.Kind.CUP && it.x == 0.84f })
        assertEquals(0.62f, props.first().y, 0.001f)
        // своя картинка
        val mine = ToonProps.parse("КОМНАТА: моё:мама.png справа")
        assertEquals(1, mine.size)
        assertEquals("мама.png", mine[0].path)
        assertEquals(ToonProp.Kind.CUSTOM, mine[0].kind)
        assertEquals(0, ToonProps.parse("КОМНАТА:").size)
        assertEquals(0, ToonProps.parse("КОМНАТА: абракадабра слева").size)
    }

    @Test
    fun propsFromScriptLines() {
        val script = "Аня: привет\nКОМНАТА: стол слева, растение справа\nБоря: пока\nРЕКВИЗИТ: чашка в центре"
        val props = ToonProps.fromScript(script)
        assertEquals(3, props.size)
        assertTrue(ToonProps.hasCup(props))
        // без чашки герой не держит
        val noCup = ToonProps.fromScript("КОМНАТА: стол слева")
        assertEquals(false, ToonProps.hasCup(noCup))
    }
}
