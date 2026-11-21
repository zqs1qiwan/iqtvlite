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

    // t213: fine-grained stages inside the prepare→first-frame window.
    @Volatile
    private var formatAt = 0L

    @Volatile
    private var decoderAt = 0L

    @Volatile
    private var lastZapMs = -1L

    @Volatile
    private var zapKey: String? = null

    /** t208 (ZAP-REPEAT-01): the key we already printed, so a re-entered channel logs once. */
    @Volatile
    private var loggedKey: String? = null

    /**
     * t204 (ZAP-KEY-01): the STABLE identity of a channel — deliberately free of the per-prepare
     * MediaItem sequence number, so a re-prepare of the same channel keeps the running measurement.
     */
    fun zapKey(channelId: Long, url: String): String = "c$channelId-l${url.hashCode().toUInt()}"

    /**
     * t211 (ZAP-NESTED-01): the key for a *selection* — one user zap may probe several variants of the
     * same group (selectedIndex 0/1/2 in playGroupAuto), and all of them must share one key so the
     * variant switch cannot re-arm and exclude the probe time.
     */
    fun groupKey(groupIndex: Int): String = "g$groupIndex"

    /** t216: true once a zap key is set and the clock is running. */
    fun isArmed(): Boolean = zapKey != null && zapAt > 0L

    /**
     * t216: arm only when nothing armed the clock yet — the **first autoplay** (cold start) has no
     * user key press, so without this fallback it produced no ZAP line at all (summaryLineOnce()
     * needs a key). A user entry always arms first, so this never overrides it.
     */
    fun armIfUnarmed(key: String, now: Long) {
        if (!isArmed()) onZapRequested(key, now)
    }

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
        // t216: the stage marks MUST be cleared here too — otherwise from the second zap on the stale
        // formatAt/decoderAt (< the new prepareAt) make formatDelayMs()/decoderDelayMs() return -1 and
        // summaryLine() silently falls back to the short (no fmt/dec/render) form.
        formatAt = 0L
        decoderAt = 0L
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

    /** t213: the first input format was reported (demuxer produced a format). */
    fun onInputFormatChanged(now: Long) {
        if (formatAt == 0L) formatAt = now
    }

    /** t213: `onVideoDecoderInitialized` — MediaCodec create+configure finished. */
    fun onVideoDecoderInitialized(now: Long) {
        if (decoderAt == 0L) decoderAt = now
    }

    /**
     * t218 (ZAP-SEG-02): prepare→first format (network first packet + demux). -1 when unknown.
     * This is the stage the device A/B showed to dominate TTFF (~94 %).
     */
    fun formatDelayMs(): Long = if (prepareAt > 0L && formatAt >= prepareAt) formatAt - prepareAt else -1L

    /**
     * t218: format→decoder-initialised (MediaCodec create/configure).
     *
     * The device trace showed `onVideoDecoderInitialized` firing **before**
     * `onInputVideoFormatChanged` (47.864 s vs 47.869 s), which made the old delta negative and dropped
     * the whole stage suffix. It is now clamped: a missing or out-of-order decoder mark counts as **0**
     * (nothing measurable happened in that window) instead of poisoning the line.
     */
    fun decoderDelayMs(): Long {
        if (formatAt == 0L || decoderAt == 0L) return 0L
        val d = decoderAt - formatAt
        return if (d > 0L) d else 0L
    }

    /**
     * t218: the remaining window to the first frame, measured from the **latest** observed mark
     * (decoder if it came after the format, otherwise the format). -1 only when nothing was observed.
     */
    fun renderDelayMs(): Long {
        if (firstFrameAt == 0L) return -1L
        val origin = maxOf(formatAt, decoderAt, prepareAt)
        if (origin == 0L || firstFrameAt < origin) return 0L
        return firstFrameAt - origin
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

    /**
     * t208 (ZAP-REPEAT-01): the same line, but **at most once per key**. `onRenderedFirstFrame` fires
     * again when the same channel is re-entered (or after an audio/video re-configure), which used to
     * print a second line with a bogus, much larger `zap`. Returns null when there is nothing new to
     * print (or the measurement is incomplete).
     */
    fun summaryLineOnce(): String? {
        val key = zapKey ?: return null
        if (key == loggedKey) return null
        val line = summaryLine() ?: return null
        loggedKey = key
        return line
    }

    /** `IQTV-ZAP zap=<x>ms prepare=<y>ms firstFrame=<z>ms` or null when the measurement is incomplete. */
    fun summaryLine(): String? {
        val total = zapToFirstFrameMs()
        if (total < 0L) return null
        val base = "zap=${total}ms prepare=${prepareDelayMs()}ms firstFrame=${firstFrameDelayMs()}ms"
        // t213/t218: append the intra-first-frame split as soon as the FORMAT was observed. A missing
        // or out-of-order decoder mark is reported as dec=0 instead of dropping all three stages.
        val fmt = formatDelayMs()
        if (fmt < 0L) return base
        val dec = decoderDelayMs()
        val render = renderDelayMs()
        return "$base fmt=${fmt}ms dec=${dec}ms render=${render}ms"
    }

    fun reset() {
        zapKey = null
        loggedKey = null
        zapAt = 0L
        formatAt = 0L
        decoderAt = 0L
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

    const val BUFFER_FOR_PLAYBACK_MS = 500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 2_000

    /**
     * t205: selectable start-up buffer tiers (ms). 300 starts fastest but an A/B on the real box showed
     * a 5.8 s rebuffer on cold start, so the DEFAULT is the safe tier (500) until the tiers are A/B'd
     * on device.
     */
    const val TIER_FAST_MS = 300
    const val TIER_BALANCED_MS = 400
    const val TIER_STABLE_MS = 500
    const val DEFAULT_BUFFER_MIN_MS = TIER_STABLE_MS

    val TIERS: List<Int> = listOf(TIER_FAST_MS, TIER_BALANCED_MS, TIER_STABLE_MS)

    /** Human label for the settings row / the debug line. */
    fun tierLabel(value: Int): String = when (sanitize(value)) {
        TIER_FAST_MS -> "快(300ms)"
        TIER_BALANCED_MS -> "均衡(400ms)"
        else -> "稳(500ms)"
    }

    /** Unknown / out-of-range Pref values fall back to the safe default (never crash the builder). */
    fun sanitize(value: Int): Int = if (value in TIERS) value else DEFAULT_BUFFER_MIN_MS

    /** Next tier when the user presses OK on the row (快 → 均衡 → 稳 → 快). */
    fun next(value: Int): Int {
        val i = TIERS.indexOf(sanitize(value))
        return TIERS[(i + 1) % TIERS.size]
    }

    /** setBufferDurationsMs takes Ints. */
    fun minBufferMs(): Int = MIN_BUFFER_MS
    fun maxBufferMs(): Int = MAX_BUFFER_MS
    fun playbackBufferAfterRebufferMs(): Int = BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS

    /** The start-up buffer the player is built with, from the persisted Pref value. */
    fun playbackBufferMinMs(prefValue: Int = DEFAULT_BUFFER_MIN_MS): Int = sanitize(prefValue)

    /** The invariant USAB2-01 / CI10077 must hold for every tier. */
    fun isValidFor(bufferMinMs: Int): Boolean =
        MIN_BUFFER_MS >= BUFFER_FOR_PLAYBACK_MS &&
            MIN_BUFFER_MS >= BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS &&
            bufferMinMs in 1..BUFFER_FOR_PLAYBACK_MS

    val isValid: Boolean
        get() = TIERS.all { isValidFor(it) }
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

    /**
     * t7（PERF-04 · 冷启 rebuffer）：首帧之后再过多久才开始读 EPG 缓存。
     *
     * 基线证据（t14）：首帧 3197ms，紧接着 **启动 rebuffer 1 次 / 4288ms**，而同一时刻
     * `epgCache=22095ms` —— 22 秒的 XMLTV 解析正好压在起播后的缓冲恢复窗口上，抢走的 CPU 让
     * 播放器补不上缓冲。这里把 EPG 缓存读取再推迟到首帧后 [EPG_POST_FIRST_FRAME_DELAY_MS]，
     * 让起播那一秒先干净地跑完；只影响"抽屉/节目单晚 ~2s 有数据"，不影响播放。
     *
     * 回退：改成 0L（等于恢复旧行为）。
     */
    const val EPG_POST_FIRST_FRAME_DELAY_MS = 2_000L

    /** 只有当这次 rebuffer 发生在起播后的这个窗口内才归因于"冷启"（用于文档/诊断）。 */
    const val COLD_START_WINDOW_MS = 6_000L

    /**
     * t27（冷启 rebuffer 实验 ③）：首帧前多等多少毫秒缓冲再 play（0 = 立即 play，旧行为）。
     * t22 证据：`rebuffer_begin bufferMs=696 since_first_frame=303ms`、`bytesPerSec=364KB/s`（需 ~1MB/s）
     * → 初期补流爬升慢。设为 300–500 后可在补流前多攒一点缓冲；**默认 0，A/B 时改这一处**。
     * 必须同时报 TTFF（基准 p50 957ms），劣化 >10% 即不采纳。
     */
    const val PREBUFFER_BEFORE_PLAY_MS = 0L
}
