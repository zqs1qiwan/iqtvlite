package com.cjlhll.iptv

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * t165: **debug-only** audio cost counters. Default off; enabled when the process is debuggable and
 * the `IQTV-AUDIO` log tag is enabled:
 *
 *     adb shell setprop log.tag.IQTV-AUDIO DEBUG
 *
 * Nothing here runs on the playback path unless [enabled] is true: the increments are plain atomics
 * and the summary is produced once, 60 s after a session starts.
 *
 * Pure Kotlin so the summary format is unit-testable.
 */
object AudioCostCounters {
    private val activeMs = AtomicLong(0)
    private val underruns = AtomicInteger(0)
    private val retries = AtomicInteger(0)
    private val tracks = AtomicInteger(0)
    private val rendered = AtomicLong(0)
    private val skipped = AtomicLong(0)

    @Volatile private var decoder: String = ""
    @Volatile private var encoding: String = ""
    @Volatile private var sampleRate: Int = 0
    @Volatile private var bufferBytes: Int = 0
    @Volatile private var active: Boolean = false

    /** Debug-only switch (see the class doc); never enabled in a normal user session. */
    var enabled: Boolean = false

    data class Sample(
        val activeMs: Long,
        val underruns: Int,
        val retries: Int,
        val tracks: Int,
        val rendered: Long,
        val skipped: Long,
        val decoder: String,
        val encoding: String,
        val sampleRate: Int,
        val bufferBytes: Int,
    )

    fun reset() {
        activeMs.set(0); underruns.set(0); retries.set(0); tracks.set(0)
        rendered.set(0); skipped.set(0)
        decoder = ""; encoding = ""; sampleRate = 0; bufferBytes = 0; active = false
    }

    fun onActiveChanged(isActive: Boolean) {
        if (!enabled) return
        active = isActive
        if (isActive) tracks.incrementAndGet()
    }

    fun onActiveMs(ms: Long) {
        if (!enabled || ms <= 0) return
        activeMs.addAndGet(ms)
    }

    fun onUnderrun() {
        if (!enabled) return
        underruns.incrementAndGet()
    }

    /** A sink/codec error that leads to a re-initialisation (retry). */
    fun onRetry() {
        if (!enabled) return
        retries.incrementAndGet()
    }

    fun onDecoder(name: String) {
        if (!enabled) return
        decoder = name
    }

    fun onTrack(encoding: String, sampleRate: Int, bufferBytes: Int) {
        if (!enabled) return
        this.encoding = encoding
        this.sampleRate = sampleRate
        this.bufferBytes = bufferBytes
    }

    fun onCounters(renderedOutput: Long, skippedOutput: Long) {
        if (!enabled) return
        rendered.set(renderedOutput)
        skipped.set(skippedOutput)
    }

    fun isActive(): Boolean = active

    fun sample(): Sample = Sample(
        activeMs.get(), underruns.get(), retries.get(), tracks.get(),
        rendered.get(), skipped.get(), decoder, encoding, sampleRate, bufferBytes,
    )

    /** One-line summary written to `IQTV-AUDIO` 60 s into a session. */
    fun format(sample: Sample): String =
        "active_ms=${sample.activeMs} underrun=${sample.underruns} retry=${sample.retries} " +
            "tracks=${sample.tracks} rendered=${sample.rendered} skipped=${sample.skipped} " +
            "decoder=${sample.decoder.ifBlank { "none" }} encoding=${sample.encoding.ifBlank { "none" }} " +
            "rate=${sample.sampleRate} buffer=${sample.bufferBytes}"
}
