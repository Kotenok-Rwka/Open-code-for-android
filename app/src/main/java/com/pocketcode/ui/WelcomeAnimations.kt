package com.pocketcode.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Анимированный шаг приветствия: иконка пульсирует, текст плавно появляется. */
@Composable
fun AnimatedWelcomeStep(
    done: Boolean,
    index: Int,
    title: String,
    text: String,
    action: String?,
    onAction: () -> Unit,
) {
    val reduce = LocalReduceMotion.current
    val pulse = if (reduce) 1f else run {
        val t = rememberInfiniteTransition(label = "welcomePulse")
        t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "pulse").value
    }
    val color by animateColorAsState(
        if (done) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(if (reduce) 0 else 320), label = "stepColor",
    )
    val alpha by animateFloatAsState(1f, tween(if (reduce) 0 else 260 + index * 90), label = "stepFade")
    Row(
        Modifier.fillMaxWidth().alpha(alpha).clickable { if (!done) onAction() }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                if (done) "✓" else "${index + 1}",
                color = color,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.scale(pulse),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            androidx.compose.material3.Text(title, style = MaterialTheme.typography.titleSmall)
            androidx.compose.material3.Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.let { androidx.compose.material3.TextButton(onClick = onAction) { androidx.compose.material3.Text(it) } }
    }
}

/** Плавный блок: расширяется при раскрытии списка возможностей. */
@Composable
fun ExpandableTip(expanded: Boolean, title: String, content: @Composable () -> Unit) {
    val reduce = LocalReduceMotion.current
    Column(Modifier.fillMaxWidth().clickable { /* состояние управляется снаружи */ }) {
        androidx.compose.material3.Text(title, style = MaterialTheme.typography.titleSmall)
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(if (reduce) 0 else 220)) + expandVertically(tween(if (reduce) 0 else 260)),
            exit = fadeOut(tween(if (reduce) 0 else 160)) + shrinkVertically(tween(if (reduce) 0 else 200)),
        ) { content() }
    }
}
