package com.cjlhll.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** t137 TEST-02: generation-based staleness rules used by LogoLoader's load/commit path. */
class LogoGenerationTest {

    private val url = "https://iptv.laobaitv.net/logo/gen.png"
    private val other = "https://iptv.laobaitv.net/logo/other.png"

    @Test
    fun freshLoadIsNotStale() {
        val gen = LogoLoader.cacheGenerationForTest()
        val uGen = LogoLoader.urlGenerationForTest(url)
        assertFalse(LogoLoader.isStaleForTest(gen, url, uGen))
    }

    @Test
    fun globalClearInvalidatesEveryUrl() {
        val gen = LogoLoader.cacheGenerationForTest()
        val uGen = LogoLoader.urlGenerationForTest(url)
        LogoLoader.bumpGenerationForTest()
        assertTrue("clearAll/clearDisk must invalidate in-flight work", LogoLoader.isStaleForTest(gen, url, uGen))
        assertTrue(LogoLoader.isStaleForTest(gen, other, LogoLoader.urlGenerationForTest(other)))
    }

    @Test
    fun perUrlInvalidateOnlyAffectsThatUrl() {
        val gen = LogoLoader.cacheGenerationForTest()
        val urlGen = LogoLoader.urlGenerationForTest(url)
        val otherGen = LogoLoader.urlGenerationForTest(other)

        LogoLoader.bumpUrlGenerationForTest(url)

        assertTrue("invalidated url becomes stale", LogoLoader.isStaleForTest(gen, url, urlGen))
        assertFalse("unrelated url must stay valid (CACHE-02b)", LogoLoader.isStaleForTest(gen, other, otherGen))
        assertNotEquals(urlGen, LogoLoader.urlGenerationForTest(url))
    }

    @Test
    fun urlGenerationIsStableUntilBumped() {
        val a = LogoLoader.urlGenerationForTest("https://x/a.png")
        val b = LogoLoader.urlGenerationForTest("https://x/a.png")
        assertTrue(a == b)
    }
}
