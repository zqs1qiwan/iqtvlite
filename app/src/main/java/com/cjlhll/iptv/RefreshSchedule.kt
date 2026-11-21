package com.cjlhll.iptv

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 刷新调度纯逻辑（D02）。与 Android/Prefs 解耦，便于用可控时钟做单测，
 * 避免只断言 Prefs 值而无法证明实际调度行为。
 */
object RefreshSchedule {
    const val SIX_HOURS_MS = 6L * 60L * 60L * 1000L
    const val RETRY_BACKOFF_MS = 5L * 60L * 1000L

    private fun backoffBlocked(nowMillis: Long, lastAttempt: Long): Boolean =
        lastAttempt > 0L && nowMillis - lastAttempt < RETRY_BACKOFF_MS

    /**
     * 播放列表是否应自动刷新。
     * [launchAttempted] 为本进程内对当前源是否已尝试过启动刷新。
     */
    fun playlistDue(
        nowMillis: Long,
        lastSuccess: Long,
        lastAttempt: Long,
        policy: PlaylistRefreshPolicy,
        launchAttempted: Boolean,
    ): Boolean {
        if (policy == PlaylistRefreshPolicy.MANUAL) return false
        if (backoffBlocked(nowMillis, lastAttempt)) return false
        return when (policy) {
            PlaylistRefreshPolicy.MANUAL -> false
            PlaylistRefreshPolicy.STARTUP_ONLY -> !launchAttempted
            PlaylistRefreshPolicy.STARTUP_6H ->
                !launchAttempted || lastSuccess <= 0L || nowMillis - lastSuccess >= SIX_HOURS_MS
        }
    }

    /**
     * EPG 是否应自动刷新。DAILY 按“当天首次启动”判定；STARTUP_6H 兼顾启动与 6 小时。
     */
    fun epgDue(
        nowMillis: Long,
        lastSuccess: Long,
        lastAttempt: Long,
        policy: EpgRefreshPolicy,
        launchAttempted: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (policy == EpgRefreshPolicy.MANUAL) return false
        if (backoffBlocked(nowMillis, lastAttempt)) return false
        val today = epochDay(nowMillis, zone)
        val lastDay = if (lastSuccess > 0L) epochDay(lastSuccess, zone) else 0L
        return when (policy) {
            EpgRefreshPolicy.MANUAL -> false
            EpgRefreshPolicy.DAILY -> !launchAttempted && lastDay != today
            EpgRefreshPolicy.STARTUP_6H ->
                !launchAttempted || lastSuccess <= 0L || nowMillis - lastSuccess >= SIX_HOURS_MS
        }
    }

    private fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
}

/** 播放列表内容校验（D10）：必须至少解析出一个带可播放协议的频道才允许替换缓存。 */
object PlaylistValidation {
    private val playableScheme = Regex("""^(https?|rtmp|rtsp|udp|rtp|mms)://""", RegexOption.IGNORE_CASE)

    fun channelCount(content: String): Int {
        if (content.isBlank()) return 0
        val channels = runCatching { M3uParser.parse(content) }.getOrNull().orEmpty()
        if (channels.isEmpty()) return 0
        val playable = channels.filter { playableScheme.containsMatchIn(it.url.trim()) }
        if (playable.isEmpty()) return 0
        return runCatching { ChannelGrouper.group(playable).size }.getOrDefault(0)
    }

    fun isValid(content: String): Boolean = channelCount(content) > 0
}
