package com.cjlhll.iptv

/**
 * t197 (A1): debug-only TTFF stopwatch for the zap path.
 *
 * Records the wall-clock of the user's channel change → `prepare()` → first rendered frame and
 * exposes the deltas so a single `adb logcat -s IQTV-ZAP` session yields the A/B numbers. It never
 * runs in release: [enabledByDefault] is false and the caller gates the logs on `Log.isLoggable`.
 *
 * Pure Kotlin (no Android imports) so the arithmetic is unit-tested.
 */
object ZapTrace {

    /** Set by the Activity when the user zaps; millis since boot-ish (SystemClock.elapsedRealtime). */
    @Volatile
    private var zapAt = 0L

    @Volatile
    private var prepareAt = 0L

    @Volatile
    private var firstFrameAt = 0L

    @Volatile
    private var lastZapMs = -1L

    @Volatile
    private var zapKey: String? = null

    /**
     * t204 (ZAP-KEY-01): the STABLE identity of a channel — deliberately free of the per-prepare
     * MediaItem sequence number, so a re-prepare of the same channel keeps the running measurement.
     */
    fun zapKey(channelId: Long, url: String): String = "c$channelId-l${url.hashCode().toUInt()}"

    /**
     * t204 (ZAP-TIMING-01): arm the stopwatch at the USER's zap entry (key press / click), so the
     * measurement starts when the user acts, not when ExoPlayer happens to prepare. Re-arming with the
     * same key is ignored, i.e. one channel change = one measurement.
     */
    fun onZapRequested(key: String, now: Long) {
        if (key == zapKey && zapAt > 0L) return
        zapKey = key
        onZap(now)
    }

    /** A channel change was requested. */
    fun onZap(now: Long) {
        zapAt = now
        prepareAt = 0L
        firstFrameAt = 0L
        lastZapMs = -1L
    }

    /**
     * `ExoPlayer.prepare()` was called.
     *
     * t204: if no user entry armed the stopwatch (e.g. the initial autoplay), prepare becomes the
     * origin so the measurement is still produced; in the normal zap path the user entry already set
     * [zapAt], so `prepareDelayMs` reflects the real key-to-prepare gap.
     */
    fun onPrepare(now: Long) {
        if (zapAt == 0L) zapAt = now
        prepareAt = now
    }

    /** `onRenderedFirstFrame` fired for the item prepared above. */
    fun onFirstFrame(now: Long): Long {
        firstFrameAt = now
        val ms = if (zapAt > 0L && now >= zapAt) now - zapAt else -1L
        lastZapMs = ms
        return ms
    }

    /** user action → prepare() (playlist/format/decoder setup). -1 when unknown. */
    fun prepareDelayMs(): Long =
        if (zapAt > 0L && prepareAt >= zapAt) prepareAt - zapAt else -1L

    /** prepare() → first frame (network + decode + first present). -1 when unknown. */
    fun firstFrameDelayMs(): Long =
        if (prepareAt > 0L && firstFrameAt >= prepareAt) firstFrameAt - prepareAt else -1L

    /** user action → first frame, i.e. what the user perceives as the zap latency. */
    fun zapToFirstFrameMs(): Long = lastZapMs

    /** `IQTV-ZAP zap=<x>ms prepare=<y>ms firstFrame=<z>ms` or null when the measurement is incomplete. */
    fun summaryLine(): String? {
        val total = zapToFirstFrameMs()
        if (total < 0L) return null
        return "zap=${total}ms prepare=${prepareDelayMs()}ms firstFrame=${firstFrameDelayMs()}ms"
    }

    fun reset() {
        zapKey = null
        zapAt = 0L
        prepareAt = 0L
        firstFrameAt = 0L
        lastZapMs = -1L
    }
}

/**
 * t197 (A2): the LoadControl numbers in one place so an A/B experiment is a single-variable change.
 *
 * Invariant (USAB2-01 / CI10077): [minBufferMs] must stay >= [bufferForPlaybackAfterRebufferMs],
 * otherwise playback crashes at start on the weak v7a box.
 */
object LoadControlTuning {
    /** t157 A/B winner for CPU (loader idles instead of spinning). */
    const val MIN_BUFFER_MS = 2_500
    const val MAX_BUFFER_MS = 6_000

    /** t197 A/B arm: 500 → 300 to start later and shave TTFF; depends on the two below. */
    const val PLAYBACK_BUFFER_MIN_MS = 300

    const val BUFFER_FOR_PLAYBACK_MS = 500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    /** setBufferDurationsMs takes Ints. */
    fun minBufferMs(): Int = MIN_BUFFER_MS
    fun maxBufferMs(): Int = MAX_BUFFER_MS
    fun playbackBufferMinMs(): Int = PLAYBACK_BUFFER_MIN_MS
    fun playbackBufferAfterRebufferMs(): Int = BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS

    val isValid: Boolean
        get() = MIN_BUFFER_MS >= BUFFER_FOR_PLAYBACK_MS &&
            MIN_BUFFER_MS >= BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS &&
            PLAYBACK_BUFFER_MIN_MS in 1..BUFFER_FOR_PLAYBACK_MS
}

/**
 * t197 (B): cold-start ordering rules, kept pure so they are testable and documented.
 */
object ColdStartPolicy {
    /** EPG must not be parsed before the first frame — it competes with it (14 s cold start). */
    const val EPG_AFTER_FIRST_FRAME = true

    /**
     * t201: hard ceiling on the "wait for the first frame" gate. If the first frame never arrives
     * (offline, broken stream, unsupported codec) the EPG cache must still be read — otherwise the
     * schedule would simply be missing.
     */
    const val EPG_CACHE_FALLBACK_MS = 10_000L


}
