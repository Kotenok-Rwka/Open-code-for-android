package com.pocketcode.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** Карточка в текущем стиле: тональная с большим скруглением в Material 3, плоская белая или тёмно-серая в iOS. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    emphasized: Boolean = false,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val style = LocalUiStyle.current
    val shape = RoundedCornerShape(metricsOf(style).cardRadius.dp)
    val container = when {
        emphasized -> cs.primaryContainer
        style == UiStyle.IOS -> cs.surfaceContainer
        else -> cs.surfaceContainerHigh
    }
    val colors = CardDefaults.cardColors(containerColor = container)
    if (onClick != null) {
        Card(onClick = onClick, shape = shape, colors = colors, modifier = modifier, content = { content() })
    } else {
        Card(shape = shape, colors = colors, modifier = modifier, content = { content() })
    }
}

/** Пустое состояние: значок, заголовок, пояснение и по желанию кнопка действия. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(34.dp)) }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

/** Три пульсирующие точки: агент думает или работает. */
@Composable
fun ThinkingDots(modifier: Modifier = Modifier) {
    if (LocalReduceMotion.current) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0 until 3) Box(Modifier.size(7.dp).alpha(0.6f).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        return
    }
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val a by t.animateFloat(
                initialValue = 0.25f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 600, delayMillis = i * 160, easing = LinearEasing), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** Лёгкая вибрация-отклик на действие (отправка, переключатель). */
@Composable
fun rememberTick(): () -> Unit {
    val h = LocalHapticFeedback.current
    return { runCatching { h.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** Рамка для блока кода и строк инструментов: в iOS тоньше и без тени. */
@Composable
fun hairline(): BorderStroke = BorderStroke(
    if (LocalUiStyle.current == UiStyle.IOS) 0.5.dp else 1.dp,
    MaterialTheme.colorScheme.outlineVariant,
)

/** Строка-подпись под значком в карточке инструмента: цвет состояния. */
fun toolStateLabel(running: Boolean, error: Boolean): String = when {
    error -> "ошибка"
    running -> "выполняется…"
    else -> "готово"
}

/** Короткое имя файла или команды из описания вызова инструмента: до первой строки и 80 знаков. */
fun toolTitle(description: String): String {
    val first = description.trim().lineSequence().firstOrNull().orEmpty().trim()
    return if (first.length > 80) first.take(80) + "…" else first
}

/** Кнопка-строка с нажатием по всей ширине; нужна там, где Card(onClick) тянет лишнюю тень. */
@Composable
fun TapRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().clickable(onClick = onClick)) { content() }
}

/** Серые «заглушки» строк списка, пока данные грузятся. Мерцают, если анимации не уменьшены. */
@Composable
fun SkeletonRows(count: Int = 6, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "skeleton")
    val pulse by t.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val a = if (LocalReduceMotion.current) 0.5f else pulse
    val c = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for (i in 0 until count) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).alpha(a).clip(RoundedCornerShape(10.dp)).background(c))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.fillMaxWidth(skeletonWidth(i)).height(12.dp).alpha(a).clip(RoundedCornerShape(6.dp)).background(c))
                    Box(Modifier.fillMaxWidth(skeletonWidth(i) * 0.55f).height(10.dp).alpha(a).clip(RoundedCornerShape(5.dp)).background(c))
                }
            }
        }
    }
}

/** Ширина строки-заглушки по номеру: разная, но всегда между 0.45 и 0.9, чтобы не выглядело как таблица. */
fun skeletonWidth(index: Int): Float = 0.45f + ((index * 37) % 10) * 0.05f

/** Следующее число показанных знаков: догоняет весь текст быстро, но не рывком (минимум 1 знак за кадр). */
fun revealStep(shown: Int, total: Int): Int {
    if (shown >= total) return total
    return minOf(total, shown + maxOf(1, (total - shown) / 8))
}

/** Обрезка текста по числу знаков без разрыва суррогатной пары (эмодзи). */
fun cutText(text: String, n: Int): String {
    if (n >= text.length) return text
    if (n <= 0) return ""
    return if (Character.isHighSurrogate(text[n - 1])) text.substring(0, n - 1) else text.substring(0, n)
}

/** Текст ответа агента, который плавно «дописывается», пока идёт работа. */
@Composable
fun SmoothMarkdown(text: String, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(0) }
    LaunchedEffect(text) {
        if (shown > text.length) shown = text.length
        while (shown < text.length) {
            kotlinx.coroutines.delay(16)
            shown = revealStep(shown, text.length)
        }
    }
    MarkdownText(cutText(text, shown), modifier = modifier)
}
