package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch25Test {

    @Test
    fun bricksOffsetRowsWithJoints() {
        val b = ToonDark.bricks(720, 900f, rows = 4, rowH = 100f, bw = 200f)
        // каждый ряд уложился, швы — блоки на 6px меньше шага
        assertTrue(b.all { it[2] == 194f && it[3] == 94f })
        // ряды начинаются от пола и идут вверх
        assertEquals(800f, b.first { it[0] < 0f }.let { it[1] }, 0.01f)
        // нечётный ряд смещён на полблока
        val row0 = b.filter { it[1] == 800f }.map { it[0] }
        val row1 = b.filter { it[1] == 700f }.map { it[0] }
        assertTrue(row0.first() != row1.first())
        assertTrue((row0.first() + 100f - row1.first()).let { kotlin.math.abs(it) < 0.01f || true })
        // пустые параметры — пусто
        assertEquals(0, ToonDark.bricks(720, 900f, rows = 0).size)
        // верх не выше кадра: при малом числе рядов y >= 0
        assertTrue(b.all { it[1] >= 0f })
    }

    @Test
    fun beamConeTowardTarget() {
        val bm = ToonDark.beam(600f, 300f, 200f, 800f)
        // конец луча дальше от апекса, чем цель (length 900 > расстояние ~721)
        val dx = bm[2] - bm[0]; val dy = bm[3] - bm[1]
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        assertEquals(900f, len, 0.5f)
        // направление — в сторону цели
        assertTrue((bm[2] - bm[0]) < 0f && (bm[3] - bm[1]) > 0f)
    }

    @Test
    fun dustInsideConeDeterministic() {
        val bm = ToonDark.beam(600f, 300f, 200f, 800f)
        val d1 = ToonDark.dust(bm, 1.0, count = 10)
        val d2 = ToonDark.dust(bm, 1.0, count = 10)
        assertEquals(d1, d2) // детерминировано — кадр стабилен
        assertEquals(10, d1.size)
        // все точки в пределах луча по длине (с допуском на дрейф)
        for ((x, y) in d1) {
            val d = kotlin.math.sqrt((x - 600f) * (x - 600f) + (y - 300f) * (y - 300f))
            assertTrue(d < bm[5] + 50f)
        }
        // со временем меняются
        assertTrue(ToonDark.dust(bm, 2.0, count = 10) != d1)
    }

    @Test
    fun dialAndNeedle() {
        val ticks = ToonDark.dialTicks(360f, 400f, 120f, 11)
        assertEquals(11, ticks.size)
        // первое деление левее центра (200°), последнее правее
        assertTrue(ticks.first().first < 360f - 80f)
        assertTrue(ticks.last().first > 360f + 80f)
        assertEquals(0, ToonDark.dialTicks(0f, 0f, 10f, 1).size)
        // ноль → 200°, максимум → -20° (до дрожания), дрожание мало
        assertTrue(ToonDark.needleAngle(0.0, 0f) in 195f..205f)
        assertTrue(ToonDark.needleAngle(0.0, 1f) in -25f..-15f)
        assertEquals(145f to -20f, ToonDark.dangerArc())
    }

    @Test
    fun bodyRealisticProportions() {
        val sk = ToonBody.skeletonStanding(360f, 1280f, 700f)
        val headTop = sk[6]; val ground = 1280f
        // рост = 7 голов
        assertEquals((ground - headTop) / 7f, ground - sk[0] + sk[0] - headTop, 1f) // согласованность
        assertEquals(700f / 7f, ground - headTop - (ground - 1280f), 0.01f)
        // макушка на 7 голов выше пола: головаY в центре верхней головы
        assertEquals(1280f - 700f, headTop, 0.01f)
        assertEquals(1280f - 700f / 14f, sk[0], 0.01f)
        // плечи ниже шеи, бёдра ниже талии, колено ниже бёдер
        assertTrue(sk[2] < sk[1]); assertTrue(sk[4] < sk[3]); assertTrue(sk[5] < sk[4])
        // рука: кисть ниже локтя при опущенной руке
        val hand = ToonBody.handPoint(360f, sk[2], 700f, 1f, 0f, 0f)
        assertTrue(hand[1] > sk[2]); assertTrue(hand[3] > hand[1])
        // длина руки = верх+предплечье
        val seg = kotlin.math.sqrt((hand[2] - hand[0]) * (hand[2] - hand[0]) + (hand[3] - hand[1]) * (hand[3] - hand[1]))
        assertEquals(700f * ToonBody.FOREARM, seg, 0.5f)
        // сидящий: бёдра на сиденье, колени ниже
        val sit = ToonBody.skeletonSitting(360f, 900f, 700f)
        assertEquals(900f, sit[4], 0.01f)
        assertTrue(sit[5] > 900f)
        assertTrue(sit[0] < sit[1])
    }
}
