package com.pocketcode

import com.pocketcode.linux.LinuxEnv
import org.junit.Assert.assertEquals
import org.junit.Test

class LinuxBundleTest {
    @Test
    fun bundledAssetNamesMatchWhatCiDownloads() {
        // CI кладёт архивы под этими именами (.github/workflows/build.yml, шаг «Встроить Alpine Linux в APK»)
        assertEquals("linux/alpine-aarch64.tar.gz", LinuxEnv.bundledAssetName("aarch64"))
        assertEquals("linux/alpine-armv7.tar.gz", LinuxEnv.bundledAssetName("armv7"))
    }
}
