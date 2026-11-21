package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class M3uParserUserAgentTest {
    @Test
    fun parsesAndNormalizesVlcUserAgent() {
        val channels = M3uParser.parse(
            """#EXTM3U
                |#EXTINF:-1 tvg-name="Channel" http-user-agent="VLC",Channel
                |http://192.168.2.1/live
            """.trimMargin()
        )
        assertEquals(1, channels.size)
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", channels.single().userAgent)
    }

    @Test
    fun preservesQuotedUserAgentWithSpaces() {
        val channels = M3uParser.parse(
            """#EXTM3U
                |#EXTINF:-1 http-user-agent="Player Agent/1.2 Android",Channel
                |http://192.168.2.1/live
            """.trimMargin()
        )
        assertEquals("Player Agent/1.2 Android", channels.single().userAgent)
    }
}
