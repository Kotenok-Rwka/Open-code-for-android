package com.pocketcode.video

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Живой предпросмотр эффектов и субтитров на экране «Видео»: здесь только расчёт кадра по времени,
 * рисует его VideoPreview.kt. Предпросмотр условный и чуть медленнее настоящего (в видео переходы длятся 0,15–0,25 с).
 */

/** Как выглядит стык двух кусков A→B в момент p. Сдвиги заданы долей ширины кадра. */
data class TFrame(
    val showB: Boolean,
    val bAlpha: Float = 1f,
    val aShiftX: Float = 0f,
    val bShiftX: Float = 0f,
    val bScale: Float = 1f,
    val white: Float = 0f,
    /** Радиус раскрытия круга как доля диагонали кадра; отрицательное значение — круга нет. */
    val circle: Float = -1f,
    /** Сила размытия на стыке, 0..1. */
    val smear: Float = 0f,
)

private val MIX_ORDER = listOf(Transition.FADE, Transition.FLASH, Transition.SLIDE, Transition.BLUR, Transition.CIRCLE)

/** Какой переход реально покажет «Микс» на данном по счёту повторе предпросмотра. */
fun mixPick(cycle: Int): Transition = MIX_ORDER[Math.floorMod(cycle, MIX_ORDER.size)]

/**
 * Кадр перехода [t] в момент [p] (0..1 за повтор): сначала стоит A, в середине стык, потом стоит B.
 * [cycle] нужен только «Миксу»: он показывает разные переходы по очереди.
 */
fun transitionFrame(t: Transition, p: Float, cycle: Int = 0): TFrame {
    val pp = p.coerceIn(0f, 1f)
    val q = ((pp - 0.35f) / 0.3f).coerceIn(0f, 1f)
    return when (if (t == Transition.MIX) mixPick(cycle) else t) {
        Transition.CUT -> TFrame(showB = pp >= 0.5f)
        Transition.ZOOM -> TFrame(showB = pp >= 0.5f, bScale = 1.15f)
        Transition.FADE -> TFrame(showB = q > 0f, bAlpha = q)
        Transition.FLASH -> TFrame(showB = q >= 0.5f, white = 1f - abs(2f * q - 1f))
        Transition.SLIDE -> TFrame(showB = q > 0f, aShiftX = -q, bShiftX = 1f - q)
        Transition.BLUR -> TFrame(showB = q > 0f, bAlpha = q, smear = sin(PI.toFloat() * q))
        Transition.CIRCLE -> TFrame(showB = q > 0f, circle = if (q > 0f) q else -1f)
        Transition.MIX -> TFrame(showB = pp >= 0.5f)
    }
}

/** Слово, которое «звучит» в момент [t] секунд: по одному за [per] секунд, в конце небольшая пауза и повтор. */
fun previewWordIndex(t: Double, n: Int, per: Double = 0.55, hold: Double = 1.0): Int {
    if (n <= 0) return 0
    val total = n * per + hold
    val m = ((t % total) + total) % total
    return (m / per).toInt().coerceIn(0, n - 1)
}

/** Сколько секунд назад началось текущее слово (для «прыжка» в стилях Поп, Громко и Комикс). */
fun previewWordAge(t: Double, n: Int, per: Double = 0.55, hold: Double = 1.0): Double {
    if (n <= 0) return 0.0
    val total = n * per + hold
    val m = ((t % total) + total) % total
    val i = previewWordIndex(t, n, per, hold)
    return (m - i * per).coerceAtLeast(0.0)
}

/** Масштаб слова при появлении: чуть меньше, быстро больше, затем обычный размер. */
fun popScale(age: Double): Float = when {
    age < 0.0 -> 0.82f
    age < 0.09 -> (0.82 + (1.18 - 0.82) * age / 0.09).toFloat()
    age < 0.19 -> (1.18 - 0.18 * (age - 0.09) / 0.10).toFloat()
    else -> 1f
}

/** Размер шрифта стиля как доля высоты кадра (как в настоящих субтитрах). */
fun previewFontFraction(style: SubStyle): Double = when (style) {
    SubStyle.CLASSIC -> 0.052
    SubStyle.BOX -> 0.05
    SubStyle.KARAOKE -> 0.058
    SubStyle.POP -> 0.085
    SubStyle.BIG -> 0.13
    SubStyle.NEON -> 0.065
    SubStyle.COMIC -> 0.09
}

/** Слова примера: пробелы разделяют, пустое значение заменяется запасной фразой. */
fun previewWords(sample: String): List<String> =
    sample.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { listOf("Subtitles") }
