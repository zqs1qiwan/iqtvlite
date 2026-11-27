package com.cjlhll.iptv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

class V032PlaybackAndNetworkTest {

    @Test
    fun frameRateMatcher_prefersExactAndIntegerMultiplesWithoutDownscaling() {
        val m60 = DisplayModeSpec(modeId = 1, width = 3840, height = 2160, refreshRateHz = 60.0f)
        val m50 = DisplayModeSpec(modeId = 2, width = 3840, height = 2160, refreshRateHz = 50.0f)
        val m24 = DisplayModeSpec(modeId = 3, width = 3840, height = 2160, refreshRateHz = 23.976f)
        val hd50 = DisplayModeSpec(modeId = 4, width = 1920, height = 1080, refreshRateHz = 50.0f)

        // 25fps stream on 60Hz display should switch to 4K@50Hz (2x multiple), NOT 1080p@50Hz
        val for25 = FrameRateMatcher.selectBestDisplayMode(25.0f, m60, listOf(m60, m50, m24, hd50))
        assertEquals(m50, for25)

        // 23.976fps stream picks 4K@23.976Hz (exact 1x)
        val for23976 = FrameRateMatcher.selectBestDisplayMode(23.976f, m60, listOf(m60, m50, m24))
        assertEquals(m24, for23976)

        // Already on optimal 50Hz for 25fps -> null (no redundant HDMI mode reset)
        assertNull(FrameRateMatcher.selectBestDisplayMode(25.0f, m50, listOf(m60, m50, m24)))

        // Invalid FPS ignored
        assertNull(FrameRateMatcher.selectBestDisplayMode(0f, m60, listOf(m60, m50)))
    }

    @Test
    fun audioPcmMath_clampsAndAppliesSaturatingGain() {
        assertEquals(-500, AudioPcmMath.clampDelayMs(-900))
        assertEquals(500, AudioPcmMath.clampDelayMs(900))
        assertEquals(100, AudioPcmMath.clampBoostPct(40))
        assertEquals(150, AudioPcmMath.clampBoostPct(250))
        assertEquals(12000, AudioPcmMath.delayFramesFor(-250, 48000))

        // Samples: 10000 -> 15000 (150%), 28000 -> saturated 32767, -28000 -> saturated -32768
        val src = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        src.putShort(10000.toShort())
        src.putShort(28000.toShort())
        src.putShort((-28000).toShort())
        src.flip()

        val dst = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        AudioPcmMath.applyGainPcm16Le(src, dst, 150)
        dst.flip()

        assertEquals(15000, dst.short.toInt())
        assertEquals(Short.MAX_VALUE.toInt(), dst.short.toInt())
        assertEquals(Short.MIN_VALUE.toInt(), dst.short.toInt())
    }

    @Test
    fun audioSyncBoostProcessor_bypassAndPadAndSkip() {
        val proc = AudioSyncBoostProcessor()
        val fmt = AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)
        proc.configure(fmt)
        proc.flush()

        // Default (0ms, 100%) must be completely inactive (zero overhead bypass)
        assertFalse(proc.isActive)

        // +10ms positive delay pads 480 frames * 4 bytes = 1920 bytes of leading silence
        proc.updateConfig(delayMs = 10, boostPct = 100)
        proc.flush()
        assertTrue(proc.isActive)

        val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        input.putShort(1200).putShort(1200).putShort(1200).putShort(1200)
        input.flip()
        proc.queueInput(input)
        val out = proc.output
        assertEquals(1920 + 8, out.remaining())
        // Leading byte is silence zero
        assertEquals(0.toByte(), out.get(0))

        // -10ms negative delay skips the 1920 leading bytes
        proc.updateConfig(delayMs = -10, boostPct = 120)
        proc.flush()
        val largeIn = ByteBuffer.allocate(2000).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1000) largeIn.putShort(1000)
        largeIn.flip()
        proc.queueInput(largeIn)
        val outNeg = proc.output
        assertEquals(2000 - 1920, outNeg.remaining())
        assertEquals(1200, outNeg.order(ByteOrder.LITTLE_ENDIAN).short.toInt())

        // Re-updating config to neutral restores passthrough inactive state
        proc.updateConfig(delayMs = 0, boostPct = 100)
        assertFalse(proc.isActive)
    }

    @Test
    fun dohWireCodec_roundTripsDnsPacketAndBypassesLanHosts() {
        assertTrue(DohResolver.isBypassHost("192.168.1.10"))
        assertTrue(DohResolver.isBypassHost("fe80::1"))
        assertTrue(DohResolver.isBypassHost("localhost"))
        assertTrue(DohResolver.isBypassHost("router.local"))
        assertTrue(DohResolver.isBypassHost("svc.internal"))
        assertTrue(DohResolver.isBypassHost("iptvbox"))
        assertFalse(DohResolver.isBypassHost("live.example.com"))

        val query = DohWireCodec.encodeQuery("live.example.com", DohWireCodec.TYPE_A, txId = 0x1234)
        // TXID & QR/RD bits & QDCOUNT=1
        assertEquals(0x12.toByte(), query[0])
        assertEquals(0x34.toByte(), query[1])
        assertEquals(0x01.toByte(), query[5])
        // Queries (QR == 0) must be rejected by decodeResponse
        assertEquals(0, DohWireCodec.decodeResponse(query, DohWireCodec.TYPE_A).size)

        // Synthesize minimal RFC 1035 response copying question and appending 1 compressed A answer (1.2.3.4, TTL=90)
        val resp = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00))
            // Question QNAME + QTYPE + QCLASS copied from query[12..]
            write(query, 12, query.size - 12)
            // Answer: pointer 0xC00C, TYPE=A (1), CLASS=IN (1), TTL=90, RDLEN=4, IP=93.184.216.34
            write(byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 90, 0x00, 0x04, 93, 184.toByte(), 216.toByte(), 34))
        }.toByteArray()

        val answers = DohWireCodec.decodeResponse(resp, DohWireCodec.TYPE_A)
        assertEquals(1, answers.size)
        assertEquals(90, answers[0].ttlSec)
        assertArrayEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), answers[0].addressBytes)
    }

    @Test
    fun diagRingBuffer_isBoundedTo60EventsAndPerChannelOverrideWorks() {
        for (i in 1..80) {
            PlaybackDiagCenter.recordEvent("TEST", "ev_$i", nowMs = 1000L + i)
        }
        val evs = PlaybackDiagCenter.recentEvents()
        assertEquals(PlaybackDiagCenter.MAX_EVENTS, evs.size)
        assertEquals("ev_21", evs.first().message)
        assertEquals("ev_80", evs.last().message)

        assertFalse(Prefs.resolveEffectiveSoftVideo("HARDWARE", "auto"))
        assertTrue(Prefs.resolveEffectiveSoftVideo("SOFTWARE", "auto"))
        assertTrue(Prefs.resolveEffectiveSoftVideo("HARDWARE", "SOFTWARE"))
        assertFalse(Prefs.resolveEffectiveSoftVideo("SOFTWARE", "HARDWARE"))
    }

    @Test
    fun dohResolver_bypassHostFallsBackCleanlyToSystemLookup() {
        assertEquals(DnsMode.SYSTEM, DnsMode.parse(null))
        assertEquals(DnsMode.DOH_ALI, DnsMode.parse("ali"))
        assertEquals(DnsMode.DOH_DNSPOD, DnsMode.parse("dnspod"))
        assertEquals("https://doh.pub/dns-query", DnsMode.DOH_DNSPOD.dohUrl)

        val expected = listOf(InetAddress.getByName("192.168.2.1"))
        val out = DohResolver.resolve(
            hostname = "192.168.2.1",
            mode = DnsMode.DOH_DNSPOD,
            pref = IpStackPreference.IPV6_FIRST,
            systemLookup = { expected },
        )
        assertNotNull(out)
        assertEquals(expected, out)
    }
}
