package com.pocketcode

import com.pocketcode.ui.parseQuestionCards
import com.pocketcode.ui.questionAnswerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionCardsTest {
    @Test
    fun parsesQuestionsWithOptions() {
        val text = """
            Подскажи, какой стек выбрать.
            QUESTIONS
            TAG: Платформы
            Q: Что важнее?
            - Быстрый MVP
              Меньше времени на запуск
            - Гибкость
              Больше контроля
            - Другое
            /QUESTIONS
        """.trimIndent()
        val parsed = parseQuestionCards(text)
        assertNotNull(parsed)
        assertEquals("Подскажи, какой стек выбрать.", parsed!!.cleanText)
        assertEquals(1, parsed.questions.size)
        assertEquals("Что важнее?", parsed.questions[0].question)
        assertEquals("Платформы", parsed.questions[0].tag)
        assertEquals(3, parsed.questions[0].options.size)
        assertEquals("Больше контроля", parsed.questions[0].options[1].description)
        assertTrue(questionAnswerText(listOf("Что важнее?" to "Гибкость")).contains("Гибкость"))
    }

    @Test
    fun parsesSeveralQuestionsAndRussianTag() {
        val text = """
            ВОПРОСЫ
            Q: Стек?
            A: Kotlin
            A: Java
            Q: Среда?
            1. Android
            2. Desktop
            /ВОПРОСЫ
        """.trimIndent()
        val parsed = parseQuestionCards(text)
        assertNotNull(parsed)
        assertEquals(2, parsed!!.questions.size)
        assertEquals(2, parsed.questions[0].options.size)
        assertEquals("Android", parsed.questions[1].options[0].label)
    }

    @Test
    fun ignoresPlainText() {
        assertNull(parseQuestionCards("Какой стек выбрать?"))
    }
}
