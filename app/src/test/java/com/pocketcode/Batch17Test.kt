package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Batch17Test {
    // 224. RuDate
    @Test
    fun ruDateFormatsAndParsesMonths() {
        assertEquals("1 апреля", RuDate.formatDate(1, 4))
        assertEquals("23 мая", RuDate.formatDate(23, 5))
        assertEquals("", RuDate.formatDate(32, 1))
        assertEquals("", RuDate.formatDate(1, 13))
        assertEquals(4, RuDate.monthByName("апрель"))
        assertEquals(5, RuDate.monthByName(" Мая "))
        assertEquals(1, RuDate.monthByName("янв."))
        assertEquals(0, RuDate.monthByName("прлюк"))
    }

    // 225. BuildNumber
    @Test
    fun buildNumberAppendsCommitCount() {
        assertEquals("1.2.7", BuildNumber.fromCommits(6, "1.2"))
        assertEquals("1.0.1", BuildNumber.fromCommits(0))
        assertEquals("2.5.11", BuildNumber.fromCommits(10, "2.5.0"))
        assertEquals("3.1.43", BuildNumber.bump(42, "3.1"))
    }

    // 226. Indent
    @Test
    fun indentConvertsTabsAndSpaces() {
        assertEquals("\tабв", Indent.toTabs("    абв"))
        assertEquals("\t\txyz", Indent.toTabs("        xyz"))
        assertEquals("  xy", Indent.toTabs("  xy"))           // меньше ширины — остаются пробелы
        assertEquals("    a", Indent.toSpaces("\ta"))
        assertEquals("        b", Indent.toSpaces("\t\tb"))
        assertEquals("c", Indent.toSpaces("c"))
        assertEquals("    a\nc", Indent.toSpaces("\ta\nc"))
    }

    // 227. CaseFmt
    @Test
    fun caseFmtSplitsAndJoins() {
        assertEquals("мой-проект", CaseFmt.kebab("Мой проект"))
        assertEquals("мой_проект", CaseFmt.snake("МойПроект"))
        assertEquals("МойПроект", CaseFmt.camel("мой проект"))
        assertEquals("Мой Проект", CaseFmt.title("мой-проект"))
        assertEquals(listOf("hello", "World"), CaseFmt.words("helloWorld"))
        assertEquals("user-name", CaseFmt.kebab("userName"))
    }

    // 228. DraftKey
    @Test
    fun draftKeysRoundTrip() {
        assertEquals("draft_chat_abc", DraftKey.forChat("abc"))
        assertEquals("draft_note_5", DraftKey.forEdit(5))
        assertEquals("abc", DraftKey.chatIdOf("draft_chat_abc"))
        assertNull(DraftKey.chatIdOf("draft_note_5"))
        assertNull(DraftKey.chatIdOf("draft_chat_"))
    }

    // 229. FileIcon
    @Test
    fun fileIconMapsExtensions() {
        assertEquals("language", FileIcon.forName("App.kt"))
        assertEquals("data_object", FileIcon.forName("cfg.json"))
        assertEquals("image", FileIcon.forName("фото.PNG"))
        assertEquals("terminal", FileIcon.forName("run.sh"))
        assertEquals("draft", FileIcon.forName("безымянный"))
        assertEquals("folder_zip", FileIcon.forName("app.apk"))
    }

    // 230. Progress
    @Test
    fun progressPercentEtaBar() {
        assertEquals(50, Progress.percent(1, 2))
        assertEquals(100, Progress.percent(5, 5))
        assertEquals(0, Progress.percent(0, 0))
        assertEquals(0, Progress.percent(5, 0))
        assertEquals(90L, Progress.eta(1, 10, 10))
        assertEquals(0L, Progress.eta(0, 10, 10))
        assertEquals(0L, Progress.eta(10, 10, 10))
        assertEquals("[#####-----]", Progress.bar(5, 10))
        assertEquals("[----------]", Progress.bar(0, 10))
        assertEquals("[##########]", Progress.bar(10, 10))
    }
}
