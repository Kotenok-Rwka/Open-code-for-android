package com.pocketcode

import com.pocketcode.linux.RootFsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsLogicTest {
    @Test
    fun localProviderTemplatesAreOpenAiCompatible() {
        val names = PROVIDER_TEMPLATES.map { it.name }
        assertTrue(names.contains("LM Studio"))
        assertTrue(names.contains("llama.cpp"))
        assertTrue(names.contains("LocalAI"))
        assertTrue(names.contains("vLLM"))
        assertTrue(PROVIDER_TEMPLATES.first { it.name == "LM Studio" }.baseUrl.contains("1234"))
    }

    @Test
    fun sshArgsDoNotUseShellAndValidateInput() {
        val p = SshProfile("home", "192.168.1.20", 2222, "user", "/data/key")
        assertNull(SshProfiles.validate(p))
        val args = SshProfiles.sshArgs(p, "echo 'hi' > /tmp/a", null)
        assertEquals("ssh", args.first())
        assertTrue(args.contains("-p"))
        assertTrue(args.contains("2222"))
        assertTrue(args.contains("user@192.168.1.20"))
        assertTrue(args.last().contains("'hi'"))
        assertFalse(args.any { it == "-c" })
        assertTrue(SshProfiles.validate(p.copy(port = 0))!!.contains("Порт"))
        assertTrue(SshProfiles.validate(p.copy(host = "host\nrm -rf /"))!!.isNotEmpty())
    }

    @Test
    fun rootfsCatalogBuildsUbuntuUrls() {
        assertEquals("arm64", RootFsCatalog.arch(arrayOf("arm64-v8a")))
        assertEquals("armhf", RootFsCatalog.arch(arrayOf("armeabi-v7a")))
        assertTrue(RootFsCatalog.ubuntuUrl("arm64").contains("22.04"))
        assertTrue(RootFsCatalog.ubuntuUrl("arm64").contains("arm64"))
        assertTrue(RootFsCatalog.ubuntuUrls("arm64").all { it.startsWith("https://cdimage.ubuntu.com/") })
        assertTrue(RootFsCatalog.validateUrl("http://example.com")!!.contains("HTTPS"))
    }
}
