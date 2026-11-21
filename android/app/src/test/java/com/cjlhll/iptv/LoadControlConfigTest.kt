package com.cjlhll.iptv

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Guard for USAB2-01: a previous LoadControl buffer config (800/6000/300/1000) violated Media3's
 * constraint minBufferMs >= bufferForPlaybackAfterRebufferMs and crashed PlayerActivity at start
 * (CI10077). This JVM test exercises the real DefaultLoadControl.Builder so an invalid parameter set
 * fails here instead of only on device.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LoadControlConfigTest {

    @Test
    fun productionBufferDurationsAreValid() {
        // t201 (LOADCONTROL-TEST-01): read the PRODUCTION numbers from LoadControlTuning, so this guard
        // can never drift back to a stale literal (it asserted 500 while production moved to 300).
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                LoadControlTuning.minBufferMs(),
                LoadControlTuning.maxBufferMs(),
                LoadControlTuning.playbackBufferMinMs(),
                LoadControlTuning.playbackBufferAfterRebufferMs(),
            )
            .setBackBuffer(0, false)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    @Test
    fun everyStartupBufferTierBuilds() {
        // t205: 快/均衡/稳 must all be accepted by the real builder and keep min >= afterRebuffer.
        org.junit.Assert.assertEquals(listOf(300, 400, 500), LoadControlTuning.TIERS)
        LoadControlTuning.TIERS.forEach { tier ->
            org.junit.Assert.assertTrue("tier $tier must satisfy the invariant", LoadControlTuning.isValidFor(tier))
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    LoadControlTuning.minBufferMs(),
                    LoadControlTuning.maxBufferMs(),
                    LoadControlTuning.playbackBufferMinMs(tier),
                    LoadControlTuning.playbackBufferAfterRebufferMs(),
                )
                .build()
        }
    }

    @Test
    fun theDefaultIsTheStableTier() {
        // t205: 300 showed a 5.8 s cold-start rebuffer on the box → default is 稳/500 until A/B.
        org.junit.Assert.assertEquals(500, LoadControlTuning.DEFAULT_BUFFER_MIN_MS)
        org.junit.Assert.assertEquals(500, LoadControlTuning.playbackBufferMinMs(0))
        org.junit.Assert.assertEquals(500, LoadControlTuning.playbackBufferMinMs(999))
    }

    @Test
    fun unknownPrefValuesFallBackAndCycleWraps() {
        org.junit.Assert.assertEquals(300, LoadControlTuning.sanitize(300))
        org.junit.Assert.assertEquals(400, LoadControlTuning.sanitize(400))
        org.junit.Assert.assertEquals(500, LoadControlTuning.sanitize(500))
        org.junit.Assert.assertEquals(500, LoadControlTuning.sanitize(123))
        org.junit.Assert.assertEquals(500, LoadControlTuning.sanitize(-1))
        org.junit.Assert.assertEquals(400, LoadControlTuning.next(300))
        org.junit.Assert.assertEquals(500, LoadControlTuning.next(400))
        org.junit.Assert.assertEquals(300, LoadControlTuning.next(500))
        org.junit.Assert.assertEquals("稳(500ms)", LoadControlTuning.tierLabel(999))
    }

    @Test
    fun productionMinBufferCoversAfterRebuffer() {
        // t157: the CPU fix relies on minBufferMs >= bufferForPlaybackAfterRebufferMs. If a future
        // edit lowers the minimum below the rebuffer threshold, the builder throws (CI10077).
        val min = LoadControlTuning.minBufferMs()
        val afterRebuffer = LoadControlTuning.playbackBufferAfterRebufferMs()
        org.junit.Assert.assertTrue(min >= afterRebuffer)
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, LoadControlTuning.maxBufferMs(), LoadControlTuning.playbackBufferMinMs(), afterRebuffer)
            .build()
    }

    @Test
    fun invalidBufferDurationsThrow() {
        // Reproduces the CI10077 crash: minBufferMs 800 < bufferForPlaybackAfterRebufferMs 1000.
        assertThrows(IllegalArgumentException::class.java) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(800, 6_000, 300, 1_000)
                .build()
        }
    }
}
