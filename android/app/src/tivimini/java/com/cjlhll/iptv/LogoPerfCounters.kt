package com.cjlhll.iptv

import java.util.concurrent.atomic.AtomicInteger

/**
 * t154: cheap counters for perf isolation. Three atomics only — no allocation per logo, no
 * background job; the summary is produced on demand (drawer close) and logged, then reset.
 *
 * Pure Kotlin so the formatting/reset contract is unit-testable.
 */
object LogoPerfCounters {
    private val prefetchRequests = AtomicInteger(0)
    private val decodes = AtomicInteger(0)
    private val cacheHits = AtomicInteger(0)

    fun onPrefetchRequest(count: Int = 1) {
        if (count > 0) prefetchRequests.addAndGet(count)
    }

    fun onDecode() {
        decodes.incrementAndGet()
    }

    fun onCacheHit() {
        cacheHits.incrementAndGet()
    }

    data class Sample(val requests: Int, val decodes: Int, val hits: Int)

    fun sample(): Sample = Sample(prefetchRequests.get(), decodes.get(), cacheHits.get())

    /** `prefetch=req:N dec:M hit:K` — the single log line used for A/B deltas. */
    fun format(sample: Sample): String =
        "prefetch=req=${sample.requests} dec=${sample.decodes} hit=${sample.hits}"

    /** Read the counters and reset them atomically enough for logging purposes. */
    fun sampleAndReset(): Sample {
        val s = Sample(
            prefetchRequests.getAndSet(0),
            decodes.getAndSet(0),
            cacheHits.getAndSet(0),
        )
        return s
    }

    fun reset() {
        prefetchRequests.set(0); decodes.set(0); cacheHits.set(0)
    }
}
