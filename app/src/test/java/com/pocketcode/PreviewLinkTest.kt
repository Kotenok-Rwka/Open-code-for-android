package com.pocketcode

import com.pocketcode.ui.isHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Превью показывает «Копировать» и «В браузере» только для внешних ссылок. */
class PreviewLinkTest {
    @Test
    fun externalLinksGetCopyAndBrowserButtons() {
        assertTrue(isHttpUrl("https://github.com/user/repo"))
        assertTrue(isHttpUrl("http://example.com"))
    }

    @Test
    fun localFilesDoNot() {
        assertFalse(isHttpUrl("file:///data/user/0/proj/index.html"))
        assertFalse(isHttpUrl("about:blank"))
    }
}
