package com.cjlhll.iptv

/**
 * t182: pure policy for the manual EPG refresh and the source-change flow.
 *
 * Kept Android-free so the "exactly one execution per request" rule can be unit-tested.
 */
object ManualRefreshPolicy {

    /** What a manual EPG request should do — exactly one of these per user action. */
    enum class Action { NONE, CONSUME_FLAG, CALL_SCHEDULER }

    /**
     * t182 (EPG-MANUAL-01): the settings row only *raises* the flag; the pump consumes it once and
     * calls the scheduler. Calling the scheduler from both places ran the EPG twice (two Toasts,
     * last-update advanced twice).
     */
    fun actionFor(manualFlagRaised: Boolean, pumpAlreadyConsumed: Boolean): Action = when {
        !manualFlagRaised -> Action.NONE
        pumpAlreadyConsumed -> Action.NONE
        else -> Action.CALL_SCHEDULER
    }

    /**
     * t182 (STARTUP-RESET-02): only a *saved* source may reset the startup gate and refresh.
     * Opening (or cancelling) the config screen must not.
     */
    fun shouldRefreshAfterSourceScreen(saved: Boolean): Boolean = saved

    /**
     * t182 (EPG-SOURCE-SAVE-01): after a save, both sources go through the scheduler; the legacy
     * `refreshAll()` path must not be used as well.
     */
    fun usesSingleRefreshPath(schedulerUsed: Boolean, legacyRefreshAllUsed: Boolean): Boolean =
        schedulerUsed && !legacyRefreshAllUsed
}
