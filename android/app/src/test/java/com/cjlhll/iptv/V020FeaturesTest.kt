package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class V020FeaturesTest {

    private fun sampleGroups(): List<ChannelGroup> {
        val urls = listOf(
            "http://example.com/1" to ("CCTV-1" to "央视频道"),
            "http://example.com/2" to ("CCTV-2" to "央视频道"),
            "http://example.com/3" to ("浙江卫视" to "卫视频道"),
            "http://example.com/4" to ("江苏卫视" to "卫视频道"),
            "http://example.com/10" to ("CCTV-10" to "央视频道"),
        )
        return urls.mapIndexed { idx, (url, meta) ->
            val ch = Channel(id = (idx + 1).toLong(), title = meta.first, url = url, group = meta.second)
            ChannelGroup(
                key = "k$idx",
                displayTitle = meta.first,
                logoUrl = null,
                group = meta.second,
                variants = listOf(ChannelVariant(ch, "HD")),
                defaultVariantIndex = 0,
            )
        }
    }

    @Test
    fun digitMatchChannelsPutsExactFirstThenPrefix() {
        val groups = sampleGroups() + List(6) { i ->
            val idx = i + 5
            val ch = Channel(id = (idx + 1).toLong(), title = "台$idx", url = "http://example.com/$idx", group = "其他")
            ChannelGroup("k$idx", ch.title, null, ch.group, listOf(ChannelVariant(ch, "HD")), 0)
        }
        val matches = DigitOsdPolicy.matchChannels("1", groups, limit = 6)
        assertEquals(listOf(1, 10, 11), matches.map { it.number })
    }

    @Test
    fun resolutionLabelHonorsExactFlag() {
        assertEquals("4K", InfoCardPolicy.resolutionLabel(3840, 2160, exact = false))
        assertEquals("3840x2160", InfoCardPolicy.resolutionLabel(3840, 2160, exact = true))
        assertEquals("FHD", InfoCardPolicy.resolutionLabel(1920, 1080, exact = false))
        assertEquals("1920x1080", InfoCardPolicy.resolutionLabel(1920, 1080, exact = true))
    }

    @Test
    fun favoritesAndGroupSortingFilteringWorkTogether() {
        val groups = sampleGroups()
        val favs = setOf("http://example.com/3")
        val sorted = ChannelGrouper.sortedDrawerGroups(
            channelGroups = groups,
            favoriteUrls = favs,
            showFavorites = true,
            showAll = true,
            groupConfig = Prefs.SourceGroupConfig(sortMode = "NAME", hiddenGroups = setOf("央视频道")),
        )
        assertEquals(listOf("全部", "收藏夹", "卫视频道"), sorted)
        val favChannels = ChannelGrouper.numberedDisplayChannelsForGroup(groups, ChannelGrouper.FAVORITES_GROUP, favs)
        assertEquals(listOf("浙江卫视"), favChannels.map { it.channel.title })
    }
}
