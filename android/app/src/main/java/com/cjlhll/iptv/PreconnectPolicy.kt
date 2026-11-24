package com.cjlhll.iptv

/**
 * t218 (arm B): neighbour-channel pre-connect — **default OFF, opt-in experiment**.
 *
 * The device A/B showed the first-frame cost is dominated by `fmt` (prepare → first format, i.e. the
 * network first packet + demux join): p50 ≈ 1134 ms of a ≈ 1207 ms TTFF, while `dec` is 7 ms and
 * `render` 75 ms. So the only lever with real headroom is warming the *network* path for the channels
 * the user is about to zap to.
 *
 * This object holds the pure policy (which channels, when, how long); [ZapPreconnect] in the Android
 * layer does the socket work. Keeping the policy pure makes it testable and keeps the risky part small.
 *
 * Safety rules (a 512 MB box must not be starved):
 *  - at most [MAX_TARGETS] channels, only the immediate neighbours of the current one;
 *  - only while the channel list is open or the player is idle (never during playback of a zap);
 *  - hard [TIMEOUT_MS] cap per attempt, cancellable, and **every failure is silent** — a failed
 *    pre-connect must never affect the real zap;
 *  - never more than one in-flight attempt at a time.
 */
object PreconnectPolicy {

    /** Channels to warm: previous and next (the two D-pad directions). */
    const val MAX_TARGETS = 2

    /** Hard cap for one warm attempt; the real zap must never wait for it. */
    const val TIMEOUT_MS = 2_000L

    /** Never warm more often than this (avoid churn when the user holds DOWN). */
    const val MIN_INTERVAL_MS = 3_000L

    /** t218: which neighbour indices to warm, in order, for a list of [size] at [current]. */
    fun neighbours(size: Int, current: Int): List<Int> {
        if (size <= 1 || current !in 0 until size) return emptyList()
        val result = ArrayList<Int>(MAX_TARGETS)
        val next = current + 1
        val prev = current - 1
        if (next in 0 until size) result += next
        if (prev in 0 until size) result += prev
        return result.take(MAX_TARGETS)
    }

    /**
     * Whether a warm pass may start now.
     * @param enabled the user's opt-in switch
     * @param drawerOpen the channel list is showing (the user is likely to zap next)
     * @param idle the player is not switching/preparing right now
     * @param inFlight an attempt is already running
     * @param sinceLastMs time since the previous pass
     */
    fun mayWarm(
        enabled: Boolean,
        drawerOpen: Boolean,
        idle: Boolean,
        inFlight: Boolean,
        sinceLastMs: Long,
    ): Boolean {
        if (!enabled) return false
        if (inFlight) return false
        if (!drawerOpen && !idle) return false
        return sinceLastMs >= MIN_INTERVAL_MS
    }

    /** t218: the warm must give up at this deadline (never blocks the zap). */
    fun deadlineFrom(now: Long): Long = now + TIMEOUT_MS

    /** Only http(s) URLs can be warmed; RTP/UDP is handled by the caller's own transport. */
    fun canWarmUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://")
    }
}
