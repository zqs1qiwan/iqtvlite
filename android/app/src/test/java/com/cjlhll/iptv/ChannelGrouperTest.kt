package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelGrouperTest {

    @Test
    fun keeps4kAndStandardChannelsSeparateEvenWithSameTvgId() {
        val ch4k = Channel(
            title = "北京卫视4K",
            url = HttpStreamAddress.resolve(
                url = "https://visual-play.v.btime.com/live/btv_shiting_s110.m3u8?txSecret=abc&txTime=6B050C9A|Referer=https://api.chinaaudiovisual.cn/",
                extinfUserAgent = null,
                extinfReferrer = "https://api.chinaaudiovisual.cn/",
            ).url,
            group = "卫视频道",
            logoUrl = "https://logo.laobaitv.net/北京卫视4K",
            tvgId = "beijingweishi",
            tvgName = "北京卫视4K",
        )
        val chHd = Channel(
            title = "北京卫视",
            url = HttpStreamAddress.resolve(
                url = "http://audiovisual.vsd.gehua.net.cn/live/BTV1HD_1200.m3u8?sign=test&auth_key=1795|User-Agent=VLC",
                extinfUserAgent = "VLC",
                extinfReferrer = null,
            ).url,
            group = "卫视频道",
            logoUrl = "https://logo.laobaitv.net/北京卫视",
            tvgId = "beijingweishi",
            tvgName = "北京卫视",
        )

        val groups = ChannelGrouper.group(listOf(ch4k, chHd))
        assertEquals(2, groups.size)
        assertEquals("北京卫视4K", groups[0].displayTitle)
        assertEquals("北京卫视", groups[1].displayTitle)
        assertNotEquals(groups[0].key, groups[1].key)

        // 两个频道仍共享同一 EPG 规范键以匹配节目单
        assertTrue("beijingweishi" in ChannelGrouper.canonicalKeys(ch4k))
        assertTrue("beijingweishi" in ChannelGrouper.canonicalKeys(chHd))
    }

    @Test
    fun stillMergesSameEditionStreamsIntoVariants() {
        val hdLine1 = Channel(
            title = "北京卫视 [1080P]",
            url = "http://example.com/btv1.m3u8",
            group = "卫视频道",
            tvgId = "beijingweishi",
            tvgName = "北京卫视",
        )
        val hdLine2 = Channel(
            title = "北京卫视 [720P]",
            url = "http://example.com/btv2.m3u8",
            group = "卫视频道",
            tvgId = "beijingweishi",
            tvgName = "北京卫视",
        )
        val uhdLine1 = Channel(
            title = "北京卫视4K",
            url = "http://example.com/btv4k_1.m3u8",
            group = "卫视频道",
            tvgId = "beijingweishi",
            tvgName = "北京卫视4K",
        )
        val uhdLine2 = Channel(
            title = "北京卫视4K 备用",
            url = "http://example.com/btv4k_2.m3u8",
            group = "卫视频道",
            tvgId = "beijingweishi",
            tvgName = "北京卫视4K",
        )

        val groups = ChannelGrouper.group(listOf(uhdLine1, uhdLine2, hdLine1, hdLine2))
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].variants.size)
        assertEquals(2, groups[1].variants.size)
    }
}
