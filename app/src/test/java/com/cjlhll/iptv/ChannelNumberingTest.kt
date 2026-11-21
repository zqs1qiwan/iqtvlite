package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNumberingTest {
    private fun sample(): List<ChannelGroup> = ChannelGrouper.group(
        listOf(
            Channel(title = "CCTV1", url = "http://a/cctv1.m3u8", tvgName = "CCTV1", group = "央视频道"),
            Channel(title = "CCTV1 HD", url = "http://a/cctv1hd.m3u8", tvgName = "CCTV1", group = "央视频道"),
            Channel(title = "CCTV2", url = "http://a/cctv2.m3u8", tvgName = "CCTV2", group = "央视频道"),
            Channel(title = "湖南卫视", url = "http://a/hunan.m3u8", tvgName = "湖南卫视", group = "卫视频道"),
        )
    )

    @Test
    fun numberedDisplayChannelsAreOneBasedGroupOrder() {
        val numbered = ChannelGrouper.numberedDisplayChannels(sample())
        assertEquals(listOf(1, 2, 3), numbered.map { it.number })
        assertEquals("CCTV1", numbered[0].channel.title)
        assertEquals("CCTV2", numbered[1].channel.title)
        assertEquals("湖南卫视", numbered[2].channel.title)
    }

    @Test
    fun duplicateVariantsShareOneGlobalNumber() {
        val groups = sample()
        val byUrl = ChannelGrouper.globalNumberByUrl(groups)
        assertEquals(1, byUrl["http://a/cctv1.m3u8"])
        assertEquals(1, byUrl["http://a/cctv1hd.m3u8"])
        assertEquals(2, byUrl["http://a/cctv2.m3u8"])
        assertEquals(3, byUrl["http://a/hunan.m3u8"])
    }

    @Test
    fun groupFilterKeepsOriginalGlobalNumbers() {
        val groups = sample()
        val satellite = ChannelGrouper.numberedDisplayChannelsForGroup(groups, "卫视频道")
        assertEquals(1, satellite.size)
        // 过滤后仍保留全局编号 3，而不是重新编号成 1
        assertEquals(3, satellite.first().number)
        assertEquals("湖南卫视", satellite.first().channel.title)
    }

    @Test
    fun allGroupReturnsEveryChannelWithGlobalNumbers() {
        val groups = sample()
        val all = ChannelGrouper.numberedDisplayChannelsForGroup(groups, "全部")
        assertEquals(3, all.size)
        assertEquals(listOf(1, 2, 3), all.map { it.number })
    }

    @Test
    fun numberToGroupIndexRoundTrips() {
        val groups = sample()
        assertEquals(0, ChannelGrouper.groupIndexForNumber(groups, 1))
        assertEquals(2, ChannelGrouper.groupIndexForNumber(groups, 3))
        assertNull(ChannelGrouper.groupIndexForNumber(groups, 0))
        assertNull(ChannelGrouper.groupIndexForNumber(groups, 4))
    }

    @Test
    fun globalNumberOfGroupMatchesIndexPlusOne() {
        val groups = sample()
        assertTrue(groups.isNotEmpty())
        assertEquals(1, ChannelGrouper.globalNumberOfGroup(groups, 0))
        assertEquals(3, ChannelGrouper.globalNumberOfGroup(groups, 2))
        assertEquals(0, ChannelGrouper.globalNumberOfGroup(groups, 9))
    }

    @Test
    fun emptyGroupsProduceNoNumbers() {
        assertTrue(ChannelGrouper.numberedDisplayChannels(emptyList()).isEmpty())
        assertTrue(ChannelGrouper.globalNumberByUrl(emptyList()).isEmpty())
    }
}
