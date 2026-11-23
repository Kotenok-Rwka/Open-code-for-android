package com.pocketcode.ui

import org.junit.Assert.assertEquals
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
    fun settingsRoundTrip() {
        val c = AppearanceConfig(themeId = "nord", cornerRadius = 8, readingMode = true)
        val json = kotlinx.serialization.json.Json.encodeToString(AppearanceConfig.serializer(), c)
        val back = kotlinx.serialization.json.Json.decodeFromString<AppearanceConfig>(json)
        assertEquals(c, back)
    }
}
