package com.pocketcode

import com.pocketcode.ui.OpenCodeInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeInstallTest {
    private val cmd = OpenCodeInstall.command()

    @Test
    fun installsPackageAndPlatformBinary() {
        assertTrue(cmd.contains("npm install -g --force opencode-ai \$P"))
        assertTrue(cmd.contains("opencode-linux-arm64-musl"))
        assertTrue(cmd.contains("код установки: \$?"))
    }

    @Test
    fun linksBinaryWhenCommandIsMissing() {
        assertTrue(cmd.contains("ln -sf"))
        assertTrue(cmd.contains(OpenCodeInstall.LINK_DIR + "/opencode"))
        assertTrue(cmd.contains("bin/opencode.exe"))
    }

    @Test
    fun failsWithNonZeroCodeWhenStillMissing() {
        assertTrue(cmd.trimEnd().endsWith("exit 1; }"))
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
