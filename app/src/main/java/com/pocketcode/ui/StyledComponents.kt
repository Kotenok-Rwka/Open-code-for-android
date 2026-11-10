package com.pocketcode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Текущий стиль оформления; задаётся один раз в теме приложения. */
val LocalUiStyle = compositionLocalOf { DEFAULT_UI_STYLE }

/** true: анимации выключены (настройка приложения или системная шкала анимации 0). */
val LocalReduceMotion = compositionLocalOf { false }

/** Скругления по спецификации Material 3 или как у элементов iOS. */
fun appShapes(style: UiStyle): Shapes = when (style) {
    UiStyle.MD3 -> Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    UiStyle.IOS -> Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(14.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )
}

/** Шкала текста iPhone: крупный заголовок 34, заголовок 17, основной 17, подписи 13. */
private val IosTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

fun appTypography(style: UiStyle): Typography = if (style == UiStyle.IOS) IosTypography else AppTypography

/** Значок «назад»: стрелка в Material 3, шеврон в iOS. */
@Composable
fun backIcon(): ImageVector = if (LocalUiStyle.current == UiStyle.IOS) AppIcons.ChevronLeft else AppIcons.Back

/** Верхняя панель экрана: плоская, на цвете фона; в Material 3 заголовок слева, в iOS по центру. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.background,
    )
    if (LocalUiStyle.current == UiStyle.IOS) {
        CenterAlignedTopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
        )
    } else {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
        )
    }
}

private val IosGreen = Color(0xFF34C759)

/** Переключатель: в Material 3 с галочкой на ползунке, в iOS зелёный с белым ползунком. */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tick = rememberTick()
    val change: ((Boolean) -> Unit)? = onCheckedChange?.let { f -> { v: Boolean -> tick(); f(v) } }
    if (LocalUiStyle.current == UiStyle.IOS) {
        Switch(
            checked = checked,
            onCheckedChange = change,
            modifier = modifier,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = IosGreen,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    } else {
        Switch(
            checked = checked,
            onCheckedChange = change,
            modifier = modifier,
            enabled = enabled,
            thumbContent = if (checked) ({
                Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            }) else null,
        )
    }
}

/**
 * Нижняя панель разделов: плавающая «капсула» с отступами от краёв и мягкой тенью.
 * Пункты рисует AppNavItem.
 */
@Composable
fun AppNavigationBar(content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ios = LocalUiStyle.current == UiStyle.IOS
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp)) {
        Surface(
            shape = RoundedCornerShape(if (ios) 34.dp else 30.dp),
            color = cs.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 10.dp,
            border = BorderStroke(0.5.dp, cs.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(if (ios) 68.dp else 64.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Пункт нижней панели. В Material 3 выбранный раздел растягивается в «таблетку» с подписью, в iOS подпись всегда под значком. */
@Composable
fun AppNavItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val ios = LocalUiStyle.current == UiStyle.IOS
    val tick = rememberTick()
    val tint = if (selected) cs.primary else cs.onSurfaceVariant
    val bg = when {
        !selected -> Color.Transparent
        ios -> cs.onSurface.copy(alpha = 0.08f)
        else -> cs.primary.copy(alpha = 0.16f)
    }
    val reduce = LocalReduceMotion.current
    val base = modifier
        .then(if (reduce || ios) Modifier else Modifier.animateContentSize())
        .clip(RoundedCornerShape(if (ios) 27.dp else 23.dp))
        .background(bg)
        .clickable { tick(); onClick() }
    if (ios) {
        Column(
            base.height(54.dp).padding(horizontal = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
        }
    } else {
        Row(
            base.height(46.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
            if (selected) Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}
