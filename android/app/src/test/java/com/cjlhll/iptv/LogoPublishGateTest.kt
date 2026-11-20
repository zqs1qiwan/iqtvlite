package com.cjlhll.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t145 TEST-01: the atomic publish gate (check + put under the same lock). */
class LogoPublishGateTest {

    private val url = "https://iptv.laobaitv.net/logo/publish.png"
    private val other = "https://iptv.laobaitv.net/logo/other.png"

    @Test
    fun freshGenerationPublishes() {
        assertTrue(
            LogoLoader.shouldPublishForTest(
                LogoLoader.cacheGenerationForTest(),
                url,
                LogoLoader.urlGenerationForTest(url),
            )
        )
    }

    @Test
    fun globalClearBlocksPublish() {
        val gen = LogoLoader.cacheGenerationForTest()
        val uGen = LogoLoader.urlGenerationForTest(url)
        LogoLoader.bumpGenerationForTest()
        assertFalse("a load started before clearAll must not publish", LogoLoader.shouldPublishForTest(gen, url, uGen))
    }

    @Test
    fun perUrlInvalidateBlocksOnlyThatUrl() {
        val gen = LogoLoader.cacheGenerationForTest()
        val uGen = LogoLoader.urlGenerationForTest(url)
        val otherGen = LogoLoader.urlGenerationForTest(other)

        LogoLoader.bumpUrlGenerationForTest(url)

        assertFalse("invalidated url must not publish", LogoLoader.shouldPublishForTest(gen, url, uGen))
        assertTrue("unrelated url still publishes (CACHE-02b)", LogoLoader.shouldPublishForTest(gen, other, otherGen))
    }

    @Test
    fun staleRuleMatchesPublishGate() {
        val gen = LogoLoader.cacheGenerationForTest()
        val uGen = LogoLoader.urlGenerationForTest(url)
        LogoLoader.bumpGenerationForTest()
        // loadInternal's stale() is defined as "not publishable" — they must agree.
        assertTrue(LogoLoader.isStaleForTest(gen, url, uGen))
        assertFalse(LogoLoader.shouldPublishForTest(gen, url, uGen))
    }

    @Test
    fun urlGenerationMapStaysBounded() {
        // URLGEN-01: the map is a bounded LRU; hammering many urls must not grow it without limit.
        repeat(2000) { i -> LogoLoader.bumpUrlGenerationForTest("https://x/$i.png") }
        assertTrue(LogoLoader.urlGenerationSizeForTest() <= 512)
    }
}
