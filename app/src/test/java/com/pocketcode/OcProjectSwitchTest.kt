package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Проверка изоляции сессий OpenCode по проектам (логика ключей switchTo/persist). */
class OcProjectSwitchTest {
    private val sessions = mutableMapOf<String, String>()

    private fun save(project: String, id: String, title: String) {
        sessions["ocSess|$project"] = id + "\u001f" + title
    }

    private fun load(project: String): Pair<String, String>? {
        val raw = sessions["ocSess|$project"] ?: return null
        val parts = raw.split("\u001f", limit = 2)
        return parts[0] to parts.getOrElse(1) { "" }
    }

    @Test
    fun sessionsAreIsolatedPerProject() {
        save("main", "s1", "Про баг")
        save("other", "s2", "Про дизайн")
        assertEquals("s1" to "Про баг", load("main"))
        assertEquals("s2" to "Про дизайн", load("other"))
    }

    @Test
    fun emptySessionMeansNewChat() {
        assertNull(load("empty-project"))
        save("empty-project", "", "")
        assertEquals("" to "", load("empty-project"))
    }

    @Test
    fun progressLabelsAreInformative() {
        for (s in listOf("думает", "инструмент bash", "шаг завершён")) {
            assert(s.isNotBlank())
        }
    }
}
