package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t218 (arm B): neighbour pre-connect policy — default-off, bounded, never competes with a zap. */
class PreconnectPolicyTest {

    @Test
    fun onlyTheImmediateNeighboursAreWarmed() {
        // next first, then previous; at most MAX_TARGETS
        assertEquals(listOf(3, 1), PreconnectPolicy.neighbours(size = 5, current = 2))
        assertEquals("first channel has no previous", listOf(1), PreconnectPolicy.neighbours(5, 0))
        assertEquals("last channel has no next", listOf(3), PreconnectPolicy.neighbours(5, 4))
        assertTrue(PreconnectPolicy.neighbours(1, 0).isEmpty())
        assertTrue("out of range current", PreconnectPolicy.neighbours(5, 9).isEmpty())
    }

    @Test
    fun disabledMeansNothingHappens() {
        // default OFF: the experiment must not touch the network at all
        assertFalse(
            PreconnectPolicy.mayWarm(
                enabled = false, drawerOpen = true, idle = true, inFlight = false, sinceLastMs = 99_999L
            )
        )
    }

    @Test
    fun onlyOneAttemptAtATimeAndOnlyWhileBrowsingOrIdle() {
        val base = PreconnectPolicy.mayWarm(
            enabled = true, drawerOpen = true, idle = false, inFlight = false, sinceLastMs = 99_999L
        )
        assertTrue("drawer open is enough", base)
        assertTrue(
            "idle is enough",
            PreconnectPolicy.mayWarm(true, drawerOpen = false, idle = true, inFlight = false, sinceLastMs = 99_999L)
        )
        assertFalse(
            "a zap in progress blocks warming",
            PreconnectPolicy.mayWarm(true, drawerOpen = false, idle = false, inFlight = false, sinceLastMs = 99_999L)
        )
        assertFalse(
            "one attempt at a time",
            PreconnectPolicy.mayWarm(true, drawerOpen = true, idle = true, inFlight = true, sinceLastMs = 99_999L)
        )
    }

    @Test
    fun warmingIsThrottled() {
        assertFalse(
            "too soon after the previous pass",
            PreconnectPolicy.mayWarm(true, drawerOpen = true, idle = true, inFlight = false, sinceLastMs = 100L)
        )
        assertTrue(
            PreconnectPolicy.mayWarm(
                true, drawerOpen = true, idle = true, inFlight = false,
                sinceLastMs = PreconnectPolicy.MIN_INTERVAL_MS
            )
        )
    }

    @Test
    fun theDeadlineIsBounded() {
        assertEquals(2_000L, PreconnectPolicy.TIMEOUT_MS)
        assertEquals(12_000L, PreconnectPolicy.deadlineFrom(10_000L))
        assertEquals(2, PreconnectPolicy.MAX_TARGETS)
    }

    @Test
    fun onlyHttpUrlsCanBeWarmed() {
        assertTrue(PreconnectPolicy.canWarmUrl("http://host/a.m3u8"))
        assertTrue(PreconnectPolicy.canWarmUrl("HTTPS://host/a"))
        assertFalse(PreconnectPolicy.canWarmUrl("rtp://239.0.0.1:5000"))
        assertFalse(PreconnectPolicy.canWarmUrl("udp://@239.0.0.1:5000"))
        assertFalse(PreconnectPolicy.canWarmUrl(""))
    }
}
