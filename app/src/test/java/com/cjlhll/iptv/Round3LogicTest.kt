package com.cjlhll.iptv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三轮 S01–S06 生产入口级回归测试。
 * 每项都驱动真实生产类（PlaybackAttemptState / AttemptEventRouter / ProbeCoordinator /
 * EpgRepository.loadStructuredCore / EpgRefreshCoordinator），在缺陷实现下这些断言会失败。
 */
class Round3LogicTest {

    // ---------- S01：媒体 attempt 身份 vs 连续失败预算 ----------
    @Test
    fun recoveryAfterReadyCanRecoverAgainInsteadOfDropping() {
        val state = PlaybackAttemptState(maxRecoveries = 3)
        state.newSession()
        // a0 失败 → 应开始恢复 attempt 1
        val d0 = state.onMediaError(0)
        assertTrue(d0 is RecoveryDecision.Recover)
        assertEquals(1, (d0 as RecoveryDecision.Recover).nextAttempt)

        // a1 READY：只重置连续失败预算，不改媒体 attempt
        state.onReady()
        assertEquals(1, state.mediaAttempt)
        assertEquals(0, state.recoveryBudget)

        // a1 再次失败 → 仍应被接受并恢复（旧实现因 attempt 被 READY 归零会 Ignore）
        val d1 = state.onMediaError(1)
        assertTrue(d1 is RecoveryDecision.Recover)
        assertEquals(2, (d1 as RecoveryDecision.Recover).nextAttempt)
    }

    @Test
    fun consecutiveFailuresExhaustBudget() {
        val state = PlaybackAttemptState(maxRecoveries = 3)
        state.newSession()
        assertTrue(state.onMediaError(0) is RecoveryDecision.Recover) // budget1
        assertTrue(state.onMediaError(1) is RecoveryDecision.Recover) // budget2
        assertTrue(state.onMediaError(2) is RecoveryDecision.Recover) // budget3
        assertTrue(state.onMediaError(3) is RecoveryDecision.Terminal) // budget4 > 3
    }

    @Test
    fun staleAttemptIgnored() {
        val state = PlaybackAttemptState()
        state.newSession()
        assertEquals(RecoveryDecision.Ignore, state.onMediaError(5))
    }

    // ---------- S02：事件来源路由 ----------
    @Test
    fun lateEventFromOldPlayerInstanceIsRejected() {
        val router = AttemptEventRouter()
        router.activate("i1-a0-s1") // 旧播放器实例
        assertTrue(router.isCurrent("i1-a0-s1"))
        // 用户切到新媒体（新播放器实例）
        router.activate("i2-a0-s2")
        // 旧实例回调晚到：携带旧 token，必须拒收
        assertFalse(router.isCurrent("i1-a0-s1"))
        assertTrue(router.isCurrent("i2-a0-s2"))
        router.clear()
        assertFalse(router.isCurrent("i2-a0-s2"))
    }

    // ---------- S03：ON_STOP 失效探测 ----------
    @Test
    fun onStopInvalidatesInFlightProbe() = runBlocking {
        val gate = PlaybackIntentGate()
        val coordinator = ProbeCoordinator(gate)
        val intent = gate.newIntent()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var result: Int? = -1
        val job = launch {
            result = coordinator.selectFirstReachable(listOf("a"), intent) { _ ->
                started.complete(Unit)
                release.await()
                50
            }
        }
        started.await()
        // 模拟 ON_STOP：Activity 提升意图并取消 probe
        gate.newIntent()
        release.complete(Unit)
        job.join()
        assertNull(result) // 停播后旧 probe 不得再提交播放
    }

    // ---------- S05：loadStructured 网络/缓存回退 ----------
    private fun xmltv(channelId: String, title: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="$channelId"><display-name>$title</display-name></channel>
          <programme channel="$channelId" start="20260101120000 +0000" stop="20260101130000 +0000"><title>$title</title></programme>
        </tv>
    """.trimIndent().toByteArray()

    @Test
    fun networkFailureFallsBackToSameSourceCache() = runBlocking {
        val cached = EpgData(mapOf("c1" to emptyList()), emptyMap())
        val result = EpgRepository.loadStructuredCore("A", cached, fetcher = { null })
        assertTrue(result is EpgLoadResult.CacheFallback)
        assertEquals("A", result.sourceId)
        assertFalse(result.isNetworkSuccess) // 回退不得记为成功
        assertEquals(cached, result.dataOrNull)
    }

    @Test
    fun networkFailureWithoutCacheIsFailure() = runBlocking {
        val result = EpgRepository.loadStructuredCore("A", null, fetcher = { null })
        assertTrue(result is EpgLoadResult.Failure)
    }

    @Test
    fun parseFailureFallsBackToCacheNotSuccess() = runBlocking {
        val cached = EpgData(mapOf("c1" to emptyList()), emptyMap())
        val result = EpgRepository.loadStructuredCore("A", cached, fetcher = { "not xml".toByteArray() })
        assertTrue(result is EpgLoadResult.CacheFallback)
        assertFalse(result.isNetworkSuccess)
    }

    @Test
    fun successfulNetworkParseIsSuccessWithSourceId() = runBlocking {
        // 注入 parser 规避 JVM 无 android.util.Xml；生产默认仍用 XmlTvParser
        val parser: (ByteArray) -> EpgData = { bytes -> EpgData(mapOf("c-${String(bytes)}" to emptyList()), emptyMap()) }
        val result = EpgRepository.loadStructuredCore("B", null, fetcher = { xmltv("b1", "B频道") }, parser = parser)
        assertTrue(result is EpgLoadResult.Success)
        assertEquals("B", result.sourceId)
        assertTrue(result.isNetworkSuccess)
    }

    @Test
    fun sourcesAandBAreIsolatedInStructuredResult() = runBlocking {
        val parser: (ByteArray) -> EpgData = { bytes -> EpgData(mapOf("ch-${String(bytes)}" to emptyList()), emptyMap()) }
        val a = EpgRepository.loadStructuredCore("A", null, fetcher = { xmltv("a1", "A") }, parser = parser)
        val b = EpgRepository.loadStructuredCore("B", null, fetcher = { xmltv("b1", "B") }, parser = parser)
        assertTrue(a is EpgLoadResult.Success && b is EpgLoadResult.Success)
        assertEquals("A", a.sourceId)
        assertEquals("B", b.sourceId)
        assertFalse(a.dataOrNull == b.dataOrNull) // 不同源数据不混用
    }

    @Test
    fun injectedFetcherNullDoesNotCallRealNetwork() = runBlocking {
        // fetcher 返回 null 即失败；若实现回退真实网络会尝试访问 "A" 并（在无网络/JVM）超时或抛错，
        // 这里只断言返回 Failure 且不产生成功
        val result = EpgRepository.loadStructuredCore("http://invalid.local/epg.xml", null, fetcher = { null })
        assertTrue(result is EpgLoadResult.Failure)
    }

    // ---------- S06：EPG 调度协调 ----------
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun startupBackoffDefersThenCatchesUp() {
        EpgRefreshCoordinator.resetForTest()
        val t0 = 100 * day
        EpgRefreshCoordinator.clock = { t0 }
        val src = "S"
        // 启动时被 5 分钟内退避挡住 → 不执行
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.STARTUP_6H, lastSuccess = 0L, lastAttempt = t0 - 60_000L)
        )
        // 退避结束后 → 补做
        EpgRefreshCoordinator.clock = { t0 + 6 * 60_000L }
        assertTrue(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.STARTUP_6H, lastSuccess = 0L, lastAttempt = t0 - 60_000L)
        )
        EpgRefreshCoordinator.markAutoAttempted(src, EpgRefreshPolicy.STARTUP_6H)
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.STARTUP_6H, lastSuccess = t0, lastAttempt = t0 + 6 * 60_000L)
        )
    }

    @Test
    fun dailyDoesNotRefreshAgainWhenResidentAcrossMidnight() {
        EpgRefreshCoordinator.resetForTest()
        val t0 = 100 * day + 5 * hour
        EpgRefreshCoordinator.clock = { t0 }
        val src = "S"
        assertTrue(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = 0L, lastAttempt = 0L)
        )
        EpgRefreshCoordinator.markAutoAttempted(src, EpgRefreshPolicy.DAILY)
        // 跨到第二天，但本进程未重启 → 不再按日期触发（“每天首次启动”语义）
        EpgRefreshCoordinator.clock = { t0 + day }
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0, lastAttempt = t0)
        )
    }

    @Test
    fun manualAndAutoShareInFlight() {
        EpgRefreshCoordinator.resetForTest()
        val src = "S"
        assertTrue(EpgRefreshCoordinator.tryBegin(src))   // 自动开始
        assertFalse(EpgRefreshCoordinator.tryBegin(src))  // 手动被去重
        EpgRefreshCoordinator.end(src)
        assertTrue(EpgRefreshCoordinator.tryBegin(src))   // 结束后可再请求
        EpgRefreshCoordinator.end(src)
    }

    // ---------- T01：DAILY 启动判定与补做（可控时钟） ----------
    @Test
    fun t01_todaySuccessStartupSkipsAndNoMidnightRefresh() {
        EpgRefreshCoordinator.resetForTest()
        val t0 = 100 * day + 5 * hour
        EpgRefreshCoordinator.clock = { t0 }
        val src = "S"
        // 今日已有成功记录（上一个进程/手动）：启动应完成判定并跳过，不发起请求
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0 - hour, lastAttempt = 0L)
        )
        // 保持常驻跨午夜：本进程已满足 DAILY 启动判定，不刷新
        EpgRefreshCoordinator.clock = { t0 + day }
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0 - hour, lastAttempt = 0L)
        )
    }

    @Test
    fun t01_oldSuccessStartupBackoffThenCatchUp() {
        EpgRefreshCoordinator.resetForTest()
        val t0 = 100 * day + 5 * hour
        EpgRefreshCoordinator.clock = { t0 }
        val src = "S"
        // 旧成功 + 刚刚尝试过（退避中）：启动暂缓，保留补做资格
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0 - day, lastAttempt = t0 - 60_000L)
        )
        // 退避结束后补做
        EpgRefreshCoordinator.clock = { t0 + 6 * 60_000L }
        assertTrue(
            EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0 - day, lastAttempt = t0 - 60_000L)
        )
        EpgRefreshCoordinator.markAutoAttempted(src, EpgRefreshPolicy.DAILY)
    }

    @Test
    fun t01_catchUpDoneThenNoMidnightRefresh() {
        EpgRefreshCoordinator.resetForTest()
        val t0 = 100 * day + 5 * hour
        EpgRefreshCoordinator.clock = { t0 }
        val src = "S"
        assertTrue(EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0 - day, lastAttempt = 0L))
        EpgRefreshCoordinator.markAutoAttempted(src, EpgRefreshPolicy.DAILY) // 补做完成
        EpgRefreshCoordinator.clock = { t0 + day }
        assertFalse(EpgRefreshCoordinator.shouldRunAuto(src, EpgRefreshPolicy.DAILY, lastSuccess = t0, lastAttempt = t0))
    }

    // ---------- T02：捕获按键状态机 ----------
    @Test
    fun t02_captureSubmitsOnceOnMatchingDownUp() {
        val state = CaptureKeyState()
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = true, keyCode = 19))
        // 重复 KeyDown 不改变待提交键
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = true, keyCode = 20, repeatCount = 3))
        assertEquals(CaptureKeyResult.Submit(19), state.onKey(isKeyDown = false, keyCode = 19))
        // 已提交后，再次 Up 不会重复提交
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = false, keyCode = 19))
    }

    @Test
    fun t02_captureConsumesDirectionsAndCancel() {
        val state = CaptureKeyState()
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = true, keyCode = 21))
        assertEquals(CaptureKeyResult.Submit(21), state.onKey(isKeyDown = false, keyCode = 21))
        assertEquals(CaptureKeyResult.Cancel, state.onKey(isKeyDown = true, keyCode = CaptureKeyState.BACK_KEYCODE))
    }

    @Test
    fun t02_captureIgnoresMismatchedUpBetweenPresses() {
        val state = CaptureKeyState()
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = true, keyCode = 19))
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = false, keyCode = 20)) // 不匹配
        assertEquals(CaptureKeyResult.Consume, state.onKey(isKeyDown = false, keyCode = 19)) // 已无待提交
    }

    // ---------- T19：跨午夜 pending 补做 ----------
    @Test
    fun t19_pendingDeferredAcrossMidnightCatchesUpThenNoRefresh() {
        EpgRefreshCoordinator.resetForTest()
        val baseDay = 100 * day
        val at2358 = baseDay + 23 * hour + 58 * 60_000L
        val lastSuccessYesterday = baseDay - 6 * hour
        val lastAttempt2355 = at2358 - 3 * 60_000L // 23:55，距 23:58 仅 3 分钟，触发退避

        // 23:58 启动评估：退避暂缓 → false 且记录 pending
        EpgRefreshCoordinator.clock = { at2358 }
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto("S", EpgRefreshPolicy.DAILY, lastSuccessYesterday, lastAttempt2355)
        )

        // 次日 00:03：退避已结束（距 23:55 已 8 分钟），pending 允许补做一次
        val at0003NextDay = baseDay + day + 3 * 60_000L
        EpgRefreshCoordinator.clock = { at0003NextDay }
        assertTrue(
            EpgRefreshCoordinator.shouldRunAuto("S", EpgRefreshPolicy.DAILY, lastSuccessYesterday, lastAttempt2355)
        )
        // 实际发起后清 pending 并完成判定
        EpgRefreshCoordinator.markAutoAttempted("S", EpgRefreshPolicy.DAILY)

        // 再跨一个午夜：已满足，不再刷新
        EpgRefreshCoordinator.clock = { at0003NextDay + day }
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto("S", EpgRefreshPolicy.DAILY, lastSuccessYesterday, lastAttempt2355)
        )
    }

    @Test
    fun t19_nonPendingSourceAcrossMidnightDoesNotStartNewDaily() {
        EpgRefreshCoordinator.resetForTest()
        val baseDay = 100 * day
        val start = baseDay + 10 * hour
        EpgRefreshCoordinator.clock = { start }
        // 今日已有成功 → 启动完成判定（非 pending）
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto("S", EpgRefreshPolicy.DAILY, lastSuccess = start - hour, lastAttempt = 0L)
        )
        // 跨午夜：非 pending 源不得新生请求
        EpgRefreshCoordinator.clock = { baseDay + day + 10 * hour }
        assertFalse(
            EpgRefreshCoordinator.shouldRunAuto("S", EpgRefreshPolicy.DAILY, lastSuccess = start - hour, lastAttempt = 0L)
        )
    }
}
