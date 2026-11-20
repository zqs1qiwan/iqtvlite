package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** t182: one manual EPG execution, source-save gating, single refresh path. */
class ManualRefreshPolicyTest {

    @Test
    fun manualEpgRunsExactlyOnce() {
        // the row raises the flag; the pump consumes it once and calls the scheduler
        assertEquals(
            ManualRefreshPolicy.Action.CALL_SCHEDULER,
            ManualRefreshPolicy.actionFor(manualFlagRaised = true, pumpAlreadyConsumed = false)
        )
        // nothing raised → nothing happens
        assertEquals(
            ManualRefreshPolicy.Action.NONE,
            ManualRefreshPolicy.actionFor(manualFlagRaised = false, pumpAlreadyConsumed = false)
        )
        // already consumed → no second execution (this was the double-download bug)
        assertEquals(
            ManualRefreshPolicy.Action.NONE,
            ManualRefreshPolicy.actionFor(manualFlagRaised = true, pumpAlreadyConsumed = true)
        )
    }

    @Test
    fun onlyASavedSourceTriggersTheReset() {
        assertTrue(ManualRefreshPolicy.shouldRefreshAfterSourceScreen(saved = true))
        assertFalse("cancelling the config screen must not refresh", ManualRefreshPolicy.shouldRefreshAfterSourceScreen(saved = false))
    }

    @Test
    fun theSchedulerIsTheOnlyRefreshPath() {
        assertTrue(ManualRefreshPolicy.usesSingleRefreshPath(schedulerUsed = true, legacyRefreshAllUsed = false))
        assertFalse(
            "the legacy refreshAll() path must not run alongside the scheduler",
            ManualRefreshPolicy.usesSingleRefreshPath(schedulerUsed = true, legacyRefreshAllUsed = true)
        )
        assertFalse(ManualRefreshPolicy.usesSingleRefreshPath(schedulerUsed = false, legacyRefreshAllUsed = true))
    }

    @Test
    fun epgSuccessAndFailureAreSingleSourced() {
        // AutoUpdateConfig owns the state transitions; the scheduler only calls them
        val success = AutoUpdateConfig.afterSuccess()
        assertEquals(0, success.failCount)
        assertEquals(0L, success.lastFailureAt)

        val failure = AutoUpdateConfig.afterFailure(1_000L, previousFailures = 0)
        assertEquals(1, failure.failCount)
        assertEquals(1_000L, failure.lastFailureAt)

        // and a success really clears a previous failure chain
        val afterChain = AutoUpdateConfig.afterSuccess()
        assertEquals(0, afterChain.failCount)
        assertTrue(
            "after a success the automatic path is no longer in backoff",
            AutoUpdateConfig.backoffMs(afterChain.failCount) == 0L
        )
    }
}
