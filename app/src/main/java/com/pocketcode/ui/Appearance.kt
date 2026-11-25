package com.pocketcode.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Готовая цветовая тема: пары акцентов для светлой и тёмной схемы. */
data class ThemePreset(
    val id: String,
    val title: String,
    val light: Color,
    val dark: Color,
    val backgroundLight: Long? = null,
    val backgroundDark: Long? = null,
)

object ThemeCatalog {
    val presets = listOf(
        ThemePreset("default", "По умолчанию", Color(0xFF2F5DA8), Color(0xFF8AB4F8)),
        ThemePreset("nord", "Nord", Color(0xFF5E81AC), Color(0xFF88C0D0), 0xFFECEFF4, 0xFF2E3440),
        ThemePreset("dracula", "Dracula", Color(0xFFBD93F9), Color(0xFFFF79C6), 0xFFF8F8F2, 0xFF282A36),
        ThemePreset("solarized", "Solarized", Color(0xFF268BD2), Color(0xFF2AA198), 0xFFFDF6E3, 0xFF002B36),
        ThemePreset("gruvbox", "Gruvbox", Color(0xFFAF3A03), Color(0xFFFABD2F), 0xFFFBF1C7, 0xFF282828),
        ThemePreset("catppuccin", "Catppuccin Mocha", Color(0xFF8839EF), Color(0xFFCBA6F7), 0xFFEFF1F5, 0xFF1E1E2E),
        ThemePreset("tokyo", "Tokyo Night", Color(0xFF7AA2F7), Color(0xFFBB9AF7), 0xFFE1E4FF, 0xFF1A1B26),
        ThemePreset("github", "GitHub Dark", Color(0xFF0969DA), Color(0xFF58A6FF), 0xFFFFFFFF, 0xFF0D1117),
        ThemePreset("sepia", "Sepia", Color(0xFF8B5A2B), Color(0xFFD2A679), 0xFFF4ECD8, 0xFF2A211A),
        ThemePreset("contrast", "Высокий контраст", Color(0xFF0000EE), Color(0xFFFFFF00), 0xFFFFFFFF, 0xFF000000),
        ThemePreset("midnight", "Midnight Blue", Color(0xFF1E3A8A), Color(0xFF60A5FA), 0xFFE6EDF7, 0xFF0B1020),
        ThemePreset("forest", "Forest", Color(0xFF2E7D32), Color(0xFF81C784), 0xFFEDF7ED, 0xFF10231A),
        ThemePreset("berry", "Berry", Color(0xFFAD1457), Color(0xFFF48FB1), 0xFFFCE4EC, 0xFF2A1020),
        ThemePreset("mono", "Монохром", Color(0xFF424242), Color(0xFFE0E0E0), 0xFFFFFFFF, 0xFF121212),
        ThemePreset("lavender", "Лаванда", Color(0xFF7E57C2), Color(0xFFB39DDB), 0xFFF7F3FF, 0xFF1E1730),
        ThemePreset("ocean", "Океан", Color(0xFF0277BD), Color(0xFF4FC3F7), 0xFFE8F6FF, 0xFF0A1F2E),
        ThemePreset("matcha", "Матча", Color(0xFF558B2F), Color(0xFFAED581), 0xFFF6FBE9, 0xFF18240F),
        ThemePreset("sunset", "Закат", Color(0xFFEF6C00), Color(0xFFFFB74D), 0xFFFFF3E0, 0xFF2B1608),
        ThemePreset("rosepine", "Rosé Pine", Color(0xFF286983), Color(0xFFEBBCBA), 0xFFFAF4ED, 0xFF191724),
        ThemePreset("nord", "Норд", Color(0xFF5E81AC), Color(0xFF88C0D0), 0xFFECF0F6, 0xFF2E3440),
        ThemePreset("dracula", "Дракула", Color(0xFFBD93F9), Color(0xFFFF79C6), 0xFFFDF6E3, 0xFF282A36),
    )

    fun byId(id: String): ThemePreset? = presets.firstOrNull { it.id == id }

    /** Цвет подложки темы; null — у темы нет своего фона и остаётся фон стиля интерфейса. */
    fun backgroundFor(id: String, dark: Boolean): Color? {
        val p = byId(id) ?: return null
        return (if (dark) p.backgroundDark else p.backgroundLight)?.let { Color(it) }
    }
    fun accentFor(id: String, dark: Boolean): Color {
        val p = byId(id) ?: return if (dark) Color(0xFF8AB4F8) else Color(0xFF2F5DA8)
        return if (dark) p.dark else p.light
    }
}

/** Уровень анимаций: 0 — выкл, 1 — минимум, 2 — норма, 3 — много. */
@kotlinx.serialization.Serializable
enum class AnimationLevel(val label: String) {
    NONE("Выключены"), MINIMAL("Минимум"), NORMAL("Обычные"), RICH("Много"),
}

@kotlinx.serialization.Serializable
enum class EasingPreset(val label: String) { STANDARD("Стандарт"), SMOOTH("Плавно"), SNAPPY("Резко") }

@kotlinx.serialization.Serializable
data class AnimationConfig(
    val level: AnimationLevel = AnimationLevel.NORMAL,
    val speed: Float = 1f,
    val easing: EasingPreset = EasingPreset.STANDARD,
    val screenTransitions: Boolean = true,
    val listAnimations: Boolean = true,
    val pulseIndicators: Boolean = true,
) {
    fun duration(baseMs: Int): Int = when (level) {
        AnimationLevel.NONE -> 0
        AnimationLevel.MINIMAL -> (baseMs * 0.4f / speed.coerceIn(0.25f, 4f)).toInt()
        AnimationLevel.NORMAL -> (baseMs / speed.coerceIn(0.25f, 4f)).toInt()
        AnimationLevel.RICH -> (baseMs * 1.4f / speed.coerceIn(0.25f, 4f)).toInt()
    }
}

/** Фон экрана: ничего, встроенный градиент/узор, свой цвет, своё изображение. */
@kotlinx.serialization.Serializable
enum class BackgroundKind(val label: String) {
    NONE("Без фона"), BUILTIN("Встроенный"), COLOR("Свой цвет"), IMAGE("Своё изображение"),
}

data class BackgroundPreset(val id: String, val title: String, val colors: List<Long>, val angle: Int = 0)

object BackgroundCatalog {
    val presets = listOf(
        BackgroundPreset("none", "Без фона", emptyList()),
        BackgroundPreset("aurora", "Аврора", listOf(0xFF0F2027, 0xFF203A43, 0xFF2C5364)),
        BackgroundPreset("sunset", "Закат", listOf(0xFFFF7E5F, 0xFFFFB475, 0xFFF8B500)),
        BackgroundPreset("ocean", "Океан", listOf(0xFF1A2980, 0xFF26D0CE)),
        BackgroundPreset("forest", "Лес", listOf(0xFF134E5E, 0xFF71B280)),
        BackgroundPreset("berry", "Ягода", listOf(0xFF4A0E4E, 0xFF9D174D, 0xFFF43F5E)),
        BackgroundPreset("graphite", "Графит", listOf(0xFF232526, 0xFF414345)),
        BackgroundPreset("paper", "Бумага", listOf(0xFFFDFBFB, 0xFFEBEDEE)),
        BackgroundPreset("mint", "Мята", listOf(0xFF00B09B, 0xFF96C93D)),
        BackgroundPreset("plum", "Слива", listOf(0xFF2B1055, 0xFF7597DE)),
        BackgroundPreset("coffee", "Кофе", listOf(0xFF3E2723, 0xFF8D6E63)),
        BackgroundPreset("sunrise", "Восход", listOf(0xFFFF9A8B, 0xFFFF6A88, 0xFFFF99AC)),
        BackgroundPreset("night", "Ночь", listOf(0xFF0F0C29, 0xFF302B63, 0xFF24243E)),
        BackgroundPreset("clouds", "Облака", listOf(0xFFE8F0FE, 0xFFD7E4F7)),
        BackgroundPreset("peach", "Персик", listOf(0xFFFFD3A5, 0xFFFD6585)),
        BackgroundPreset("steel", "Сталь", listOf(0xFF485563, 0xFF29323C)),
        BackgroundPreset("neon", "Неон", listOf(0xFF00F5A0, 0xFF00D9F5)),
        BackgroundPreset("candy", "Карамель", listOf(0xFFFBD3E9, 0xFFBB377D)),
        BackgroundPreset("lilac", "Сирень", listOf(0xFFD3CCE3, 0xFFE9E4F0)),
    )

    fun byId(id: String): BackgroundPreset = presets.firstOrNull { it.id == id } ?: presets.first()
}

/** true — выбран любой фон, отличный от «без фона»: тогда подложка экранов становится прозрачной. */
fun hasBackdrop(id: String): Boolean = id.isNotBlank() && id != "none"

/** Цвет «тепла» подложки: смешивается с родным фоном темы, чтобы светлая тема не была чисто белой. */
data class BaseTintPreset(val id: String, val title: String, val hex: String)

object BaseTints {
    val presets = listOf(
        BaseTintPreset("default", "Родной", ""),
        BaseTintPreset("warm", "Тёплый", "#F7E8D2"),
        BaseTintPreset("beige", "Бежевый", "#EFE2CB"),
        BaseTintPreset("cool", "Холодный", "#E4ECFA"),
        BaseTintPreset("mint", "Мятный", "#E2F3E7"),
        BaseTintPreset("rose", "Розовый", "#F9E6EC"),
        BaseTintPreset("lavender", "Лаванда", "#ECE4F8"),
        BaseTintPreset("slate", "Графит", "#E1E5EA"),
    )

    fun byId(id: String): BaseTintPreset = presets.firstOrNull { it.id == id } ?: presets.first()
}

/** Варианты вписывания фонового изображения. */
val BACKGROUND_FITS = listOf("crop" to "Заполнить", "fit" to "Вписать", "fill" to "Растянуть")

/** Настройки внешнего вида, которые не дублируются в Settings.kt. */
@kotlinx.serialization.Serializable
data class AppearanceConfig(
    val themeId: String = "default",
    /** Идентификатор палитры из ACCENTS/EXTRA_ACCENTS, либо пусто = цвет темы. */
    val accentId: String = "blue",
    val customColor: String = "",
    val fontFamily: String = "system",
    val monoFontFamily: String = "system",
    val chatFontScale: Float = 1f,
    val uiFontScale: Float = 1f,
    val animations: AnimationConfig = AnimationConfig(),
    val background: String = "none",
    val backgroundCustomColor: String = "#111318",
    val backgroundImage: String = "",
    /** Насколько подложка прозрачная: 0 — фон не видно, 0.85 — почти открытый фон. */
    val backgroundScrim: Float = 0.55f,
    /** crop | fit | fill — как вписать картинку фона. */
    val backgroundFit: String = "crop",
    /** Размытие картинки фона в dp, 0 — без размытия. */
    val backgroundBlur: Float = 0f,
    /** Цвет «тепла» подложки, #RRGGBB; пусто — родной цвет темы. */
    val baseTint: String = "",
    /** Насколько сильно тепло подложки смешивается с темой, 0..1. */
    val baseTintStrength: Float = 0.35f,
    /** Прозрачность карточек (стекло), 0.35..1. При включённом фоне по умолчанию стекло, а не чёрные плиты. */
    val cardOpacity: Float = 0.55f,
    val cornerRadius: Int = 20,
    val strokeWidth: Int = 1,
    val readingMode: Boolean = false,
    val themeJson: String = "",
)

object AppearanceCodec {
    fun parseColor(hex: String): Color? {
        val s = hex.trim().removePrefix("#")
        if (s.length !in setOf(6, 8)) return null
        val v = s.toULongOrNull(16) ?: return null
        return if (s.length == 6) Color((0xFF000000uL or v).toLong()) else Color(v.toLong())
    }
}

/** Шрифт, выбранный в «Оформлении»: файл из filesDir/fonts; null — оставить системный. */
fun appearanceTypeface(name: String, filesDir: java.io.File): android.graphics.Typeface? {
    if (name.isBlank() || name == "system") return null
    val f = java.io.File(filesDir, "fonts/$name")
    return if (f.isFile) runCatching { android.graphics.Typeface.createFromFile(f) }.getOrNull() else null
}

/**
 * Типографика с пользовательским шрифтом, масштабом чата и режимом чтения.
 * Шрифт применяется ко всем стилям; масштаб и увеличенный интерлиньяж — к основному тексту (body).
 */
fun styledTypography(
    base: androidx.compose.material3.Typography,
    family: androidx.compose.ui.text.font.FontFamily?,
    chatScale: Float,
    readingMode: Boolean,
): androidx.compose.material3.Typography {
    val mul = chatScale.coerceIn(0.7f, 2f) * if (readingMode) 1.15f else 1f
    val lh = if (readingMode) 1.3f else 1f
    if (family == null && mul == 1f && lh == 1f) return base
    fun s(t: androidx.compose.ui.text.TextStyle, body: Boolean) = t.copy(
        fontFamily = family ?: t.fontFamily,
        fontSize = if (body) t.fontSize * mul else t.fontSize,
        lineHeight = if (body) t.lineHeight * mul * lh else t.lineHeight,
    )
    return androidx.compose.material3.Typography(
        displayLarge = s(base.displayLarge, false),
        displayMedium = s(base.displayMedium, false),
        displaySmall = s(base.displaySmall, false),
        headlineLarge = s(base.headlineLarge, false),
        headlineMedium = s(base.headlineMedium, false),
        headlineSmall = s(base.headlineSmall, false),
        titleLarge = s(base.titleLarge, false),
        titleMedium = s(base.titleMedium, false),
        titleSmall = s(base.titleSmall, false),
        bodyLarge = s(base.bodyLarge, true),
        bodyMedium = s(base.bodyMedium, true),
        bodySmall = s(base.bodySmall, false),
        labelLarge = s(base.labelLarge, false),
        labelMedium = s(base.labelMedium, false),
        labelSmall = s(base.labelSmall, false),
    )
}

/** Скругления из «Оформления»: единая шкала от радиуса large. */
fun appearanceShapes(radius: Int): androidx.compose.material3.Shapes {
    val r = radius.coerceIn(0, 40)
    return androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape((r / 4).coerceAtLeast(2).dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape((r / 2).dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape((r * 3 / 4).dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(r.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape((r * 3 / 2).dp),
    )
}
