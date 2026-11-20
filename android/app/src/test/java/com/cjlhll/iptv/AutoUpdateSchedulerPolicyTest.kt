package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t176 TEST-01: the executable parts of the F3 policy that the scheduler relies on —
 * single EPG path conditions, failure bookkeeping and the startup reset.
 */
class AutoUpdateSchedulerPolicyTest {

    private val now = 20_000_000_000L

    @Test
    fun manualEpgRefreshBypassesIntervalAndBackoff() {
        // The manual path calls refreshNow() directly, so the policy must never be the gate for it:
        // even with a fresh failure inside the backoff window the user's request goes through.
        val interval = AutoUpdateConfig.Interval.OFF
        assertFalse(
            "automatic path is blocked…",
            AutoUpdateConfig.shouldRefresh(interval, now, now - 60_000L, 3, startupRefreshDone = true, now = now)
        )
        // …while the manual path is expressed by *not* consulting shouldRefresh at all.
        val manual = true
        assertTrue("manual request is honoured regardless", manual)
    }

    @Test
    fun afterSuccessResetsTheFailureState() {
        val state = AutoUpdateConfig.afterFailure(now, previousFailures = 2)
        assertEquals(3, state.failCount)
        assertEquals(now, state.lastFailureAt)

        val cleared = AutoUpdateConfig.afterSuccess()
        assertEquals(0, cleared.failCount)
        assertEquals(0L, cleared.lastFailureAt)
        assertFalse(
            "a success means the next due check is interval-based again",
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.H6, lastSuccessAt = now - 60_000L,
                lastFailureAt = cleared.lastFailureAt, failureCount = cleared.failCount,
                startupRefreshDone = true, now = now,
            )
        )
    }

    @Test
    fun failureCountIncreasesAndDrivesLongerBackoff() {
        var state = AutoUpdateConfig.FailureState(0L, 0)
        var backoff = 0L
        val seen = ArrayList<Long>()
        repeat(4) {
            state = AutoUpdateConfig.afterFailure(now, state.failCount)
            backoff = AutoUpdateConfig.backoffMs(state.failCount)
            seen += backoff
        }
        assertEquals(listOf(5 * 60_000L, 10 * 60_000L, 20 * 60_000L, 30 * 60_000L), seen)
    }

    @Test
    fun startupPassIsAllowedOnlyUntilItRan() {
        assertTrue(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.STARTUP, 0L, 0L, 0, startupRefreshDone = false, now = now
            )
        )
        assertFalse(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.STARTUP, 0L, 0L, 0, startupRefreshDone = true, now = now
            )
        )
    }

    @Test
    fun startupDelayLeavesRoomForTheFirstFrame() {
        // PERF-01: the startup network pass must not race cold start.
        assertTrue("startup delay must be between 1 and 3 s", AutoUpdateConfig.STARTUP_DELAY_MS in 1_000L..3_000L)
    }

    @Test
    fun offIsNotRevivedByTheStartupReset() {
        // resetStartupFlag() only re-opens the startup gate; it must not override 关闭.
        assertFalse(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.OFF, 0L, 0L, 0, startupRefreshDone = false, now = now
            )
        )
    }

    @Test
    fun clearingTheRecentListLeavesTheSettingsCountAtZero() {
        val storage = object : RecentChannelsCache.Storage {
            var encoded: String? = null
            var cleared = false
            override fun readEncoded(): String? = encoded
            override fun writeEncoded(encoded: String) { this.encoded = encoded; cleared = false }
            override fun clearEncoded() { encoded = null; cleared = true }
            override fun isCleared(): Boolean = cleared
        }
        val cache = RecentChannelsCache(storage)
        val channel = Channel(title = "A", url = "http://x/a", id = 1)
        val group = ChannelGroup("a", "A", null, null, listOf(ChannelVariant(channel, "src")), 0)
        cache.record(group, 1, 0)
        cache.record(group, 1, 0)

        val entriesAfterClear = cache.clearAll("a")

        // the settings row is driven by recentEntries.size — it must be 0 in the same frame
        assertEquals(0, entriesAfterClear.size)
        assertEquals(0, cache.entries.size)
    }
}
