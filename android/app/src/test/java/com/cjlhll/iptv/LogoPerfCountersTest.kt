package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** t154: the perf-isolation counter contract (`prefetch=req:N dec:M hit:K`). */
class LogoPerfCountersTest {

    @Test
    fun countsAreReportedInTheLogFormat() {
        LogoPerfCounters.reset()
        LogoPerfCounters.onPrefetchRequest(12)
        repeat(5) { LogoPerfCounters.onDecode() }
        repeat(7) { LogoPerfCounters.onCacheHit() }

        val sample = LogoPerfCounters.sample()

        assertEquals(12, sample.requests)
        assertEquals(5, sample.decodes)
        assertEquals(7, sample.hits)
        assertEquals("prefetch=req=12 dec=5 hit=7", LogoPerfCounters.format(sample))
    }

    @Test
    fun sampleAndResetClearsTheCounters() {
        LogoPerfCounters.reset()
        LogoPerfCounters.onPrefetchRequest(3)
        LogoPerfCounters.onDecode()

        val first = LogoPerfCounters.sampleAndReset()
        val second = LogoPerfCounters.sample()

        assertEquals(3, first.requests)
        assertEquals(1, first.decodes)
        assertEquals(0, second.requests)
        assertEquals(0, second.decodes)
        assertEquals(0, second.hits)
        assertEquals("prefetch=req=0 dec=0 hit=0", LogoPerfCounters.format(second))
    }

    @Test
    fun nonPositiveRequestCountsAreIgnored() {
        LogoPerfCounters.reset()
        LogoPerfCounters.onPrefetchRequest(0)
        LogoPerfCounters.onPrefetchRequest(-4)
        assertEquals(0, LogoPerfCounters.sample().requests)
    }

    @Test
    fun countersAccumulate() {
        LogoPerfCounters.reset()
        repeat(4) { LogoPerfCounters.onPrefetchRequest(2) }
        assertEquals(8, LogoPerfCounters.sample().requests)
    }

    @Test
    fun counterLogFormatIsStable() {
        val line = LogoPerfCounters.format(LogoPerfCounters.Sample(0, 0, 0))
        assertTrue(line.startsWith("prefetch=req="))
        assertTrue(line.contains(" dec=") && line.contains(" hit="))
    }
}
