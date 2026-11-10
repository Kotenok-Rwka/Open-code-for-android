package com.pocketcode.ui

import androidx.compose.foundation.layout.Column
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

/** Верхняя панель экрана: в Material 3 слева, в iOS по центру на цвете фона. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    if (LocalUiStyle.current == UiStyle.IOS) {
        CenterAlignedTopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
    } else {
        TopAppBar(
            title = title,
            modifier = modifier,
            navigationIcon = navigationIcon,
            actions = actions,
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

/** Нижняя панель разделов: в iOS с тонкой линией сверху и без заливки под значком. */
@Composable
fun AppNavigationBar(content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (LocalUiStyle.current == UiStyle.IOS) {
        Column {
            HorizontalDivider(thickness = 0.5.dp, color = cs.outlineVariant)
            NavigationBar(containerColor = cs.surfaceContainerLow, tonalElevation = 0.dp, content = content)
        }
    } else {
        NavigationBar(containerColor = cs.surfaceContainer, content = content)
    }
}

/** Цвета пункта нижней панели под текущий стиль. */
@Composable
fun navItemColors(): NavigationBarItemColors {
    val cs = MaterialTheme.colorScheme
    return if (metricsOf(LocalUiStyle.current).navIndicator) {
        NavigationBarItemDefaults.colors()
    } else {
        NavigationBarItemDefaults.colors(
            selectedIconColor = cs.primary,
            selectedTextColor = cs.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = cs.onSurfaceVariant,
            unselectedTextColor = cs.onSurfaceVariant,
        )
    }
}
