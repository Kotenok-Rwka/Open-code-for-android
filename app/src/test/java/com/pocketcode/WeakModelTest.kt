package com.pocketcode

import com.pocketcode.agent.ModelTier
import com.pocketcode.agent.WeakModelGuard
import com.pocketcode.llm.JsonRepair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakModelTest {
    @Test
    fun flashAndSmallModelsGetScaffoldMode() {
        // «hy 4 flash» и подобные быстрые модели должны попадать в режим со строительными лесами
        for (m in listOf("hy 4 flash", "hy-4-flash", "gemini-2.5-flash", "gpt-4o-mini", "llama-3.1-8b", "deepseek-chat:free")) {
            assertEquals(m, "scaffold", ModelTier.mode(m, 200_000, false))
        }
        assertEquals("focused", ModelTier.mode("qwen2.5-coder:14b", 16_000, false))
        assertEquals("auto", ModelTier.mode("claude-sonnet-4.5", 200_000, false))
        // Локальная модель — всегда леса, даже если имя солидное
        assertEquals("scaffold", ModelTier.mode("my-model", 200_000, true))
    }

    @Test
    fun codeInChatInsteadOfFilesIsDetected() {
        val answer = "Готово:\n```kotlin\nclass A {\n    fun b() = 1\n}\n```"
        assertTrue(WeakModelGuard.codeInChat("Добавь класс A в проект", answer))
        // Вопрос — это нормальный ответ с примером кода
        assertFalse(WeakModelGuard.codeInChat("Как работает класс A?", answer))
        // Просили изменить, но кода в ответе нет — это не тот случай
        assertFalse(WeakModelGuard.codeInChat("Добавь класс A", "Готово, файл создан."))
    }

    @Test
    fun diagnosticsReportIsReadCorrectly() {
        assertTrue(WeakModelGuard.diagnosticsOk("Main.kt: OK"))
        assertTrue(WeakModelGuard.diagnosticsOk("Main.kt: OK\napp.py: OK"))
        assertFalse(WeakModelGuard.diagnosticsOk("Main.kt: скобки не совпадают: открыто 3, закрыто 2"))
        // Нет node — это не ошибка кода, иначе агент зациклится на «исправлении»
        assertTrue(WeakModelGuard.diagnosticsOk("app.js: node не найден или ошибка (см. выше)"))
        assertTrue(WeakModelGuard.asksToChange("Можешь добавить кнопку?"))
    }

    @Test
    fun jsonRepairFixesWeakModelArguments() {
        assertEquals("{\"path\":\"a.kt\"}", JsonRepair.sanitize("{\"path\":\"a.kt\",}"))
        assertEquals("{\"path\":\"a.kt\"}", JsonRepair.sanitize("Конечно! {\"path\":\"a.kt\"}"))
        assertEquals("{\"path\":\"a.kt\"}", JsonRepair.sanitize("{\"path\":\"a.kt\""))
        assertEquals("{\"a\":[1,2]}", JsonRepair.sanitize("{\"a\":[1,2]}"))
    }
}
