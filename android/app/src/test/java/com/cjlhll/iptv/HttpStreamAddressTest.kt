package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpStreamAddressTest {

    @Test
    fun inheritsExtinfHeadersAcrossMultipleUrlsUnderSingleExtinfAndRespectsPipeOverride() {
        val m3u = """
            #EXTM3U x-tvg-url="https://epg.example.com/e.xml"
            #EXTINF:-1 tvg-id="dongfangweishi" tvg-name="东方卫视" http-user-agent="VLC" http-referer="https://base.example/",东方卫视
            http://audiovisual.vsd.gehua.net.cn/1111111
            http://audiovisual.vsd.gehua.net.cn/222222|Referer=https://override.example/&Origin=https://override.example
            http://audiovisual.vsd.gehua.net.cn/333333
            #EXTINF:-1 tvg-id="dongfangweishi" tvg-name="东方卫视" group-title="卫视频道",东方卫视
            http://audiovisual.vsd.gehua.net.cn/444444
        """.trimIndent()

        val extras = HttpStreamAddress.extractM3uExtras(m3u)
        assertEquals("https://epg.example.com/e.xml", extras.headerEpgUrl)

        val h1 = extras.perRawUrlHeaders["http://audiovisual.vsd.gehua.net.cn/1111111"]!!
        assertEquals("https://base.example/", h1["Referer"])
        assertEquals(HttpStreamAddress.DEFAULT_USER_AGENT, h1["User-Agent"])

        val h3 = extras.perRawUrlHeaders["http://audiovisual.vsd.gehua.net.cn/333333"]!!
        assertEquals("https://base.example/", h3["Referer"])

        val resolved2 = HttpStreamAddress.resolve(
            url = "http://audiovisual.vsd.gehua.net.cn/222222|Referer=https://override.example/&Origin=https://override.example",
            extinfUserAgent = h1["User-Agent"],
            extinfReferrer = h1["Referer"],
            extinfHeaders = extras.perRawUrlHeaders["http://audiovisual.vsd.gehua.net.cn/222222"] ?: emptyMap(),
        )
        assertEquals("http://audiovisual.vsd.gehua.net.cn/222222", resolved2.url)
        assertEquals(HttpStreamAddress.DEFAULT_USER_AGENT, resolved2.userAgent)
        assertEquals("https://override.example/", resolved2.referrer)
        assertEquals("https://override.example", resolved2.headers["Origin"])

        // 第二个独立 #EXTINF 没有声明头，不应污染继承上一个 #EXTINF 的头
        assertEquals(null, extras.perRawUrlHeaders["http://audiovisual.vsd.gehua.net.cn/444444"])
    }
}
