package com.pocketcode.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/** Единые настройки анимаций приложения, уважающие «Уменьшить анимации». */
object Motion {
    @Composable
    fun ms(base: Int): Int = if (LocalReduceMotion.current) 0 else base

    @Composable
    fun spring(): androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        if (LocalReduceMotion.current) tween(0) else androidx.compose.animation.core.spring(dampingRatio = Spring.DampingRatioMediumBouncy)
}

/** Плавное нажатие для карточек: лёгкое уменьшение и подсветка. */
@Composable
fun rememberPressColor(base: Color, pressed: Color): androidx.compose.runtime.State<Color> {
    val interaction = remember { MutableInteractionSource() }
    val pressedNow by interaction.collectIsPressedAsState()
    return animateColorAsState(if (pressedNow) pressed else base, tween(Motion.ms(120)), label = "pressColor")
}

/** Модификатор «подъём при наведении/нажатии» для всех карточек. */
fun Modifier.animatedPress(enabled: Boolean = true): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s = if (enabled && pressed) 0.98f else 1f
    graphicsLayer(scaleX = s, scaleY = s).clickable(interactionSource = interaction, indication = null) { }
}
