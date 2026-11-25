package com.pocketcode

import com.pocketcode.ui.FeaturesSummary
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FeaturesSummaryTest {
    @Test
    fun cronCountsTasks() {
        val withTasks = JSONObject().put("tasks", JSONArray().put(JSONObject()).put(JSONObject()))
        assertEquals("2 задач", FeaturesSummary.cron(withTasks))
        assertEquals("0 задач", FeaturesSummary.cron(JSONObject().put("tasks", JSONArray())))
    }

    @Test
    fun missingDataShowsNoConnection() {
        assertEquals("нет связи", FeaturesSummary.cron(null))
        assertEquals("нет связи", FeaturesSummary.count(null, "total"))
        assertEquals("нет связи", FeaturesSummary.count(JSONObject(), "total"))
    }

    @Test
    fun countsRenderWithSuffix() {
        assertEquals("5 / 100", FeaturesSummary.count(JSONObject().put("score", 5), "score", " / 100"))
        assertEquals("12 вызовов", FeaturesSummary.count(JSONObject().put("total", 12), "total", " вызовов"))
        assertEquals("0", FeaturesSummary.count(JSONObject().put("total", 0), "total"))
    }
}
