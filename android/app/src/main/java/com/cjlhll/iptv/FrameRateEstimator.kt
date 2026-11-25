package com.cjlhll.iptv

/**
 * t40：渲染帧间隔 → 真实帧率推断（纯 Kotlin，Android-free，可 JVM 单测）。
 *
 * 这组直播组播 TS 的 Media3 `Format.frameRate = -1`（NO_VALUE），传递链无 bug（见 FPS_DIAGNOSIS.md），
 * 所以改为统计**实际渲染帧**的间隔来推断真实 fps。
 * 只保留一个滑动窗口的间隔值；调用方（Metrics.onVideoFrameRendered）负责采集，低开销。
 */
object FrameRateEstimator {

    /** 滑动窗口最大帧间隔样本数（约 30 帧 ≈ 1 秒 @30fps，0.5 秒 @60fps）。 */
    const val MAX_SAMPLES = 30

    /**
     * 由帧间隔（毫秒）列表推断 fps。
     * - 用中位数间隔（抗单帧抖动/丢帧），fps = 1000 / medianMs；
     * - 空 / 全非正 / 样本 < [MIN_SAMPLES] → null（数据不足，调用方回退 25）；
     * - 结果钳到 [MIN_FPS, MAX_FPS]，过滤异常（如 1ms 间隔的 1000fps）。
     */
    const val MIN_SAMPLES = 4
    const val MIN_FPS = 12f
    const val MAX_FPS = 120f

    fun fpsFromIntervals(intervalsMs: List<Long>): Float? {
        val valid = intervalsMs.filter { it > 0 }
        if (valid.size < MIN_SAMPLES) return null
        val sorted = valid.sorted()
        // 用 Double 求中位数，保留 16.5ms 这类半值，避免整数除法把 1ms 截成 0。
        val medianMs = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2].toDouble()
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        if (medianMs <= 0.0) return null
        val raw = (1000.0 / medianMs).toFloat()
        return raw.coerceIn(MIN_FPS, MAX_FPS)
    }

    /** 帧率最终取值来源（供 DEBUG 日志 source=format|inferred|fallback）。 */
    enum class Source { FORMAT, INFERRED, FALLBACK }

    data class Resolution(val source: Source, val value: Float)

    /**
     * 最终帧率决策：真实 Format.frameRate>0 → format；否则推断值>0 → inferred；否则 [fallback]（25）。
     * 两者都 >0 时真实值优先（t40 要求）。
     */
    fun resolve(formatFps: Float, inferredFps: Float?, fallback: Float = 25f): Resolution = when {
        formatFps > 0f && !formatFps.isNaN() -> Resolution(Source.FORMAT, formatFps)
        inferredFps != null && inferredFps > 0f && !inferredFps.isNaN() -> Resolution(Source.INFERRED, inferredFps)
        else -> Resolution(Source.FALLBACK, fallback)
    }

    /**
     * t40：由「一段时间窗口内处理的帧数」推断 fps（对应 Media3 onVideoFrameProcessingOffset 的
     * frameCount 增量）。fps = frames * 1000 / elapsedMs。样本不足/时间非法 → null。
     */
    fun fpsFromFrameCount(frames: Int, elapsedMs: Long): Float? {
        if (frames <= 0 || elapsedMs <= 0) return null
        val raw = frames * 1000f / elapsedMs
        if (raw.isNaN()) return null
        return raw.coerceIn(MIN_FPS, MAX_FPS)
    }

    /** 中位数映射的便捷封装：40ms→25fps、16/17ms→≈60fps（取整交给上层 frameRateLabel）。 */
    fun infer(intervalsMs: List<Long>): Float? = fpsFromIntervals(intervalsMs)

    /**
     * t40：累积帧计数滑动窗口（供 StreamPlayback.Metrics.onVideoFrameProcessingOffset 使用）。
     * onVideoFrameProcessingOffset 给的 frameCount 是**自上次回调以来的增量**（不是累积总数），
     * 这里按 ~1 秒时间窗累计帧数与时长，窗口满/超时后算一次 fps（取最近窗口，抗稳态抖动）。
     * 只用几个 long，无集合分配；调用在 ExoPlayer 播放线程，不占主线程。
     */
    class FrameRateWindow(
        private val windowMs: Long = 1_000L,
        private val minFrames: Int = 10,
    ) {
        private var windowStart = -1L // -1 表示未启动（elapsedRealtime 可能为 0）
        private var frames = 0

        /**
         * @param frameDelta onVideoFrameProcessingOffset 的 frameCount —— Media3 语义为
         *   "自上次回调以来处理的帧数"（每批增量，渲染器每次上报后清零），直接累加即可。
         */
        fun onFrameBatch(nowMs: Long, frameDelta: Int): Float? {
            if (frameDelta <= 0) return null
            if (windowStart < 0L) { windowStart = nowMs; frames = 0 }
            frames += frameDelta
            val elapsed = nowMs - windowStart
            if (elapsed >= windowMs) {
                val fps = if (frames >= minFrames) fpsFromFrameCount(frames, elapsed) else null
                windowStart = nowMs // 开新窗
                frames = 0
                return fps
            }
            return null
        }

        fun reset() { windowStart = -1L; frames = 0 }
    }

    /**
     * 基于渲染帧 PTS 的真实帧率测量器（供 ExoPlayer `VideoFrameMetadataListener.onVideoFrameAboutToBeRendered` 使用）：
     * - 直接读取每一帧的精确解码时间戳 `presentationTimeUs`（微秒），计算相邻帧间隔滑动中位数；
     * - 零集合分配（固定 LongArray 环形缓冲），彻底免疫主线程抖动与系统时钟漂移；
     * - 6 帧内（50fps 约 0.12s，25fps 约 0.24s）即可输出精确真实帧率（24 / 25 / 30 / 50 / 60fps）。
     */
    class PtsFrameRateTracker(
        private val windowSize: Int = 16,
        private val minSamples: Int = 6,
    ) {
        private val deltasUs = LongArray(windowSize)
        private val scratch = LongArray(windowSize)
        private var lastPtsUs = Long.MIN_VALUE
        private var count = 0
        private var writePos = 0
        private var cachedFps: Float? = null
        private var framesSinceSort = 0

        fun onFramePtsUs(ptsUs: Long): Float? {
            val prev = lastPtsUs
            lastPtsUs = ptsUs
            if (prev == Long.MIN_VALUE) return null
            val delta = ptsUs - prev
            // 有效帧间隔：6ms (166fps) .. 100ms (10fps)，自动过滤切流跳变/重复帧/seek
            if (delta !in 6_000L..100_000L) return null
            deltasUs[writePos] = delta
            writePos = (writePos + 1) % windowSize
            if (count < windowSize) count++
            if (count < minSamples) return null
            framesSinceSort++
            if (count >= windowSize && cachedFps != null && framesSinceSort < windowSize) {
                return cachedFps
            }
            framesSinceSort = 0
            for (i in 0 until count) scratch[i] = deltasUs[i]
            java.util.Arrays.sort(scratch, 0, count)
            val medianUs = if (count % 2 == 1) {
                scratch[count / 2].toDouble()
            } else {
                (scratch[count / 2 - 1] + scratch[count / 2]) / 2.0
            }
            if (medianUs <= 0.0) return null
            val fps = (1_000_000.0 / medianUs).toFloat().coerceIn(MIN_FPS, MAX_FPS)
            cachedFps = fps
            return fps
        }

        fun reset() {
            lastPtsUs = Long.MIN_VALUE
            count = 0
            writePos = 0
            cachedFps = null
            framesSinceSort = 0
        }
    }
}
