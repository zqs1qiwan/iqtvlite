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
    fun loadControlKeepsTheCrashInvariantForEveryTier() {
        // t205: the start-up buffer is a user tier; every tier must keep the USAB2-01 invariant.
        assertEquals(2_500, LoadControlTuning.minBufferMs())
        assertEquals(6_000, LoadControlTuning.maxBufferMs())
        assertEquals(2_000, LoadControlTuning.playbackBufferAfterRebufferMs())
        assertEquals(listOf(300, 400, 500), LoadControlTuning.TIERS)
        assertTrue("2500 >= 2000 for every tier", LoadControlTuning.isValid)
        LoadControlTuning.TIERS.forEach { tier ->
            assertEquals(tier, LoadControlTuning.playbackBufferMinMs(tier))
            assertTrue("$tier must not exceed bufferForPlayback", tier <= LoadControlTuning.BUFFER_FOR_PLAYBACK_MS)
        }
        // the default is the fast tier (300ms)
        assertEquals(300, LoadControlTuning.playbackBufferMinMs())
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
    fun theFirstFrameWindowIsSplitIntoFormatDecoderAndRender() {
        // t213: prepare → format (demux/network) → decoder init (MediaCodec create/configure) → render.
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.groupKey(1), 10_000L)
        ZapTrace.onPrepare(11_000L)
        ZapTrace.onInputFormatChanged(11_120L)          // +120 ms
        ZapTrace.onVideoDecoderInitialized(11_640L)     // +520 ms  ← the expensive part on v7a
        ZapTrace.onFirstFrame(12_300L)                  // +660 ms  ← first decode + Surface submit
        assertEquals(120L, ZapTrace.formatDelayMs())
        assertEquals(520L, ZapTrace.decoderDelayMs())
        assertEquals(660L, ZapTrace.renderDelayMs())
        assertEquals(
            "zap=2300ms prepare=1000ms firstFrame=1300ms fmt=120ms dec=520ms render=660ms",
            ZapTrace.summaryLineOnce()
        )
    }

    @Test
    fun decoderBeforeFormatDoesNotDropTheStages() {
        // t218: the device trace had onVideoDecoderInitialized (47.864s) BEFORE
        // onInputVideoFormatChanged (47.869s) → the old delta was negative and the whole suffix vanished.
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.groupKey(1), 10_000L)
        ZapTrace.onPrepare(10_200L)
        ZapTrace.onVideoDecoderInitialized(10_210L)   // arrives first
        ZapTrace.onInputFormatChanged(10_400L)
        ZapTrace.onFirstFrame(11_100L)
        assertEquals(200L, ZapTrace.formatDelayMs())
        assertEquals("an out-of-order decoder mark counts as 0", 0L, ZapTrace.decoderDelayMs())
        assertEquals("render runs from the latest mark", 700L, ZapTrace.renderDelayMs())
        assertEquals(
            "zap=1100ms prepare=200ms firstFrame=900ms fmt=200ms dec=0ms render=700ms",
            ZapTrace.summaryLineOnce()
        )
    }

    @Test
    fun aMissingDecoderEventStillReportsFormatAndRender() {
        // A reused codec may not emit onVideoDecoderInitialized at all.
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.groupKey(2), 10_000L)
        ZapTrace.onPrepare(10_150L)
        ZapTrace.onInputFormatChanged(10_950L)
        ZapTrace.onFirstFrame(11_400L)
        assertEquals(0L, ZapTrace.decoderDelayMs())
        val line = ZapTrace.summaryLineOnce()!!
        assertTrue("fmt must survive: $line", line.contains("fmt=800ms"))
        assertTrue("dec is clamped to 0: $line", line.contains("dec=0ms"))
        assertTrue("render must survive: $line", line.contains("render="))
    }

    @Test
    fun aNegativeWindowIsClampedToZero() {
        // First frame observed before the last mark (reordering) → 0, never a negative number.
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.groupKey(3), 10_000L)
        ZapTrace.onPrepare(10_100L)
        ZapTrace.onInputFormatChanged(10_900L)
        ZapTrace.onFirstFrame(10_800L)
        assertEquals(0L, ZapTrace.renderDelayMs())
        assertFalse(ZapTrace.summaryLineOnce()!!.contains("render=-"))
    }

    @Test
    fun theFirstAutoplayAlsoReportsALineWithoutAnyKeyPress() {
        // t216: cold start autoplay never went through a user entry → no key → summaryLineOnce()
        // returned null and there was no ZAP line at all.
        ZapTrace.reset()
        ZapTrace.armIfUnarmed(ZapTrace.zapKey(5L, "http://src/auto"), 20_000L)
        ZapTrace.onPrepare(20_050L)
        ZapTrace.onInputFormatChanged(20_170L)
        ZapTrace.onVideoDecoderInitialized(20_800L)
        ZapTrace.onFirstFrame(21_500L)
        assertEquals(
            "zap=1500ms prepare=50ms firstFrame=1450ms fmt=120ms dec=630ms render=700ms",
            ZapTrace.summaryLineOnce()
        )

        // a user entry that already armed the clock keeps its own origin
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.groupKey(1), 30_000L)
        ZapTrace.armIfUnarmed(ZapTrace.zapKey(5L, "http://src/auto"), 30_400L) // must be ignored
        assertEquals(0L, ZapTrace.prepareDelayMs().let { if (it < 0) 0L else it })
        ZapTrace.onPrepare(30_400L)
        assertEquals("user arm stays the origin", 400L, ZapTrace.prepareDelayMs())
    }

    @Test
    fun theSecondZapStillReportsTheThreeStages() {
        // t216: onZap() used to leave formatAt/decoderAt from the previous channel, so from the second
        // zap on the stage split was silently dropped (fmt/dec/render = -1 → base line only).
        fun zapAndReport(base: Long, group: Int): String? {
            ZapTrace.onZapRequested(ZapTrace.groupKey(group), base)
            ZapTrace.onPrepare(base + 200L)
            ZapTrace.onInputFormatChanged(base + 320L)
            ZapTrace.onVideoDecoderInitialized(base + 900L)
            ZapTrace.onFirstFrame(base + 1_500L)
            return ZapTrace.summaryLineOnce()
        }

        val first = zapAndReport(10_000L, 1)
        assertEquals(
            "zap=1500ms prepare=200ms firstFrame=1300ms fmt=120ms dec=580ms render=600ms",
            first
        )

        // a completely different channel afterwards must produce the full line again
        val second = zapAndReport(30_000L, 2)
        assertEquals(
            "the second zap must keep its own stage split",
            "zap=1500ms prepare=200ms firstFrame=1300ms fmt=120ms dec=580ms render=600ms",
            second
        )
        assertEquals(120L, ZapTrace.formatDelayMs())
        assertEquals(580L, ZapTrace.decoderDelayMs())
        assertEquals(600L, ZapTrace.renderDelayMs())
    }

    @Test
    fun withoutDecoderEventsTheLineStaysCompact() {
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.groupKey(2), 10_000L)
        ZapTrace.onPrepare(10_100L)
        ZapTrace.onFirstFrame(10_900L)
        // t218: with no format event the stage suffix is omitted entirely (fmt stays -1); the clamped
        // helpers return 0 rather than a negative number when there is nothing to measure.
        assertEquals(-1L, ZapTrace.formatDelayMs())
        assertEquals(0L, ZapTrace.decoderDelayMs())
        // without a format mark the remaining window is measured from prepare (100 → 900 = 800 ms)
        assertEquals(800L, ZapTrace.renderDelayMs())
        assertEquals("zap=900ms prepare=100ms firstFrame=800ms", ZapTrace.summaryLineOnce())
    }

    @Test
    fun aMultiVariantSelectionArmsOnceForAnyChosenVariant() {
        // t211 (ZAP-NESTED-01): playGroupAuto arms with the GROUP key, then probes selectedIndex 0/1/2.
        val groupKey = ZapTrace.groupKey(7)
        listOf(0, 1, 2).forEach { selectedIndex ->
            ZapTrace.reset()
            ZapTrace.onZapRequested(groupKey, 10_000L)
            val probeMs = 40L * (selectedIndex + 1)
            ZapTrace.onPrepare(10_000L + probeMs)
            val prepared = ZapTrace.prepareDelayMs()
            org.junit.Assert.assertTrue("selectedIndex=$selectedIndex must keep a positive prepare gap", prepared > 0L)
            org.junit.Assert.assertEquals(probeMs, prepared)
            ZapTrace.onFirstFrame(10_000L + probeMs + 600L)
            org.junit.Assert.assertNotNull(ZapTrace.summaryLineOnce())
        }
    }

    @Test
    fun nestedVariantSwitchesDoNotRestartTheMeasurement() {
        ZapTrace.reset()
        val k = ZapTrace.groupKey(3)
        ZapTrace.onZapRequested(k, 1_000L)
        ZapTrace.onPrepare(1_250L)
        assertEquals(250L, ZapTrace.prepareDelayMs())
        ZapTrace.onZapRequested(k, 1_400L)
        assertEquals("nested arm must not move the origin", 250L, ZapTrace.prepareDelayMs())
    }

    @Test
    fun theLineIsPrintedOncePerChannelEvenIfTheFrameRepeats() {
        // t208 (ZAP-REPEAT-01): onRenderedFirstFrame fires again when the same channel is re-entered;
        // that must not produce a second line (nor a bogus larger zap).
        ZapTrace.reset()
        val key = ZapTrace.zapKey(9L, "http://src/z")
        ZapTrace.onZapRequested(key, 1_000L)
        ZapTrace.onPrepare(1_100L)
        ZapTrace.onFirstFrame(1_700L)
        assertEquals("zap=700ms prepare=100ms firstFrame=600ms", ZapTrace.summaryLineOnce())
        assertNull("a repeated first frame must not print again", ZapTrace.summaryLineOnce())

        // a different channel prints again
        ZapTrace.onZapRequested(ZapTrace.zapKey(10L, "http://src/z"), 2_000L)
        ZapTrace.onPrepare(2_050L)
        ZapTrace.onFirstFrame(2_500L)
        assertEquals("zap=500ms prepare=50ms firstFrame=450ms", ZapTrace.summaryLineOnce())
        assertNull(ZapTrace.summaryLineOnce())
    }

    @Test
    fun aRealZapEntryLeavesAPositivePrepareGap() {
        // t208 (ZAP-ENTRY-01): when the arm happens at the entry (playGroupAuto/Variant) and the
        // prepare happens later, prepareDelay is a real gap — not the old 0 from the fallback.
        ZapTrace.reset()
        ZapTrace.onZapRequested(ZapTrace.zapKey(11L, "http://src/dpad"), 10_000L)
        ZapTrace.onPrepare(10_320L)   // D-pad path: ~0.3 s of variant probing before prepare
        assertEquals(320L, ZapTrace.prepareDelayMs())
        assertEquals(1_450L, ZapTrace.onFirstFrame(11_450L))
        assertEquals(320L, ZapTrace.prepareDelayMs())
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
