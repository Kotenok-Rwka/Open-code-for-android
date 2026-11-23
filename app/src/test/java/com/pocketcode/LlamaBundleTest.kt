package com.pocketcode

import com.pocketcode.linux.BundledLlama
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaBundleTest {
    @Test
    fun assetPathIsForArm64() {
        val name = BundledLlama.assetName()
        assertTrue(name == null || name == "native/arm64-v8a/llama-server")
    }
}
