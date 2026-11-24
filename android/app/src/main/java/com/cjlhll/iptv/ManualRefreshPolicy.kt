package com.cjlhll.iptv

/**
 * t189 (MANUAL-POLICY-01 / SOURCE-SAVE-02): pure policy for the manual EPG refresh, the saved-source
 * flow and the "one network pass at a time" gate.
 */
object ManualRefreshPolicy {

    /** What a manual EPG request should do — exactly one of these per user action. */
    enum class Action { NONE, CONSUME_FLAG, CALL_SCHEDULER }

    /**
     * The settings row (and the legacy flag) only *raise* a request; the flow pump consumes it once
     * and calls the scheduler. Previously the scheduler was called from both places, which ran the
     * EPG twice (two Toasts, last-update advanced twice).
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
     * t189 (SOURCE-SAVE-02): a single ticket for the network pass, so a 60 s tick, a saved source and
     * a manual refresh can never run concurrently (and a save cannot be followed by a second startup
     * pass). Thread-safe because the scheduler touches it from IO coroutines.
     */
    class RefreshGate {
        @Volatile
        private var inFlight = false

        /** @return true when this caller now owns the pass and must call [exit] when finished. */
        @Synchronized
        fun tryEnter(): Boolean {
            if (inFlight) return false
            inFlight = true
            return true
        }

        @Synchronized
        fun exit() {
            inFlight = false
        }

        fun isInFlight(): Boolean = inFlight
    }
}
