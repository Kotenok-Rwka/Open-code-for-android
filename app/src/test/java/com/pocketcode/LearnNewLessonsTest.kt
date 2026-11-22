package com.pocketcode.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnNewLessonsTest {
    @Test
    fun newLessonsCoverNewFeatures() {
        val ids = ALL_LESSONS.map { it.id }
        for (id in listOf("howai", "tools", "appearance_themes", "plugins_github", "sync")) {
            assertTrue("Нет урока $id", ids.contains(id))
        }
    }

    @Test
    fun lessonsHaveQuizAndAnswers() {
        for (l in ALL_LESSONS.filter { it.id in setOf("howai", "tools", "appearance_themes", "plugins_github", "sync") }) {
            assertTrue(l.title.isNotBlank())
            assertTrue(l.body.isNotBlank())
            assertTrue(l.question.isNotBlank())
            assertTrue(l.options.size >= 2)
            assertTrue(l.answer in l.options.indices)
            assertTrue(l.explain.isNotBlank())
        }
    }

    @Test
    fun lessonCountGrew() {
        assertTrue(ALL_LESSONS.size >= 20)
    }
}
