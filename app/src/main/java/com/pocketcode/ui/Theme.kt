package com.pocketcode.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class Accent(val id: String, val title: String, val dark: Color, val light: Color)

val ACCENTS = listOf(
    Accent("blue", "Синий", Color(0xFF8AB4F8), Color(0xFF2F5DA8)),
    Accent("teal", "Бирюзовый", Color(0xFF4DD0C4), Color(0xFF00796B)),
    Accent("violet", "Фиолетовый", Color(0xFFB69DF8), Color(0xFF6A4CC4)),
    Accent("green", "Зелёный", Color(0xFF7BD88F), Color(0xFF2E7D45)),
    Accent("orange", "Оранжевый", Color(0xFFFFB067), Color(0xFFC25E00)),
    Accent("rose", "Розовый", Color(0xFFF48FB1), Color(0xFFB4246B)),
    Accent("vscode", "Как в VS Code", Color(0xFF4FC1FF), Color(0xFF007ACC)),
    Accent("sand", "Песочный", Color(0xFFE6C48F), Color(0xFF8A5A00)),
    Accent("contrast", "Контрастный", Color(0xFFFFE54C), Color(0xFF0030A0)),
)

/** Дополнительные цвета: появляются в выборе акцента после перехода по ссылке на экране «Спасибо автору». */
val EXTRA_ACCENTS = listOf(
    Accent("lime", "Лайм", Color(0xFFC6F24E), Color(0xFF5B7F00)),
    Accent("coral", "Коралл", Color(0xFFFF8A75), Color(0xFFB3402A)),
    Accent("indigo", "Индиго", Color(0xFF9FA8FF), Color(0xFF3F51B5)),
    Accent("gold", "Золотой", Color(0xFFFFCA28), Color(0xFF8D6E00)),
)

fun accentById(id: String): Accent? = ACCENTS.firstOrNull { it.id == id } ?: EXTRA_ACCENTS.firstOrNull { it.id == id }

/** Цвета для выбора: обычные, а дополнительные только если открыты. Уже выбранный цвет не пропадает, даже если настройки восстановили из копии. */
fun visibleAccents(unlocked: Boolean, selected: String): List<Accent> =
    ACCENTS + EXTRA_ACCENTS.filter { unlocked || it.id == selected }

/** Смешивает родной цвет подложки с «теплом» пользователя; без тепла возвращает цвет как есть. */
fun tintedColor(base: Color, tint: Color?, strength: Float): Color =
    if (tint == null || strength <= 0.001f) base else lerp(base, tint, strength.coerceIn(0f, 1f))

/** Прозрачные карточки («стекло»): фон просвечивает сквозь поверхности, но не сквозь текст. */
fun ColorScheme.withCardAlpha(alpha: Float): ColorScheme {
    val a = alpha.coerceIn(0.35f, 1f)
    if (a >= 0.999f) return this
    return copy(
        surfaceContainerLowest = surfaceContainerLowest.copy(alpha = a),
        surfaceContainerLow = surfaceContainerLow.copy(alpha = a),
        surfaceContainer = surfaceContainer.copy(alpha = a),
        surfaceContainerHigh = surfaceContainerHigh.copy(alpha = a),
        surfaceContainerHighest = surfaceContainerHighest.copy(alpha = a),
        surfaceVariant = surfaceVariant.copy(alpha = a),
    )
}

/** Своя палитра: одинакова на всех телефонах. amoled — чистый чёрный фон, tint — «тепло» подложки. */
fun buildScheme(dark: Boolean, amoled: Boolean, a: Accent, tint: Color? = null, tintStrength: Float = 0.35f, themeBase: Color? = null): ColorScheme {
    val white = Color.White
    val black = Color.Black
    val cardWhite = tintedColor(white, tint, tintStrength * 0.6f)
    return if (dark) {
        val base = tintedColor(if (amoled) black else themeBase ?: Color(0xFF111318), tint, tintStrength)
        val c = a.dark
        darkColorScheme(
            primary = c,
            onPrimary = Color(0xFF0B0F14),
            primaryContainer = lerp(base, c, 0.30f),
            onPrimaryContainer = lerp(c, white, 0.6f),
            secondary = lerp(c, white, 0.2f),
            onSecondary = Color(0xFF0B0F14),
            secondaryContainer = lerp(base, c, 0.18f),
            onSecondaryContainer = lerp(c, white, 0.55f),
            tertiary = c,
            background = base,
            onBackground = Color(0xFFE6E7EB),
            surface = base,
            onSurface = Color(0xFFE6E7EB),
            surfaceVariant = lerp(base, cardWhite, if (amoled) 0.12f else 0.13f),
            onSurfaceVariant = Color(0xFFABB1BD),
            // Слои без обводок: карточка (Container) заметно светлее фона, поля ввода и чипы (High) ещё светлее.
            surfaceContainerLowest = lerp(base, black, 0.4f),
            surfaceContainerLow = lerp(base, cardWhite, 0.045f),
            surfaceContainer = lerp(base, cardWhite, 0.075f),
            surfaceContainerHigh = lerp(base, cardWhite, 0.11f),
            surfaceContainerHighest = lerp(base, cardWhite, 0.15f),
            outline = Color(0xFF666D7A),
            outlineVariant = lerp(base, white, 0.10f),
        )
    } else {
        val base = tintedColor(themeBase ?: Color(0xFFF4F6F9), tint, tintStrength)
        val c = a.light
        lightColorScheme(
            primary = c,
            onPrimary = white,
            primaryContainer = lerp(cardWhite, c, 0.16f),
            onPrimaryContainer = lerp(c, black, 0.45f),
            secondary = lerp(c, black, 0.1f),
            secondaryContainer = lerp(cardWhite, c, 0.10f),
            onSecondaryContainer = lerp(c, black, 0.4f),
            tertiary = c,
            background = base,
            surface = base,
            onSurface = Color(0xFF1A1C20),
            surfaceVariant = lerp(base, black, 0.07f),
            onSurfaceVariant = Color(0xFF4A505C),
            // Карточки чуть светлее фона (с учётом тепла), поля ввода и чипы темнее фона.
            surfaceContainerLowest = cardWhite,
            surfaceContainerLow = lerp(base, cardWhite, 0.6f),
            surfaceContainer = cardWhite,
            surfaceContainerHigh = lerp(base, black, 0.045f),
            surfaceContainerHighest = lerp(base, black, 0.08f),
            outline = Color(0xFF8A909C),
            outlineVariant = lerp(base, black, 0.09f),
        )
    }
}

/**
 * Палитра в стиле iOS: светлая сгруппированная подложка и белые карточки, в тёмной теме почти чёрный фон
 * и карточки цвета 1C1C1E. Синий акцент заменяется системным синим iOS, остальные акценты берутся как есть.
 */
fun buildIosScheme(dark: Boolean, amoled: Boolean, a: Accent, tint: Color? = null, tintStrength: Float = 0.35f, themeBase: Color? = null): ColorScheme {
    val white = Color.White
    val black = Color.Black
    val cardWhite = tintedColor(white, tint, tintStrength * 0.6f)
    return if (dark) {
        val base = tintedColor(if (amoled) black else themeBase ?: Color(0xFF0D0D0F), tint, tintStrength)
        val c = if (a.id == "blue") Color(0xFF0A84FF) else a.dark
        val onC = if (a.id == "blue") white else Color(0xFF0B0F14)
        darkColorScheme(
            primary = c,
            onPrimary = onC,
            primaryContainer = lerp(base, c, 0.30f),
            onPrimaryContainer = lerp(c, white, 0.6f),
            secondary = c,
            onSecondary = onC,
            secondaryContainer = Color(0xFF2C2C2E),
            onSecondaryContainer = Color(0xFFF2F2F7),
            tertiary = c,
            background = base,
            onBackground = Color(0xFFF2F2F7),
            surface = base,
            onSurface = Color(0xFFF2F2F7),
            surfaceVariant = Color(0xFF2C2C2E),
            onSurfaceVariant = Color(0xFF98989F),
            surfaceContainerLowest = black,
            surfaceContainerLow = Color(0xFF161618),
            surfaceContainer = Color(0xFF1C1C1E),
            surfaceContainerHigh = Color(0xFF2C2C2E),
            surfaceContainerHighest = Color(0xFF3A3A3C),
            outline = Color(0xFF636366),
            outlineVariant = Color(0xFF38383A),
        )
    } else {
        val base = tintedColor(themeBase ?: Color(0xFFF2F2F7), tint, tintStrength)
        val c = if (a.id == "blue") Color(0xFF007AFF) else a.light
        lightColorScheme(
            primary = c,
            onPrimary = white,
            primaryContainer = lerp(cardWhite, c, 0.14f),
            onPrimaryContainer = lerp(c, black, 0.45f),
            secondary = c,
            onSecondary = white,
            secondaryContainer = lerp(base, black, 0.06f),
            onSecondaryContainer = Color(0xFF1C1C1E),
            tertiary = c,
            background = base,
            onBackground = black,
            surface = base,
            onSurface = black,
            surfaceVariant = lerp(base, black, 0.06f),
            onSurfaceVariant = Color(0xFF6E6E73),
            surfaceContainerLowest = cardWhite,
            surfaceContainerLow = lerp(base, cardWhite, 0.7f),
            surfaceContainer = cardWhite,
            surfaceContainerHigh = cardWhite,
            surfaceContainerHighest = lerp(base, black, 0.08f),
            outline = Color(0xFFC6C6C8),
            outlineVariant = Color(0xFFD1D1D6),
        )
    }
}

/**
 * Палитра стиля Aurora: глубокий фон, слоистые поверхности с тонкой обводкой и насыщенный акцент.
 * Отличается от Material 3 более контрастными границами и мягкими, но заметными карточками.
 */
fun buildAuroraScheme(dark: Boolean, amoled: Boolean, a: Accent, tint: Color? = null, tintStrength: Float = 0.35f, themeBase: Color? = null): ColorScheme {
    val white = Color.White
    val black = Color.Black
    val cardWhite = tintedColor(white, tint, tintStrength * 0.6f)
    return if (dark) {
        val base = tintedColor(if (amoled) black else themeBase ?: Color(0xFF0B0E14), tint, tintStrength)
        val c = a.dark
        darkColorScheme(
            primary = c,
            onPrimary = Color(0xFF071018),
            primaryContainer = lerp(base, c, 0.26f),
            onPrimaryContainer = lerp(c, white, 0.72f),
            secondary = lerp(c, white, 0.3f),
            onSecondary = Color(0xFF071018),
            secondaryContainer = lerp(base, c, 0.14f),
            onSecondaryContainer = lerp(c, white, 0.7f),
            tertiary = lerp(c, Color(0xFFFF8AB4), 0.35f),
            background = base,
            onBackground = Color(0xFFE8EBF2),
            surface = base,
            onSurface = Color(0xFFE8EBF2),
            surfaceVariant = lerp(base, cardWhite, 0.10f),
            onSurfaceVariant = Color(0xFFA8B0C0),
            surfaceContainerLowest = lerp(base, black, 0.35f),
            surfaceContainerLow = lerp(base, cardWhite, 0.035f),
            surfaceContainer = lerp(base, cardWhite, 0.055f),
            surfaceContainerHigh = lerp(base, cardWhite, 0.085f),
            surfaceContainerHighest = lerp(base, cardWhite, 0.115f),
            outline = Color(0xFF5C6472),
            outlineVariant = lerp(base, white, 0.09f),
        )
    } else {
        val base = tintedColor(themeBase ?: Color(0xFFF6F7FB), tint, tintStrength)
        val c = a.light
        lightColorScheme(
            primary = c,
            onPrimary = white,
            primaryContainer = lerp(white, c, 0.14f),
            onPrimaryContainer = lerp(c, black, 0.5f),
            secondary = lerp(c, black, 0.12f),
            onSecondary = white,
            secondaryContainer = lerp(white, c, 0.09f),
            onSecondaryContainer = lerp(c, black, 0.45f),
            tertiary = lerp(c, Color(0xFFB4246B), 0.3f),
            background = base,
            onBackground = Color(0xFF14161B),
            surface = base,
            onSurface = Color(0xFF14161B),
            surfaceVariant = lerp(base, black, 0.06f),
            onSurfaceVariant = Color(0xFF4C5361),
            surfaceContainerLowest = cardWhite,
            surfaceContainerLow = lerp(base, cardWhite, 0.7f),
            surfaceContainer = cardWhite,
            surfaceContainerHigh = lerp(base, black, 0.035f),
            surfaceContainerHighest = lerp(base, black, 0.07f),
            outline = Color(0xFF9AA1AE),
            outlineVariant = lerp(base, black, 0.07f),
        )
    }
}

/** Динамические цвета из обоев, но с чёрным фоном для AMOLED. */
fun amoledize(s: ColorScheme): ColorScheme = s.copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0F11), surfaceContainer = Color(0xFF15171A),
    surfaceContainerHigh = Color(0xFF1D2024), surfaceContainerHighest = Color(0xFF272B30),
)

/** Шкала Aurora: крупные заголовки с плотным трекингом, спокойный основной текст, читаемые подписи. */
val AuroraTypography = androidx.compose.material3.Typography(
    headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineSmall = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal),
    bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
)

val AppTypography = androidx.compose.material3.Typography(
    headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)
