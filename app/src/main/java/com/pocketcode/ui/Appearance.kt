package com.pocketcode.ui

import androidx.compose.ui.graphics.Color

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
    )

    fun byId(id: String): ThemePreset? = presets.firstOrNull { it.id == id }
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
    )

    fun byId(id: String): BackgroundPreset = presets.firstOrNull { it.id == id } ?: presets.first()
}

/** Настройки внешнего вида, которые не дублируются в Settings.kt. */
@kotlinx.serialization.Serializable
data class AppearanceConfig(
    val themeId: String = "default",
    val customColor: String = "",
    val fontFamily: String = "system",
    val monoFontFamily: String = "system",
    val chatFontScale: Float = 1f,
    val uiFontScale: Float = 1f,
    val animations: AnimationConfig = AnimationConfig(),
    val background: String = "none",
    val backgroundCustomColor: String = "#111318",
    val backgroundImage: String = "",
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
