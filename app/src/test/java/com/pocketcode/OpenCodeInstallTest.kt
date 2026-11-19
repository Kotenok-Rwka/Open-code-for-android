package com.pocketcode

import com.pocketcode.ui.OpenCodeInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeInstallTest {
    private val cmd = OpenCodeInstall.command()

    @Test
    fun installsPackageForExplicitPlatform() {
        assertTrue(cmd.contains("--os=linux --cpu=\$A --libc=musl opencode-ai \$P"))
        assertTrue(cmd.contains("A=arm64"))
        assertTrue(cmd.contains("A=x64"))
        assertTrue(cmd.contains("P=opencode-linux-\$A-musl"))
        assertTrue(cmd.contains("код установки: \$?"))
    }

    @Test
    fun installsSystemLibraries() {
        for (pkg in listOf("libgcc", "libstdc++", "gcompat", "nodejs", "npm")) assertTrue(pkg, cmd.contains(pkg))
        assertTrue(cmd.indexOf("apk add") < cmd.indexOf("npm install"))
    }

    @Test
    fun fixesPermissions() {
        assertTrue(cmd.contains("chmod -R a+rX"))
        assertTrue(cmd.contains("chmod a+rx"))
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
