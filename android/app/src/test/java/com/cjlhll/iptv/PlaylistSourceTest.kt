package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSourceTest {

    @Test
    fun playlistSourcesSerializeAndParseRoundTrip() {
        val list = listOf(
            PlaylistSource(
                id = "src_1",
                name = "老白直播源",
                url = "https://iptv.laobaitv.net",
                epgUrl = "https://epg.laobaitv.net",
                enabled = true,
                userAgent = "",
            ),
            PlaylistSource(
                id = "src_2",
                name = "老白专享源",
                url = "http://lbzs.laobaitv.net",
                epgUrl = "https://epg.laobaitv.net",
                enabled = false,
                userAgent = "aliplayer",
            ),
        )
        val encoded = PlaylistSources.serialize(list)
        val decoded = PlaylistSources.parse(encoded)
        assertEquals(list, decoded)
    }

    @Test
    fun visibleSourcesRespectsShowHideFlagAndNeverReturnsEmpty() {
        val s1 = PlaylistSource("1", "主源", "https://a.test/1.m3u", enabled = true)
        val s2 = PlaylistSource("2", "备用源", "https://b.test/2.m3u", enabled = false)
        assertEquals(listOf(s1), PlaylistSources.visibleSources(listOf(s1, s2)))

        // 若全部隐藏，兜底保留首个防止抽屉空源
        val allHidden = listOf(s1.copy(enabled = false), s2)
        assertEquals(listOf(s1.copy(enabled = false)), PlaylistSources.visibleSources(allHidden))
    }

    @Test
    fun customHeadersParseAndFormatCanonicalizesKeys() {
        val raw = """
            # 注释行
            referer: https://api.chinaaudiovisual.cn/
            origin=https://embedindia.st
            X-Custom-Token: abc123
            user-agent: IgnoredOrKept
        """.trimIndent()
        val parsed = PlaylistSources.parseCustomHeaders(raw)
        assertEquals("https://api.chinaaudiovisual.cn/", parsed["Referer"])
        assertEquals("https://embedindia.st", parsed["Origin"])
        assertEquals("abc123", parsed["X-Custom-Token"])
        assertEquals("IgnoredOrKept", parsed["User-Agent"])
        val formatted = PlaylistSources.formatCustomHeaders(parsed)
        assertEquals(parsed, PlaylistSources.parseCustomHeaders(formatted))
    }

    @Test
    fun lbzsLaobaiM3uExtractsEpgExtinfAndUrlPipeHeadersWithUrlPriority() {
        val m3u = """
            #EXTM3U x-tvg-url="https://epg.laobaitv.net"
            #EXTINF:-1 tvg-id="CCTV1" tvg-name="CCTV1" http-user-agent="VLC" http-referer="https://extinf.example/",CCTV1综合
            http://stream.test/cctv1.m3u8|User-Agent=aliplayer&Referer=https://api.chinaaudiovisual.cn/&Origin=https://origin.test
            #EXTINF:-1 tvg-id="CCTV2" http-user-agent="VLC" http-referer="https://embedindia.st/",CCTV2财经
            http://stream.test/cctv2.m3u8
            #EXTINF:-1 tvg-id="CCTV3",CCTV3综艺
            #EXTVLCOPT:http-user-agent=AptvPlayer/1.4.2
            #EXTVLCOPT:http-referrer=https://vlcopt.test/
            http://stream.test/cctv3.m3u8
            #EXTINF:-1 tvg-id="CCTV4",CCTV4中文国际
            http://stream.test/cctv4.m3u8|User-Agent=VLC
        """.trimIndent()

        val extras = HttpStreamAddress.extractM3uExtras(m3u)
        assertEquals("https://epg.laobaitv.net", extras.headerEpgUrl)

        // 1. URL | 与 EXTINF 同时存在时：URL | 必须优先！
        val rawUrl1 = "http://stream.test/cctv1.m3u8|User-Agent=aliplayer&Referer=https://api.chinaaudiovisual.cn/&Origin=https://origin.test"
        val r1 = HttpStreamAddress.resolve(
            url = rawUrl1,
            extinfUserAgent = "VLC",
            extinfReferrer = "https://extinf.example/",
            extinfHeaders = extras.perRawUrlHeaders[rawUrl1].orEmpty(),
        )
        assertEquals("http://stream.test/cctv1.m3u8", r1.url)
        assertEquals("aliplayer", r1.userAgent)
        assertEquals("https://api.chinaaudiovisual.cn/", r1.referrer)
        assertEquals("https://origin.test", r1.headers["Origin"])

        // 2. 仅 EXTINF 存在时：自动识别 http-user-agent="VLC" (归一化为 VLC/3.0.20) 与 http-referer
        val rawUrl2 = "http://stream.test/cctv2.m3u8"
        val r2 = HttpStreamAddress.resolve(
            url = rawUrl2,
            extinfUserAgent = null,
            extinfReferrer = null,
            extinfHeaders = extras.perRawUrlHeaders[rawUrl2].orEmpty(),
        )
        assertEquals("http://stream.test/cctv2.m3u8", r2.url)
        assertEquals(HttpStreamAddress.DEFAULT_USER_AGENT, r2.userAgent)
        assertEquals("https://embedindia.st/", r2.referrer)

        // 3. #EXTVLCOPT 指令识别
        val rawUrl3 = "http://stream.test/cctv3.m3u8"
        val r3 = HttpStreamAddress.resolve(
            url = rawUrl3,
            extinfUserAgent = null,
            extinfReferrer = null,
            extinfHeaders = extras.perRawUrlHeaders[rawUrl3].orEmpty(),
        )
        assertEquals("AptvPlayer/1.4.2", r3.userAgent)
        assertEquals("https://vlcopt.test/", r3.referrer)

        // 4. 仅 URL |User-Agent=VLC 时归一化为标准 VLC UA
        val r4 = HttpStreamAddress.resolve("http://stream.test/cctv4.m3u8|User-Agent=VLC", null, null)
        assertEquals("http://stream.test/cctv4.m3u8", r4.url)
        assertEquals(HttpStreamAddress.DEFAULT_USER_AGENT, r4.userAgent)
        assertNull(r4.referrer)
    }

    @Test
    fun playbackRequestMergesGlobalHeadersBelowChannelHeaders() {
        val chWithUrlOverride = Channel(
            title = "CCTV1",
            url = "http://stream.test/1.m3u8",
            userAgent = "aliplayer",
            referrer = "https://channel-ref.test/",
            customHeaders = mapOf("Origin" to "https://channel-origin.test"),
        )
        val headers1 = MediaPlaybackRequest(chWithUrlOverride).httpHeaders(
            globalUserAgent = "GlobalUA/1.0",
            globalCustomHeaders = mapOf(
                "Referer" to "https://global-ref.test/",
                "Origin" to "https://global-origin.test",
                "Cookie" to "sid=global",
            ),
        )
        assertEquals("aliplayer", headers1["User-Agent"])
        assertEquals("https://channel-ref.test/", headers1["Referer"])
        assertEquals("https://channel-origin.test", headers1["Origin"])
        assertEquals("sid=global", headers1["Cookie"])

        // 频道无自带 UA/Header 时：全局配置生效
        val plainChannel = Channel(title = "CCTV5", url = "http://stream.test/5.m3u8")
        val headers2 = MediaPlaybackRequest(plainChannel).httpHeaders(
            globalUserAgent = "GlobalUA/1.0",
            globalCustomHeaders = mapOf("Referer" to "https://global-ref.test/"),
        )
        assertEquals("GlobalUA/1.0", headers2["User-Agent"])
        assertEquals("https://global-ref.test/", headers2["Referer"])

        // 频道与全局均为空时：兜底默认 VLC UA
        val headers3 = MediaPlaybackRequest(plainChannel).httpHeaders()
        assertEquals(HttpStreamAddress.DEFAULT_USER_AGENT, headers3["User-Agent"])
        assertFalse(headers3.containsKey("Referer"))
    }

    @Test
    fun pipeQuotedUserAgentStripsOuterQuotes() {
        val parsed = HttpStreamAddress.resolve(
            url = "http://173.208.212.130:8181/720p/cctv1.m3u8|User-Agent=\"bingcha/1.1 (mianfeifenxiang)\"",
            extinfUserAgent = "VLC",
            extinfReferrer = null,
        )
        assertEquals("http://173.208.212.130:8181/720p/cctv1.m3u8", parsed.url)
        assertEquals("bingcha/1.1 (mianfeifenxiang)", parsed.userAgent)
    }
}
