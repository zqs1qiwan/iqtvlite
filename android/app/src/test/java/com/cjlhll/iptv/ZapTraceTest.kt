package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t197 (A1/A2/B): TTFF arithmetic, the LoadControl invariant and the cold-start ordering rules. */
class ZapTraceTest {

    @Test
    fun zapLatencyIsSplitIntoPrepareAndFirstFrame() {
        ZapTrace.reset()
        ZapTrace.onZap(1_000L)
        ZapTrace.onPrepare(1_120L)
        assertEquals("zap → prepare", 120L, ZapTrace.prepareDelayMs())
        val total = ZapTrace.onFirstFrame(1_780L)
        assertEquals("zap → first frame", 780L, total)
        assertEquals("prepare → first frame", 660L, ZapTrace.firstFrameDelayMs())
        assertEquals(780L, ZapTrace.zapToFirstFrameMs())
        assertEquals(
            "zap=780ms prepare=120ms firstFrame=660ms",
            ZapTrace.summaryLine()
        )
    }

    @Test
    fun anIncompleteMeasurementReportsNothing() {
        ZapTrace.reset()
        assertNull("no zap yet", ZapTrace.summaryLine())
        assertEquals(-1L, ZapTrace.zapToFirstFrameMs())

        ZapTrace.onZap(5_000L)
        assertEquals(-1L, ZapTrace.firstFrameDelayMs())
        assertNull(ZapTrace.summaryLine())
    }

    @Test
    fun aNewZapResetsThePreviousMeasurement() {
        ZapTrace.reset()
        ZapTrace.onZap(1_000L)
        ZapTrace.onPrepare(1_100L)
        ZapTrace.onFirstFrame(2_000L)
        assertEquals(1_000L, ZapTrace.zapToFirstFrameMs())

        ZapTrace.onZap(3_000L)
        assertEquals(-1L, ZapTrace.zapToFirstFrameMs())
        assertEquals(-1L, ZapTrace.prepareDelayMs())

        ZapTrace.onPrepare(3_050L)
        assertEquals(50L, ZapTrace.prepareDelayMs())
    }

    @Test
    fun loadControlKeepsTheCrashInvariantWithTheNewBufferArm() {
        // t197 A2: playbackBufferMin 500 → 300, everything else untouched.
        assertEquals(2_500, LoadControlTuning.minBufferMs())
        assertEquals(6_000, LoadControlTuning.maxBufferMs())
        assertEquals(300, LoadControlTuning.playbackBufferMinMs())
        assertEquals(2_000, LoadControlTuning.playbackBufferAfterRebufferMs())
        assertTrue("2500 >= 2000 and 300 <= 500 must hold", LoadControlTuning.isValid)
        assertTrue(
            "the A/B arm must stay below bufferForPlayback",
            LoadControlTuning.playbackBufferMinMs() < LoadControlTuning.BUFFER_FOR_PLAYBACK_MS
        )
    }

    @Test
    fun epgIsNotReadBeforeTheFirstFrame() {
        // t197 B: cold start must not parse XMLTV before the first frame.
        assertFalse(ColdStartPolicy.mayLoadEpgCache(firstFrameRendered = false))
        assertTrue(ColdStartPolicy.mayLoadEpgCache(firstFrameRendered = true))
    }

    @Test
    fun theCachedPlaylistIsPreferredWhenPresent() {
        assertTrue(ColdStartPolicy.usesCacheFirstPlaylist(cachedAvailable = true))
        assertFalse(ColdStartPolicy.usesCacheFirstPlaylist(cachedAvailable = false))
    }
}
