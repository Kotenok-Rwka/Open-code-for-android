// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Итог установки барабана на значение. [reached] — достигнуто; [value] — что показано сейчас (не прочитано — null), [from] — что было в начале;
 * [attempts] — сколько действий сделано (ввод, прокрутка, перетаскивание); [method] — чем закончили: text (ввод), actions (прокрутка действиями),
 * drag (перетаскивание), none; [error] — причина, если не достигнуто (not_a_wheel, unknown_value, at_end, not_moving, not_reached…).
 */
data class WheelOutcome(
    val reached: Boolean,
    val value: String?,
    val from: String?,
    val attempts: Int,
    val method: String,
    val error: String? = null,
)

/** Строка текста, распознанная на экране. [bounds] — левый, верхний, правый, нижний края в долях экрана 0–999 (см. [OcrText.normalize]). */
data class OcrLine(val text: String, val bounds: List<Int>, val confidence: Float? = null)

/** Чистая логика распознавания текста с экрана. */
object OcrText {
    /** Пиксель [p] в кадре размером [size] → доля 0–999. */
    fun normalize(p: Int, size: Int): Int {
        if (size <= 1) return 0
        return min(999, max(0, (p * 999.0 / (size - 1)).roundToInt()))
    }

    /** Доля 0–999 → пиксель. */
    fun toPixel(n: Int, size: Int): Int = (n * (size - 1) / 999.0).roundToInt()

    /** Дерево экрана почти без текста (игра, самописный интерфейс, барабаны): стоит прочитать текст с картинки. */
    fun needsOcr(textNodes: Int): Boolean = textNodes < MIN_TEXT_NODES

    /**
     * Строки для модели: «текст» и центр в пикселях экрана [width]×[height], сверху вниз, слева направо. Одинаковые строки в одной точке
     * схлопываются (две модели распознавания часто находят одно и то же).
     */
    fun format(lines: List<OcrLine>, width: Int, height: Int, limit: Int = MAX_LINES): String {
        val rows = lines.filter { it.text.isNotBlank() && it.bounds.size == 4 }.map {
            val cx = toPixel((it.bounds[0] + it.bounds[2]) / 2, width)
            val cy = toPixel((it.bounds[1] + it.bounds[3]) / 2, height)
            Triple(it.text.trim(), cx, cy)
        }.distinctBy { (t, x, y) -> Triple(t, x / DEDUP_PX, y / DEDUP_PX) }
            .sortedWith(compareBy({ it.third / ROW_PX }, { it.second }))
        if (rows.isEmpty()) return ""
        val shown = rows.take(limit)
        return buildString {
            append("Текст на экране (распознан по картинке, координаты — центр строки):")
            for ((t, x, y) in shown) append("\n  «").append(t).append("» (").append(x).append(", ").append(y).append(")")
            if (rows.size > shown.size) append("\n  … и ещё ").append(rows.size - shown.size)
        }
    }

    private const val MIN_TEXT_NODES = 3
    private const val MAX_LINES = 80
    private const val DEDUP_PX = 12
    private const val ROW_PX = 24
}

/** Ответ модели об итоге установки барабана. */
object WheelText {
    fun result(o: WheelOutcome, target: String): String {
        val now = o.value?.let { " Сейчас показано: «$it»." }.orEmpty()
        if (o.reached) return "Барабан установлен на «${o.value ?: target}»" + (o.from?.takeIf { it != o.value }?.let { " (было «$it»)" } ?: "") + "."
        val why = when (o.error) {
            "ref_expired" -> "Номер элемента устарел: вызови phone_look и укажи номер из свежего списка."
            "not_supported" -> "Барабан сейчас не поддерживается: нужна включённая служба доступности."
            "not_a_wheel" -> "Это не барабан выбора: элемент не прокручивается и в нём нет списка значений."
            "at_end" -> "Барабан дошёл до края, нужного значения «$target» на нём нет."
            "unknown_value" -> "Не удалось прочитать значения барабана (попробуй phone_look и укажи номер строки барабана)."
            "not_moving" -> "Барабан не двигается от перетаскивания."
            else -> "Не получилось довести барабан до «$target»."
        }
        return why + now
    }
}
