package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t133 + t141: recent-channels / last-channel rules (pure JVM, no Android Context). */
class RecentChannelsTest {

    private fun ch(id: Long, title: String, url: String = "http://x/$id") =
        Channel(title = title, url = url, id = id)

    private fun group(key: String, title: String = key, vararg variants: Channel) =
        ChannelGroup(
            key = key,
            displayTitle = title,
            logoUrl = variants.firstOrNull()?.logoUrl,
            group = null,
            variants = variants.map { ChannelVariant(it, "src") },
            defaultVariantIndex = 0,
        )

    @Test
    fun pushDeduplicatesByGroupKey() {
        val g = group("cctv1", "CCTV-1", ch(1, "CCTV-1"), ch(11, "CCTV-1 HD", "http://x/11"))
        var list = RecentChannels.push(emptyList(), g, 1, 0)
        list = RecentChannels.push(list, group("cctv2", "CCTV-2", ch(2, "CCTV-2")), 2, 0)
        assertEquals(listOf("cctv2", "cctv1"), list.map { it.groupKey })

        // switching the line/quality inside the same group updates the row, does not add one
        list = RecentChannels.push(list, g, 1, 1)
        assertEquals(listOf("cctv1", "cctv2"), list.map { it.groupKey })
        assertEquals(2, list.size)
        assertEquals(1, list.first().variantIndex)
    }

    @Test
    fun pushCapsAtMaxEntries() {
        var list = emptyList<RecentChannels.Entry>()
        repeat(RecentChannels.MAX + 5) { i ->
            list = RecentChannels.push(list, group("g$i", "G$i", ch(i + 1L, "C$i")), i + 1, 0)
        }
        assertEquals(RecentChannels.MAX, list.size)
        assertEquals("g${RecentChannels.MAX + 4}", list.first().groupKey)
        assertTrue(list.none { it.groupKey == "g0" })
    }

    @Test
    fun encodeDecodeRoundTripsAndSanitizesSeparators() {
        val g = group("key\u0001with\u0002sep", "T\u0001X", ch(7, "A", "http://x/1"))
        val list = RecentChannels.push(emptyList(), g, 3, 0)
        val back = RecentChannels.decode(RecentChannels.encode(list))
        assertEquals(1, back.size)
        assertTrue("separators must be sanitized", !back[0].groupKey.contains('\u0001') && !back[0].groupKey.contains('\u0002'))
        assertEquals(3, back[0].number)
    }

    @Test
    fun decodeToleratesGarbageEmptyAndLegacyRows() {
        assertEquals(emptyList<RecentChannels.Entry>(), RecentChannels.decode(null))
        assertEquals(emptyList<RecentChannels.Entry>(), RecentChannels.decode(""))
        assertEquals(emptyList<RecentChannels.Entry>(), RecentChannels.decode("garbage"))

        // legacy 5-field row (t133 format) still resolves by url fallback
        val legacy = RecentChannels.decode("id:1\u00011\u0001A\u0001\u0001http://x/1")
        assertEquals(1, legacy.size)
        assertEquals("url:http://x/1", legacy[0].groupKey)
    }

    @Test
    fun resolveSkipsMissingGroupsAndFallsBackToUrl() {
        val groups = listOf(
            group("cctv1", "CCTV-1", ch(1, "CCTV-1")),
            group("cctv2", "CCTV-2", ch(2, "CCTV-2")),
        )
        val entries = listOf(
            RecentChannels.Entry("cctv1", 1, "CCTV-1", null, 0, "http://x/1"),
            RecentChannels.Entry("gone", 9, "Gone", null, 0, "http://x/999"),
        )
        val resolved = RecentChannels.resolve(entries, groups)
        assertEquals(listOf("CCTV-1"), resolved.map { it.third.title })

        // url fallback when the group key changed after a playlist refresh
        val byUrl = RecentChannels.resolve(
            listOf(RecentChannels.Entry("old-key", 2, "CCTV-2", null, 0, "http://x/2")),
            groups
        )
        assertEquals(listOf("CCTV-2"), byUrl.map { it.third.title })
    }

    @Test
    fun resolveUsesStoredVariantIndex() {
        val g = group("cctv1", "CCTV-1", ch(1, "CCTV-1"), ch(11, "CCTV-1 HD", "http://x/11"))
        val resolved = RecentChannels.resolve(
            listOf(RecentChannels.Entry("cctv1", 1, "CCTV-1", null, 1, "http://x/11")),
            listOf(g)
        )
        assertEquals("http://x/11", resolved.single().third.url)
    }

    @Test
    fun previousSkipsCurrentGroup() {
        val list = listOf(
            RecentChannels.Entry("cctv2", 2, "B", null, 0, "u2"),
            RecentChannels.Entry("cctv1", 1, "A", null, 0, "u1"),
        )
        assertEquals("cctv1", RecentChannels.previous(list, "cctv2")?.groupKey)
        assertNull(RecentChannels.previous(listOf(RecentChannels.Entry("cctv2", 2, "B", null, 0, "u2")), "cctv2"))
        assertNull(RecentChannels.previous(emptyList(), "cctv2"))
        assertEquals("cctv2", RecentChannels.previous(list, null)?.groupKey)
    }

    @Test
    fun effectiveVariantFallsBackWhenOutOfRange() {
        val g = group("cctv1", "CCTV-1", ch(1, "CCTV-1"))
        // stored variant index 5 no longer exists after the playlist lost that line
        val resolved = RecentChannels.resolve(
            listOf(RecentChannels.Entry("cctv1", 1, "CCTV-1", null, 5, "http://x/1")),
            listOf(g)
        )
        assertEquals("http://x/1", resolved.single().third.url)
    }
}
