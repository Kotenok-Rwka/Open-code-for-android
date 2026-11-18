package com.pocketcode.video.toon

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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
)

/** Рисование плоских вырезных героев и фонов в духе вертикальных мультиков. Размер кадра 720×1280 (9:16). */
class ToonDraw(val w: Int = 720, val h: Int = 1280) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    private val skins = intArrayOf(0xFFF2C6A0.toInt(), 0xFFE0A87C.toInt(), 0xFFFAD7B5.toInt(), 0xFFC68B63.toInt())
    private val hairs = intArrayOf(0xFF3B2A20.toInt(), 0xFFB5651D.toInt(), 0xFF1E1E24.toInt(), 0xFFE5C07B.toInt())
    private val shirts = intArrayOf(0xFFE5533D.toInt(), 0xFF3D7BE5.toInt(), 0xFF3DB27A.toInt(), 0xFF9B59D0.toInt())

    fun draw(c: Canvas, f: ToonFrame) {
        background(c, f.scene, f.t)
        val n = f.heroes.size.coerceAtMost(4)
        for (i in 0 until n) {
            val cx = w * (i + 1f) / (n + 1f)
            val talking = f.heroes[i] == f.speaker
            hero(c, i, cx, h * 0.58f, if (n >= 3) 0.8f else 1f, f.t + i * 0.7, talking, if (talking) f.mouthOpen else 0f, if (talking) f.mouthWidth else 0f)
        }
        if (f.title.isNotEmpty() && f.t < 2.2) title(c, f.title, f.t)
        if (f.subtitle.isNotEmpty() && f.choiceAnim < 0f) subtitle(c, f.subtitle)
        if (f.choice != null && f.choiceAnim >= 0f) choice(c, f.choice, f.choiceAnim, f.cta)
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

    private fun hero(c: Canvas, idx: Int, cx: Float, baseY: Float, scale: Float, t: Double, talking: Boolean, open: Float, width: Float) {
        val skin = skins[idx % skins.size]
        val hair = hairs[(idx + 1) % hairs.size]
        val shirt = shirts[idx % shirts.size]
        val bob = (sin(t * 2.2) * 4.0 + (if (talking) abs(sin(t * 7.0)) * 6.0 * open else 0.0)).toFloat()
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
        // руки; говорящий жестикулирует
        val wave = if (talking) (sin(t * 5.0) * 14.0).toFloat() else 0f
        fill.color = shirt
        c.drawRoundRect(RectF(-108f, -20f, -74f, 120f + wave), 17f, 17f, fill)
        c.drawRoundRect(RectF(74f, -20f, 108f, 120f - wave), 17f, 17f, fill)
        fill.color = skin
        c.drawCircle(-91f, 126f + wave, 20f, fill)
        c.drawCircle(91f, 126f - wave, 20f, fill)
        // шея и голова
        c.drawRect(-18f, -62f, 18f, -30f, fill)
        c.drawCircle(0f, -130f, 84f, fill)
        // причёска
        fill.color = hair
        path.reset()
        path.addArc(RectF(-90f, -222f, 90f, -48f), 180f, 180f)
        path.lineTo(70f, -150f)
        path.quadTo(0f, -190f, -70f, -150f)
        path.close()
        c.drawPath(path, fill)
        // глаза с морганием
        val blink = (t % 3.4) in 3.25..3.4
        fill.color = Color.WHITE
        val eyeH = if (blink) 3f else 22f
        c.drawOval(RectF(-46f, -140f - eyeH / 2, -14f, -140f + eyeH / 2), fill)
        c.drawOval(RectF(14f, -140f - eyeH / 2, 46f, -140f + eyeH / 2), fill)
        if (!blink) {
            fill.color = 0xFF1B1B1B.toInt()
            c.drawCircle(-30f, -139f, 7f, fill)
            c.drawCircle(30f, -139f, 7f, fill)
        }
        line.color = hair; line.strokeWidth = 7f
        val brow = if (talking) -open * 6f else 0f
        c.drawLine(-50f, -166f + brow, -12f, -170f + brow, line)
        c.drawLine(12f, -170f + brow, 50f, -166f + brow, line)
        mouth(c, 0f, -88f, open, width)
        c.restore()
    }

    /** Рот: open 0 — улыбка-линия, дальше открывается; width −1 вытянут «у/о», +1 растянут «и/е». */
    private fun mouth(c: Canvas, cx: Float, cy: Float, open: Float, width: Float) {
        val mw = 22f + width * 8f
        if (open < 0.08f) {
            line.color = 0xFF6B2D2D.toInt(); line.strokeWidth = 6f
            path.reset()
            path.moveTo(cx - mw, cy)
            path.quadTo(cx, cy + 10f, cx + mw, cy)
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
