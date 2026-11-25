package com.pocketcode

import kotlin.math.abs

/** Логика жестов чата: чистая функция, покрыта юнит-тестом (без Compose). */
object Gestures {
    /** Что делать по свайпу: NONE / REPLY / COPY / DRAFT. Порог и направление — в пикселях. */
    fun action(dx: Float, dy: Float, threshold: Float = 90f): String {
        if (abs(dx) < threshold || abs(dx) < abs(dy)) return "NONE"
        return if (dx < 0) "DRAFT" else "REPLY"  // свайп вправо — ответить, влево — в черновик
    }

    /** Двойной тап по сообщению — «цитата». */
    fun doubleTap(pressCount: Int): Boolean = pressCount == 2
}
