package com.cjlhll.iptv

/**
 * t225: first-frame watchdog policy (pure, unit-tested).
 *
 * Some high-bitrate / multicast channels (e.g. 睛彩 ~8.3 Mbps) never produce a rendered first frame:
 * the player sits in BUFFERING forever and the user sees a black screen. This watchdog gives every zap
 * one automatic retry, then tells the user instead of leaving them staring at nothing.
 *
 * Rules:
 *  - armed after every `prepare()`; cancelled by a first frame, a new zap, pause, an overlay or exit;
 *  - **8 s** without a rendered first frame → one retry (never more than [MAX_RETRIES]);
 *  - [TIMEOUT_MS] again without a frame → give up with a toast (no retry loop);
 *  - a generation counter makes stale timers inert, so a retry can never double-fire.
 */
object FirstFrameWatchdogPolicy {

    /** 8 s: normal channels render far quicker (p50 ≈ 1.2 s), so nothing changes for them. */
    const val TIMEOUT_MS = 8_000L

    /** Exactly one automatic retry — never a loop. */
    const val MAX_RETRIES = 1

    enum class Action { RETRY, GIVE_UP, IGNORE }

    /**
     * Generation-stamped watchdog. Every [arm] starts a new generation; a timer that finishes for an
     * older generation returns [Action.IGNORE] and does nothing (no duplicate prepare).
     */
    class Watchdog(private val timeoutMs: Long = TIMEOUT_MS, private val maxRetries: Int = MAX_RETRIES) {
        private var generation = 0
        private var retries = 0
        private var armed = false
        private var channelKey: String? = null

        /** Start (or restart) the watchdog; returns the generation the timer must carry. */
        @Synchronized
        fun arm(channelKey: String): Int {
            generation++
            retries = 0
            armed = true
            this.channelKey = channelKey
            return generation
        }

        @Synchronized
        fun isArmed(): Boolean = armed

        @Synchronized
        fun retriesUsed(): Int = retries

        @Synchronized
        fun currentChannel(): String? = channelKey

        /** First frame arrived → cancel. */
        @Synchronized
        fun onFirstFrame() {
            armed = false
        }

        /** New zap / pause / overlay / exit → cancel silently. */
        @Synchronized
        fun cancel() {
            armed = false
        }

        /**
         * A timer of [gen] expired. Stale generations (already re-armed by a newer zap) are ignored.
         * The first expiry schedules a retry; the next one gives up.
         */
        @Synchronized
        fun onTimeout(gen: Int): Action {
            if (!armed) return Action.IGNORE
            if (gen != generation) return Action.IGNORE   // a newer zap owns the watchdog now
            if (retries < maxRetries) {
                retries++
                return Action.RETRY
            }
            armed = false
            return Action.GIVE_UP
        }

        @Synchronized
        fun timeoutMillis(): Long = timeoutMs

        @Synchronized
        fun reset() {
            generation++
            retries = 0
            armed = false
            channelKey = null
        }
    }

    /** The log line for a retry (DEBUG gated by the caller). */
    fun retryLine(channelKey: String, retry: Int): String =
        "firstFrameTimeout retry=$retry channel=$channelKey"

    /** Message shown when even the retry produced no frame. */
    const val GIVE_UP_MESSAGE = "该频道暂不可播（网络/源响应慢）"
}
