package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t220: multicast pre-warm policy — default off, bounded budget, multicast-only. */
class MulticastPrewarmPolicyTest {

    @Test
    fun onlyMulticastGroupsAreParsed() {
        assertEquals(
            MulticastPrewarmPolicy.Endpoint("239.1.2.3", 5000),
            MulticastPrewarmPolicy.parse("rtp://239.1.2.3:5000")
        )
        assertEquals(
            MulticastPrewarmPolicy.Endpoint("239.255.0.1", 1234),
            MulticastPrewarmPolicy.parse("udp://@239.255.0.1:1234")
        )
        assertEquals(
            MulticastPrewarmPolicy.Endpoint("224.0.1.1", 8000),
            MulticastPrewarmPolicy.parse("udp://224.0.1.1:8000/path")
        )
        // unicast http (the TCP arm's territory) and non-multicast hosts are not warmed here
        assertNull(MulticastPrewarmPolicy.parse("http://example.com/live.ts"))
        assertNull(MulticastPrewarmPolicy.parse("rtp://10.0.0.5:5000"))
        assertNull(MulticastPrewarmPolicy.parse("udp://@host.example:5000"))
        assertNull(MulticastPrewarmPolicy.parse("rtp://239.1.2.3"))
        assertNull(MulticastPrewarmPolicy.parse("rtp://239.1.2.3:99999"))
    }

    @Test
    fun theNeighbourIsOnlyOneChannel() {
        assertEquals(2, MulticastPrewarmPolicy.neighbour(size = 5, current = 1))
        assertEquals("last channel warms the previous one", 3, MulticastPrewarmPolicy.neighbour(5, 4))
        assertNull(MulticastPrewarmPolicy.neighbour(size = 1, current = 0))
        assertNull(MulticastPrewarmPolicy.neighbour(size = 5, current = 9))
        assertEquals(1, MulticastPrewarmPolicy.MAX_TARGETS)
    }

    @Test
    fun disabledMeansNoNetworkWork() {
        assertFalse(
            MulticastPrewarmPolicy.mayWarm(
                enabled = false, drawerOpen = true, idle = true, inFlight = false, sinceLastMs = 99_999L
            )
        )
    }

    @Test
    fun budgetsAndGuardsAreBounded() {
        assertTrue(MulticastPrewarmPolicy.mayWarm(true, drawerOpen = true, idle = false, inFlight = false, sinceLastMs = 99_999L))
        assertTrue(MulticastPrewarmPolicy.mayWarm(true, drawerOpen = false, idle = true, inFlight = false, sinceLastMs = 99_999L))
        assertFalse("no warm while a zap is running", MulticastPrewarmPolicy.mayWarm(true, false, false, false, 99_999L))
        assertFalse("one socket at a time", MulticastPrewarmPolicy.mayWarm(true, true, true, true, 99_999L))
        assertFalse("throttled", MulticastPrewarmPolicy.mayWarm(true, true, true, false, 10L))

        assertEquals(3 * 1024 * 1024, MulticastPrewarmPolicy.MAX_BYTES)
        assertFalse(MulticastPrewarmPolicy.reachedBudget(3 * 1024 * 1024 - 1))
        assertTrue(MulticastPrewarmPolicy.reachedBudget(3 * 1024 * 1024))
        assertEquals(2_500L, MulticastPrewarmPolicy.TIMEOUT_MS)
    }

    @Test
    fun theDebugLineIsStable() {
        assertEquals(
            "joined bytes=1024 elapsed=250ms idr=true",
            MulticastPrewarmPolicy.summaryLine(1024L, 250L, idrSeen = true)
        )
    }
}
