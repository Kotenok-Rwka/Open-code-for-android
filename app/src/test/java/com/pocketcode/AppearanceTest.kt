package com.pocketcode.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun themeCatalogIsLarge() {
        assertTrue(ThemeCatalog.presets.size >= 14)
        assertEquals("Nord", ThemeCatalog.byId("nord")?.title)
        assertTrue(ThemeCatalog.accentFor("dracula", true).toString().isNotBlank())
    }

    @Test
    fun animationDurationsRespectLevelAndSpeed() {
        assertEquals(0, AnimationConfig(level = AnimationLevel.NONE).duration(200))
        val normal = AnimationConfig(level = AnimationLevel.NORMAL, speed = 1f).duration(200)
        val fast = AnimationConfig(level = AnimationLevel.NORMAL, speed = 2f).duration(200)
        assertTrue(fast < normal)
        assertTrue(AnimationConfig(level = AnimationLevel.RICH).duration(200) > normal)
    }

    @Test
    fun colorParsing() {
        assertNotNull(AppearanceCodec.parseColor("#8AB4F8"))
        assertNotNull(AppearanceCodec.parseColor("FF8AB4F8"))
        assertEquals(null, AppearanceCodec.parseColor("bad"))
    }

    @Test
    fun backgroundsIncludeNoneAndPresets() {
        assertEquals("Без фона", BackgroundCatalog.byId("none").title)
        assertTrue(BackgroundCatalog.presets.size >= 8)
        assertTrue(BackgroundCatalog.byId("aurora").colors.size >= 2)
    }

    @Test
    fun accentPaletteIsAvailableAndBackgroundsPersist() {
        assertTrue(ACCENTS.size >= 8)
        assertNotNull(accentById("violet"))
        val c = AppearanceConfig(accentId = "violet", background = "image", backgroundImage = "my.jpg")
        val j = kotlinx.serialization.json.Json { encodeDefaults = true }
        val json = j.encodeToString(AppearanceConfig.serializer(), c)
        val back = j.decodeFromString<AppearanceConfig>(json)
        assertEquals("violet", back.accentId)
        assertEquals("image", back.background)
        assertEquals("my.jpg", back.backgroundImage)
    }

    @Test
    fun legacyAppearanceWithoutAccentStillDecodes() {
        val old = """{"themeId":"default","customColor":"","background":"none","cornerRadius":20}"""
        val c = kotlinx.serialization.json.Json.decodeFromString<AppearanceConfig>(old)
        assertEquals("blue", c.accentId)
    }

    @Test
    fun backdropDetection() {
        assertFalse(hasBackdrop("none"))
        assertFalse(hasBackdrop(""))
        assertTrue(hasBackdrop("aurora"))
        assertTrue(hasBackdrop("image"))
    }

    @Test
    fun lightThemeCanBeWarmInsteadOfPureWhite() {
        val plain = buildScheme(false, false, ACCENTS.first())
        val warm = buildScheme(false, false, ACCENTS.first(), Color(0xFFF7E8D2), 0.6f)
        assertTrue(plain.background != warm.background)
        // Карточки тоже тёплые, а не чисто белые.
        assertTrue(plain.surfaceContainer != warm.surfaceContainer)
    }

    @Test
    fun zeroTintStrengthKeepsThemeAsIs() {
        val plain = buildScheme(false, false, ACCENTS.first())
        val same = buildScheme(false, false, ACCENTS.first(), Color(0xFFF7E8D2), 0f)
        assertEquals(plain.background, same.background)
    }

    @Test
    fun cardOpacityMakesSurfacesTranslucent() {
        val solid = buildScheme(true, false, ACCENTS.first())
        val glass = solid.withCardAlpha(0.5f)
        assertEquals(1f, solid.surfaceContainer.alpha, 0.001f)
        assertTrue(glass.surfaceContainer.alpha < 0.6f)
        assertEquals(solid.primary, glass.primary)
    }

    @Test
    fun newAppearanceFieldsSurviveRoundTrip() {
        val c = AppearanceConfig(
            background = "image",
            backgroundImage = "wall.jpg",
            backgroundScrim = 0.7f,
            backgroundFit = "fit",
            backgroundBlur = 12f,
            baseTint = "#F7E8D2",
            baseTintStrength = 0.5f,
            cardOpacity = 0.8f,
        )
        val json = kotlinx.serialization.json.Json.encodeToString(AppearanceConfig.serializer(), c)
        val back = kotlinx.serialization.json.Json.decodeFromString<AppearanceConfig>(json)
        assertEquals(c, back)
        assertEquals("fit", back.backgroundFit)
        assertEquals(0.8f, back.cardOpacity, 0.001f)
    }

    @Test
    fun baseTintsAreAvailable() {
        assertTrue(BaseTints.presets.size >= 6)
        assertTrue(BaseTints.presets.any { it.hex.isNotBlank() })
        assertEquals("", BaseTints.byId("unknown").hex)
    }

    @Test
    fun settingsRoundTrip() {
        val c = AppearanceConfig(themeId = "nord", cornerRadius = 8, readingMode = true)
        val json = kotlinx.serialization.json.Json.encodeToString(AppearanceConfig.serializer(), c)
        val back = kotlinx.serialization.json.Json.decodeFromString<AppearanceConfig>(json)
        assertEquals(c, back)
    }

    @Test
    fun themeBackgroundReachesTheScheme() {
        assertNotNull(ThemeCatalog.byId("rosepine"))
        for (dark in listOf(true, false)) {
            val base = ThemeCatalog.backgroundFor("rosepine", dark)!!
            assertEquals(base, buildScheme(dark, false, ACCENTS.first(), themeBase = base).background)
            assertEquals(base, buildIosScheme(dark, false, ACCENTS.first(), themeBase = base).background)
            assertEquals(base, buildAuroraScheme(dark, false, ACCENTS.first(), themeBase = base).background)
        }
        val dark = ThemeCatalog.backgroundFor("rosepine", true)
        assertEquals(Color.Black, buildScheme(true, true, ACCENTS.first(), themeBase = dark).background)
        assertEquals(null, ThemeCatalog.backgroundFor("no-such-theme", true))
    }
}
