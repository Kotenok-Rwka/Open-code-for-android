package com.pocketcode.video.toon

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Batch 25: тёмная сцена «как в сериалах» и реалистичная структура тела.
 * - Кладка стен: смещённые ряды блоков со швами (как бетон в бункере).
 * - Луч фонаря: конус от точки к цели + пылинки, медленно плывущие в луче.
 * - Дозиметр-прибор: круг со шкалой, стрелка дрожит у опасной зоны.
 * - Тело: реалистичные пропорции (голова ~1/7 роста, суставы плечо-локоть-кисть,
 *   колено) — чистая геометрия для ToonDraw.
 */

object ToonDark {

    /** Кладка: ряды блоков со смещением на полблока. Возвращает [x, y, w, h] блоков. */
    fun bricks(w: Int, wallBottom: Float, rows: Int = 9, rowH: Float = 92f, bw: Float = 190f): List<FloatArray> {
        if (rows <= 0 || rowH <= 0f || bw <= 0f) return emptyList()
        val out = ArrayList<FloatArray>()
        for (r in 0 until rows) {
            val y = wallBottom - (r + 1) * rowH
            if (y < 0f) break
            val shift = if (r % 2 == 1) bw / 2 else 0f
            var x = -bw / 2 + shift
            while (x < w) {
                out += floatArrayOf(x, y, bw - 6f, rowH - 6f) // 6px — шов
                x += bw
            }
        }
        return out
    }

    /** Конус луча фонаря: [apexX, apexY, targetX, targetY, halfAngleDeg, length]. */
    fun beam(apexX: Float, apexY: Float, targetX: Float, targetY: Float, halfAngleDeg: Float = 13f, length: Float = 900f): FloatArray {
        val dx = targetX - apexX
        val dy = targetY - apexY
        val d = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val ux = dx / d
        val uy = dy / d
        return floatArrayOf(apexX, apexY, apexX + ux * length, apexY + uy * length, halfAngleDeg, length)
    }

    /** Пылинки в луче: детерминированный дрейф по времени t, точки внутри конуса. */
    fun dust(beam: FloatArray, t: Double, count: Int = 26, seed: Long = 7L): List<Pair<Float, Float>> {
        val ax = beam[0]; val ay = beam[1]
        val half = Math.toRadians(beam[4].toDouble())
        val len = beam[5]
        val out = ArrayList<Pair<Float, Float>>()
        var s = seed
        fun rnd(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s shr 33).toDouble() / (1L shl 31).toDouble()).coerceIn(0.0, 1.0)
        }
        var i = 0
        while (out.size < count) {
            val r1 = rnd(); val r2 = rnd(); val r3 = rnd()
            val d = (0.25 + 0.75 * r1) * len
            val ang = (r2 - 0.5) * 2 * half
            val drift = sin(t * 0.7 + i) * 6.0
            val rise = (t * (14.0 + 18.0 * r3)) % 40.0
            val px = ax + cos(ang) * d + drift
            val py = ay + sin(ang) * d - rise
            out += px.toFloat() to py.toFloat()
            i++
        }
        return out
    }

    /** Циферблат прибора: точки делений шкалы (0..50) по дуге. */
    fun dialTicks(cx: Float, cy: Float, radius: Float, count: Int = 11): List<Pair<Float, Float>> {
        if (count < 2) return emptyList()
        return (0 until count).map { i ->
            val a = Math.toRadians(200.0 - 220.0 * i / (count - 1)) // от 200° до -20°
            (cx + cos(a).toFloat() * radius * 0.78f) to (cy - sin(a).toFloat() * radius * 0.78f)
        }
    }

    /** Угол стрелки (градусы) по значению 0..1 с лёгким дрожанием прибора. */
    fun needleAngle(t: Double, value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        val jitter = (sin(t * 9.0) * 1.5 + sin(t * 3.7) * 1.0).toFloat()
        return 200f - 220f * v + jitter
    }

    /** Опасная зона шкалы (последние 25%): [от угла, до угла]. */
    fun dangerArc(): Pair<Float, Float> = 145f to -20f}

/** Реалистичные пропорции тела: все размеры — доли роста героя. */
object ToonBody {
    const val HEAD: Float = 0.14285715f      // голова ~1/7 роста (реалистично, не чиби)
    const val CHIN: Float = 0.85714287f
    const val SHOULDER: Float = 0.815f
    const val WAIST: Float = 0.60f
    const val HIP: Float = 0.52f
    const val KNEE: Float = 0.27f

    const val SHOULDER_W: Float = 0.21f      // ширина плеч в долях роста
    const val HIP_W: Float = 0.17f

    const val UPPER_ARM: Float = 0.145f      // плечо→локоть
    const val FOREARM: Float = 0.13f         // локоть→кисть
    const val THIGH: Float = 0.25f           // бедро→колено
    const val SHIN: Float = 0.23f            // колено→стопа

    /**
     * Точки скелета стоящего героя: [головаY, шеяY, плечиY, талияY, бёдраY, коленоY, макушкаY] —
     * экранные координаты (y вниз).
     */
    fun skeletonStanding(cx: Float, groundY: Float, h: Float): FloatArray = floatArrayOf(
        groundY - h * (1f - HEAD / 2),
        groundY - h * CHIN,
        groundY - h * SHOULDER,
        groundY - h * WAIST,
        groundY - h * HIP,
        groundY - h * KNEE,
        groundY - h,
    )

    /** Кисть руки при углах плеча/локтя: [локотьX, локотьY, кистьX, кистьY] от плеча. */
    fun handPoint(shoulderX: Float, shoulderY: Float, h: Float, sideSign: Float, shoulderDeg: Float, elbowDeg: Float): FloatArray {
        val upper = h * UPPER_ARM
        val fore = h * FOREARM
        val a1 = Math.toRadians((180f + shoulderDeg * sideSign).toDouble())
        val ex = shoulderX + sin(a1).toFloat() * upper * sideSign
        val ey = shoulderY + cos(a1).toFloat() * upper
        val a2 = a1 + Math.toRadians((elbowDeg * sideSign).toDouble())
        val hx = ex + sin(a2).toFloat() * fore * sideSign
        val hy = ey + cos(a2).toFloat() * fore
        return floatArrayOf(ex, ey, hx, hy)
    }

    /** Сидящий герой: бёдра на сиденье, колени согнуты. Точки как в standing. */
    fun skeletonSitting(cx: Float, seatY: Float, h: Float): FloatArray {
        val headTop = seatY - h * (1f - HIP)
        val headC = headTop + h * HEAD / 2
        val neck = headTop + h * HEAD + h * 0.03f
        val sh = neck + h * 0.05f
        return floatArrayOf(headC, neck, sh, seatY, seatY, seatY + h * SHIN, headTop)
    }
}
