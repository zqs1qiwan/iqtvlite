package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 针对刷新调度、恢复预算与内容校验的状态/并发/策略回归测试（D02/D03/D04/D10）。
 * 使用可控时间戳与真实 M3U 解析，避免仅镜像实现或只断言 token。
 */
class RepairLogicTest {
    private val zone = ZoneId.of("UTC")
    private val hour = 3_600_000L
    private val day = 24 * hour

    // ---- 播放列表刷新策略 ----
    @Test
    fun manualPlaylistPolicyNeverAutoRefreshes() {
        assertFalse(RefreshSchedule.playlistDue(1_000L, 0L, 0L, PlaylistRefreshPolicy.MANUAL, false))
        assertFalse(RefreshSchedule.playlistDue(1_000L, 0L, 0L, PlaylistRefreshPolicy.MANUAL, true))
    }

    @Test
    fun startupOnlyPolicyRefreshesOncePerLaunch() {
        assertTrue(RefreshSchedule.playlistDue(1_000L, 0L, 0L, PlaylistRefreshPolicy.STARTUP_ONLY, false))
        assertFalse(RefreshSchedule.playlistDue(1_000L, 0L, 0L, PlaylistRefreshPolicy.STARTUP_ONLY, true))
    }

    @Test
    fun startup6hRefreshesAtLaunchOrEverySixHours() {
        val now = 100 * hour
        // 启动未尝试 → 刷新
        assertTrue(RefreshSchedule.playlistDue(now, now - 10 * hour, 0L, PlaylistRefreshPolicy.STARTUP_6H, false))
        // 已尝试且成功在 1 小时内 → 不刷新
        assertFalse(RefreshSchedule.playlistDue(now, now - hour, 0L, PlaylistRefreshPolicy.STARTUP_6H, true))
        // 已尝试但距上次成功超过 6 小时 → 刷新
        assertTrue(RefreshSchedule.playlistDue(now, now - 7 * hour, 0L, PlaylistRefreshPolicy.STARTUP_6H, true))
    }

    @Test
    fun failureBackoffBlocksAutoRefreshWithinFiveMinutes() {
        val now = 100 * hour
        // 上次尝试在 2 分钟前 → 退避
        assertFalse(RefreshSchedule.playlistDue(now, 0L, now - 2 * 60_000L, PlaylistRefreshPolicy.STARTUP_6H, false))
        // 6 分钟前 → 允许
        assertTrue(RefreshSchedule.playlistDue(now, 0L, now - 6 * 60_000L, PlaylistRefreshPolicy.STARTUP_6H, false))
    }

    // ---- EPG 刷新策略 ----
    @Test
    fun epgManualNeverAutoRefreshes() {
        assertFalse(RefreshSchedule.epgDue(1_000L, 0L, 0L, EpgRefreshPolicy.MANUAL, false, zone))
    }

    @Test
    fun epgDailyRefreshesOnFirstLaunchOfNewDayOnly() {
        val now = 10 * day + 5 * hour
        // 昨天成功、今天尚未尝试 → 刷新
        assertTrue(RefreshSchedule.epgDue(now, now - day, 0L, EpgRefreshPolicy.DAILY, false, zone))
        // 今天已成功、尚未尝试 → 不刷新
        assertFalse(RefreshSchedule.epgDue(now, now - hour, 0L, EpgRefreshPolicy.DAILY, false, zone))
        // 已尝试过（本进程）→ 不刷新
        assertFalse(RefreshSchedule.epgDue(now, now - day, 0L, EpgRefreshPolicy.DAILY, true, zone))
    }

    @Test
    fun epgStartup6hHonoursSixHourWindow() {
        val now = 100 * hour
        assertTrue(RefreshSchedule.epgDue(now, now - 7 * hour, 0L, EpgRefreshPolicy.STARTUP_6H, true, zone))
        assertFalse(RefreshSchedule.epgDue(now, now - hour, 0L, EpgRefreshPolicy.STARTUP_6H, true, zone))
    }

    // ---- 恢复预算/事件归属 ----
    @Test
    fun recoveryAcceptsOnlyCurrentSessionAndAttempt() {
        // 同一会话同一 attempt → 接受
        assertTrue(PlaybackRecoveryPolicy.accepts(3, 1, 3, 1))
        // 旧会话（用户已换台）→ 拒收
        assertFalse(PlaybackRecoveryPolicy.accepts(2, 0, 3, 0))
        // 旧 attempt（重复/过期事件）→ 拒收
        assertFalse(PlaybackRecoveryPolicy.accepts(3, 0, 3, 1))
    }

    @Test
    fun recoveryExhaustsAfterThreeRecoveries() {
        assertFalse(PlaybackRecoveryPolicy.exhausted(0))
        assertFalse(PlaybackRecoveryPolicy.exhausted(2))
        assertTrue(PlaybackRecoveryPolicy.exhausted(3))
        assertTrue(PlaybackRecoveryPolicy.exhausted(4))
    }

    @Test
    fun prepareTimeoutIsTwelveSeconds() {
        assertEquals(12_000L, PlaybackRecoveryPolicy.PREPARE_TIMEOUT_MS)
    }

    // ---- 内容校验（D10）----
    @Test
    fun validPlaylistWithChannelsPasses() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV1",CCTV1
            http://a/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV2",CCTV2
            http://a/cctv2.m3u8
        """.trimIndent()
        assertTrue(PlaylistValidation.isValid(m3u))
        assertEquals(2, PlaylistValidation.channelCount(m3u))
    }

    @Test
    fun emptyOrGarbagePlaylistIsRejected() {
        assertFalse(PlaylistValidation.isValid(""))
        assertFalse(PlaylistValidation.isValid("#EXTM3U\n\n"))
        assertFalse(PlaylistValidation.isValid("not a playlist at all"))
    }
}
