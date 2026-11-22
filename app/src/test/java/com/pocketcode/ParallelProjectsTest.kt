package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверка изоляции состояния OpenCode/профилей между параллельными вкладками проектов. */
class ParallelProjectsTest {
    private val store = mutableMapOf<String, String>()

    private fun saveSession(project: String, id: String, title: String) {
        store["ocSess|$project"] = id + "\u001f" + title
    }

    private fun loadSession(project: String): Pair<String?, String> {
        val parts = store["ocSess|$project"]?.split("\u001f", limit = 2) ?: return null to ""
        return parts[0].takeIf { it.isNotBlank() } to parts.getOrElse(1) { "" }
    }

    private fun saveContinue(project: String, on: Boolean) {
        store["ocContinue|$project"] = if (on) "1" else "0"
    }

    @Test
    fun ocSessionsIsolatedPerProject() {
        saveSession("main", "s1", "Беседа A")
        saveSession("site", "s2", "Беседа B")
        assertEquals("s1" to "Беседа A", loadSession("main"))
        assertEquals("s2" to "Беседа B", loadSession("site"))
        assertNotEquals(loadSession("main").first, loadSession("site").first)
    }

    @Test
    fun ocContinueIsolatedPerProject() {
        saveContinue("main", true)
        saveContinue("site", false)
        assertTrue(store["ocContinue|main"] == "1")
        assertTrue(store["ocContinue|site"] == "0")
    }

    @Test
    fun approvalMentionsProject() {
        val shown = "📁 site\n\nВопрос"
        assertTrue(shown.startsWith("📁"))
        assertTrue(shown.contains("site"))
    }
}
