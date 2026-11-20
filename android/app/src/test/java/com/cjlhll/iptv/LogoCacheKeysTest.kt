package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t130 TEST-01: contract tests for the logo cache key scheme that
 * [LogoLoader.invalidate] / [LogoLoader.stats] rely on. Pure (no Android Context).
 */
class LogoCacheKeysTest {

    private val url = "https://iptv.laobaitv.net/logo/cctv1.png"

    @Test
    fun memoryKeyIsSizePrefixedAndEndsWithUrl() {
        val key = LogoLoader.memoryKeyForTest(url, 64, 40)
        assertEquals("64x40|$url", key)
        // invalidate() removes memory entries by this suffix match.
        assertTrue("invalidate suffix match", key.endsWith("|$url"))
    }

    @Test
    fun memoryKeyDistinguishesTargetSize() {
        assertNotEquals(
            LogoLoader.memoryKeyForTest(url, 64, 40),
            LogoLoader.memoryKeyForTest(url, 80, 80)
        )
    }

    @Test
    fun diskKeyIsStableForSameUrlAndSize() {
        val a = LogoLoader.diskKeyForTest(url, 64, 40)
        val b = LogoLoader.diskKeyForTest(url, 64, 40)
        assertEquals(a, b)
        // invalidate() deletes disk files by this prefix.
        assertTrue("invalidate prefix match", a.startsWith(LogoLoader.sha256ForTest(url) + "_"))
        assertTrue(a.endsWith("_64x40"))
    }

    @Test
    fun diskKeyDiffersAcrossUrlsAndSizes() {
        assertNotEquals(
            LogoLoader.diskKeyForTest(url, 64, 40),
            LogoLoader.diskKeyForTest("https://iptv.laobaitv.net/logo/cctv2.png", 64, 40)
        )
        assertNotEquals(
            LogoLoader.diskKeyForTest(url, 64, 40),
            LogoLoader.diskKeyForTest(url, 80, 80)
        )
    }

    @Test
    fun sha256IsDeterministicLowercaseHex64() {
        val h = LogoLoader.sha256ForTest(url)
        assertEquals(64, h.length)
        assertEquals(h.lowercase(), h)
        assertEquals(h, LogoLoader.sha256ForTest(url))
        assertTrue(h.all { it in "0123456789abcdef" })
        assertNotEquals(h, LogoLoader.sha256ForTest("$url?x=1"))
    }
}
