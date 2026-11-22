package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogTest {
    private val weak = LocalModelCatalog.Device(3, 16, 30)
    private val strong = LocalModelCatalog.Device(16, 200, 34)

    @Test
    fun catalogHasAllKinds() {
        val ids = LocalModelCatalog.all.map { it.id }
        assertTrue(ids.any { it.contains("coder") })
        assertTrue(ids.any { it.contains("vl") })
        assertTrue(LocalModelCatalog.all.any { m -> m.capabilities.contains(ModelCapability.VISION) })
        assertTrue(LocalModelCatalog.all.any { m -> m.capabilities.contains(ModelCapability.FAST) })
    }

    @Test
    fun weakDeviceGetsSmallModel() {
        val rec = LocalModelCatalog.recommend(weak, code = true, vision = false, fast = true, limit = 1).first()
        assertTrue(rec.paramsB <= 4f)
        assertTrue(rec.minRamGb <= 3)
    }

    @Test
    fun strongDeviceCanGetCoder() {
        val rec = LocalModelCatalog.recommend(strong, code = true, vision = false, fast = false, limit = 5)
        assertTrue(rec.any { it.capabilities.contains(ModelCapability.CODE) && it.paramsB >= 7f })
    }

    @Test
    fun visionFilterOnlyReturnsVision() {
        val list = LocalModelCatalog.filter(capability = ModelCapability.VISION, device = strong)
        assertTrue(list.isNotEmpty())
        assertTrue(list.all { it.capabilities.contains(ModelCapability.VISION) })
    }

    @Test
    fun ratingsAndBadgesWork() {
        assertTrue(LocalModelCatalog.all.all { it.quality in 1..10 })
        val topCode = LocalModelCatalog.topIn(ModelCapability.CODE, 1).first()
        assertTrue(topCode.id.contains("coder:14b") || topCode.id.contains("qwq"))
        assertTrue(LocalModelCatalog.badge(topCode).isNotBlank())
        val byRating = LocalModelCatalog.filter(sortBy = LocalModelCatalog.SortBy.RATING)
        assertTrue(byRating.first().quality >= byRating.last().quality)
    }

    @Test
    fun sortingWorks() {
        val bySize = LocalModelCatalog.filter(sortBy = LocalModelCatalog.SortBy.SIZE)
        assertTrue(bySize.first().paramsB <= bySize.last().paramsB)
        val byContext = LocalModelCatalog.filter(sortBy = LocalModelCatalog.SortBy.CONTEXT)
        assertTrue(byContext.first().contextK >= byContext.last().contextK)
    }
}
