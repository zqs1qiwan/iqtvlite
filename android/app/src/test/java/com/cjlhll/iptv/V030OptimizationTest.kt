package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class V030OptimizationTest {

    private fun groupWithLogo(index: Int, logoUrl: String?): ChannelGroup {
        val ch = Channel(
            id = index.toLong(),
            title = "Channel $index",
            url = "http://example.com/$index",
            logoUrl = logoUrl,
            group = "Default",
        )
        return ChannelGroup(
            key = "g$index",
            displayTitle = ch.title,
            logoUrl = logoUrl,
            group = "Default",
            variants = listOf(ChannelVariant(channel = ch, qualityLabel = "Line 1")),
            defaultVariantIndex = 0,
        )
    }

    @Test
    fun buildAdjacentLogoUrlsCentersOnCurrentWrapsAndDeduplicates() {
        val groups = listOf(
            groupWithLogo(0, "http://img/0.png"),
            groupWithLogo(1, "http://img/1.png"),
            groupWithLogo(2, " "),
            groupWithLogo(3, "http://img/0.png"),
            groupWithLogo(4, null),
            groupWithLogo(5, "http://img/5.png"),
        )

        val urls = buildAdjacentLogoUrls(groups, currentIndex = 0, radius = 2)

        assertEquals(
            listOf(
                "http://img/0.png",
                "http://img/1.png",
                "http://img/5.png",
            ),
            urls,
        )
    }

    @Test
    fun buildAdjacentLogoUrlsHandlesEmptyAndNegativeRadius() {
        assertEquals(emptyList<String>(), buildAdjacentLogoUrls(emptyList(), 0))
        val single = listOf(groupWithLogo(0, "http://img/a.png"))
        assertEquals(emptyList<String>(), buildAdjacentLogoUrls(single, 0, radius = -1))
        assertEquals(listOf("http://img/a.png"), buildAdjacentLogoUrls(single, 0, radius = 3))
    }

    @Test
    fun formatRemainingMinutesFormatsCnEnAndBounds() {
        val now = 1_000_000L
        assertEquals("", InfoCardPolicy.formatRemainingMinutes(0L, now, AppLang.ZH))
        assertEquals("", InfoCardPolicy.formatRemainingMinutes(now - 10_000L, now, AppLang.EN))
        assertEquals("", InfoCardPolicy.formatRemainingMinutes(now, now, AppLang.ZH))
        assertEquals("剩余 <1 分钟", InfoCardPolicy.formatRemainingMinutes(now + 20_000L, now, AppLang.ZH))
        assertEquals("<1 min left", InfoCardPolicy.formatRemainingMinutes(now + 20_000L, now, AppLang.EN))
        assertEquals("剩余 12 分钟", InfoCardPolicy.formatRemainingMinutes(now + 12 * 60_000L, now, AppLang.ZH))
        assertEquals("12 min left", InfoCardPolicy.formatRemainingMinutes(now + 12 * 60_000L, now, AppLang.EN))
    }

    @Test
    fun playlistOrderSortingStrictlyPreservesFirstAppearanceOrder() {
        fun groupWithCategory(index: Int, category: String): ChannelGroup {
            val ch = Channel(
                id = index.toLong(),
                title = "Ch $index",
                url = "http://example.com/$index",
                group = category,
            )
            return ChannelGroup(
                key = "cat_$index",
                displayTitle = ch.title,
                logoUrl = null,
                group = category,
                variants = listOf(ChannelVariant(channel = ch, qualityLabel = "Line 1")),
                defaultVariantIndex = 0,
            )
        }
        val groups = listOf(
            groupWithCategory(0, "北京频道"),
            groupWithCategory(1, "港澳台"),
            groupWithCategory(2, "其他频道"),
            groupWithCategory(3, "央视频道"),
        )

        assertEquals(
            listOf("北京频道", "港澳台", "其他频道", "央视频道"),
            ChannelGrouper.allRawGroupNames(groups),
        )
        assertEquals(
            listOf("全部", "北京频道", "港澳台", "其他频道", "央视频道"),
            ChannelGrouper.sortedDrawerGroups(
                channelGroups = groups,
                favoriteUrls = emptySet(),
                showFavorites = true,
                showAll = true,
                groupConfig = Prefs.SourceGroupConfig(sortMode = "DEFAULT"),
            ),
        )
    }
}
