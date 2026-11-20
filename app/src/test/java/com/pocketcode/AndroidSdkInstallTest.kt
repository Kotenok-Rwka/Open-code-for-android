package com.pocketcode

import com.pocketcode.ui.AndroidSdkInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSdkInstallTest {
    private val cmd = AndroidSdkInstall.command()

    @Test
    fun installsJdkAndSdkIntoSdkDir() {
        assertTrue(cmd.contains("openjdk17"))
        assertTrue(cmd.contains("SDK=" + AndroidSdkInstall.SDK_DIR))
        assertTrue(cmd.contains(AndroidSdkInstall.CMDLINE_TOOLS_URL))
        assertTrue(cmd.contains(AndroidSdkInstall.PLATFORM))
        assertTrue(cmd.contains(AndroidSdkInstall.BUILD_TOOLS))
        assertTrue(cmd.contains("--licenses"))
    }

    @Test
    fun checksAapt2AndWritesGradleProperties() {
        assertTrue(cmd.contains("\" version >/dev/null"))
        assertTrue(cmd.contains("android.aapt2FromMavenOverride="))
        assertTrue(cmd.contains("org.gradle.java.home="))
        assertTrue(cmd.contains("local.properties"))
        // если aapt2 не работает, об этом сказано честно
        assertTrue(cmd.contains("НЕ запускается"))
        assertTrue(cmd.contains("GitHub Actions"))
    }

    @Test
    fun checksFreeSpace() {
        assertTrue(cmd.contains("df -Pk"))
        assertTrue(cmd.contains(AndroidSdkInstall.MIN_FREE_KB.toString()))
    }

    @Test
    fun reportsResult() {
        assertTrue(cmd.contains("код установки SDK: 0"))
        assertTrue(cmd.contains("код установки SDK: 1"))
        assertTrue(cmd.contains(AndroidSdkInstall.ENV_PREFIX.trim()))
    }

    @Test
    fun placeholdersAreReplaced() {
        assertFalse(cmd.contains("§"))
        assertFalse(Regex("@[A-Z0-9]+@").containsMatchIn(cmd))
    }

    @Test
    fun shellBlocksAreBalanced() {
        assertEquals(Regex("\\bif\\b").findAll(cmd).count(), Regex("\\bfi\\b").findAll(cmd).count())
        assertEquals(Regex("\\bfor\\b").findAll(cmd).count(), Regex("\\bdone\\b").findAll(cmd).count())
    }

    @Test
    fun shellAcceptsTheSyntax() {
        val p = try { ProcessBuilder("sh", "-n", "-c", cmd).redirectErrorStream(true).start() } catch (e: Exception) { return }
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }
}
