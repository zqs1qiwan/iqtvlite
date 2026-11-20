package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t165: debug-only audio cost counters — off by default, exact summary line when on. */
class AudioCostCountersTest {

    private fun enabledCounters() = AudioCostCounters.apply {
        enabled = true
        reset()
    }

    @Test
    fun countersAreInertWhenDisabled() {
        AudioCostCounters.enabled = false
        AudioCostCounters.reset()
        AudioCostCounters.onUnderrun()
        AudioCostCounters.onRetry()
        AudioCostCounters.onDecoder("c2.android.mp3.decoder")
        AudioCostCounters.onCounters(10, 2)

        val sample = AudioCostCounters.sample()

        assertEquals(0, sample.underruns)
        assertEquals(0, sample.retries)
        assertEquals("", sample.decoder)
        assertEquals(0L, sample.rendered)
        assertFalse(AudioCostCounters.isActive())
    }

    @Test
    fun summaryLineHasTheExpectedShape() {
        val c = enabledCounters()
        c.onDecoder("ffmpeg")
        c.onTrack(encoding = "2", sampleRate = 48000, bufferBytes = 16384)
        c.onActiveChanged(true)
        repeat(3) { c.onUnderrun() }
        repeat(2) { c.onRetry() }
        c.onCounters(120, 4)

        val line = AudioCostCounters.format(c.sample())

        assertEquals(
            "active_ms=0 underrun=3 retry=2 tracks=1 rendered=120 skipped=4 " +
                "decoder=ffmpeg encoding=2 rate=48000 buffer=16384",
            line
        )
    }

    @Test
    fun emptySessionPrintsNonePlaceholders() {
        val c = enabledCounters()
        val line = AudioCostCounters.format(c.sample())
        assertTrue("no decoder yet must print none", line.contains("decoder=none"))
        assertTrue(line.contains("encoding=none"))
        assertTrue(line.contains("underrun=0"))
    }

    @Test
    fun resetClearsEveryCounter() {
        val c = enabledCounters()
        c.onUnderrun(); c.onRetry(); c.onDecoder("x"); c.onActiveChanged(true)
        c.reset()

        val sample = c.sample()
        assertEquals(0, sample.underruns)
        assertEquals(0, sample.tracks)
        assertEquals("", sample.decoder)
        assertFalse(c.isActive())
    }

    @Test
    fun activeMillisecondsAccumulateButIgnoreNonPositive() {
        val c = enabledCounters()
        c.onActiveMs(1_000)
        c.onActiveMs(2_500)
        c.onActiveMs(0)
        c.onActiveMs(-5)
        assertEquals(3_500, c.sample().activeMs)
    }
}
