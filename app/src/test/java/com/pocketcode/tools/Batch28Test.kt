package com.pocketcode.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Batch28Test {

    @Test
    fun smallTalkDetected() {
        for (t in listOf("Привет", "привет!", "Как дела?", "Спасибо большое", "Пока", "Кто ты?", "Что ты умеешь")) {
            assertTrue("должно быть болтовней: $t", SmallTalk.isSmallTalk(t))
            assertNotNull("должен быть ответ: $t", SmallTalk.reply(t))
        }
    }

    @Test
    fun notSmallTalk() {
        for (t in listOf("", "   ", "Какая погода в Москве", "найди котиков в интернете",
                "напиши функцию сортировки на kotlin", "как дела у проекта pocketcode на github")) {
            assertFalse("не должно быть болтовней: $t", SmallTalk.isSmallTalk(t))
            assertNull("не должен отвечать болтовней: $t", SmallTalk.reply(t))
        }
    }

    @Test
    fun replyVariesByIntent() {
        assertTrue(SmallTalk.reply("Привет")!!.contains("Привет"))
        assertTrue(SmallTalk.reply("Как дела")!!.contains("готов"))
        assertTrue(SmallTalk.reply("Спасибо")!!.contains("пожалуйста"))
        assertTrue(SmallTalk.reply("Пока")!!.contains("До встречи"))
        assertTrue(SmallTalk.reply("Кто ты")!!.contains("Помощник"))
        // имя приложения подставляется
        assertTrue(SmallTalk.reply("Кто ты", "PocketCode")!!.contains("PocketCode"))
    }

    @Test
    fun weatherToolMeta() {
        val tool = WeatherTool()
        assertEquals("weather", tool.name)
        assertTrue(tool.description.contains("Погода"))
        // без одобрения — быстрый бытовой инструмент
        assertFalse(tool.needsApproval(kotlinx.serialization.json.buildJsonObject {}))
        assertEquals("погода: Москва", tool.describe(
            kotlinx.serialization.json.buildJsonObject { put("city", "Москва") }))
    }
}
