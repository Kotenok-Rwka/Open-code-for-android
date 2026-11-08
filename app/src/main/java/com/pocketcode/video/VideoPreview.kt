package com.pocketcode.video

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketcode.ui.I18n
import com.pocketcode.ui.Text
import kotlin.math.hypot

/** Секунды с начала показа; обновляется каждый кадр, пока предпросмотр на экране. [key] перезапускает отсчёт. */
@Composable
private fun rememberClock(key: Any?): State<Double> {
    val t = remember(key) { mutableStateOf(0.0) }
    LaunchedEffect(key) {
        var start = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { n ->
                if (start < 0) start = n
                t.value = (n - start) / 1_000_000_000.0
            }
        }
    }
    return t
}

private val Sky = Brush.verticalGradient(listOf(Color(0xFF1B2A49), Color(0xFF6A4C93), Color(0xFFF08A5D)))

/** Условный кадр видео: небо и холмы, чтобы субтитры были видны на фоне. */
@Composable
private fun SampleFrame(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Sky)) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFFFFE08A), radius = size.height * 0.16f, center = Offset(size.width * 0.72f, size.height * 0.36f))
            val hills = Path().apply {
                moveTo(0f, size.height)
                lineTo(0f, size.height * 0.72f)
                cubicTo(size.width * 0.2f, size.height * 0.55f, size.width * 0.4f, size.height * 0.8f, size.width * 0.6f, size.height * 0.66f)
                cubicTo(size.width * 0.78f, size.height * 0.55f, size.width * 0.9f, size.height * 0.7f, size.width, size.height * 0.62f)
                lineTo(size.width, size.height)
                close()
            }
            drawPath(hills, Color(0xFF14213D))
        }
        content()
    }
}

/** Текст с обводкой: нижний слой рисует контур, верхний заливку. */
@Composable
private fun OutlinedWord(text: String, size: Float, fill: Color, stroke: Color, strokeWidth: Float, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Bold, italic: Boolean = false) {
    Box(modifier) {
        if (strokeWidth > 0f) {
            BasicText(
                text,
                style = TextStyle(
                    color = stroke, fontSize = size.sp, fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                    drawStyle = Stroke(width = strokeWidth, join = StrokeJoin.Round),
                ),
            )
        }
        BasicText(text, style = TextStyle(color = fill, fontSize = size.sp, fontWeight = weight, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal))
    }
}

private val Yellow = Color(0xFFFFF000)
private val BigColors = listOf(Color.White, Color(0xFFFFF000), Color(0xFF50E850), Color(0xFFFF50B4))

/** Пример надписи выбранного стиля на условном кадре: слова появляются так же, как в готовом видео. */
@Composable
fun SubtitlePreview(style: SubStyle, modifier: Modifier = Modifier) {
    val words = previewWords(I18n.t("Это пример ваших субтитров"))
    val clock by rememberClock(style)
    val t = clock
    val active = previewWordIndex(t, words.size)
    val age = previewWordAge(t, words.size)
    BoxWithConstraints(modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        // Кадр показан крупнее настоящего, чтобы текст читался на телефоне (в 1,5 раза)
        val h = maxHeight
        val px = (h.value * previewFontFraction(style) * 1.5).toFloat()
        SampleFrame(Modifier.fillMaxSize()) {
            val pad = 12.dp
            when (style) {
                SubStyle.CLASSIC -> Bottom(h) {
                    Words(words, px, { _ -> Color.White }, Color.Black, px * 0.14f)
                }

                SubStyle.BOX -> Bottom(h) {
                    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x8C000000)).padding(horizontal = pad, vertical = 4.dp)) {
                        Words(words, px, { _ -> Color.White }, Color.Transparent, 0f)
                    }
                }

                SubStyle.KARAOKE -> Bottom(h) {
                    Words(words, px, { i -> if (i <= active) Yellow else Color.White }, Color.Black, px * 0.16f)
                }

                SubStyle.POP -> Bottom(h) {
                    Row(horizontalArrangement = Arrangement.spacedBy((px * 0.3f).dp), verticalAlignment = Alignment.CenterVertically) {
                        words.forEachIndexed { i, w ->
                            val sc = if (i == active) popScale(age) else 1f
                            OutlinedWord(
                                w.trimEnd(',', '.').uppercase(), px, if (i == active) Yellow else Color.White, Color.Black, px * 0.2f,
                                Modifier.graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (i <= active) 1f else 0f },
                            )
                        }
                    }
                }

                SubStyle.BIG -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val sc = popScale(age)
                    OutlinedWord(
                        words[active].trimEnd(',', '.').uppercase(), px, BigColors[active % BigColors.size], Color.Black, px * 0.18f,
                        Modifier.graphicsLayer { scaleX = sc; scaleY = sc },
                    )
                }

                SubStyle.NEON -> Bottom(h) {
                    Row(horizontalArrangement = Arrangement.spacedBy((px * 0.3f).dp)) {
                        words.forEachIndexed { i, w ->
                            val glow = if (i % 2 == 0) Color(0xFFFF40FF) else Color(0xFF40E0FF)
                            BasicText(
                                w,
                                style = TextStyle(
                                    color = Color.White, fontSize = px.sp, fontWeight = FontWeight.SemiBold,
                                    shadow = Shadow(glow, Offset.Zero, 22f),
                                ),
                            )
                        }
                    }
                }

                SubStyle.COMIC -> Bottom(h) {
                    val sc = popScale(age)
                    Row(
                        Modifier.graphicsLayer { rotationZ = -4f; scaleX = sc; scaleY = sc },
                        horizontalArrangement = Arrangement.spacedBy((px * 0.3f).dp),
                    ) {
                        words.forEach { w -> OutlinedWord(w.uppercase(), px, Yellow, Color.Black, px * 0.3f, weight = FontWeight.ExtraBold, italic = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bottom(frameHeight: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = frameHeight * 0.1f), contentAlignment = Alignment.BottomCenter) { content() }
}

@Composable
private fun Words(words: List<String>, px: Float, color: (Int) -> Color, stroke: Color, strokeWidth: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy((px * 0.28f).dp)) {
        words.forEachIndexed { i, w -> OutlinedWord(w, px, color(i), stroke, strokeWidth, weight = FontWeight.SemiBold) }
    }
}

private val ClipA = Brush.linearGradient(listOf(Color(0xFF0F766E), Color(0xFF38BDF8)))
private val ClipB = Brush.linearGradient(listOf(Color(0xFFDB2777), Color(0xFFF59E0B)))

@Composable
private fun DemoClip(brush: Brush, label: String, modifier: Modifier = Modifier) {
    Box(modifier.background(brush), contentAlignment = Alignment.Center) {
        BasicText(label, style = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 54.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center))
    }
}

/** Показывает, как выглядит стык двух кусков (A → B). В настоящем видео он быстрее: здесь замедлен, чтобы разглядеть. */
@Composable
fun TransitionPreview(t: Transition, modifier: Modifier = Modifier) {
    val clock by rememberClock(t)
    val cycleSec = 2.6
    val cycle = (clock / cycleSec).toInt()
    val p = ((clock % cycleSec) / cycleSec).toFloat()
    val f = transitionFrame(t, p, cycle)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            DemoClip(ClipA, "A", Modifier.fillMaxSize().graphicsLayer { translationX = f.aShiftX * w })
            if (f.showB) {
                val circleMod = if (f.circle >= 0f && f.circle < 0.999f) {
                    Modifier.drawWithContent {
                        val r = f.circle * hypot(w, h) / 2f
                        val path = Path().apply { addOval(Rect(Offset(w / 2f, h / 2f), r)) }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    }
                } else Modifier
                val blurMod = if (f.smear > 0.01f && Build.VERSION.SDK_INT >= 31) Modifier.blur((10 * f.smear).dp) else Modifier
                DemoClip(
                    ClipB, "B",
                    Modifier.fillMaxSize().then(blurMod).then(circleMod).graphicsLayer {
                        alpha = f.bAlpha
                        translationX = f.bShiftX * w
                        scaleX = f.bScale
                        scaleY = f.bScale
                    },
                )
            }
            if (f.white > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = f.white)))
        }
        Text(
            if (t == Transition.MIX) "Показаны разные переходы по очереди" else "Так выглядит стык двух кусков (A и B). В видео он быстрее.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
