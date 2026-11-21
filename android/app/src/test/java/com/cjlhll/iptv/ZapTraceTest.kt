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
    fun theEpgCacheGateIsAPlainConstant() {
        // t204: mayLoadEpgCache() had no production caller (the effect uses a 10 s timeout instead),
        // so it is gone; the rule that remains is the constant itself.
        assertTrue(ColdStartPolicy.EPG_AFTER_FIRST_FRAME)
    }

    @Test
    fun theStopwatchIsArmedOncePerRealZap() {
        // t204 (ZAP-TIMING-01): the arm happens at the user entry (playChannel)…
        ZapTrace.reset()
        val key1 = ZapTrace.zapKey(42L, "http://src/a")
        ZapTrace.onZapRequested(key1, 1_000L)
        ZapTrace.onPrepare(1_100L)
        assertEquals("key press → prepare", 100L, ZapTrace.prepareDelayMs())
        ZapTrace.onFirstFrame(1_780L)
        assertEquals(780L, ZapTrace.zapToFirstFrameMs())

        // …and a second press for the SAME channel does not restart the running measurement
        ZapTrace.onZapRequested(key1, 1_500L)
        assertEquals("same channel keeps its measurement", 780L, ZapTrace.zapToFirstFrameMs())
        assertEquals(100L, ZapTrace.prepareDelayMs())

        // a different channel re-arms it
        ZapTrace.onZapRequested(ZapTrace.zapKey(43L, "http://src/b"), 3_000L)
        assertEquals(-1L, ZapTrace.zapToFirstFrameMs())
        assertEquals(-1L, ZapTrace.prepareDelayMs())
    }

    @Test
    fun theKeyIsTheChannelNotThePerPrepareMediaId() {
        // t204 (ZAP-KEY-01): production used to arm from `id` = "c…-l…-s<sequence>", which changes on
        // EVERY prepare → the same channel always looked new. The key must be channel-stable.
        val a = ZapTrace.zapKey(7L, "http://src/x")
        val aAgain = ZapTrace.zapKey(7L, "http://src/x")
        val b = ZapTrace.zapKey(7L, "http://src/y")
        assertEquals("same channel → same key", a, aAgain)
        assertFalse("different stream of the same channel → different key", a == b)

        ZapTrace.reset()
        ZapTrace.onZapRequested(a, 1_000L)
        ZapTrace.onPrepare(1_050L)
        ZapTrace.onFirstFrame(1_700L)
        // a re-prepare for the same channel (new sequence id) must keep the measurement
        ZapTrace.onZapRequested(aAgain, 1_900L)
        assertEquals(700L, ZapTrace.zapToFirstFrameMs())
    }

    @Test
    fun prepareSelfArmsWhenNoUserEntryHappened() {
        // Initial autoplay has no user zap; prepare becomes the origin so a line is still produced.
        ZapTrace.reset()
        ZapTrace.onPrepare(5_000L)
        assertEquals("fallback origin has no key-to-prepare gap", 0L, ZapTrace.prepareDelayMs())
        assertEquals(300L, ZapTrace.onFirstFrame(5_300L))
    }

    @Test
    fun theProductionArmProducesASummaryLine() {
        // t201: without the arm call summaryLine() was always null and logcat had no `zap=` line.
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.zapKey(1L, "channel-42"), 10_000L)
        ZapTrace.onPrepare(10_150L)
        ZapTrace.onFirstFrame(10_765L)
        assertEquals("zap=765ms prepare=150ms firstFrame=615ms", ZapTrace.summaryLine())
    }

    @Test
    fun theEpgCacheFallbackIsBounded() {
        // t201 (EPG-COLLECT-01): if the first frame never arrives the cache must still load.
        assertTrue(ColdStartPolicy.EPG_CACHE_FALLBACK_MS in 5_000L..30_000L)
        assertTrue("a fallback window must exist", ColdStartPolicy.EPG_CACHE_FALLBACK_MS > 0L)
    }
}
