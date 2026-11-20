package com.cjlhll.iptv

/**
 * t172 (F3): pure policy for the automatic playlist / EPG updates.
 *
 * All decisions live here (interval parsing, due-time, failure backoff) so they are unit-testable and
 * the scheduler in [AutoUpdateScheduler] only has to execute them.
 */
object AutoUpdateConfig {

    /** 关闭 / 启动时 / 每 6h / 每 12h / 每 24h — the single-choice list shown in settings. */
    enum class Interval(val label: String, val periodMs: Long) {
        OFF("关闭", 0L),
        STARTUP("启动时", 0L),
        H6("每 6 小时", 6 * 3600_000L),
        H12("每 12 小时", 12 * 3600_000L),
        H24("每 24 小时", 24 * 3600_000L),
    }

    /** Default keeps the pre-F3 behaviour: refresh once at startup. */
    val DEFAULT = Interval.STARTUP

    private val ORDER = listOf(Interval.OFF, Interval.STARTUP, Interval.H6, Interval.H12, Interval.H24)

    fun parse(raw: String?): Interval =
        ORDER.firstOrNull { it.name == raw } ?: DEFAULT

    fun encode(interval: Interval): String = interval.name

    /** Next value when the user presses OK on the item (cycles through the list). */
    fun next(current: Interval): Interval {
        val index = ORDER.indexOf(current).let { if (it < 0) 0 else it }
        return ORDER[(index + 1) % ORDER.size]
    }

    fun isEnabled(interval: Interval): Boolean = interval != Interval.OFF

    /** True when the interval implies a periodic refresh (not OFF/STARTUP). */
    fun isPeriodic(interval: Interval): Boolean = interval.periodMs > 0L

    // ---- failure backoff: 5 min, doubling, capped at 30 min ----
    const val BACKOFF_BASE_MS = 5 * 60_000L
    const val BACKOFF_MAX_MS = 30 * 60_000L

    fun backoffMs(failureCount: Int): Long {
        if (failureCount <= 0) return 0L
        var delay = BACKOFF_BASE_MS
        repeat(failureCount - 1) {
            delay *= 2
            if (delay >= BACKOFF_MAX_MS) return BACKOFF_MAX_MS
        }
        return delay.coerceAtMost(BACKOFF_MAX_MS)
    }

    /**
     * Should an automatic refresh run now?
     *
     * @param interval user choice
     * @param lastSuccessAt last successful refresh (0 = never)
     * @param lastFailureAt last failed attempt (0 = never)
     * @param failureCount consecutive failures (drives the backoff)
     * @param startupRefreshDone whether this process already did its startup refresh
     */
    fun shouldRefresh(
        interval: Interval,
        lastSuccessAt: Long,
        lastFailureAt: Long,
        failureCount: Int,
        startupRefreshDone: Boolean,
        now: Long,
    ): Boolean {
        if (interval == Interval.OFF) return false
        if (lastFailureAt > 0L) {
            // A failed attempt is retried once its backoff window elapses — that is the whole point of
            // the backoff (waiting a full 6/12/24 h interval after a transient failure would be wrong).
            return now - lastFailureAt >= backoffMs(failureCount)
        }
        if (!startupRefreshDone) return true // startup refresh (also the "启动时" option)
        if (interval == Interval.STARTUP) return false
        if (lastSuccessAt <= 0L) return true
        return now - lastSuccessAt >= interval.periodMs
    }

    /** Human label for the "上次成功更新" line. */
    fun lastSuccessLabel(lastSuccessAt: Long, now: Long): String {
        if (lastSuccessAt <= 0L) return "从未更新"
        val diff = (now - lastSuccessAt).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        return when {
            minutes < 1L -> "刚刚"
            minutes < 60L -> "$minutes 分钟前"
            minutes < 24 * 60L -> "${minutes / 60} 小时前"
            else -> "${minutes / (24 * 60)} 天前"
        }
    }
}
