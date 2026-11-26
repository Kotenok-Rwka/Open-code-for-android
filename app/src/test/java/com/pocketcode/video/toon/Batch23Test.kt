package com.pocketcode.video.toon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch23Test {

    @Test
    fun richLinesRoundTrip() {
        val all = ToonRichLib.defaults()
        assertEquals(6, all.size)
        val back = ToonRichLib.parse(ToonRichLib.serialize(all))
        assertEquals(all, back)
        // пользовательская правка одной сцены заменяет только её
        val custom = ToonRich(ToonScene.ROOM, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        val merged = ToonRichLib.merge(all, listOf(custom))
        assertEquals(6, merged.size)
        assertEquals(custom, merged.first { it.scene == ToonScene.ROOM })
        assertEquals(all.first { it.scene == ToonScene.SEA }, merged.first { it.scene == ToonScene.SEA })
    }

    @Test
    fun richLineRejectsGarbage() {
        assertNull(ToonRich.fromLine("мусор"))
        assertNull(ToonRich.fromLine("NIGHT|zz|0|0|0|0|0|0|0|0"))
        assertNotNull(ToonRich.fromLine(ToonRichLib.defaults().first().toLine()))
        assertEquals(0, ToonRichLib.parse("# комментарий\n\n").size)
    }

    @Test
    fun wallpaperTilesCoversCanvasStaggered() {
        val tiles = ToonRichGeom.wallpaperTiles(720, 1280, 64)
        assertTrue(tiles.isNotEmpty())
        // все в пределах кадра
        assertTrue(tiles.all { it.first in 0f..720f && it.second in 0f..1280f })
        // шахматный сдвиг: есть тайл на сдвинутой полусмещённой строке
        assertTrue(tiles.any { it.first % 64f == 32f })
        // без дубликатов
        assertEquals(tiles.size, tiles.toSet().size)
    }

    @Test
    fun curtainFoldsDenseTowardInnerEdge() {
        // штора слева: полотно от x=0 вправо, складки сгущаются у правого (внутреннего) края
        val folds = ToonRichGeom.curtainFolds(0f, 200f, 4)
        assertEquals(4, folds.size)
        assertTrue(folds.zipWithNext().all { (a, b) -> b > a })
        val gaps = folds.zipWithNext { a, b -> b - a }
        assertTrue(gaps.last() < gaps.first()) // шаг уменьшается к внутреннему краю
        assertEquals(emptyList<Float>(), ToonRichGeom.curtainFolds(0f, 0f, 3))
    }

    @Test
    fun heroShadowOffsetsFromCenterLight() {
        val left = ToonRichGeom.heroShadow(120f, 900f, 1f)
        val right = ToonRichGeom.heroShadow(600f, 900f, 1f)
        assertEquals(900f, left[1], 0.01f)
        // герой левее центра — тень вправо, и наоборот
        assertTrue(left[0] > 120f)
        assertTrue(right[0] < 600f)
        // масштаб уменьшает радиус и смещение
        val small = ToonRichGeom.heroShadow(120f, 900f, 0.8f)
        assertTrue(small[2] < left[2])
    }

    @Test
    fun swayVignetteLampPicture() {
        assertTrue(ToonRichGeom.sway(0.0) <= 4f)
        assertTrue(ToonRichGeom.sway(0.75, 4f, 3.0) < 0f) // качнулся в другую сторону
        assertEquals(0, ToonRichGeom.vignetteAlpha(360f, 640f, 720, 1280))
        assertTrue(ToonRichGeom.vignetteAlpha(0f, 0f, 720, 1280) in 85..90)
        assertEquals(360f, ToonRichGeom.lampRadius(720, 0f), 0.01f)
        assertTrue(ToonRichGeom.lampRadius(720, 1f) > ToonRichGeom.lampRadius(720, 0f))
        val fr = ToonRichGeom.pictureFrame(720, 1280, 540f, 400f, 1f)
        assertEquals(fr[0], 540f - 720f * 0.11f, 0.01f)
        assertEquals(fr[3], 400f + 720f * 0.14f, 0.01f)
    }
}
