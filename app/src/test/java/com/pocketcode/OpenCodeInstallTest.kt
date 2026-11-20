package com.pocketcode

import com.pocketcode.ui.OpenCodeInstall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeInstallTest {
    private val cmd = OpenCodeInstall.command()

    @Test
    fun downloadsPackageTarballDirectlyFromRegistry() {
        assertTrue(cmd.contains("curl -fL --retry 3"))
        assertTrue(cmd.contains("/-/\$P-\$V.tgz"))
        assertTrue(cmd.contains("tar -xzf"))
        assertTrue(cmd.contains("package/bin/opencode"))
        assertTrue(cmd.contains("chmod 755 " + OpenCodeInstall.LINK_DIR + "/opencode"))
        assertTrue(cmd.contains("https://registry.npmjs.org"))
        assertTrue(cmd.contains("https://registry.npmmirror.com"))
    }

    @Test
    fun picksPackageByArchitecture() {
        assertTrue(cmd.contains("A=arm64"))
        assertTrue(cmd.contains("A=x64"))
        assertTrue(cmd.contains("P=opencode-linux-\$A-musl"))
        // незнакомая архитектура не должна молча ставить не тот бинарник
        assertTrue(cmd.contains("не поддерживается OpenCode"))
    }

    @Test
    fun checksDownloadIntegrity() {
        assertTrue(cmd.contains("sha1sum"))
        assertTrue(cmd.contains("gzip -t"))
        assertTrue(cmd.contains(OpenCodeInstall.FALLBACK_VERSION))
    }

    @Test
    fun installsLibrariesBeforeDownloading() {
        for (pkg in listOf("curl", "libgcc", "libstdc++", "gcompat", "git")) assertTrue(pkg, cmd.contains(pkg))
        assertTrue(cmd.indexOf("apk add") < cmd.indexOf("curl -fL"))
    }

    @Test
    fun cleansLeftoversOfFailedInstallBeforeDownloadAndBeforeNpm() {
        val rm = "rm -rf \"\$R\"/opencode-ai \"\$R\"/.opencode-* \"\$R\"/opencode-linux-*"
        val first = cmd.indexOf(rm)
        assertTrue(first in 0 until cmd.indexOf("curl -fL"))
        // и ещё раз перед запасным способом через npm, иначе npm падает с ENOTEMPTY
        val second = cmd.indexOf(rm, first + 1)
        assertTrue(second in 0 until cmd.indexOf("npm install -g"))
    }

    @Test
    fun checksFreeSpace() {
        assertTrue(cmd.contains("df -Pk"))
        assertTrue(cmd.contains(OpenCodeInstall.MIN_FREE_KB.toString()))
        assertTrue(cmd.contains("Мало места"))
    }

    @Test
    fun doesNothingWhenAlreadyInstalled() {
        assertTrue(cmd.contains("OpenCode уже установлен"))
    }

    @Test
    fun hasNpmFallbackWithExplicitPlatform() {
        assertTrue(cmd.contains("npm install -g --force --foreground-scripts --os=linux --cpu=\$A --libc=musl opencode-ai \$P"))
        assertTrue(cmd.contains("opencode.exe"))
    }

    @Test
    fun reportsResultForFreeStart() {
        // «Начать бесплатно» ищет именно эту строку и код выхода 0
        assertTrue(cmd.contains("код установки: 0"))
        assertTrue(cmd.contains("код установки: 1"))
        assertTrue(cmd.trimEnd().endsWith("exit 1"))
    }

    @Test
    fun linkDirIsConfigurable() {
        val c = OpenCodeInstall.command("/opt/bin")
        assertTrue(c.contains("/opt/bin/opencode"))
        assertFalse(c.contains("@LINK@"))
        assertFalse(c.contains("§"))
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
