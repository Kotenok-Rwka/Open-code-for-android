package com.pocketcode

import com.pocketcode.tools.GitHubRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GitHubRefsTest {
    @Test
    fun repoNameFromUrlsAndPlainForm() {
        assertEquals("Kotenok-Rwka/Open-code-for-android", GitHubRefs.repoName("https://github.com/Kotenok-Rwka/Open-code-for-android"))
        assertEquals("a/b", GitHubRefs.repoName("https://github.com/a/b.git"))
        assertEquals("a/b", GitHubRefs.repoName("git@github.com:a/b.git"))
        assertEquals("a/b", GitHubRefs.repoName("https://github.com/a/b/"))
        assertEquals("a/b", GitHubRefs.repoName("a/b"))
        assertEquals("a/b.c", GitHubRefs.repoName(" a/b.c "))
    }

    @Test
    fun repoNameRejectsGarbage() {
        assertNull(GitHubRefs.repoName("просто текст"))
        assertNull(GitHubRefs.repoName("https://example.com/a/b"))
        assertNull(GitHubRefs.repoName("onlyname"))
    }

    @Test
    fun encodePathKeepsSlashesAndEscapesSpaces() {
        assertEquals("app/src/main/My%20File.kt", GitHubRefs.encodePath("app/src/main/My File.kt"))
        assertEquals("a/b.txt", GitHubRefs.encodePath("/a//b.txt"))
        assertEquals("a/b", GitHubRefs.encodePath("./a/./b"))
        assertEquals("", GitHubRefs.encodePath(""))
    }

    @Test
    fun cleanPathRejectsParentSegments() {
        try {
            GitHubRefs.cleanPath("a/../b")
            fail("ожидалась ошибка")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(".."))
        }
    }

    @Test
    fun mergeMethodDefaultsAndValidates() {
        assertEquals("merge", GitHubRefs.mergeMethod(null))
        assertEquals("merge", GitHubRefs.mergeMethod(""))
        assertEquals("squash", GitHubRefs.mergeMethod(" Squash "))
        try {
            GitHubRefs.mergeMethod("force")
            fail("ожидалась ошибка")
        } catch (e: IllegalArgumentException) {
            // нормально
        }
    }

    @Test
    fun blobShaMatchesGit() {
        // git hash-object для пустого файла и для "hello\n"
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitHubRefs.blobSha(ByteArray(0)))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitHubRefs.blobSha("hello\n".toByteArray()))
    }

    @Test
    fun binaryDetection() {
        assertTrue(GitHubRefs.isBinary(byteArrayOf(1, 0, 2)))
        assertFalse(GitHubRefs.isBinary("текст".toByteArray()))
    }
}
