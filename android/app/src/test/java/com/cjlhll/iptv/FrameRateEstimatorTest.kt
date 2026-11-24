package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** t40：渲染帧间隔 → 真实 fps 推断 + 来源决策（纯 JVM）。 */
class FrameRateEstimatorTest {

    @Test
    fun fpsFromIntervalsMaps40msTo25And16msTo60() {
        val intervals40 = List(10) { 40L }
        assertEquals(25f, FrameRateEstimator.fpsFromIntervals(intervals40)!!, 0.01f)
        val intervals16 = listOf(16L, 17L, 16L, 17L, 16L, 17L, 16L, 17L)
        // 中位数≈16/17ms → ≈60fps
        assertEquals(60f, FrameRateEstimator.fpsFromIntervals(intervals16)!!, 2f)
        val intervals20 = List(10) { 20L }
        assertEquals(50f, FrameRateEstimator.fpsFromIntervals(intervals20)!!, 0.01f)
    }

    @Test
    fun fpsFromIntervalsUsesMedianAndFiltersOutliers() {
        // 30 个 40ms + 2 个异常 1ms → 中位数仍是 40ms → 25fps（抗抖动）。
        val withOutliers = List(30) { 40L } + listOf(1L, 1L)
        assertEquals(25f, FrameRateEstimator.fpsFromIntervals(withOutliers)!!, 0.01f)
    }

    @Test
    fun fpsFromIntervalsReturnsNullOnInsufficientOrInvalidData() {
        assertNull(FrameRateEstimator.fpsFromIntervals(emptyList()))
        assertNull(FrameRateEstimator.fpsFromIntervals(listOf(40L, 40L))) // < MIN_SAMPLES
        assertNull(FrameRateEstimator.fpsFromIntervals(listOf(0L, -1L, 0L, -5L))) // 全非正
        // 异常快（1ms）被钳到 MAX_FPS，异常慢（1000ms）被钳到 MIN_FPS。
        assertEquals(FrameRateEstimator.MAX_FPS, FrameRateEstimator.fpsFromIntervals(List(6) { 1L })!!, 0.01f)
        assertEquals(FrameRateEstimator.MIN_FPS, FrameRateEstimator.fpsFromIntervals(List(6) { 1000L })!!, 0.01f)
    }

    @Test
    fun frameCountWindowInfersRealFps() {
        // 40 帧 / 1000ms = 40fps（模拟 onVideoFrameProcessingOffset 批量上报）。
        assertEquals(40f, FrameRateEstimator.fpsFromFrameCount(40, 1000L)!!, 0.01f)
        assertEquals(25f, FrameRateEstimator.fpsFromFrameCount(25, 1000L)!!, 0.01f)
        assertEquals(60f, FrameRateEstimator.fpsFromFrameCount(100, 1667L)!!, 1f)
        assertNull(FrameRateEstimator.fpsFromFrameCount(0, 1000L))
        assertNull(FrameRateEstimator.fpsFromFrameCount(40, 0L))

        // 滑动窗口：4 批各 25 帧、每批 250ms → 累计 100 帧/1000ms → 100fps? 窗口在 elapsed>=1000 才出值。
        val window = FrameRateEstimator.FrameRateWindow(windowMs = 1000L, minFrames = 10)
        assertNull(window.onFrameBatch(0L, 25))       // 首帧启动窗口
        assertNull(window.onFrameBatch(250L, 25))
        assertNull(window.onFrameBatch(500L, 25))
        assertNull(window.onFrameBatch(750L, 25))
        val fps = window.onFrameBatch(1000L, 25)      // 累计 125 帧 / 1000ms = 125 → 钳到 MAX 120
        assertEquals(FrameRateEstimator.MAX_FPS, fps!!, 0.01f)
    }

    @Test
    fun resolvePrefersFormatThenInferredThenFallback() {
        // 真实 Format fps 优先。
        val fromFormat = FrameRateEstimator.resolve(50f, 25f)
        assertEquals(FrameRateEstimator.Source.FORMAT, fromFormat.source)
        assertEquals(50f, fromFormat.value)
        // 无真实 fps → 推断值。
        val fromInferred = FrameRateEstimator.resolve(-1f, 30f)
        assertEquals(FrameRateEstimator.Source.INFERRED, fromInferred.source)
        assertEquals(30f, fromInferred.value)
        // 都没有 → 25 兜底。
        val fromFallback = FrameRateEstimator.resolve(-1f, null)
        assertEquals(FrameRateEstimator.Source.FALLBACK, fromFallback.source)
        assertEquals(25f, fromFallback.value)
        // NaN 真实值也走推断。
        assertEquals(
            FrameRateEstimator.Source.INFERRED,
            FrameRateEstimator.resolve(Float.NaN, 24f).source,
        )
    }

    @Test
    fun ptsFrameRateTrackerMeasuresExact50And25And5994Fps() {
        val tracker = FrameRateEstimator.PtsFrameRateTracker()
        // 50fps 流（每帧 20_000us），前 6 帧（5 个 delta）尚不足 minSamples=6，第 7 帧出精确 50.0fps
        var pts = 1_000_000L
        var last: Float? = null
        repeat(10) {
            last = tracker.onFramePtsUs(pts)
            pts += 20_000L
        }
        assertEquals(50f, last!!, 0.05f)

        // 切台重置后，25fps 流（每帧 40_000us），掺杂 1 次异常跳变仍稳定 25.0fps
        tracker.reset()
        pts = 0L
        repeat(8) { idx ->
            last = tracker.onFramePtsUs(pts)
            pts += if (idx == 3) 500_000L else 40_000L // 1 次异常跳变自动忽略
        }
        assertEquals(25f, last!!, 0.05f)

        // 59.94fps 流（每帧 16_683us）
        tracker.reset()
        pts = 0L
        repeat(10) {
            last = tracker.onFramePtsUs(pts)
            pts += 16_683L
        }
        assertEquals(59.94f, last!!, 0.2f)
    }
}
