package com.pocketcode

import com.pocketcode.ui.GitHubText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NewUiLogicTest {
    @Test
    fun githubTextTranslatesKnownTermsWithoutNetwork() {
        val out = GitHubText.translate("Collection of agent skills and plugins", "de")
        assertNotNull(out)
        assertTrue(out!!.contains("навыки"))
        assertTrue(out.contains("плагины"))
    }

    @Test
    fun githubTextKeepsUnknownTextAndSkipsRussian() {
        assertNull(GitHubText.translate("Collection of agent skills", "ru"))
        assertNull(GitHubText.translate("zzz qqq", "en"))
    }

    @Test
    fun skillCatalogHasModernFrontendSkills() {
        val names = SKILL_CATALOG.map { it.name }
        for (n in listOf("frontend-design", "design-system", "frontend-patterns", "vue-patterns", "web-a11y")) {
            assertTrue("Нет навыка $n", n in names)
        }
        assertTrue(SKILL_CATALOG.all { it.content.isNotBlank() && it.about.isNotBlank() })
    }

    @Test
    fun githubSearchUsesFiftyResultsPerPage() {
        val s = File("src/main/java/com/pocketcode/ui/PluginSearch.kt").readText()
        assertTrue("perPage должен быть 50", s.contains("perPage: Int = 50"))
        assertTrue(s.contains("perPage.coerceIn(1, 50)"))
    }

    @Test
    fun newStringsAreTranslatedEverywhere() {
        val dir = File("src/main/assets/i18n")
        val required = listOf(
            "Переводить описания", "Наверх", "Вниз", "Отменить скачивание",
            "Отправить сейчас", "Меньше запросов"
        )
        for (l in listOf("en", "de", "es", "zh", "ar")) {
            val text = File(dir, "$l.json").readText()
            required.forEach { assertTrue("Нет перевода «$it» в $l", text.contains("\"$it\"")) }
        }
    }

    @Test
    fun globalInstallSettingIsPersisted() {
        val s = File("src/main/java/com/pocketcode/ui/PluginsScreen.kt").readText()
        assertTrue(s.contains("skillsInstallGlobal"))
        assertTrue(s.contains("prefs.rawPut(\"skillsInstallGlobal\""))
    }
}
