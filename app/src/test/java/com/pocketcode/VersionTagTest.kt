package com.pocketcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTagTest {
    @Test
    fun oldBuildTagsKeepTheirNumber() {
        assertEquals(200 to "0.6.200", VersionUtil.fromTag("build-200"))
    }

    @Test
    fun newTagsBecomeVersionCode() {
        assertEquals(4_000_007 to "4.0.7", VersionUtil.fromTag("v4.0.7"))
        assertEquals(4_001_001 to "4.1.1", VersionUtil.fromTag("v4.1.1"))
        assertEquals(5_000_001 to "5.0.1", VersionUtil.fromTag("v5.0.1"))
    }

    @Test
    fun newVersionsAlwaysBeatOldBuilds() {
        val old = VersionUtil.fromTag("build-9999")!!.first
        assertTrue(VersionUtil.fromTag("v4.0.1")!!.first > old)
    }

    @Test
    fun versionsOrderedAcrossMinorAndMajor() {
        val a = VersionUtil.fromTag("v4.0.99")!!.first
        val b = VersionUtil.fromTag("v4.1.1")!!.first
        val c = VersionUtil.fromTag("v5.0.1")!!.first
        assertTrue(a < b && b < c)
    }

    @Test
    fun foreignTagsAreIgnored() {
        assertNull(VersionUtil.fromTag("nightly"))
        assertNull(VersionUtil.fromTag("build-abc"))
        assertNull(VersionUtil.fromTag("4.0.1"))
        assertNull(VersionUtil.fromTag("v4.0"))
    }
}
