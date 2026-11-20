package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t172 (F3): interval selection, failure backoff and the "关闭 = no automatic refresh" rule. */
class AutoUpdateConfigTest {

    private val now = 10_000_000_000L
    private val hour = 3600_000L

    @Test
    fun defaultsToStartup() {
        assertEquals(AutoUpdateConfig.Interval.STARTUP, AutoUpdateConfig.parse(null))
        assertEquals(AutoUpdateConfig.Interval.STARTUP, AutoUpdateConfig.parse("nonsense"))
        assertEquals(AutoUpdateConfig.DEFAULT, AutoUpdateConfig.parse(null))
    }

    @Test
    fun encodeDecodeRoundTripsEveryOption() {
        AutoUpdateConfig.Interval.values().forEach { interval ->
            assertEquals(interval, AutoUpdateConfig.parse(AutoUpdateConfig.encode(interval)))
        }
    }

    @Test
    fun cyclingFollowsTheSettingsOrder() {
        assertEquals(AutoUpdateConfig.Interval.STARTUP, AutoUpdateConfig.next(AutoUpdateConfig.Interval.OFF))
        assertEquals(AutoUpdateConfig.Interval.H6, AutoUpdateConfig.next(AutoUpdateConfig.Interval.STARTUP))
        assertEquals(AutoUpdateConfig.Interval.H12, AutoUpdateConfig.next(AutoUpdateConfig.Interval.H6))
        assertEquals(AutoUpdateConfig.Interval.H24, AutoUpdateConfig.next(AutoUpdateConfig.Interval.H12))
        assertEquals("the list wraps back to 关闭", AutoUpdateConfig.Interval.OFF, AutoUpdateConfig.next(AutoUpdateConfig.Interval.H24))
    }

    @Test
    fun offNeverRefreshesAutomatically() {
        // even with a startup pass pending and a stale timestamp
        assertFalse(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.OFF, lastSuccessAt = 0L, lastFailureAt = 0L,
                failureCount = 0, startupRefreshDone = false, now = now
            )
        )
        assertFalse(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.OFF, lastSuccessAt = now - 48 * hour, lastFailureAt = 0L,
                failureCount = 0, startupRefreshDone = true, now = now
            )
        )
        assertFalse(AutoUpdateConfig.isEnabled(AutoUpdateConfig.Interval.OFF))
    }

    @Test
    fun startupOptionRefreshesOnceAtStartupOnly() {
        assertTrue(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.STARTUP, 0L, 0L, 0, startupRefreshDone = false, now = now
            )
        )
        assertFalse(
            "启动时 must not refresh again in the same process",
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.STARTUP, now - 48 * hour, 0L, 0, startupRefreshDone = true, now = now
            )
        )
    }

    @Test
    fun periodicIntervalsRefreshAfterThePeriod() {
        val interval = AutoUpdateConfig.Interval.H6
        assertFalse(
            AutoUpdateConfig.shouldRefresh(interval, now - 1 * hour, 0L, 0, startupRefreshDone = true, now = now)
        )
        assertTrue(
            AutoUpdateConfig.shouldRefresh(interval, now - 7 * hour, 0L, 0, startupRefreshDone = true, now = now)
        )
        assertTrue(
            AutoUpdateConfig.shouldRefresh(AutoUpdateConfig.Interval.H24, now - 25 * hour, 0L, 0, true, now)
        )
        assertFalse(
            AutoUpdateConfig.shouldRefresh(AutoUpdateConfig.Interval.H24, now - 12 * hour, 0L, 0, true, now)
        )
    }

    @Test
    fun failureBackoffBlocksRetriesAndDoublesToThirtyMinutes() {
        assertEquals(0L, AutoUpdateConfig.backoffMs(0))
        assertEquals(5 * 60_000L, AutoUpdateConfig.backoffMs(1))
        assertEquals(10 * 60_000L, AutoUpdateConfig.backoffMs(2))
        assertEquals(20 * 60_000L, AutoUpdateConfig.backoffMs(3))
        assertEquals(30 * 60_000L, AutoUpdateConfig.backoffMs(4))
        assertEquals("capped at 30 min", 30 * 60_000L, AutoUpdateConfig.backoffMs(9))

        // a failure 2 minutes ago with 1 failure (5 min window) blocks the retry
        assertFalse(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.H6, lastSuccessAt = now - 24 * hour,
                lastFailureAt = now - 2 * 60_000L, failureCount = 1, startupRefreshDone = true, now = now
            )
        )
        // the same failure after the window allows a retry
        assertTrue(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.H6, lastSuccessAt = now - 24 * hour,
                lastFailureAt = now - 6 * 60_000L, failureCount = 1, startupRefreshDone = true, now = now
            )
        )
    }

    @Test
    fun backoffAlsoAppliesToTheStartupPass() {
        // a failure recorded right before the process restarted must not trigger an immediate retry
        assertFalse(
            AutoUpdateConfig.shouldRefresh(
                AutoUpdateConfig.Interval.STARTUP, 0L, lastFailureAt = now - 60_000L,
                failureCount = 1, startupRefreshDone = false, now = now
            )
        )
    }

    @Test
    fun lastSuccessLabelIsHumanReadable() {
        assertEquals("从未更新", AutoUpdateConfig.lastSuccessLabel(0L, now))
        assertEquals("刚刚", AutoUpdateConfig.lastSuccessLabel(now - 20_000L, now))
        assertEquals("5 分钟前", AutoUpdateConfig.lastSuccessLabel(now - 5 * 60_000L, now))
        assertEquals("3 小时前", AutoUpdateConfig.lastSuccessLabel(now - 3 * hour, now))
        assertEquals("2 天前", AutoUpdateConfig.lastSuccessLabel(now - 48 * hour, now))
    }

    @Test
    fun periodicFlagMatchesThePeriod() {
        assertFalse(AutoUpdateConfig.isPeriodic(AutoUpdateConfig.Interval.OFF))
        assertFalse(AutoUpdateConfig.isPeriodic(AutoUpdateConfig.Interval.STARTUP))
        assertTrue(AutoUpdateConfig.isPeriodic(AutoUpdateConfig.Interval.H6))
        assertTrue(AutoUpdateConfig.isPeriodic(AutoUpdateConfig.Interval.H12))
        assertTrue(AutoUpdateConfig.isPeriodic(AutoUpdateConfig.Interval.H24))
    }
}
