package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RtpMulticastAndDualStackTest {

    @Test
    fun parseEndpointSupportsRtpUdpMulticastUnicastAndRejectsInvalid() {
        val rtpMulticast = RtpPacketParser.parseEndpoint("rtp://239.3.1.129:8008")
        assertNotNull(rtpMulticast)
        assertEquals("rtp", rtpMulticast!!.scheme)
        assertEquals("239.3.1.129", rtpMulticast.host)
        assertEquals(8008, rtpMulticast.port)
        assertTrue(rtpMulticast.isMulticast)

        val rtpAt = RtpPacketParser.parseEndpoint("rtp://@239.3.1.129:8008")
        assertNotNull(rtpAt)
        assertEquals("239.3.1.129", rtpAt!!.host)
        assertEquals(8008, rtpAt.port)
        assertTrue(rtpAt.isMulticast)

        val rtpSsm = RtpPacketParser.parseEndpoint("rtp://10.1.2.3@239.3.1.129:8008")
        assertNotNull(rtpSsm)
        assertEquals("239.3.1.129", rtpSsm!!.host)
        assertEquals(8008, rtpSsm.port)
        assertTrue(rtpSsm.isMulticast)

        val rtpPipeAndQuery = RtpPacketParser.parseEndpoint("rtp://239.3.1.129:8008?fcc=10.0.0.1:8027|User-Agent=VLC")
        assertNotNull(rtpPipeAndQuery)
        assertEquals("239.3.1.129", rtpPipeAndQuery!!.host)
        assertEquals(8008, rtpPipeAndQuery.port)
        assertTrue(rtpPipeAndQuery.isMulticast)

        val udpV6Multicast = RtpPacketParser.parseEndpoint("udp://[ff05::1:3]:5000")
        assertNotNull(udpV6Multicast)
        assertEquals("udp", udpV6Multicast!!.scheme)
        assertEquals("ff05::1:3", udpV6Multicast.host)
        assertEquals(5000, udpV6Multicast.port)
        assertTrue(udpV6Multicast.isMulticast)

        val udpUnicast = RtpPacketParser.parseEndpoint("udp://192.168.1.50:1234")
        assertNotNull(udpUnicast)
        assertEquals("192.168.1.50", udpUnicast!!.host)
        assertEquals(1234, udpUnicast.port)
        assertFalse(udpUnicast.isMulticast)

        assertNull(RtpPacketParser.parseEndpoint("http://239.3.1.129:8008/live.ts"))
        assertNull(RtpPacketParser.parseEndpoint("rtp://239.3.1.129"))
    }

    @Test
    fun extractPayloadHandlesRawTsStandardRtpComplexRtpAndRtcp() {
        // 1. Raw MPEG-TS directly over UDP (even if mislabeled rtp://)
        val rawTs = ByteArray(1316)
        rawTs[0] = 0x47
        rawTs[188] = 0x47
        val rawSlice = RtpPacketParser.extractPayload(rawTs, rawTs.size)
        assertEquals(RtpPacketParser.PayloadSlice(offset = 0, length = 1316), rawSlice)

        // 2. Standard 12-byte RFC 2250 RTP v2 header + 1316 bytes TS payload
        val stdRtp = ByteArray(12 + 1316)
        stdRtp[0] = 0x80.toByte()
        stdRtp[1] = 0x21.toByte()
        stdRtp[12] = 0x47
        val stdSlice = RtpPacketParser.extractPayload(stdRtp, stdRtp.size)
        assertEquals(RtpPacketParser.PayloadSlice(offset = 12, length = 1316), stdSlice)

        // 3. RTP v2 with P=1, X=1, CC=2 -> 0xB2; 12 + 2*4 CSRC + 4 ext hdr + 1*4 ext payload = 28, plus 188 TS + 4 pad
        val complex = ByteArray(12 + 8 + 8 + 188 + 4)
        complex[0] = 0xB2.toByte()
        complex[1] = 0x21.toByte()
        // Extension header word count = 1 (bytes 22..23)
        complex[22] = 0x00
        complex[23] = 0x01
        complex[28] = 0x47
        complex[complex.size - 1] = 0x04
        val complexSlice = RtpPacketParser.extractPayload(complex, complex.size)
        assertEquals(RtpPacketParser.PayloadSlice(offset = 28, length = 188), complexSlice)

        // 4. RTCP SR (PT=200), truncated header, and invalid RTP v1 are skipped
        val rtcp = ByteArray(28)
        rtcp[0] = 0x80.toByte()
        rtcp[1] = 200.toByte()
        assertNull(RtpPacketParser.extractPayload(rtcp, rtcp.size))

        val truncated = ByteArray(8)
        truncated[0] = 0x80.toByte()
        assertNull(RtpPacketParser.extractPayload(truncated, truncated.size))

        val rtpV1 = ByteArray(200)
        rtpV1[0] = 0x40.toByte()
        assertNull(RtpPacketParser.extractPayload(rtpV1, rtpV1.size))
    }

    @Test
    fun netUtilOrderAddressesRespectsStackPreferenceWithFallback() {
        val v4a = InetAddress.getByAddress("a.example", byteArrayOf(10, 0, 0, 1))
        val v4b = InetAddress.getByAddress("a.example", byteArrayOf(10, 0, 0, 2))
        val v6a = InetAddress.getByAddress(
            "a.example",
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1),
        )
        val v6b = InetAddress.getByAddress(
            "a.example",
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2),
        )
        val mixed = listOf(v4a, v6a, v4b, v6b)

        assertEquals(
            listOf(v6a, v6b, v4a, v4b),
            NetUtil.orderAddresses(mixed, IpStackPreference.IPV6_FIRST),
        )
        assertEquals(
            listOf(v4a, v4b, v6a, v6b),
            NetUtil.orderAddresses(mixed, IpStackPreference.IPV4_FIRST),
        )
    }

    @Test
    fun selectSoftwareOnlyVideoDecodersRejects1080pCappedSwDecodersOn4k() {
        val mixed1080p = listOf(
            VideoDecoderCandidate("hw-hevc", "OMX.goke.video.decoder.hevc", softwareOnly = false, sizeSupported = true),
            VideoDecoderCandidate("sw-c2", "c2.android.hevc.decoder", softwareOnly = true, sizeSupported = true),
            VideoDecoderCandidate("sw-google", "OMX.google.hevc.decoder", softwareOnly = true, sizeSupported = false),
        )
        assertEquals(listOf("sw-c2"), selectSoftwareOnlyVideoDecoders(mixed1080p))

        // On 4K UHD (3840x2160), OMX.google.hevc.decoder is capped at 1920x1088 (sizeSupported = false),
        // while OMX.goke.video.decoder.hevc supports 4096x2306 -> must keep hardware decoder first!
        val mixed4k = listOf(
            VideoDecoderCandidate("sw-google", "OMX.google.hevc.decoder", softwareOnly = true, sizeSupported = false),
            VideoDecoderCandidate("hw-hevc", "OMX.goke.video.decoder.hevc", softwareOnly = false, sizeSupported = true),
        )
        assertEquals(listOf("hw-hevc", "sw-google"), selectSoftwareOnlyVideoDecoders(mixed4k))

        val hwOnly = listOf(
            VideoDecoderCandidate("hw-1", "OMX.qcom.video.decoder.avc", softwareOnly = false, sizeSupported = true),
            VideoDecoderCandidate("hw-2", "c2.qti.avc.decoder", softwareOnly = false, sizeSupported = true),
        )
        assertEquals(listOf("hw-1", "hw-2"), selectSoftwareOnlyVideoDecoders(hwOnly))
    }

    @Test
    fun extractM3uExtrasPreservesPerEntryHeadersAndExtGrpForDuplicateUrls() {
        val playlist = """
            #EXTM3U
            #EXTGRP:News
            #EXTINF:-1,CCTV-1 A
            #EXTVLCOPT:http-referrer=https://first.example.com/
            http://cdn.example.com/live.ts
            #EXTGRP:Sports
            #EXTINF:-1,CCTV-1 B
            #EXTVLCOPT:http-referrer=https://second.example.com/
            http://cdn.example.com/live.ts
        """.trimIndent()

        val extras = HttpStreamAddress.extractM3uExtras(playlist)
        assertEquals(2, extras.perEntryHeaders.size)
        assertEquals("https://first.example.com/", extras.perEntryHeaders[0]["Referer"])
        assertEquals("https://second.example.com/", extras.perEntryHeaders[1]["Referer"])
        assertEquals(listOf("News", "Sports"), extras.perEntryExtGrp)
    }

    @Test
    fun channelGrouperDoesNotMisclassifyPortAsResolution() {
        val portChannel = Channel(
            title = "Channel One",
            url = "http://10.0.0.1:10800/live/stream.ts",
            group = "Test",
            id = 101L,
        )
        val hdPathChannel = Channel(
            title = "Channel Two",
            url = "http://10.0.0.1:8080/live/1080p/index.m3u8",
            group = "Test",
            id = 102L,
        )

        val portGroups = ChannelGrouper.group(listOf(portChannel))
        assertEquals(1, portGroups.size)
        assertFalse(portGroups[0].variants[0].qualityLabel.contains("1080p"))

        val hdGroups = ChannelGrouper.group(listOf(hdPathChannel))
        assertEquals(1, hdGroups.size)
        assertTrue(hdGroups[0].variants[0].qualityLabel.contains("1080p"))
    }

    @Test
    fun rtpStreamAssemblerDeduplicatesReordersHandlesWraparoundAndLoss() {
        fun makeRtpPacket(seq: Int, ssrc: Int, markerByte: Byte): ByteArray {
            val pkt = ByteArray(12 + 188)
            pkt[0] = 0x80.toByte()
            pkt[1] = 0x21.toByte()
            pkt[2] = ((seq ushr 8) and 0xFF).toByte()
            pkt[3] = (seq and 0xFF).toByte()
            pkt[8] = ((ssrc ushr 24) and 0xFF).toByte()
            pkt[9] = ((ssrc ushr 16) and 0xFF).toByte()
            pkt[10] = ((ssrc ushr 8) and 0xFF).toByte()
            pkt[11] = (ssrc and 0xFF).toByte()
            pkt[12] = 0x47
            pkt[13] = markerByte
            return pkt
        }

        val assembler = RtpStreamAssembler()
        val parsed = RtpPacketParser.ParsedPacket()
        val emittedMarkers = ArrayList<Int>()
        val tmp = ByteArray(256)

        fun pushAndCollect(raw: ByteArray) {
            assertTrue(RtpPacketParser.inspectPacket(raw, raw.size, parsed))
            when (assembler.offer(raw, parsed)) {
                RtpStreamAssembler.OfferResult.DROP,
                RtpStreamAssembler.OfferResult.BUFFERED -> Unit
                RtpStreamAssembler.OfferResult.DELIVER_CURRENT -> {
                    emittedMarkers.add(raw[parsed.offset + 1].toInt() and 0xFF)
                    assembler.onCurrentDelivered()
                    while (assembler.hasReady()) {
                        val n = assembler.readReadyInto(tmp, 0, tmp.size)
                        assertEquals(188, n)
                        emittedMarkers.add(tmp[1].toInt() and 0xFF)
                    }
                }
                RtpStreamAssembler.OfferResult.FLUSH_THEN_CURRENT -> {
                    while (assembler.hasReady()) {
                        val n = assembler.readReadyInto(tmp, 0, tmp.size)
                        assertEquals(188, n)
                        emittedMarkers.add(tmp[1].toInt() and 0xFF)
                    }
                    emittedMarkers.add(raw[parsed.offset + 1].toInt() and 0xFF)
                }
                RtpStreamAssembler.OfferResult.DRAIN_READY -> {
                    while (assembler.hasReady()) {
                        val n = assembler.readReadyInto(tmp, 0, tmp.size)
                        assertEquals(188, n)
                        emittedMarkers.add(tmp[1].toInt() and 0xFF)
                    }
                }
            }
        }

        // 1. In-order + duplicate packets dropped
        pushAndCollect(makeRtpPacket(100, 1, 10))
        pushAndCollect(makeRtpPacket(100, 1, 10))
        pushAndCollect(makeRtpPacket(101, 1, 11))
        pushAndCollect(makeRtpPacket(101, 1, 11))
        pushAndCollect(makeRtpPacket(102, 1, 12))
        assertEquals(listOf(10, 11, 12), emittedMarkers)

        // 2. Out-of-order packet 202 buffered while waiting on 201, then drained in order
        emittedMarkers.clear()
        pushAndCollect(makeRtpPacket(200, 2, 20))
        pushAndCollect(makeRtpPacket(202, 2, 22))
        pushAndCollect(makeRtpPacket(201, 2, 21))
        pushAndCollect(makeRtpPacket(203, 2, 23))
        assertEquals(listOf(20, 21, 22, 23), emittedMarkers)

        // 3. 16-bit sequence wraparound 65534 -> 65535 -> 0 -> 1 with reorder (0 before 65535)
        emittedMarkers.clear()
        pushAndCollect(makeRtpPacket(65534, 3, 30))
        pushAndCollect(makeRtpPacket(0, 3, 32))
        pushAndCollect(makeRtpPacket(65535, 3, 31))
        pushAndCollect(makeRtpPacket(1, 3, 33))
        assertEquals(listOf(30, 31, 32, 33), emittedMarkers)

        // 4. Genuine loss of 301: 4 subsequent arrivals advance past missing packet without stall
        emittedMarkers.clear()
        pushAndCollect(makeRtpPacket(300, 4, 40))
        pushAndCollect(makeRtpPacket(302, 4, 42))
        pushAndCollect(makeRtpPacket(303, 4, 43))
        pushAndCollect(makeRtpPacket(304, 4, 44))
        pushAndCollect(makeRtpPacket(305, 4, 45))
        assertEquals(listOf(40, 42, 43, 44, 45), emittedMarkers)

        // 5. Timeout flush + SSRC change resync
        emittedMarkers.clear()
        pushAndCollect(makeRtpPacket(400, 5, 50))
        pushAndCollect(makeRtpPacket(402, 5, 52))
        assertEquals(listOf(50), emittedMarkers)
        assertTrue(assembler.flushOnTimeout())
        while (assembler.hasReady()) {
            val n = assembler.readReadyInto(tmp, 0, tmp.size)
            assertEquals(188, n)
            emittedMarkers.add(tmp[1].toInt() and 0xFF)
        }
        assertEquals(listOf(50, 52), emittedMarkers)

        // New SSRC resets sequence expectation immediately
        emittedMarkers.clear()
        pushAndCollect(makeRtpPacket(9000, 99, 90))
        assertEquals(listOf(90), emittedMarkers)
    }

    @Test
    fun multitestTitlesQualityHintsAndSemicolonMultiGroupsWork() {
        val cgtnNews = Channel(
            title = "CGTN 新闻[343][高清][1080i]",
            url = "rtp://239.3.1.215:4220",
            group = "[高清]北京IPTV",
            tvgId = "20",
            tvgName = "CGTN",
            id = 201L,
            entryIndex = 10,
            groups = listOf("[高清]北京IPTV"),
        )
        val brtvSdr = Channel(
            title = "BRTV北京卫视[81][4K SDR]",
            url = "rtp://239.3.1.22:8001",
            group = "[高清]北京",
            tvgId = "30",
            tvgName = "北京卫视",
            id = 202L,
            entryIndex = 11,
            groups = listOf("[高清]北京", "[高清]卫视", "4K"),
        )
        val brtvHdr = Channel(
            title = "BRTV北京卫视[421][4K HDR]",
            url = "rtp://239.3.1.118:8001",
            group = "[高清]北京",
            tvgId = "30",
            tvgName = "北京卫视",
            id = 203L,
            entryIndex = 12,
            groups = listOf("[高清]北京", "[高清]卫视", "4K"),
        )
        val fangshan = Channel(
            title = "房山电视台[301][高清][1080i]",
            url = "rtp://239.3.1.96:8001",
            group = "[高清]北京",
            id = 204L,
            entryIndex = 13,
            groups = listOf("[高清]北京"),
        )

        val grouped = ChannelGrouper.group(listOf(cgtnNews, brtvSdr, brtvHdr, fangshan))
        assertEquals(4, grouped.size)
        assertEquals("CGTN 新闻", grouped[0].displayTitle)
        assertTrue(grouped[0].variants[0].qualityLabel.contains("1080i"))
        assertEquals("BRTV北京卫视 4K SDR", grouped[1].displayTitle)
        assertTrue(grouped[1].variants[0].qualityLabel.contains("4K SDR"))
        assertEquals("BRTV北京卫视 4K HDR", grouped[2].displayTitle)
        assertTrue(grouped[2].variants[0].qualityLabel.contains("4K HDR"))
        assertFalse(grouped[3].variants[0].qualityLabel.contains("301"))
        assertTrue(grouped[3].variants[0].qualityLabel.contains("1080i"))

        val drawerGroups = ChannelGrouper.sortedDrawerGroups(grouped)
        assertTrue("[高清]北京" in drawerGroups)
        assertTrue("[高清]卫视" in drawerGroups)
        assertTrue("4K" in drawerGroups)

        val all = ChannelGrouper.numberedDisplayChannels(grouped)
        val k4Only = ChannelGrouper.numberedDisplayChannelsForGroup(grouped, "4K", precomputedAll = all)
        assertEquals(listOf(2, 3), k4Only.map { it.number })
        assertEquals(listOf("BRTV北京卫视 4K SDR", "BRTV北京卫视 4K HDR"), k4Only.map { it.channel.title })
    }

    @Test
    fun rtpStreamAssemblerTracksRfc3550PacketLossAcrossReorderDuplicatesAndWrap() {
        fun makeRtp(seq: Int): ByteArray {
            val pkt = ByteArray(12 + 188)
            pkt[0] = 0x80.toByte()
            pkt[1] = 0x21.toByte()
            pkt[2] = ((seq ushr 8) and 0xFF).toByte()
            pkt[3] = (seq and 0xFF).toByte()
            pkt[11] = 7
            pkt[12] = 0x47
            return pkt
        }

        val clean = RtpStreamAssembler()
        val parsed = RtpPacketParser.ParsedPacket()
        // 60 packets spanning wraparound 65510..33 with duplicate & small reorder -> 0% loss
        for (i in 0 until 60) {
            val seq = (65510 + i) and 0xFFFF
            val pkt = makeRtp(seq)
            assertTrue(RtpPacketParser.inspectPacket(pkt, pkt.size, parsed))
            clean.offer(pkt, parsed)
            clean.onCurrentDelivered()
        }
        val dup = makeRtp((65510 + 58) and 0xFFFF)
        assertTrue(RtpPacketParser.inspectPacket(dup, dup.size, parsed))
        assertEquals(RtpStreamAssembler.OfferResult.DROP, clean.offer(dup, parsed))
        assertEquals(60L, clean.receivedPackets())
        assertEquals(0L, clean.lostPackets())
        assertEquals(0, clean.lossPercent())

        // 50% packet loss (even sequences missing across 100 expected packets)
        val lossy = RtpStreamAssembler()
        for (i in 0 until 100 step 2) {
            val pkt = makeRtp(1000 + i)
            assertTrue(RtpPacketParser.inspectPacket(pkt, pkt.size, parsed))
            lossy.offer(pkt, parsed)
        }
        assertEquals(50L, lossy.receivedPackets())
        assertEquals(99L, lossy.expectedPackets())
        assertEquals(49L, lossy.lostPackets())
        assertTrue(lossy.lossPercent() in 48..50)
    }

    @Test
    fun rtpCircularByteBufferHandlesWrapOverflowAndConcurrentRead() {
        val ring = RtpCircularByteBuffer(capacity = 16)
        val p1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val p2 = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        ring.write(p1, 0, p1.size)
        ring.write(p2, 0, p2.size) // Total 20B in 16B ring -> oldest 4 bytes dropped without blocking
        assertEquals(4L, ring.overflowBytes())

        val out = ByteArray(16)
        val n = ring.read(out, 0, out.size, timeoutMs = 100)
        assertEquals(16, n)
        assertEquals(
            listOf(5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
            out.map { it.toInt() },
        )

        ring.close()
        assertEquals(-1, ring.read(out, 0, out.size, timeoutMs = 50))
    }

    @Test
    fun firstFrameWatchdogMessageHighlightsSevereMulticastLoss() {
        val normal = FirstFrameWatchdogPolicy.giveUpMessage(AppLang.ZH, rtpLossPercent = 0)
        assertEquals(FirstFrameWatchdogPolicy.GIVE_UP_MESSAGE, normal)

        val lossyZh = FirstFrameWatchdogPolicy.giveUpMessage(AppLang.ZH, rtpLossPercent = 68)
        assertTrue(lossyZh.contains("68%"))
        assertTrue(lossyZh.contains("有线"))

        val lossyEn = FirstFrameWatchdogPolicy.giveUpMessage(AppLang.EN, rtpLossPercent = 68)
        assertTrue(lossyEn.contains("68%"))
        assertTrue(lossyEn.contains("Ethernet"))
    }
}
