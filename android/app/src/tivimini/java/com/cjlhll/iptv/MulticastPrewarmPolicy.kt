package com.cjlhll.iptv

/**
 * t220: multicast pre-warm policy — **opt-in experiment, default OFF**.
 *
 * The t219 A/B showed TCP pre-connect does nothing (the source is multicast), and the `fmt` stage
 * (~560 ms) is UDP group join + first TS packet + waiting for the first IDR. So this arm warms the
 * **real transport**: join the neighbour's multicast group with its own socket, read a short slice of
 * the stream (bounded ring budget), then leave again. When the user actually zaps there, the kernel
 * IGMP membership and the route are already in place, and (if the platform keeps the membership) the
 * first packets arrive sooner.
 *
 * Honest limitation, documented in MULTICAST_PREWARM.md: this build does **not** inject the buffered
 * packets into ExoPlayer — the player still opens its own socket. The arm is therefore
 * "join + first-packet probe", not "instant start from a pre-read buffer".
 *
 * Budgets (a 512 MB box must never be starved):
 *  - one neighbour, one socket at a time;
 *  - at most [MAX_BYTES] read into a throw-away ring;
 *  - hard [TIMEOUT_MS] cap, cancellable, every failure silent;
 *  - only while the channel list is open or the player is idle, throttled.
 */
object MulticastPrewarmPolicy {

    const val MAX_TARGETS = 1
    const val TIMEOUT_MS = 2_500L
    const val MAX_BYTES = 3 * 1024 * 1024
    const val MIN_INTERVAL_MS = 5_000L

    /** A parsed multicast endpoint. */
    data class Endpoint(val host: String, val port: Int)

    /**
     * Parse `rtp://239.1.2.3:5000`, `udp://@239.1.2.3:5000`, `udp://239.1.2.3:5000`.
     * Returns null for anything that is not a multicast group (unicast http, IGMP-less URLs, …).
     */
    fun parse(url: String): Endpoint? {
        val u = url.trim()
        val lowered = u.lowercase()
        val scheme = when {
            lowered.startsWith("rtp://") -> "rtp://"
            lowered.startsWith("udp://") -> "udp://"
            else -> return null
        }
        var rest = u.substring(scheme.length)
        if (rest.startsWith("@")) rest = rest.substring(1)
        rest = rest.substringBefore('/')
        val host = rest.substringBeforeLast(':', "")
        val portText = rest.substringAfterLast(':', "")
        if (host.isBlank() || portText.isBlank()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return if (isMulticastHost(host)) Endpoint(host, port) else null
    }

    /** 224.0.0.0/4 (IPv4 multicast). Hostnames are not pre-warmed (they would need DNS first). */
    fun isMulticastHost(host: String): Boolean {
        val first = host.substringBefore('.').toIntOrNull() ?: return false
        return first in 224..239
    }

    /** The neighbour to warm for [size] channels at [current] (next first, else previous). */
    fun neighbour(size: Int, current: Int): Int? {
        if (size <= 1 || current !in 0 until size) return null
        if (current + 1 in 0 until size) return current + 1
        if (current - 1 in 0 until size) return current - 1
        return null
    }

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

    /** Stop receiving once this many bytes were read (ring budget). */
    fun reachedBudget(bytesRead: Long): Boolean = bytesRead >= MAX_BYTES

    /** Human summary for the debug log. */
    fun summaryLine(bytes: Long, elapsedMs: Long, idrSeen: Boolean): String =
        "joined bytes=$bytes elapsed=${elapsedMs}ms idr=$idrSeen"
}
