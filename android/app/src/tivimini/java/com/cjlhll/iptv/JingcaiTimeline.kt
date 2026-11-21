package com.cjlhll.iptv

/**
 * t15（docs/JINGCAI_TIMELINE_DIAGNOSIS.md）：睛彩 PCR/PTS 时间轴错位的**纯逻辑**解析与判定。
 *
 * 实测（captain 抓流）：睛彩 117 的 PCR pid=104 首值 ≈32152.9s，而首个视频 PTS ≈64307.0s →
 * 偏差 ≈32154s（约 8.9 小时），于是样本时间戳相对 PCR 落在“未来”，解码器建得起来但吐不出首帧。
 *
 * 本文件不 import 任何 Android/Compose 类型，全部可 JVM 单测（`JingcaiTimelineTest`）：
 * - TS 包头解析（PID / adaptation_field_control / payload / PCR flag）
 * - PCR 读取（33-bit base*300 + 6-bit ext，27MHz）
 * - PMT 里 PCR_PID 的定位与改写（含 MPEG-2 CRC32 重算）
 * - 首个视频 PES 的 PTS 读取
 * - 偏差判定（>60s 才动手）
 */
object JingcaiTimeline {

    const val TS_SYNC = 0x47
    const val TS_SIZE = 188
    const val PID_PMT = 0x0000
    const val PID_NULL = 0x1FFF

    /** 偏差超过这个秒数才认为“时间轴错位”（自动检测门槛，t15 规格）。 */
    const val MAX_OFFSET_SECONDS = 60.0

    // ── TS 包头 ─────────────────────────────────────────────────────────────
    fun packetPid(p: ByteArray, off: Int): Int =
        ((p[off + 1].toInt() and 0x1F) shl 8) or (p[off + 2].toInt() and 0xFF)

    /** 0=保留 1=仅负载 2=仅适配 3=适配+负载 */
    fun adaptationFieldControl(p: ByteArray, off: Int): Int = (p[off + 3].toInt() shr 4) and 0x3

    fun hasPayload(p: ByteArray, off: Int): Boolean = adaptationFieldControl(p, off).let { it == 1 || it == 3 }
    fun hasAdaptation(p: ByteArray, off: Int): Boolean = adaptationFieldControl(p, off).let { it == 2 || it == 3 }
    fun payloadUnitStart(p: ByteArray, off: Int): Boolean = (p[off + 1].toInt() and 0x40) != 0

    fun pcrFlag(p: ByteArray, off: Int): Boolean {
        if (!hasAdaptation(p, off)) return false
        val afl = p[off + 4].toInt() and 0xFF
        if (afl < 1) return false
        return (p[off + 5].toInt() and 0x10) != 0
    }

    /** PCR（27MHz ticks）；没有 PCR 返回 null。 */
    fun readPcr27(p: ByteArray, off: Int): Long? {
        if (!pcrFlag(p, off)) return null
        val s = off + 6
        if (s + 5 >= p.size) return null
        val b0 = p[s].toLong() and 0xFF
        val b1 = p[s + 1].toLong() and 0xFF
        val b2 = p[s + 2].toLong() and 0xFF
        val b3 = p[s + 3].toLong() and 0xFF
        val b4 = p[s + 4].toLong() and 0xFF
        val b5 = p[s + 5].toLong() and 0xFF
        val base = (b0 shl 25) or (b1 shl 17) or (b2 shl 9) or (b3 shl 1) or (b4 shr 7)
        val ext = ((b4 and 0x01) shl 8) or b5
        return base * 300L + ext
    }

    fun pcrSeconds(pcr27: Long): Double = pcr27 / 27_000_000.0

    /** “仅 PCR、无 payload”的包 —— 实验 A 唯一允许丢弃的包型。 */
    fun isPcrOnlyPacket(p: ByteArray, off: Int, pcrPid: Int): Boolean =
        packetPid(p, off) == pcrPid && hasAdaptation(p, off) && !hasPayload(p, off) && pcrFlag(p, off)

    /** payload 的起始偏移（相对包起点）；无 payload 返回 -1。 */
    fun payloadOffset(p: ByteArray, off: Int): Int {
        if (!hasPayload(p, off)) return -1
        var q = off + 4
        if (hasAdaptation(p, off)) {
            val afl = p[q].toInt() and 0xFF
            q += 1 + afl
        }
        return if (q < off + TS_SIZE) q else -1
    }

    // ── PMT：定位 / 改写 PCR_PID ────────────────────────────────────────────
    /** 返回 section 的起点（绝对偏移）；不是 PMT 包返回 -1。 */
    fun pmtSectionOffset(p: ByteArray, off: Int): Int {
        if (packetPid(p, off) != PID_PMT || !payloadUnitStart(p, off)) return -1
        val po = payloadOffset(p, off)
        if (po < 0) return -1
        val ptr = p[po].toInt() and 0xFF
        val s = po + 1 + ptr
        if (s + 8 >= off + TS_SIZE) return -1
        if ((p[s].toInt() and 0xFF) != 0x02) return -1 // table_id = PMT
        return s
    }

    /** PMT 的 PCR_PID；不是 PMT / 越界返回 -1。 */
    fun pmtPcrPid(p: ByteArray, off: Int): Int {
        val s = pmtSectionOffset(p, off)
        if (s < 0) return -1
        return ((p[s + 8].toInt() and 0x1F) shl 8) or (p[s + 9].toInt() and 0xFF)
    }

    /** section 总长（含 3 字节头）；非法返回 -1。 */
    fun pmtSectionTotalLength(p: ByteArray, off: Int): Int {
        val s = pmtSectionOffset(p, off)
        if (s < 0) return -1
        val sectionLength = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val total = 3 + sectionLength
        if (total < 12) return -1
        return total
    }

    /**
     * 把 PMT 的 PCR_PID 改写成 [newPid] 并重算 CRC32（实验 B）。原地修改，长度不变。
     * 返回 true = 真的改了（且包内完整）。
     */
    fun rewritePmtPcrPid(p: ByteArray, off: Int, newPid: Int = PID_NULL): Boolean {
        val s = pmtSectionOffset(p, off)
        if (s < 0) return false
        val total = pmtSectionTotalLength(p, off)
        if (total < 0) return false
        // 允许 section 跨包时截断：只要整段在同一个包内才改写（PMT 很小，实践总在单包内）。
        if (s + total > off + TS_SIZE) return false
        p[s + 8] = ((p[s + 8].toInt() and 0xE0) or ((newPid shr 8) and 0x1F)).toByte()
        p[s + 9] = (newPid and 0xFF).toByte()
        val crc = crc32Mpeg2(p, s, total - 4)
        p[s + total - 4] = ((crc ushr 24) and 0xFF).toByte()
        p[s + total - 3] = ((crc ushr 16) and 0xFF).toByte()
        p[s + total - 2] = ((crc ushr 8) and 0xFF).toByte()
        p[s + total - 1] = (crc and 0xFF).toByte()
        return true
    }

    /** MPEG-2 CRC32（poly 0x04C11DB7，初值 0xFFFFFFFF，不反转）。 */
    fun crc32Mpeg2(data: ByteArray, start: Int, length: Int): Int {
        var crc = -0x1 // 0xFFFFFFFF
        for (i in start until start + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if ((crc and 0x80000000.toInt()) != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
        }
        return crc
    }

    /** 校验 PMT 包内的 CRC（整段含 CRC 计算后应为 0）。 */
    fun pmtCrcValid(p: ByteArray, off: Int): Boolean {
        val s = pmtSectionOffset(p, off)
        if (s < 0) return false
        val total = pmtSectionTotalLength(p, off)
        if (total < 0 || s + total > off + TS_SIZE) return false
        return crc32Mpeg2(p, s, total) == 0
    }

    // ── 首个视频 PES 的 PTS ────────────────────────────────────────────────
    /** 视频 stream_id 0xE0..0xEF 且带 PTS 时返回 90kHz PTS，否则 null。 */
    fun readVideoPts(p: ByteArray, off: Int): Long? {
        val po = payloadOffset(p, off)
        if (po < 0 || po + 13 >= off + TS_SIZE) return null
        if ((p[po].toInt() and 0xFF) != 0x00 || (p[po + 1].toInt() and 0xFF) != 0x00 ||
            (p[po + 2].toInt() and 0xFF) != 0x01
        ) return null
        val streamId = p[po + 3].toInt() and 0xFF
        if (streamId < 0xE0 || streamId > 0xEF) return null
        val flags = p[po + 7].toInt() and 0xFF
        if ((flags and 0x80) == 0) return null // '10' marker
        val ptsDtsFlags = (flags shr 6) and 0x3
        if (ptsDtsFlags < 2) return null
        val b0 = p[po + 9].toLong() and 0xFF
        val b1 = p[po + 10].toLong() and 0xFF
        val b2 = p[po + 11].toLong() and 0xFF
        val b3 = p[po + 12].toLong() and 0xFF
        val b4 = p[po + 13].toLong() and 0xFF
        return ((b0 shr 1) and 0x07) shl 30 or (b1 shl 22) or (((b2 shr 1) and 0x7F) shl 15) or
            (b3 shl 7) or ((b4 shr 1) and 0x7F)
    }

    fun ptsSeconds(pts90k: Long): Double = pts90k / 90_000.0

    // ── 判定 ───────────────────────────────────────────────────────────────
    /** PTS 与 PCR 的偏差（秒，带符号：>0 表示 PTS 在未来）。 */
    fun offsetSeconds(pcrSeconds: Double, ptsSeconds: Double): Double = ptsSeconds - pcrSeconds

    /** 偏差 > 60s 才认为需要修（自动检测门槛）。任一未知 → false。 */
    fun needsTimelineFix(pcrSeconds: Double?, ptsSeconds: Double?): Boolean {
        if (pcrSeconds == null || ptsSeconds == null) return false
        return kotlin.math.abs(offsetSeconds(pcrSeconds, ptsSeconds)) > MAX_OFFSET_SECONDS
    }

    /** `IQTV-JINGCAI pcr=.. pts=.. offset=.. mode=..` 一行（t15 规格）。 */
    fun diagnosticLine(mode: String, pcrSeconds: Double?, ptsSeconds: Double?, pcrPid: Int, filtered: Int, rewrotePmt: Int): String {
        val pcr = pcrSeconds?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "none"
        val pts = ptsSeconds?.let { String.format(java.util.Locale.US, "%.3f", it) } ?: "none"
        val off = if (pcrSeconds != null && ptsSeconds != null) {
            String.format(java.util.Locale.US, "%.3f", offsetSeconds(pcrSeconds, ptsSeconds))
        } else "none"
        return "pcr=$pcr pts=$pts offset=$off mode=$mode pcr_pid=$pcrPid drop_pcr_only=$filtered rewrite_pmt=$rewrotePmt"
    }
}
