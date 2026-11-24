package com.cjlhll.iptv

/**
 * t172 (F3): pure policy for the automatic playlist / EPG updates.
 *
 * All decisions live here (interval parsing, due-time, failure backoff) so they are unit-testable and
 * the scheduler in [AutoUpdateScheduler] only has to execute them.
 */
object AutoUpdateConfig {

    /** 关闭 / 启动时 / 每 6h / 每 12h / 每 24h — the single-choice list shown in settings. */
    enum class Interval(val label: String, val labelEn: String, val periodMs: Long) {
        OFF("关闭", "Off", 0L),
        STARTUP("启动时", "On startup", 0L),
        H2("每 2 小时", "Every 2h", 2 * 3600_000L),
        H4("每 4 小时", "Every 4h", 4 * 3600_000L),
        H6("每 6 小时", "Every 6h", 6 * 3600_000L),
        H8("每 8 小时", "Every 8h", 8 * 3600_000L),
        H12("每 12 小时", "Every 12h", 12 * 3600_000L),
        H16("每 16 小时", "Every 16h", 16 * 3600_000L),
        H24("每 24 小时", "Every 24h", 24 * 3600_000L),
        H48("每 48 小时", "Every 48h", 48 * 3600_000L),
        H72("每 72 小时", "Every 72h", 72 * 3600_000L);

        fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else label
    }

    /** Default keeps the pre-F3 behaviour: refresh once at startup. */
    val DEFAULT = Interval.STARTUP

    private val ORDER = listOf(Interval.OFF, Interval.STARTUP, Interval.H6, Interval.H12, Interval.H24)
    val EPG_HOURS_ORDER = listOf(
        Interval.OFF,
        Interval.STARTUP,
        Interval.H2,
        Interval.H4,
        Interval.H8,
        Interval.H16,
        Interval.H24,
        Interval.H48,
        Interval.H72,
    )

    fun parse(raw: String?): Interval =
        Interval.entries.firstOrNull { it.name == raw } ?: DEFAULT

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

    /** t176: failure bookkeeping carried by the scheduler, kept pure so it can be tested. */
    data class FailureState(val lastFailureAt: Long, val failCount: Int)

    /** A successful refresh clears the failure state, so the next due check is interval-based. */
    fun afterSuccess(): FailureState = FailureState(0L, 0)

    /** A failed attempt records the time and bumps the consecutive counter (backoff input). */
    fun afterFailure(now: Long, previousFailures: Int): FailureState =
        FailureState(now, previousFailures + 1)

    /** t176 (PERF-01): the startup pass waits for the first frame instead of racing cold start. */
    const val STARTUP_DELAY_MS = 1_500L

    /** Human label for the "上次成功更新" line. */
    fun lastSuccessLabel(lastSuccessAt: Long, now: Long, lang: AppLang = AppLang.ZH): String {
        if (lastSuccessAt <= 0L) return if (lang == AppLang.EN) "Never" else "从未更新"
        val diff = (now - lastSuccessAt).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        return if (lang == AppLang.EN) {
            when {
                minutes < 1L -> "Just now"
                minutes < 60L -> "${minutes}m ago"
                minutes < 24 * 60L -> "${minutes / 60}h ago"
                else -> "${minutes / (24 * 60)}d ago"
            }
        } else {
            when {
                minutes < 1L -> "刚刚"
                minutes < 60L -> "$minutes 分钟前"
                minutes < 24 * 60L -> "${minutes / 60} 小时前"
                else -> "${minutes / (24 * 60)} 天前"
            }
        }
    }
}
