package com.pocketcode.linux

import android.content.Context
import java.io.File

/**
 * Встроенный в APK нативный llama.cpp (assets/native/<abi>/llama-server).
 * Сборка бинарников делается workflow .github/workflows/build-llama.yml.
 */
object BundledLlama {
    fun assetName(): String? = when {
        (android.os.Build.SUPPORTED_ABIS as Array<String>?)?.any { it.equals("arm64-v8a", true) } == true -> "native/arm64-v8a/llama-server"
        else -> null
    }

    /** Распаковывает бинарник в приватную папку и делает исполняемым. */
    fun install(context: Context): File? {
        val name = assetName() ?: return null
        val dir = File(context.filesDir, "native").apply { mkdirs() }
        val out = File(dir, "llama-server")
        if (out.exists() && out.length() > 0) {
            out.setExecutable(true)
            return out
        }
        val input = runCatching { context.assets.open(name) }.getOrNull() ?: return null
        input.use { it.copyTo(out.outputStream(), 64 * 1024) }
        out.setExecutable(true)
        return out.takeIf { it.length() > 0 }
    }
}
