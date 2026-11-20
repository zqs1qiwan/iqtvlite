package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t153 TEST-01: resolve/previous rules that PLAY-01, VARIANT-01 and LEGACY-01 depend on.
 * Pure JVM — the same functions the Activity calls.
 */
class RecentChannelsResolveTest {

    private fun ch(id: Long, title: String, url: String = "http://x/$id") =
        Channel(title = title, url = url, id = id)

    private fun group(key: String, vararg variants: Channel) = ChannelGroup(
        key = key,
        displayTitle = key,
        logoUrl = variants.firstOrNull()?.logoUrl,
        group = null,
        variants = variants.map { ChannelVariant(it, "src") },
        defaultVariantIndex = 0,
    )

    private val hd = ch(11, "CCTV-1 HD", "http://x/11")
    private val sd = ch(1, "CCTV-1", "http://x/1")
    private val cctv1 = group("cctv1", sd, hd)
    private val cctv2 = group("cctv2", ch(2, "CCTV-2"))
    private val groups = listOf(cctv1, cctv2)

    private fun entry(key: String, variant: Int = 0, url: String = "") =
        RecentChannels.Entry(key, 1, key, null, variant, url)

    @Test
    fun variantMatchesByFallbackUrlBeforeIndex() {
        // stored index 0 (SD) but the recorded line was HD → the url wins (VARIANT-01)
        val resolved = RecentChannels.resolveOne(listOf(entry("cctv1", 0, "http://x/11")), groups)
        assertEquals("http://x/11", resolved.single().channel.url)
        assertEquals(1, resolved.single().variantIndex)
    }

    @Test
    fun variantFallsBackToClampedIndexWhenUrlIsGone() {
        val resolved = RecentChannels.resolveOne(listOf(entry("cctv1", 9, "http://x/gone")), groups)
        assertEquals(0, resolved.single().variantIndex) // clamped into range
        assertEquals("http://x/1", resolved.single().channel.url)
    }

    @Test
    fun previousResolvedSkipsSameGroupAndUnresolved() {
        val entries = listOf(
            entry("cctv1", 1, "http://x/11"),   // another line of the CURRENT channel
            entry("ghost"),                     // no longer in the playlist
            entry("cctv2"),
        )
        val target = RecentChannels.previousResolved(entries, groups, "cctv1")
        assertEquals("cctv2", target?.group?.key)
        assertEquals(1, target?.groupIndex)
        val resolvedTarget = requireNotNull(target)
        assertTrue(resolvedTarget.variantIndex in groups[resolvedTarget.groupIndex].variants.indices)
    }

    @Test
    fun previousResolvedReturnsNullWhenNothingUsable() {
        // PLAY-01: caller must not switch (and must not set state) when this is null.
        assertNull(RecentChannels.previousResolved(listOf(entry("cctv1")), groups, "cctv1"))
        assertNull(RecentChannels.previousResolved(listOf(entry("ghost")), groups, "cctv2"))
        assertNull(RecentChannels.previousResolved(emptyList(), groups, null))
        assertNull(RecentChannels.previousResolved(listOf(entry("cctv2")), emptyList(), null))
    }

    @Test
    fun legacyUrlRowResolvesAndDeduplicatesWithGroupRow() {
        val legacy = RecentChannels.Entry("url:http://x/1", 1, "CCTV-1", null, 0, "http://x/1")
        val modern = entry("cctv1", 0, "http://x/1")
        val resolved = RecentChannels.resolvedDistinct(listOf(legacy, modern), groups)
        assertEquals("LEGACY-01: same channel must appear once", 1, resolved.size)
        assertEquals("cctv1", resolved.single().group.key)
    }

    @Test
    fun legacyUrlRowAloneStillResolves() {
        val legacy = RecentChannels.Entry("url:http://x/2", 2, "CCTV-2", null, 0, "http://x/2")
        val resolved = RecentChannels.resolvedDistinct(listOf(legacy), groups)
        assertEquals("cctv2", resolved.single().group.key)
        assertFalse(resolved.single().group.key == "url:http://x/2")
    }

    @Test
    fun distinctKeepsNewestOrderAndCap() {
        var entries = emptyList<RecentChannels.Entry>()
        repeat(RecentChannels.MAX + 3) { i ->
            entries = listOf(RecentChannels.Entry("g$i", i, "g$i", null, 0, "")) + entries
        }
        val many = (0 until RecentChannels.MAX + 3).map { group("g$it", ch(it + 1L, "c$it")) }
        val resolved = RecentChannels.resolvedDistinct(entries, many)
        assertEquals(RecentChannels.MAX, resolved.size)
        assertEquals("g${RecentChannels.MAX + 2}", resolved.first().group.key)
    }

    @Test
    fun debouncedListKeepsIntermediateChannel() {
        // DEBOUNCE-01 regression: push A→B→C onto the in-memory list (never a stale Prefs snapshot).
        var list = emptyList<RecentChannels.Entry>()
        list = RecentChannels.push(list, cctv1, 1, 0)
        list = RecentChannels.push(list, cctv2, 2, 0)
        list = RecentChannels.push(list, group("cctv3", ch(3, "CCTV-3")), 3, 0)
        assertEquals(listOf("cctv3", "cctv2", "cctv1"), list.map { it.groupKey })
        // and the round-trip through Prefs keeps all three
        val persisted = RecentChannels.decode(RecentChannels.encode(list))
        assertEquals(3, persisted.size)
        assertEquals("cctv2", persisted[1].groupKey)
    }

    @Test
    fun clearProducesEmptyDecodableState() {
        val cleared = RecentChannels.encode(emptyList())
        assertTrue(RecentChannels.decode(cleared).isEmpty())
        assertNull(RecentChannels.previousResolved(emptyList(), groups, "cctv1"))
    }
}
