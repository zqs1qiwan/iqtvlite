package com.cjlhll.iptv

/**
 * t15：把数据源字节流按 TS 包（188B）切分，做**实验 A/B** 的原地变换，并统计时间轴诊断。
 *
 * 纯逻辑（无 Android import），`JingcaiTimelineTest` 可直接喂合成包验证：
 * - A：丢弃“仅 PCR、无 payload”的包（按 PMT 的 pcr_pid）；
 * - B：把 PMT 的 PCR_PID 改写为 0x1FFF 并重算 CRC32；
 * - AUTO：先透传，读到 PCR 与首个视频 PTS 后若偏差 > 60s，则自动开启 A+B；
 * - OFF / E1 / E2 / E3 / C：完全不改字节（只做统计，零影响）。
 *
 * 只有确认了 188 字节对齐才动手；无法对齐时永久透传（健康频道零风险）。
 * 输出用内部 staging，`pop` 可以按任意 maxLen 返回（不要求 maxLen 是 188 的倍数）。
 */
class JingcaiTsPipeline(
    private val mode: JingcaiExperiment.Mode,
    private val logger: (String) -> Unit = {},
) {
    private var buf = ByteArray(JingcaiTimeline.TS_SIZE * 320)
    private var start = 0
    private var end = 0

    private val staging = ByteArray(JingcaiTimeline.TS_SIZE * 64)
    private var stageStart = 0
    private var stageEnd = 0

    private var alignment = ALIGN_UNKNOWN
    private var filtered = 0
    private var rewrotePmt = 0
    // t54：实验 D（剥离 PCR）与 E（PES 时间戳归零）的计数与基准。
    private var strippedPcr = 0
    private var rewrittenPes = 0
    private var basePts90k = -1L
    private var vPts: Long? = null

    var pcrPid: Int = -1
        private set
    var pcrSeconds: Double? = null
        private set
    var ptsSeconds: Double? = null
        private set
    var autoActivated: Boolean = false
        private set
    var modeLabel: String = mode.id
        private set

    private var filtering = mode == JingcaiExperiment.Mode.A
    private var rewritingPmt = mode == JingcaiExperiment.Mode.B
    private var diagLogged = false

    private val auto get() = mode == JingcaiExperiment.Mode.AUTO

    /** 是否对字节流做了任何改写（OFF/E1/E2/E3/C 恒 false）。 */
    val mutates: Boolean
        get() = mode == JingcaiExperiment.Mode.A || mode == JingcaiExperiment.Mode.B || auto

    fun push(src: ByteArray, off: Int, len: Int) {
        ensure(len)
        System.arraycopy(src, off, buf, end, len)
        end += len
    }

    private fun ensure(extra: Int) {
        if (end + extra <= buf.size) return
        val live = end - start
        val need = live + extra
        if (need > buf.size) {
            var cap = buf.size
            while (cap < need) cap *= 2
            buf = buf.copyOf(cap)
        }
        System.arraycopy(buf, start, buf, 0, live)
        start = 0
        end = live
    }

    /** 从内部队列取出可输出的字节写入 dst，返回写入量（可能 0 = 还需要更多输入）。 */
    fun pop(dst: ByteArray, dstOff: Int, maxLen: Int): Int {
        if (maxLen <= 0) return 0
        if (!mutates) {
            val n = minOf(maxLen, end - start)
            System.arraycopy(buf, start, dst, dstOff, n)
            start += n
            return n
        }
        if (stageEnd - stageStart == 0) fillStaging()
        val n = minOf(maxLen, stageEnd - stageStart)
        if (n <= 0) return 0
        System.arraycopy(staging, stageStart, dst, dstOff, n)
        stageStart += n
        return n
    }

    private fun fillStaging() {
        stageStart = 0
        stageEnd = 0
        alignIfNeeded()
        if (alignment == ALIGN_UNKNOWN) return
        if (alignment == ALIGN_OFF) {
            // 无法对齐 → 原样透传（走 staging，保持同一个出口）。
            val n = minOf(staging.size, end - start)
            System.arraycopy(buf, start, staging, 0, n)
            start += n
            stageEnd = n
            return
        }
        var written = 0
        while (written + JingcaiTimeline.TS_SIZE <= staging.size) {
            if (end - start < JingcaiTimeline.TS_SIZE) break
            val p = start
            inspect(p)
            var drop = false
            if (filtering && pcrPid >= 0 && JingcaiTimeline.isPcrOnlyPacket(buf, p, pcrPid)) {
                drop = true
                filtered++
            } else if (rewritingPmt && JingcaiTimeline.packetPid(buf, p) == JingcaiTimeline.PID_PMT) {
                if (JingcaiTimeline.rewritePmtPcrPid(buf, p, JingcaiTimeline.PID_NULL)) rewrotePmt++
            }
            // t54 实验 D：彻底去 PCR —— 丢 PCR-only 包 + 剥离 adaptation field 的 PCR_flag。
            if (!drop && mode == JingcaiExperiment.Mode.D) {
                if (pcrPid >= 0 && JingcaiTimeline.isPcrOnlyPacket(buf, p, pcrPid)) {
                    drop = true
                    filtered++
                } else if (JingcaiTimeline.stripPcrFlag(buf, p)) {
                    strippedPcr++
                }
            }
            // t54 实验 E：PTS/DTS 归零（基准 = 首个带 PTS 的 PES 包，通常是视频）。
            if (!drop && mode == JingcaiExperiment.Mode.E) {
                val pts = JingcaiTimeline.readPesPts(buf, p)
                if (pts != null) {
                    if (basePts90k < 0L) {
                        basePts90k = pts
                        logger("IQTV-JINGCAI mode=E base_pts=${String.format(java.util.Locale.US, "%.3f", pts / 90000.0)}s")
                    }
                    if (JingcaiTimeline.rewritePesTimestamps(buf, p, basePts90k)) {
                        rewrittenPes++
                        if (vPts == null) vPts = JingcaiTimeline.readPesPts(buf, p)
                    }
                }
            }
            start += JingcaiTimeline.TS_SIZE
            if (!drop) {
                System.arraycopy(buf, p, staging, written, JingcaiTimeline.TS_SIZE)
                written += JingcaiTimeline.TS_SIZE
            }
        }
        stageEnd = written
    }

    private fun alignIfNeeded() {
        if (alignment != ALIGN_UNKNOWN) return
        val live = end - start
        if (live < JingcaiTimeline.TS_SIZE * 2) return
        val limit = minOf(live - JingcaiTimeline.TS_SIZE * 2, 8192)
        var i = 0
        while (i <= limit) {
            if ((buf[start + i].toInt() and 0xFF) == JingcaiTimeline.TS_SYNC &&
                (buf[start + i + JingcaiTimeline.TS_SIZE].toInt() and 0xFF) == JingcaiTimeline.TS_SYNC
            ) {
                start += i
                alignment = ALIGN_ALIGNED
                return
            }
            i++
        }
        // 攒够 8KB 还找不到同步 → 放弃改写，永久透传（健康频道零影响）。
        if (live >= 8192 + JingcaiTimeline.TS_SIZE * 2) {
            alignment = ALIGN_OFF
            logger("timeline alignment failed; pass-through")
        }
    }

    /** 统计 + AUTO 判定（只看不改）。 */
    private fun inspect(p: Int) {
        val pid = JingcaiTimeline.packetPid(buf, p)
        if (pcrPid < 0 && pid == JingcaiTimeline.PID_PMT) {
            val v = JingcaiTimeline.pmtPcrPid(buf, p)
            if (v >= 0) pcrPid = v
        }
        if (pcrSeconds == null && pcrPid >= 0 && pid == pcrPid) {
            JingcaiTimeline.readPcr27(buf, p)?.let { pcrSeconds = JingcaiTimeline.pcrSeconds(it) }
        }
        if (ptsSeconds == null && pid != JingcaiTimeline.PID_PMT && pid != JingcaiTimeline.PID_NULL) {
            JingcaiTimeline.readVideoPts(buf, p)?.let { ptsSeconds = JingcaiTimeline.ptsSeconds(it) }
        }
        if (auto && !autoActivated && JingcaiTimeline.needsTimelineFix(pcrSeconds, ptsSeconds)) {
            autoActivated = true
            filtering = true
            rewritingPmt = true
            logger("auto timeline fix activated ${diagnosticLine()}")
        }
        if (!diagLogged && pcrSeconds != null && ptsSeconds != null) {
            diagLogged = true
            logger(diagnosticLine())
        }
    }

    fun diagnosticLine(): String =
        JingcaiTimeline.diagnosticLine(
            mode = if (autoActivated) "auto-on" else modeLabel,
            pcrSeconds = pcrSeconds,
            ptsSeconds = ptsSeconds,
            pcrPid = pcrPid,
            filtered = filtered,
            rewrotePmt = rewrotePmt,
        ) + " strip_pcr=$strippedPcr pes_rewrite=$rewrittenPes vpts=" +
            (vPts?.let { String.format(java.util.Locale.US, "%.3f", it / 90_000.0) } ?: "none")

    fun pendingBytes(): Int = end - start

    /** EOF 时把还没成包/未对齐的尾巴原样吐出，保证不丢字节。 */
    fun drainRaw(dst: ByteArray, dstOff: Int, maxLen: Int): Int {
        val staged = stageEnd - stageStart
        if (staged > 0) {
            val n = minOf(maxLen, staged)
            System.arraycopy(staging, stageStart, dst, dstOff, n)
            stageStart += n
            return n
        }
        val n = minOf(maxLen, end - start)
        if (n <= 0) return 0
        System.arraycopy(buf, start, dst, dstOff, n)
        start += n
        return n
    }

    private companion object {
        const val ALIGN_UNKNOWN = 0
        const val ALIGN_ALIGNED = 1
        const val ALIGN_OFF = -1
    }
}
