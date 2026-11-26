package com.pocketcode.video.toon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.sin

/** Что нужно знать, чтобы нарисовать один кадр. */
class ToonFrame(
    val t: Double,
    val scene: ToonScene,
    val title: String,
    val heroes: List<String>,
    /** Кто сейчас говорит: имя героя, null — рассказчик или тишина. */
    val speaker: String?,
    val mouthOpen: Float,
    val mouthWidth: Float,
    val subtitle: String,
    val choice: ToonChoice?,
    /** 0..1 — как давно показан выбор (для плавного появления), −1 — выбора нет. */
    val choiceAnim: Float,
    val cta: String,
    val looks: List<ToonLook> = emptyList(),
    val mood: Mood = Mood.CALM,
)

/** Рисование плоских вырезных героев и фонов в духе вертикальных мультиков. Размер кадра 720×1280 (9:16). */
class ToonDraw(val w: Int = 720, val h: Int = 1280) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val paintBmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    /** Batch23: детализированный рендер — узор обоев, шторы, тени, свет, виньетка. */
    var detailed: Boolean = true
    /** Палитры сцен (можно доработать и сохранить через ToonRichLib). */
    var palettes: List<ToonRich> = ToonRichLib.defaults()
    /** Batch24: реквизит сцены (стол, окно, чашка, свои PNG). */
    var props: List<ToonProp> = emptyList()
    /** Говорящий держит чашку у рта. */
    var holdsCup: Boolean = false
    /** Batch26: тонировка времени суток и погода. */
    var sceneFx: ToonSceneFx = ToonSceneFx()
    private val propBitmaps = HashMap<String, android.graphics.Bitmap?>()

    fun draw(c: Canvas, f: ToonFrame) {
        background(c, f.scene, f.t)
        if (detailed) richDetails(c, f.scene, f.t)
        drawProps(c)
        val n = f.heroes.size.coerceAtMost(4)
        for (i in 0 until n) {
            val cx = w * (i + 1f) / (n + 1f)
            val talking = f.heroes[i] == f.speaker
            val look = f.looks.getOrNull(i) ?: ToonLooks.of(f.heroes[i], i)
            val sc = (if (n >= 3) 0.8f else 1f) * look.scale
            if (detailed) heroShadow(c, cx, sc)
            hero(c, look, cx, h * 0.58f + (1f - look.scale) * 200f, sc, f.t + i * 0.7, talking, if (talking) f.mouthOpen else 0f, if (talking) f.mouthWidth else 0f, if (talking) f.mood else Mood.CALM, talking && holdsCup)
        }
        if (f.title.isNotEmpty() && f.t < 2.2) title(c, f.title, f.t)
        if (f.subtitle.isNotEmpty() && f.choiceAnim < 0f) subtitle(c, f.subtitle)
        if (f.choice != null && f.choiceAnim >= 0f) choice(c, f.choice, f.choiceAnim, f.cta)
        if (detailed) vignette(c)
        sceneWeather(c, f.t)
    }

    // ---------- Batch23: детали богатого фона ----------

    private fun richOf(s: ToonScene): ToonRich? = palettes.firstOrNull { it.scene == s }

    private fun richDetails(c: Canvas, s: ToonScene, t: Double) {
        val p = richOf(s) ?: return
        val floorY = h * 0.74f
        // тёмная сцена: кладка стен + луч фонаря + дозиметр (Batch25)
        if (s == ToonScene.BUNKER) {
            fill.color = 0x66000000.toInt()
            for (b in ToonDark.bricks(w, floorY + 40f)) {
                fill.color = 0x3D000000.toInt()
                c.drawRoundRect(RectF(b[0], b[1], b[0] + b[2], b[1] + b[3]), 4f, 4f, fill)
            }
            // луч фонаря от героя справа к центру, с плывущей пылью
            val bm = ToonDark.beam(w * 0.82f, h * 0.42f, w * 0.38f, h * 0.62f)
            fill.shader = LinearGradient(bm[0], bm[1], bm[2], bm[3],
                0x66FFF0A8, 0x00000000, Shader.TileMode.CLAMP)
            path.reset()
            path.moveTo(bm[0], bm[1])
            val ang = Math.toRadians(bm[4].toDouble())
            val dx = bm[2] - bm[0]; val dy = bm[3] - bm[1]
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            val perpX = -dy / d; val perpY = dx / d
            val spread = (Math.tan(ang) * d).toFloat()
            path.lineTo(bm[2] + perpX * spread, bm[3] + perpY * spread)
            path.lineTo(bm[2] - perpX * spread, bm[3] - perpY * spread)
            path.close()
            c.drawPath(path, fill)
            fill.shader = null
            fill.color = 0x88FFF0A8.toInt()
            for ((dustX, dustY) in ToonDark.dust(bm, t)) c.drawCircle(dustX, dustY, 3f, fill)
            // дозиметр в руке: циферблат со шкалой и дрожащей стрелкой
            val dcx = w * 0.80f; val dcy = h * 0.55f
            fill.color = 0xFFE9E4D8.toInt()
            c.drawCircle(dcx, dcy, 74f, fill)
            line.color = 0xFF22262E.toInt(); line.strokeWidth = 6f
            c.drawCircle(dcx, dcy, 74f, line)
            line.strokeWidth = 4f
            for ((tx, ty) in ToonDark.dialTicks(dcx, dcy, 62f)) c.drawLine(dcx, dcy, tx, ty, line)
            // опасная зона красной дугой
            line.color = 0xFFD23B2E.toInt(); line.strokeWidth = 8f
            c.drawArc(RectF(dcx - 62f, dcy - 62f, dcx + 62f, dcy + 62f), ToonDark.dangerArc().second, ToonDark.dangerArc().first - ToonDark.dangerArc().second, false, line)
            val na = Math.toRadians(ToonDark.needleAngle(t, 0.72f).toDouble())
            line.color = 0xFF22262E.toInt(); line.strokeWidth = 6f
            c.drawLine(dcx, dcy, dcx + (Math.cos(na) * 52f).toFloat(), dcy - (Math.sin(na) * 52f).toFloat(), line)
        }
        // узор обоев на стене
        if (p.wallpaper != 0 && s != ToonScene.BUNKER) {
            fill.color = p.wallpaper
            fill.alpha = 42
            for ((tx, ty) in ToonRichGeom.wallpaperTiles(w, h, 64)) {
                if (ty > floorY - 20f) continue
                path.reset()
                path.moveTo(tx, ty - 9f); path.lineTo(tx + 9f, ty); path.lineTo(tx, ty + 9f); path.lineTo(tx - 9f, ty)
                path.close()
                c.drawPath(path, fill)
            }
            fill.alpha = 255
        }
        // плинтус
        fill.color = p.baseboard
        c.drawRect(0f, floorY - 14f, w.toFloat(), floorY, fill)
        // картины и часы на стене (в «домашних» сценах, не в бункере)
        if ((s == ToonScene.ROOM || s == ToonScene.GAS) && s != ToonScene.BUNKER) {
            val fr = ToonRichGeom.pictureFrame(w, h, w * 0.22f, h * 0.26f, 1f)
            fill.color = p.accent
            c.drawRect(fr[0], fr[1], fr[2], fr[3], fill)
            fill.color = 0xFF3A2A1A.toInt()
            c.drawRect(fr[0] + 12f, fr[1] + 12f, fr[2] - 12f, fr[3] - 12f, fill)
            // часы с качающимся маятником
            val cx = w * 0.80f
            val cy = h * 0.20f
            fill.color = p.baseboard
            c.drawCircle(cx, cy, 46f, fill)
            line.color = 0xFF3A2A1A.toInt(); line.strokeWidth = 5f
            val ang = Math.toRadians(ToonRichGeom.sway(t).toDouble())
            c.drawLine(cx, cy, cx + (Math.sin(ang) * 34f).toFloat(), cy + (Math.cos(ang) * 34f).toFloat(), line)
        }
        // шторы по бокам
        val cw = w * 0.09f
        for (side in 0..1) {
            val edgeX = if (side == 0) 0f else w - cw
            fill.color = p.curtain
            c.drawRect(edgeX, 0f, edgeX + cw, h * 0.62f, fill)
            fill.color = p.curtainDark
            for (fx in ToonRichGeom.curtainFolds(edgeX, cw, 4)) {
                c.drawLine(fx, 0f, fx, h * 0.62f, line.apply { this.color = p.curtainDark; strokeWidth = 6f })
            }
        }
        // тёплый свет лампы сверху (в бункере вместо него — луч фонаря)
        if (p.lamp != 0 && s != ToonScene.BUNKER) {
            fill.shader = RadialGradient(w / 2f, 0f, ToonRichGeom.lampRadius(w, 0.8f),
                (p.lamp and 0xFFFFFF) or 0x33000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h * 0.7f, fill)
            fill.shader = null
        }
    }

    private fun heroShadow(c: Canvas, cx: Float, scale: Float) {
        val sh = ToonRichGeom.heroShadow(cx, h * 0.92f, scale)
        fill.color = 0x55000000.toInt()
        c.drawOval(RectF(sh[0] - sh[2], sh[1] - sh[3], sh[0] + sh[2], sh[1] + sh[3]), fill)
    }

    private fun vignette(c: Canvas) {
        fill.shader = RadialGradient(w / 2f, h / 2f, w * 0.95f,
            0x00000000, (ToonRichGeom.vignetteAlpha(w * 0.08f, h * 0.08f, w, h) shl 24), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        fill.shader = null
    }

    // ---------- фоны ----------

    private fun grad(c: Canvas, top: Int, bottom: Int, y0: Float, y1: Float) {
        fill.shader = LinearGradient(0f, y0, 0f, y1, top, bottom, Shader.TileMode.CLAMP)
        c.drawRect(0f, y0, w.toFloat(), y1, fill)
        fill.shader = null
    }

    private fun background(c: Canvas, s: ToonScene, t: Double) {
        val gy = h * 0.74f
        when (s) {
            ToonScene.NIGHT -> {
                grad(c, 0xFF0B1330.toInt(), 0xFF2B3D73.toInt(), 0f, gy)
                fill.color = 0xFFEAF1FF.toInt()
                c.drawRect(0f, gy, w.toFloat(), h.toFloat(), fill)
                fill.color = 0xFFFFF4C2.toInt()
                c.drawCircle(w * 0.8f, h * 0.12f, 52f, fill)
                fill.color = Color.WHITE
                for (i in 0 until 60) {
                    val x = (i * 97 % w).toFloat()
                    val y = (((i * 61) % h) + t * (40 + i % 5 * 18)).toFloat() % h
                    c.drawCircle(x + sin(t + i).toFloat() * 8f, y, (2 + i % 3).toFloat(), fill)
                }
            }
            ToonScene.SEA -> {
                grad(c, 0xFF7EC8F2.toInt(), 0xFFD8F1FF.toInt(), 0f, h * 0.45f)
                fill.color = 0xFFFFD84D.toInt()
                c.drawCircle(w * 0.2f, h * 0.14f, 60f, fill)
                fill.color = 0xFF2E86C1.toInt()
                c.drawRect(0f, h * 0.45f, w.toFloat(), h.toFloat(), fill)
                fill.color = 0xFF5DADE2.toInt()
                for (k in 0 until 6) {
                    path.reset()
                    val y0 = h * 0.47f + k * 46f
                    path.moveTo(0f, y0)
                    var x = 0f
                    while (x <= w) {
                        path.lineTo(x, y0 + sin(x / 60.0 + t * 1.6 + k).toFloat() * 8f)
                        x += 20f
                    }
                    path.lineTo(w.toFloat(), y0 + 30f)
                    path.lineTo(0f, y0 + 30f)
                    path.close()
                    c.drawPath(path, fill)
                }
                fill.color = 0xFF8D6E4B.toInt()
                c.drawRect(0f, h * 0.7f, w.toFloat(), h * 0.76f, fill)
                fill.color = 0xFF6B5237.toInt()
                for (i in 0..8) c.drawRect(i * 90f, h * 0.76f, i * 90f + 18f, h * 0.86f, fill)
            }
            ToonScene.ROOM -> {
                grad(c, 0xFFF3E3C8.toInt(), 0xFFE8CFA5.toInt(), 0f, h * 0.7f)
                fill.color = 0xFF9C6B3F.toInt()
                c.drawRect(0f, h * 0.7f, w.toFloat(), h.toFloat(), fill)
                fill.color = 0xFFBFE3F7.toInt()
                rect.set(w * 0.62f, h * 0.12f, w * 0.92f, h * 0.34f)
                c.drawRoundRect(rect, 12f, 12f, fill)
                line.color = 0xFF7A5230.toInt(); line.strokeWidth = 10f
                c.drawRoundRect(rect, 12f, 12f, line)
                c.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, line)
                c.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), line)
            }
            ToonScene.GAS -> {
                grad(c, 0xFF10182B.toInt(), 0xFF3B2F5A.toInt(), 0f, gy)
                fill.color = 0xFF23232C.toInt()
                c.drawRect(0f, gy, w.toFloat(), h.toFloat(), fill)
                fill.color = 0xFFF2D230.toInt()
                for (i in 0..5) c.drawRect(i * 130f + 20f, h * 0.9f, i * 130f + 90f, h * 0.9f + 12f, fill)
                // навес заправки и вывеска
                fill.color = 0xFFD9D9E0.toInt()
                c.drawRect(0f, h * 0.2f, w.toFloat(), h * 0.25f, fill)
                fill.color = 0xFFE5533D.toInt()
                c.drawRect(0f, h * 0.25f, w.toFloat(), h * 0.265f, fill)
                fill.color = 0xFF6B6B78.toInt()
                c.drawRect(60f, h * 0.265f, 84f, gy, fill)
                c.drawRect(w - 84f, h * 0.265f, w - 60f, gy, fill)
                val blinkOn = (t % 1.6) < 1.1
                fill.color = if (blinkOn) 0xFFFFE066.toInt() else 0xFF7A6B2A.toInt()
                rect.set(w * 0.3f, h * 0.06f, w * 0.7f, h * 0.15f)
                c.drawRoundRect(rect, 16f, 16f, fill)
                fill.color = 0xFF10182B.toInt()
                textFill.textSize = 52f
                c.drawText("МОТЕЛЬ", w / 2f, h * 0.122f, textFill.apply { color = 0xFF10182B.toInt() })
                textFill.color = Color.WHITE
            }
            ToonScene.TENT -> {
                grad(c, 0xFF0B1330.toInt(), 0xFF2B3D73.toInt(), 0f, gy)
                fill.color = 0xFFEAF1FF.toInt()
                c.drawRect(0f, gy, w.toFloat(), h.toFloat(), fill)
                fill.color = 0xFF16294D.toInt()
                path.reset()
                path.moveTo(0f, gy); path.lineTo(w * 0.25f, gy - 260f); path.lineTo(w * 0.5f, gy); path.close()
                path.moveTo(w * 0.35f, gy); path.lineTo(w * 0.7f, gy - 320f); path.lineTo(w.toFloat(), gy); path.close()
                c.drawPath(path, fill)
                // палатка с тёплым светом
                fill.color = 0xFFE58A3D.toInt()
                path.reset()
                path.moveTo(w * 0.1f, gy + 90f); path.lineTo(w * 0.28f, gy - 120f); path.lineTo(w * 0.46f, gy + 90f); path.close()
                c.drawPath(path, fill)
                fill.color = 0xFFFFE9A8.toInt()
                path.reset()
                path.moveTo(w * 0.24f, gy + 90f); path.lineTo(w * 0.28f, gy - 20f); path.lineTo(w * 0.32f, gy + 90f); path.close()
                c.drawPath(path, fill)
                fill.color = Color.WHITE
                for (i in 0 until 40) {
                    val x = (i * 131 % w).toFloat()
                    val y = (((i * 53) % h) + t * (30 + i % 4 * 20)).toFloat() % h
                    c.drawCircle(x, y, (2 + i % 3).toFloat(), fill)
                }
            }
            ToonScene.CITY -> {
                grad(c, 0xFF3A2A6B.toInt(), 0xFFF08A5D.toInt(), 0f, gy)
                for (i in 0 until 7) {
                    val bw = w / 7f
                    val bh = h * (0.18f + ((i * 37) % 10) / 40f)
                    fill.color = if (i % 2 == 0) 0xFF1B1730.toInt() else 0xFF28224A.toInt()
                    c.drawRect(i * bw, gy - bh, (i + 1) * bw - 4f, gy, fill)
                    fill.color = 0xFFFFD66B.toInt()
                    var y = gy - bh + 20f
                    while (y < gy - 20f) {
                        c.drawRect(i * bw + 14f, y, i * bw + 30f, y + 16f, fill)
                        c.drawRect(i * bw + 48f, y, i * bw + 64f, y + 16f, fill)
                        y += 46f
                    }
                }
                fill.color = 0xFF2B2B35.toInt()
                c.drawRect(0f, gy, w.toFloat(), h.toFloat(), fill)
            }
        }
    }

    // ---------- герой ----------

    private fun hero(c: Canvas, look: ToonLook, cx: Float, baseY: Float, scale: Float, t: Double, talking: Boolean, open: Float, width: Float, mood: Mood, holds: Boolean = false) {
        val skin = look.skin
        val hair = look.hair
        val shirt = look.shirt
        val pose = ToonAnim.pose(t, talking, open, ToonMood.valueOf(mood.name), holds)
        val bob = pose.breath
        c.save()
        c.translate(cx, baseY + bob)
        c.scale(scale, scale)
        // ноги и тело
        fill.color = 0xFF2F3A4F.toInt()
        c.drawRect(-46f, 110f, -6f, 300f, fill)
        c.drawRect(6f, 110f, 46f, 300f, fill)
        fill.color = 0xFF1B1B1B.toInt()
        c.drawRoundRect(RectF(-56f, 290f, -2f, 316f), 10f, 10f, fill)
        c.drawRoundRect(RectF(2f, 290f, 56f, 316f), 10f, 10f, fill)
        fill.color = shirt
        c.drawRoundRect(RectF(-78f, -40f, 78f, 140f), 40f, 40f, fill)
        // полоска на одежде, чтобы герои сильнее различались
        fill.color = Color.argb(60, 255, 255, 255)
        c.drawRect(-78f, 40f, 78f, 62f, fill)
        // руки с суставами по реалистичным пропорциям (ToonBody): плечо→локоть→кисть
        val lSh = pose.arms[0]; val lEl = pose.arms[1]; val rSh = pose.arms[2]; val rEl = pose.arms[3]
        fun arm(sideSign: Float, shoulderDeg: Float, elbowDeg: Float) {
            // локальные координаты героя: пол ≈ 316, рост ≈ 616 (от макушки -300)
            val sx = sideSign * ToonBody.SHOULDER_W / 2 * 616f
            val sy = -300f + (1f - ToonBody.SHOULDER) * 616f
            val upper = 616f * ToonBody.UPPER_ARM
            val fore = 616f * ToonBody.FOREARM
            val a1 = Math.toRadians((180f + shoulderDeg * sideSign).toDouble())
            val ex = sx + (Math.sin(a1) * upper).toFloat() * sideSign
            val ey = sy + (Math.cos(a1) * upper).toFloat()
            val a2 = a1 + Math.toRadians((elbowDeg * sideSign).toDouble())
            val hx = ex + (Math.sin(a2) * fore).toFloat() * sideSign
            val hy = ey + (Math.cos(a2) * fore).toFloat()
            line.color = shirt; line.strokeWidth = 24f
            c.drawLine(sx, sy, ex, ey, line)
            c.drawLine(ex, ey, hx, hy, line)
            fill.color = skin
            c.drawCircle(hx, hy, 17f, fill)
            if (holds && sideSign > 0) cup(c, hx, hy - 14f)
        }
        arm(-1f, lSh, lEl)
        arm(1f, rSh, rEl)
        // шея
        c.drawRect(-18f, -62f, 18f, -30f, fill)
        // голова с лёгким наклоном (поворот вокруг шеи)
        c.save()
        c.rotate(pose.headTilt, 0f, -30f)
        // длинные волосы сзади головы
        fill.color = hair
        if (look.style == HairStyle.LONG) c.drawRoundRect(RectF(-92f, -190f, 92f, -20f), 60f, 60f, fill)
        if (look.style == HairStyle.BUN) c.drawCircle(0f, -222f, 30f, fill)
        // голова
        fill.color = skin
        c.drawCircle(0f, -130f, 84f, fill)
        // борода
        if (look.beard) {
            fill.color = hair
            path.reset()
            path.addArc(RectF(-70f, -120f, 70f, -30f), 0f, 180f)
            path.close()
            c.drawPath(path, fill)
            fill.color = skin
            c.drawOval(RectF(-30f, -118f, 30f, -70f), fill)
        }
        // причёска или кепка
        fill.color = hair
        when (look.style) {
            HairStyle.BALD, HairStyle.LONG, HairStyle.BUN -> {}
            HairStyle.CAP -> {
                fill.color = shirts2(shirt)
                path.reset()
                path.addArc(RectF(-88f, -218f, 88f, -60f), 180f, 180f)
                path.close()
                c.drawPath(path, fill)
                c.drawRoundRect(RectF(10f, -168f, 118f, -150f), 9f, 9f, fill)
            }
            else -> {
                path.reset()
                path.addArc(RectF(-90f, -222f, 90f, -48f), 180f, 180f)
                path.lineTo(70f, -150f)
                path.quadTo(0f, -190f, -70f, -150f)
                path.close()
                c.drawPath(path, fill)
            }
        }
        // глаза с плавным морганием (фаза Batch24); удивлённый смотрит шире
        val eyeK = pose.eyeOpen
        val wide = if (mood == Mood.WOW) 8f else 0f
        fill.color = Color.WHITE
        val eyeH = 3f + (22f + wide - 3f) * eyeK
        c.drawOval(RectF(-46f, -140f - eyeH / 2, -14f, -140f + eyeH / 2), fill)
        c.drawOval(RectF(14f, -140f - eyeH / 2, 46f, -140f + eyeH / 2), fill)
        if (eyeK > 0.25f) {
            fill.color = 0xFF1B1B1B.toInt()
            c.drawCircle(-30f, -139f, 7f * eyeK.coerceAtLeast(0.4f), fill)
            c.drawCircle(30f, -139f, 7f * eyeK.coerceAtLeast(0.4f), fill)
        }
        if (look.glasses) {
            line.color = 0xFF222222.toInt(); line.strokeWidth = 5f
            c.drawCircle(-30f, -140f, 24f, line)
            c.drawCircle(30f, -140f, 24f, line)
            c.drawLine(-6f, -140f, 6f, -140f, line)
        }
        // брови: говорящий поднимает их, вопрос поднимает одну, грусть опускает внешние края
        line.color = if (look.style == HairStyle.BALD && !look.beard) 0xFF5A4636.toInt() else hair
        line.strokeWidth = 7f
        val up = if (talking) -open * 6f else 0f
        var l0 = -166f + up
        var l1 = -170f + up
        var r0 = -170f + up
        var r1 = -166f + up
        when (mood) {
            Mood.ASK -> { r0 -= 14f; r1 -= 14f }
            Mood.WOW -> { l0 -= 12f; l1 -= 12f; r0 -= 12f; r1 -= 12f }
            Mood.SAD -> { l0 += 10f; r1 += 10f }
            Mood.CALM -> {}
        }
        c.drawLine(-50f, l0, -12f, l1, line)
        c.drawLine(12f, r0, 50f, r1, line)
        mouth(c, 0f, -88f, open, width, mood == Mood.SAD)
        c.restore()
    }

    private fun shirts2(shirt: Int): Int =
        Color.rgb((Color.red(shirt) * 0.7f).toInt(), (Color.green(shirt) * 0.7f).toInt(), (Color.blue(shirt) * 0.7f).toInt())

    /** Рот: open 0 — улыбка-линия, дальше открывается; width −1 вытянут «у/о», +1 растянут «и/е». */
    private fun mouth(c: Canvas, cx: Float, cy: Float, open: Float, width: Float, sad: Boolean = false) {
        val mw = 22f + width * 8f
        if (open < 0.08f) {
            line.color = 0xFF6B2D2D.toInt(); line.strokeWidth = 6f
            path.reset()
            path.moveTo(cx - mw, cy)
            path.quadTo(cx, if (sad) cy - 10f else cy + 10f, cx + mw, cy)
            c.drawPath(path, line)
            return
        }
        val mh = 6f + open * 30f
        fill.color = 0xFF5A1E24.toInt()
        c.drawOval(RectF(cx - mw, cy - mh / 2, cx + mw, cy + mh / 2), fill)
        if (open > 0.35f) {
            fill.color = 0xFFE86F7B.toInt()
            c.drawOval(RectF(cx - mw * 0.55f, cy + mh * 0.05f, cx + mw * 0.55f, cy + mh / 2 - 1f), fill)
        }
        if (open > 0.25f && width > -0.4f) {
            fill.color = Color.WHITE
            c.drawRoundRect(RectF(cx - mw * 0.7f, cy - mh / 2 + 1f, cx + mw * 0.7f, cy - mh / 2 + 9f), 3f, 3f, fill)
        }
    }

    // ---------- Batch24: чашка и реквизит ----------

    /** Чашка в кисти руки. */
    private fun cup(c: Canvas, hx: Float, hy: Float) {
        fill.color = 0xFFE8E4DC.toInt()
        c.drawRoundRect(RectF(hx - 20f, hy - 22f, hx + 20f, hy + 16f), 6f, 6f, fill)
        line.color = 0xFFB9B2A6.toInt(); line.strokeWidth = 6f
        c.drawCircle(hx + 26f, hy - 2f, 10f, line)
        fill.color = 0xFF6B4A32.toInt()
        c.drawOval(RectF(hx - 16f, hy - 20f, hx + 16f, hy - 12f), fill)
    }

    /** Нарисовать весь реквизит сцены позади героев. */
    private fun drawProps(c: Canvas) {
        val floorY = h * 0.74f
        for (p in props) {
            val px = w * p.x
            val py = h * p.y
            when (p.kind) {
                ToonProp.Kind.TABLE -> {
                    fill.color = 0xFF8A5A3B.toInt()
                    c.drawRoundRect(RectF(px - 130f, py - 14f, px + 130f, py + 10f), 6f, 6f, fill)
                    c.drawRect(px - 100f, py, px - 84f, floorY, fill)
                    c.drawRect(px + 84f, py, px + 100f, floorY, fill)
                }
                ToonProp.Kind.CHAIR -> {
                    fill.color = 0xFF7A4E33.toInt()
                    c.drawRoundRect(RectF(px - 46f, py - 12f, px + 46f, py + 10f), 6f, 6f, fill)
                    c.drawRoundRect(RectF(px - 46f, py - 120f, px + 46f, py - 96f), 8f, 8f, fill)
                    c.drawRect(px - 40f, py, px - 26f, floorY, fill)
                    c.drawRect(px + 26f, py, px + 40f, floorY, fill)
                }
                ToonProp.Kind.WINDOW -> {
                    fill.color = 0xFFBFE6F7.toInt()
                    c.drawRoundRect(RectF(px - 90f, py - 120f, px + 90f, py + 90f), 8f, 8f, fill)
                    line.color = 0xFFE8D8C0.toInt(); line.strokeWidth = 12f
                    c.drawRoundRect(RectF(px - 90f, py - 120f, px + 90f, py + 90f), 8f, 8f, line)
                    c.drawLine(px, py - 120f, px, py + 90f, line)
                    c.drawLine(px - 90f, py - 15f, px + 90f, py - 15f, line)
                }
                ToonProp.Kind.LAMP -> {
                    fill.color = 0xFFE4B04A.toInt()
                    path.reset()
                    path.moveTo(px - 60f, py - 150f); path.lineTo(px + 60f, py - 150f); path.lineTo(px + 38f, py - 110f); path.lineTo(px - 38f, py - 110f)
                    path.close(); c.drawPath(path, fill)
                    line.color = 0xFF6B5A3B.toInt(); line.strokeWidth = 10f
                    c.drawLine(px, py - 110f, px, floorY, line)
                    c.drawRoundRect(RectF(px - 50f, floorY - 10f, px + 50f, floorY + 8f), 8f, 8f, fill)
                }
                ToonProp.Kind.TV -> {
                    fill.color = 0xFF22262E.toInt()
                    c.drawRoundRect(RectF(px - 110f, py - 80f, px + 110f, py + 70f), 8f, 8f, fill)
                    fill.color = 0xFF5F86B0.toInt()
                    c.drawRoundRect(RectF(px - 96f, py - 66f, px + 96f, py + 56f), 4f, 4f, fill)
                    c.drawRect(px - 14f, py + 70f, px + 14f, floorY, fill)
                }
                ToonProp.Kind.PLANT -> {
                    fill.color = 0xFFB0603C.toInt()
                    path.reset()
                    path.moveTo(px - 44f, py - 60f); path.lineTo(px + 44f, py - 60f); path.lineTo(px + 30f, floorY); path.lineTo(px - 30f, floorY)
                    path.close(); c.drawPath(path, fill)
                    fill.color = 0xFF3E7A44.toInt()
                    for (leaf in listOf(-38f to -140f, 0f to -165f, 38f to -140f, -20f to -110f, 22f to -112f)) {
                        c.drawOval(RectF(px + leaf.first - 18f, py + leaf.second, px + leaf.first + 18f, py + leaf.second + 58f), fill)
                    }
                }
                ToonProp.Kind.SHELF -> {
                    fill.color = 0xFF8A5A3B.toInt()
                    c.drawRect(px - 90f, py, px + 90f, py + 12f, fill)
                    for (i in 0..2) {
                        fill.color = 0xFF4A7BA8.toInt()
                        val bx = px - 60f + i * 55f
                        c.drawRect(bx, py - 44f, bx + 34f, py, fill)
                    }
                }
                ToonProp.Kind.CUP -> { /* чашка рисуется в руке героя */ }
                ToonProp.Kind.PICTURE -> {
                    val fr = ToonRichGeom.pictureFrame(w, h, px, py, 0.9f)
                    fill.color = 0xFFE4B04A.toInt()
                    c.drawRect(fr[0], fr[1], fr[2], fr[3], fill)
                    fill.color = 0xFF7FA8C9.toInt()
                    c.drawRect(fr[0] + 10f, fr[1] + 10f, fr[2] - 10f, fr[3] - 10f, fill)
                }
                ToonProp.Kind.CUSTOM -> customBitmap(c, p, px, py, floorY)
            }
        }
    }

    /** Своя картинка пользователя: вписать в сцену с мягкой тенью. */
    private fun customBitmap(c: Canvas, p: ToonProp, px: Float, py: Float, floorY: Float) {
        val bmp = propBitmaps.getOrPut(p.path) { decodeProp(p.path) }
        if (bmp == null) {
            // картинка не найдена/не читается — рисуем «заглушку-рамку», чтобы кадр не ломался
            fill.color = 0x55888888.toInt()
            c.drawRoundRect(RectF(px - 60f, py - 80f, px + 60f, py + 40f), 6f, 6f, fill)
            return
        }
        val targetH = 260f * p.scale
        val k = targetH / bmp.height
        val bw = bmp.width * k
        val bh = targetH
        fill.color = 0x44000000.toInt()
        c.drawOval(RectF(px - bw / 2, floorY - 14f, px + bw / 2, floorY + 12f), fill)
        c.drawBitmap(bmp, null, RectF(px - bw / 2, floorY - bh, px + bw / 2, floorY), paintBmp)
    }

    private fun decodeProp(path: String): android.graphics.Bitmap? = try {
        val f = java.io.File(path)
        if (f.isFile) android.graphics.BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Exception) { null }

    /** Тонировка времени суток + погодные частицы поверх всего кадра (Batch26). */
    private fun sceneWeather(c: Canvas, t: Double) {
        val tint = ToonSceneFx.tintOf(sceneFx.time)
        if (tint != 0) {
            fill.color = tint
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        }
        when (sceneFx.weather) {
            "дождь" -> {
                line.color = 0x88AFCBE8.toInt(); line.strokeWidth = 4f
                var i = 0L
                val n = 40
                while (i < n) {
                    val s = (i * 2654435761L) and 0xFFFF
                    val x = ((s % w) + t * 60 * ((s % 3) + 1)) % w
                    val y = ((s * 7) % h + t * 900) % h
                    c.drawLine(x.toFloat(), y.toFloat(), (x - 8).toFloat(), (y + 34).toFloat(), line)
                    i++
                }
            }
            "снег" -> {
                fill.color = 0xCCFFFFFF.toInt()
                var i = 0
                val n = 46
                while (i < n) {
                    val s = ((i * 2654435761L) and 0xFFFF).toInt()
                    val x = ((s % w) + sin(t * 0.9 + i) * 30) % w
                    val y = ((s * 13) % h + t * 120 * ((s % 3) + 1)) % h
                    c.drawCircle(x.toFloat(), y.toFloat(), 3f + (s % 4), fill)
                    i++
                }
            }
            "туман" -> {
                fill.color = 0x55C8D0D8.toInt()
                c.drawRect(0f, h * 0.4f, w.toFloat(), h.toFloat(), fill)
            }
        }
    }

    // ---------- текст ----------

    fun wrap(text: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in text.split(" ").filter { it.isNotEmpty() }) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(cand) <= maxW || cur.isEmpty()) cur = cand else { out += cur; cur = word }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private fun outlined(c: Canvas, text: String, x: Float, y: Float, size: Float, color: Int = Color.WHITE) {
        textFill.textSize = size
        textFill.color = color
        textStroke.textSize = size
        textStroke.strokeWidth = size * 0.2f
        c.drawText(text, x, y, textStroke)
        c.drawText(text, x, y, textFill)
    }

    private fun subtitle(c: Canvas, text: String) {
        val up = text.uppercase()
        var size = 84f
        textFill.textSize = size
        var lines = wrap(up, textFill, w - 80f)
        while (lines.size > 3 && size > 48f) {
            size -= 6f
            textFill.textSize = size
            lines = wrap(up, textFill, w - 80f)
        }
        val lh = size * 1.12f
        var y = h * 0.8f - (lines.size - 1) * lh / 2
        for (l in lines) {
            outlined(c, l, w / 2f, y, size)
            y += lh
        }
    }

    private fun title(c: Canvas, text: String, t: Double) {
        val a = (1.0 - ((t - 1.6) / 0.6).coerceIn(0.0, 1.0)).toFloat()
        val y = h * 0.14f - (1f - a) * 30f
        textFill.textSize = 64f
        val lines = wrap(text.uppercase(), textFill, w - 100f).take(3)
        var yy = y
        for (l in lines) {
            outlined(c, l, w / 2f, yy, 64f, Color.argb((255 * a).toInt(), 255, 224, 64))
            yy += 72f
        }
    }

    private fun choice(c: Canvas, ch: ToonChoice, anim: Float, cta: String) {
        val a = anim.coerceIn(0f, 1f)
        fill.color = Color.argb((200 * a).toInt(), 10, 10, 20)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
        val top = h * 0.16f + (1f - a) * 80f
        textFill.textSize = 58f
        var y = top
        for (l in wrap(ch.question.uppercase(), textFill, w - 100f).take(4)) {
            outlined(c, l, w / 2f, y, 58f, Color.rgb(255, 224, 64))
            y += 68f
        }
        y += 40f
        val colors = intArrayOf(0xFFE5533D.toInt(), 0xFF3D7BE5.toInt(), 0xFF3DB27A.toInt())
        for ((i, o) in ch.options.withIndex()) {
            rect.set(60f, y, w - 60f, y + 120f)
            fill.color = colors[i % colors.size]
            c.drawRoundRect(rect, 36f, 36f, fill)
            line.color = Color.WHITE; line.strokeWidth = 6f
            c.drawRoundRect(rect, 36f, 36f, line)
            textFill.textSize = 46f
            var size = 46f
            while (textFill.measureText(o.uppercase()) > w - 160f && size > 26f) { size -= 4f; textFill.textSize = size }
            outlined(c, o.uppercase(), w / 2f, rect.centerY() + size * 0.35f, size)
            y += 160f
        }
        if (cta.isNotEmpty()) {
            val pulse = 1f + 0.04f * sin(anim * 20.0).toFloat()
            c.save()
            c.scale(pulse, pulse, w / 2f, h * 0.88f)
            var yy = h * 0.88f
            textFill.textSize = 44f
            for (l in wrap(cta.uppercase(), textFill, w - 100f).take(2)) {
                outlined(c, l, w / 2f, yy, 44f)
                yy += 52f
            }
            c.restore()
        }
    }

}
