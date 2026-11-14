// Основано на Mika (https://github.com/wangzan101/Mika), Apache-2.0, © 2026 wangzan101. Изменено для PocketCode.
package com.pocketcode.phone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Алгоритмы для барабанов выбора (время, дата, регион): узнать значение, посчитать направление и число шагов, оценить высоту ячейки.
 * Не зависят от способа прокрутки; чистая логика. «Вперёд» — значение на барабане идёт дальше (нижняя ячейка уходит в середину, число обычно растёт).
 */
object WheelMath {
    private val ONLY_NUMBER = Regex("""^-?\d{1,6}$""")
    private val LEADING_NUMBER = Regex("""^\D{0,3}?(\d{1,6})(?!\d)""")
    private val DIGITS = Regex("""(?<!\d)\d{1,6}(?!\d)""")
    private val SEPARATORS = Regex("""[,，、:：;；|/()（）\[\]【】<>《》·•]+""")

    /** Для сравнения: без пробелов, полноширинные цифры в обычные, без учёта регистра. */
    fun normalize(s: String): String = buildString {
        for (c in s.trim()) when {
            c.isWhitespace() -> Unit
            c in '０'..'９' -> append('0' + (c - '０'))
            else -> append(c.lowercaseChar())
        }
    }

    /** Целое число из подписи: «09» → 9, «9 ч» → 9, «2026 г.» → 2026, «Выбрано, 21» → 21. Нескольких чисел с ходу не угадываем: берём первое у начала. */
    fun number(s: String?): Int? {
        val t = s?.let(::normalize)?.ifEmpty { null } ?: return null
        if (ONLY_NUMBER.matches(t)) return t.toIntOrNull()
        DIGITS.findAll(t).toList().singleOrNull()?.let { return it.value.toIntOrNull() }
        return LEADING_NUMBER.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Подпись, разрезанная знаками препинания на части (после [normalize]). */
    fun segments(s: String): List<String> = normalize(s).split(SEPARATORS).filter { it.isNotEmpty() }

    /** Значение для модели: если в подпись подмешано слово-состояние («Выбрано, 21»), оставляем часть с цифрами. */
    fun clean(label: String?): String? {
        val t = label?.trim()?.ifEmpty { null } ?: return null
        val parts = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return t
        return parts.filter { p -> p.any { it.isDigit() || it in '０'..'９' } }.singleOrNull() ?: t
    }

    /** Цель — просто число («9», «09», «2026»). */
    fun numericTarget(target: String): Int? = normalize(target).takeIf(ONLY_NUMBER::matches)?.toIntOrNull()

    /** Показано ли сейчас на барабане нужное [target]: числа сравниваются по значению, текст — по совпадению или началу/концу подписи. */
    fun matches(shown: String?, target: String): Boolean {
        val s = shown?.let(::normalize)?.ifEmpty { null } ?: return false
        val t = normalize(target).ifEmpty { return false }
        numericTarget(target)?.let { return number(s) == it }
        fun affix(x: String) = x == t || x.startsWith(t) || x.endsWith(t)
        return affix(s) || segments(s).any(::affix)
    }

    /** Длина круга по видимым значениям: в числах один раз случается «падение» (58 59 00 01); не круг — null. */
    fun modulus(visible: List<String>): Int? {
        val n = visible.map { number(it) ?: return null }
        if (n.size < 2) return null
        val drops = (1 until n.size).filter { n[it] < n[it - 1] }
        if (drops.size != 1) return null
        val i = drops.single()
        return (n.subList(0, i).max() - n.subList(i, n.size).min() + 1).takeIf { it >= 2 }
    }

    /** Сколько шагов вперёд (отрицательное — назад) от [current] до [target]; при известном круге идём короткой стороной. */
    fun distance(current: Int, target: Int, modulus: Int?): Int {
        val d = target - current
        if (modulus == null || modulus < 2) return d
        val m = ((d % modulus) + modulus) % modulus
        return if (m <= modulus / 2) m else m - modulus
    }

    /** Сколько шагов прошёл барабан от [before] до [after]; не числа — null. */
    fun moved(before: String?, after: String?, modulus: Int?): Int? {
        val b = number(before) ?: return null
        val a = number(after) ?: return null
        return distance(b, a, modulus)
    }

    /** Высота ячейки: медиана расстояний между центрами соседних элементов; мало данных — null. */
    fun itemHeight(centers: List<Int>): Int? {
        val sorted = centers.sorted()
        val gaps = (1 until sorted.size).map { sorted[it] - sorted[it - 1] }.filter { it > 2 }
        if (gaps.isEmpty()) return null
        return gaps.sorted()[gaps.size / 2]
    }

    /** Индекс выбранного элемента: единственный помеченный выбранным; иначе накрывающий среднюю линию [centerY]; иначе ближайший к ней; нет — -1. */
    fun selectedIndex(items: List<Item>, centerY: Int, itemHeight: Int?): Int {
        items.indices.filter { items[it].selected }.singleOrNull()?.let { return it }
        if (items.isNotEmpty() && items.all { it.height > 0 }) {
            return items.indices.filter { abs(items[it].centerY - centerY) * 2 <= items[it].height }
                .minByOrNull { abs(items[it].centerY - centerY) } ?: -1
        }
        return selected(items.map { it.centerY }, centerY, itemHeight)
    }

    /** Если средняя ячейка не показана, а выше и ниже видны числа, возвращает значение средней (с тем же написанием); не вывести — null. */
    fun between(above: String, below: String): String? {
        val a = number(above) ?: return null
        val b = number(below) ?: return null
        val mid = when {
            abs(b - a) == 2 -> (a + b) / 2
            (a == 11 && b == 1) || (a == 1 && b == 11) -> 12
            b < a && b <= 2 -> if (b >= 1) b - 1 else a + 1
            a < b && a <= 2 -> if (a >= 1) a - 1 else b + 1
            else -> return null
        }
        if (mid < 0) return null
        val token = DIGITS.find(above.trim())?.value ?: return null
        val other = DIGITS.find(below.trim())?.value.orEmpty()
        val padded = listOf(token, other).any { it.length > 1 && it.startsWith('0') }
        return above.trim().replaceFirst(token, if (padded) mid.toString().padStart(token.length, '0') else mid.toString())
    }

    /** Столбец вокруг элемента [anchor]: подряд идущие элементы с примерно одинаковым шагом (заголовки и кнопки выше и ниже не входят). */
    fun column(items: List<Item>, anchor: Int): List<Item> {
        if (items.size < 3 || anchor !in items.indices) return items
        val near = listOfNotNull(
            items.getOrNull(anchor - 1)?.let { items[anchor].centerY - it.centerY },
            items.getOrNull(anchor + 1)?.let { it.centerY - items[anchor].centerY },
        ).filter { it > 2 }
        val h = near.minOrNull() ?: return items
        fun even(gap: Int) = gap > h * 0.4 && gap < h * 1.8
        var lo = anchor
        var hi = anchor
        while (lo > 0 && even(items[lo].centerY - items[lo - 1].centerY)) lo--
        while (hi < items.lastIndex && even(items[hi + 1].centerY - items[hi].centerY)) hi++
        return items.subList(lo, hi + 1)
    }

    /** Индекс элемента, ближайшего к средней линии; дальше трёх четвертей высоты ячейки — -1. */
    fun selected(centers: List<Int>, centerY: Int, itemHeight: Int?): Int {
        val best = centers.indices.minByOrNull { abs(centers[it] - centerY) } ?: return -1
        if (itemHeight != null && abs(centers[best] - centerY) > itemHeight * 0.75) return -1
        return best
    }

    /** Сколько шагов вперёд до цели (отрицательное — назад) или null, если по видимому не посчитать. */
    fun stepsTo(visible: List<String>, selectedIndex: Int, target: String, increasingForward: Boolean = true, knownModulus: Int? = null): Int? {
        if (selectedIndex !in visible.indices) return null
        visible.indexOfFirst { matches(it, target) }.takeIf { it >= 0 }?.let { return it - selectedIndex }
        val t = numericTarget(target) ?: return null
        val cur = number(visible[selectedIndex]) ?: return null
        val d = distance(cur, t, modulus(visible) ?: knownModulus)
        return if (increasingForward) d else -d
    }

    /** Разбивает [steps] ячеек на перетаскивания не длиннее [maxDrag]; положительное значение — палец вверх (вперёд). */
    fun drags(steps: Int, itemHeight: Int, maxDrag: Int): List<Int> {
        if (steps == 0 || itemHeight <= 0) return emptyList()
        val perDrag = (maxDrag / itemHeight).coerceAtLeast(1)
        val sign = if (steps > 0) 1 else -1
        val out = mutableListOf<Int>()
        var left = abs(steps)
        while (left > 0) {
            val n = minOf(left, perDrag)
            out += sign * n * itemHeight
            left -= n
        }
        return out
    }

    /** Видимый элемент барабана: текст, центр в пикселях, помечен ли выбранным, собственная высота (0 — неизвестна). */
    data class Item(val text: String, val centerX: Int, val centerY: Int, val selected: Boolean = false, val height: Int = 0)

    /** Похож ли вертикальный список на барабан: 3–9 коротких разных значений ровно по центру столбца с равными промежутками. */
    fun looksLikeWheel(items: List<Item>, left: Int, right: Int): Boolean {
        if (items.size !in 3..9 || right <= left) return false
        val texts = items.map { normalize(it.text) }
        if (texts.any { it.isEmpty() || it.length > MAX_ITEM_CHARS } || texts.toSet().size != texts.size) return false
        val mid = (left + right) / 2.0
        if (items.any { abs(it.centerX - mid) > (right - left) * 0.15 }) return false
        val ys = items.map { it.centerY }
        if (ys.zipWithNext().any { (a, b) -> b <= a }) return false
        val gaps = ys.zipWithNext { a, b -> b - a }
        return gaps.max() < gaps.min() * 1.5
    }

    private const val MAX_ITEM_CHARS = 8

    /** Реальная высота ячейки по итогу перетаскивания: [dragged] пикселей дали [moved] шагов; 0 шагов — null. */
    fun calibrated(dragged: Int, moved: Int): Int? =
        if (moved == 0) null else abs((dragged.toDouble() / moved).roundToInt()).takeIf { it > 2 }
}

/**
 * Следующий шаг при прокрутке барабана действиями доступности («вперёд/назад»). После каждой пачки сюда передают прочитанное значение,
 * а он говорит, куда и сколько раз прокрутить, или останавливается (at_end, not_moving, cap, overshoot, unknown_value).
 */
class WheelSteps(private val target: String, private val cap: Int = 80, private val burst: Int = 5) {
    sealed interface Step {
        object Done : Step
        data class Act(val forward: Boolean, val count: Int) : Step
        data class Stop(val reason: String) : Step
    }

    var actions: Int = 0
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var increasingForward = true
    private var calibrated = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastAct: Step.Act? = null
    private var reversals = 0
    private var scanForward = true
    private var scanReversed = false

    /** После пачки прокруток: выбранное значение [current] и видимые элементы [visible] сверху вниз. */
    fun next(current: String?, visible: List<String> = emptyList()): Step {
        if (WheelMath.matches(current, target)) return Step.Done
        if (current == null) return Step.Stop("unknown_value")
        WheelMath.modulus(visible)?.let { modulus = it }
        val prev = lastAct
        val unchanged = prev != null && current == last
        if (actions >= cap) return Step.Stop("cap")
        if (numeric != null && WheelMath.number(current) != null) {
            if (prev != null) {
                if (unchanged) return Step.Stop(if (modulus != null) "not_moving" else "at_end")
                val moved = WheelMath.moved(last, current, modulus)
                if (!calibrated && moved != null && moved != 0) {
                    calibrated = true
                    if ((moved > 0) != prev.forward) increasingForward = false
                }
            }
            val d = WheelMath.distance(WheelMath.number(current)!!, numeric, modulus).let { if (increasingForward) it else -it }
            val forward = d > 0
            val turned = prev != null && forward != prev.forward
            if (turned && ++reversals > 3) return Step.Stop("overshoot")
            val n = if (turned) 1 else minOf(abs(d), burst)
            return act(forward, n.coerceAtMost(cap - actions), current)
        }
        val sel = visible.indexOfFirst { it == current }
        val at = visible.indexOfFirst { WheelMath.matches(it, target) }
        if (sel >= 0 && at >= 0) return act(at > sel, minOf(abs(at - sel), burst, cap - actions), current)
        if (unchanged) {
            if (scanReversed) return Step.Stop("at_end")
            scanReversed = true
            scanForward = !scanForward
        }
        return act(scanForward, 1, current)
    }

    private fun act(forward: Boolean, count: Int, current: String?): Step {
        val a = Step.Act(forward, count.coerceAtLeast(1))
        last = current
        lastAct = a
        actions += a.count
        return a
    }
}

/**
 * Следующий шаг при перетаскивании барабана, который рисует само приложение. После каждого перетаскивания сюда передают прочитанный
 * столбец; он говорит, сколько ячеек и как тянуть, или останавливается (at_end, not_moving, not_reached, unknown_value).
 */
class WheelDrags(private val target: String, rowHeight: Int, private val maxMoves: Int = 5, private val maxStill: Int = 3) {
    sealed interface Step {
        object Done : Step
        data class Drag(val steps: Int, val boost: Double, val slow: Boolean) : Step
        data class Stop(val reason: String) : Step
    }

    var itemHeight: Int = rowHeight.coerceAtLeast(1)
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var started = false
    private var increasingForward = true
    private var directionKnown = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastDrag: Step.Drag? = null
    private var lastScanning = false
    private var scanForward = true
    private var scanReversed = false
    private var moves = 0
    private var still = 0

    /** После перетаскивания: элементы столбца, их центры, индекс выбранного и пройденные пальцем пиксели [dragged] (вверх — плюс). */
    fun next(items: List<String>, selected: Int, centers: List<Int> = emptyList(), dragged: Int = 0): Step {
        val current = items.getOrNull(selected)
        if (WheelMath.matches(current, target)) return Step.Done
        if (items.isEmpty()) return Step.Stop("unknown_value")
        if (!started) {
            started = true
            WheelMath.itemHeight(centers)?.let { itemHeight = it }
        }
        WheelMath.modulus(items)?.let { modulus = it }
        val prev = lastDrag
        if (prev != null) {
            if (current != last) {
                still = 0
                moves++
                val moved = WheelMath.moved(last, current, modulus)
                if (moved != null && moved != 0) {
                    if (!directionKnown && numeric != null) {
                        directionKnown = true
                        if ((moved > 0) != (dragged > 0)) increasingForward = false
                    }
                    WheelMath.calibrated(dragged, moved)?.let { itemHeight = it }
                }
            } else if (atEnd(items, selected, forward = prev.steps > 0)) {
                if (!lastScanning || scanReversed) return Step.Stop("at_end")
                scanReversed = true
                scanForward = !scanForward
            } else {
                still++
            }
        }
        if (still >= maxStill) return Step.Stop("not_moving")
        if (moves >= maxMoves) return Step.Stop("not_reached")
        val planned = WheelMath.stepsTo(items, selected, target, increasingForward, modulus)
        if (planned == 0) return Step.Stop("not_reached")
        lastScanning = planned == null
        val steps = planned ?: ((items.size - 1).coerceAtLeast(1) * if (scanForward) 1 else -1)
        last = current
        val boost = if (abs(steps) <= 1) 1.0 + BOOST * still else 1.0
        return Step.Drag(steps, boost, slow = still % 2 == 0).also { lastDrag = it }
    }

    private fun atEnd(items: List<String>, selected: Int, forward: Boolean): Boolean {
        if (modulus != null || items.size < 2 || selected !in items.indices) return false
        return if (forward) selected == items.lastIndex else selected == 0
    }

    private companion object {
        const val BOOST = 0.6
    }
}
