package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t133: recent-channels / last-channel rules (pure JVM, no Android Context). */
class RecentChannelsTest {

    private fun ch(id: Long, title: String, url: String = "http://x/$id") =
        Channel(title = title, url = url, id = id)

    @Test
    fun pushPutsNewestFirstAndDeduplicatesByStableId() {
        val a = ch(1, "A")
        val b = ch(2, "B")
        var list = RecentChannels.push(emptyList(), a, 1)
        list = RecentChannels.push(list, b, 2)
        assertEquals(listOf("id:2", "id:1"), list.map { it.key })

        // re-selecting A moves it to the front instead of duplicating
        list = RecentChannels.push(list, a, 1)
        assertEquals(listOf("id:1", "id:2"), list.map { it.key })
        assertEquals(2, list.size)
    }

    @Test
    fun pushCapsAtMaxEntries() {
        var list = emptyList<RecentChannels.Entry>()
        repeat(RecentChannels.MAX + 5) { i -> list = RecentChannels.push(list, ch(i + 1L, "C$i"), i + 1) }
        assertEquals(RecentChannels.MAX, list.size)
        // newest kept, oldest dropped
        assertEquals("id:${RecentChannels.MAX + 5}", list.first().key)
        assertTrue(list.none { it.key == "id:1" })
    }

    @Test
    fun encodeDecodeRoundTripsAndSurvivesSeparators() {
        val tricky = Channel(title = "A\u0001B\u0002C", url = "http://x/1", id = 7, logoUrl = "http://x/l.png")
        val list = RecentChannels.push(emptyList(), tricky, 3)
        val back = RecentChannels.decode(RecentChannels.encode(list))
        assertEquals(1, back.size)
        assertEquals("id:7", back[0].key)
        assertEquals(3, back[0].number)
        assertEquals("http://x/1", back[0].url)
        assertEquals("http://x/l.png", back[0].logoUrl)
        assertTrue("separators must be sanitized", !back[0].title.contains('\u0001'))
    }

    @Test
    fun decodeToleratesGarbageAndEmpty() {
        assertEquals(emptyList<RecentChannels.Entry>(), RecentChannels.decode(null))
        assertEquals(emptyList<RecentChannels.Entry>(), RecentChannels.decode(""))
        assertEquals(emptyList<RecentChannels.Entry>(), RecentChannels.decode("garbage"))
    }

    @Test
    fun resolveSkipsMissingChannelsAndFallsBackToUrl() {
        val live = listOf(ch(1, "A"), ch(2, "B"))
        val entries = listOf(
            RecentChannels.Entry("id:1", 1, "A", null, "http://x/1"),
            RecentChannels.Entry("id:999", 9, "Gone", null, "http://x/999"),
        )
        val resolved = RecentChannels.resolve(entries, live)
        assertEquals(listOf("A"), resolved.map { it.second.title })

        // id-less channel resolves via url key
        val noId = Channel(title = "C", url = "http://x/c", id = 0)
        assertEquals("url:http://x/c", RecentChannels.stableKey(noId))
        val byUrl = RecentChannels.resolve(
            listOf(RecentChannels.Entry("url:http://x/c", 3, "C", null, "http://x/c")),
            listOf(noId)
        )
        assertEquals(listOf("C"), byUrl.map { it.second.title })
    }

    @Test
    fun previousReturnsMostRecentOtherChannel() {
        val list = listOf(
            RecentChannels.Entry("id:2", 2, "B", null, "u2"),
            RecentChannels.Entry("id:1", 1, "A", null, "u1"),
        )
        assertEquals("id:1", RecentChannels.previous(list, "id:2")?.key)
        assertNull(RecentChannels.previous(listOf(RecentChannels.Entry("id:2", 2, "B", null, "u2")), "id:2"))
        assertNull(RecentChannels.previous(emptyList(), "id:2"))
        // no current channel → newest entry is the "previous"
        assertEquals("id:2", RecentChannels.previous(list, null)?.key)
    }
}
