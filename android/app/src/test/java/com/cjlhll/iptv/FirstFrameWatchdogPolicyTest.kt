package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t225: one automatic retry after 8 s without a first frame, then a single give-up. */
class FirstFrameWatchdogPolicyTest {

    private val retry = FirstFrameWatchdogPolicy.Action.RETRY
    private val giveUp = FirstFrameWatchdogPolicy.Action.GIVE_UP
    private val ignore = FirstFrameWatchdogPolicy.Action.IGNORE

    @Test
    fun eightSecondsIsTheTimeoutAndOnlyOneRetryIsAllowed() {
        assertEquals(8_000L, FirstFrameWatchdogPolicy.TIMEOUT_MS)
        assertEquals(1, FirstFrameWatchdogPolicy.MAX_RETRIES)
        assertEquals(8_000L, FirstFrameWatchdogPolicy.Watchdog().timeoutMillis())
    }

    @Test
    fun theFirstTimeoutRetriesOnceThenGivesUp() {
        val w = FirstFrameWatchdogPolicy.Watchdog()
        val gen = w.arm("c7")
        assertEquals(retry, w.onTimeout(gen))
        assertEquals(1, w.retriesUsed())
        // still no frame after another 8 s → give up, never a second retry
        assertEquals(giveUp, w.onTimeout(gen))
        assertFalse("watchdog stops after giving up", w.isArmed())
        // once it gave up, any further timer is inert (no extra toast, no extra retry)
        assertEquals(ignore, w.onTimeout(gen))
    }

    @Test
    fun aFirstFrameCancelsEverything() {
        val w = FirstFrameWatchdogPolicy.Watchdog()
        val gen = w.arm("c7")
        w.onFirstFrame()
        assertFalse(w.isArmed())
        assertEquals("a cancelled watchdog never retries", ignore, w.onTimeout(gen))
    }

    @Test
    fun aNewZapCancelsTheOldTimerAndStartsFreshRetries() {
        val w = FirstFrameWatchdogPolicy.Watchdog()
        val first = w.arm("c7")
        assertEquals(retry, w.onTimeout(first))
        // the user zapped away: the old timer must be inert, and the new channel gets its own retry
        val second = w.arm("c8")
        assertEquals(ignore, w.onTimeout(first))
        assertEquals(0, w.retriesUsed())
        assertEquals("c8", w.currentChannel())
        assertEquals(retry, w.onTimeout(second))
    }

    @Test
    fun staleTimersCannotDoublePrepare() {
        val w = FirstFrameWatchdogPolicy.Watchdog()
        val gen = w.arm("c7")
        w.cancel()
        assertEquals(ignore, w.onTimeout(gen))
        assertEquals("no retry was consumed", 0, w.retriesUsed())
    }

    @Test
    fun theRetryLogLineIsStable() {
        assertEquals(
            "firstFrameTimeout retry=1 channel=c7",
            FirstFrameWatchdogPolicy.retryLine("c7", 1)
        )
        assertTrue(FirstFrameWatchdogPolicy.GIVE_UP_MESSAGE.contains("暂不可播"))
    }
}
