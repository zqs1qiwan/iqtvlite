package com.cjlhll.iptv

import java.time.Instant
import java.time.ZoneId

/**
 * 每源 EPG 刷新统一协调入口（S06）。
 *
 * - 启动 / 周期 / 手动 / 配置后刷新全部经 [tryBegin]/[end] 共享 inFlight，避免并发重复请求。
 * - DAILY 严格按“每天首次启动”：本进程内一旦实际尝试过该源当天启动刷新，后续周期不再按日期触发；
 *   启动被 5 分钟退避暂缓时**不标记已尝试**，退避结束后由周期补做。
 * - STARTUP_6H：启动一次 + 距上次成功 6 小时。
 * - [clock] 可注入用于可控时钟测试。
 */
object EpgRefreshCoordinator {
    @Volatile
    var clock: () -> Long = { System.currentTimeMillis() }

    private val inFlight = HashSet<String>()
    private val launchAttempted = HashSet<String>() // 本进程内该源是否已实际发起过启动/首次刷新
    private val dailySeeded = HashSet<String>()     // DAILY：本进程内该源当天首次启动是否已实际发起
    private val dailyLaunchDay = HashMap<String, Long>() // DAILY：本进程该源启动判定的基准日期
    private val dailySatisfied = HashSet<String>()  // DAILY：本进程该源启动判定已满足（今日已有成功或已实际尝试）
    private val dailyPending = HashSet<String>()    // DAILY：启动被退避暂缓、尚待补做的源（T19）

    @Synchronized
    fun resetForTest() {
        inFlight.clear(); launchAttempted.clear(); dailySeeded.clear(); dailyLaunchDay.clear(); dailySatisfied.clear(); dailyPending.clear()
        clock = { System.currentTimeMillis() }
    }

    @Synchronized
    fun isInFlight(source: String): Boolean = inFlight.contains(source)

    /** 进入请求；已有进行中则返回 false（手动与自动去重）。 */
    @Synchronized
    fun tryBegin(source: String): Boolean {
        if (inFlight.contains(source)) return false
        inFlight.add(source)
        return true
    }

    @Synchronized
    fun end(source: String) { inFlight.remove(source) }

    /** 是否应执行自动刷新（不负责 begin；调用方 tryBegin 成功后调用 markAutoAttempted）。 */
    @Synchronized
    fun shouldRunAuto(
        source: String,
        policy: EpgRefreshPolicy,
        lastSuccess: Long,
        lastAttempt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (policy == EpgRefreshPolicy.MANUAL) return false
        val now = clock()
        val today = epochDay(now, zone)
        val lastDay = if (lastSuccess > 0) epochDay(lastSuccess, zone) else -1L
        return when (policy) {
            EpgRefreshPolicy.MANUAL -> false
            EpgRefreshPolicy.DAILY -> {
                // 分离“启动资格判定”与“实际请求”：本进程首个判定日作为基准日（T01）
                if (dailySatisfied.contains(source)) return false
                val launchDay = dailyLaunchDay.getOrPut(source) { today }
                if (today != launchDay) {
                    // 常驻跨午夜：非 pending 源不得新生 DAILY 请求；
                    // pending 源（启动时被退避暂缓且尚未补做）待退避结束后允许补做一次（T19）
                    if (!dailyPending.contains(source)) return false
                    if (lastAttempt > 0 && now - lastAttempt < RefreshSchedule.RETRY_BACKOFF_MS) return false
                    return true
                }
                if (lastDay == today) {
                    // 启动时今日已有成功记录：完成该次 DAILY 启动判定，跳过且不再因跨午夜触发
                    dailySatisfied.add(source)
                    dailyPending.remove(source)
                    return false
                }
                // 启动/本轮需要刷新：退避暂缓时显式记录 pending 补做资格（不置 satisfied）
                if (lastAttempt > 0 && now - lastAttempt < RefreshSchedule.RETRY_BACKOFF_MS) {
                    dailyPending.add(source)
                    return false
                }
                true
            }
            EpgRefreshPolicy.STARTUP_6H -> {
                if (lastAttempt > 0 && now - lastAttempt < RefreshSchedule.RETRY_BACKOFF_MS) return false
                !launchAttempted.contains(source) || lastSuccess <= 0L || now - lastSuccess >= RefreshSchedule.SIX_HOURS_MS
            }
        }
    }

    /** 实际发起自动刷新后调用：记录本进程启动/当天首次状态与实际尝试。 */
    @Synchronized
    fun markAutoAttempted(source: String, policy: EpgRefreshPolicy) {
        launchAttempted.add(source)
        if (policy == EpgRefreshPolicy.DAILY) {
            dailySeeded.add(source)
            dailySatisfied.add(source)
            dailyPending.remove(source)
        }
    }

    private fun epochDay(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()
}
