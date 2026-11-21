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

    // ── t54：TS Probe（只读解析实际收到的字节，默认关）─────────────────────
    data class PmtStream(val streamType: Int, val pid: Int)

    data class TsProbe(
        val pmtPid: Int,
        val pcrPid: Int,
        val pcrPresent: Boolean,
        val streams: List<PmtStream>,
        val videoPid: Int,
        val audioPid: Int,
        val videoPts90k: Long?,
        val audioPts90k: Long?,
        val pidTop: List<Pair<Int, Int>>,
        val ccErrors: Int,
        val packets: Int,
        // t54（captain 追加）：null 包占比 / PES 声明长度分布 / PMT 间隔
        val nullPackets: Int = 0,
        val pesLenZero: Int = 0,
        val pesLenNonZero: Int = 0,
        val pmtIntervalPackets: Int = -1,
    ) {
        /** null 包占比（%）；PC 对照：睛彩 ≈20%、CCTV ≈1.6%。 */
        val nullRatioPct: Double get() = if (packets <= 0) 0.0 else nullPackets * 100.0 / packets
        val avOffset90k: Long?
            get() = if (videoPts90k != null && audioPts90k != null) videoPts90k - audioPts90k else null
    }

    private fun sec3(v: Long?): String =
        v?.let { String.format(java.util.Locale.US, "%.3f", it / 90_000.0) } ?: "none"

    /** `IQTV-JINGCAI-PROBE pmt_pid=.. pcr_pid=.. pcr=.. streams=.. vpts=.. apts=.. av=.. pid_top=.. cc_err=..` */
    fun probeLine(p: TsProbe): String = buildString {
        append("pmt_pid=").append(p.pmtPid)
        append(" pcr_pid=").append(p.pcrPid)
        append(" pcr=").append(if (p.pcrPresent) "yes" else "no")
        append(" streams=").append(
            p.streams.joinToString(",") { String.format(java.util.Locale.US, "%02x@%04x", it.streamType, it.pid) }
                .ifEmpty { "none" },
        )
        append(" v_pid=").append(p.videoPid).append(" a_pid=").append(p.audioPid)
        append(" vpts=").append(sec3(p.videoPts90k)).append(" apts=").append(sec3(p.audioPts90k))
        append(" av=").append(sec3(p.avOffset90k))
        append(" pid_top=").append(
            p.pidTop.joinToString(",") { String.format(java.util.Locale.US, "%04x:%d", it.first, it.second) },
        )
        append(" cc_err=").append(p.ccErrors).append(" packets=").append(p.packets)
        append(" null_pct=").append(String.format(java.util.Locale.US, "%.2f", p.nullRatioPct))
        append(" pes_len_zero=").append(p.pesLenZero).append(" pes_len_nonzero=").append(p.pesLenNonZero)
        append(" pmt_interval=").append(p.pmtIntervalPackets)
    }

    /** PAT（PID 0）里的第一个 program_map_PID；没有则 -1。 */
    fun patPmtPid(p: ByteArray, off: Int): Int {
        if (packetPid(p, off) != 0x0000 || !payloadUnitStart(p, off)) return -1
        val po = payloadOffset(p, off)
        if (po < 0) return -1
        val s = po + 1 + (p[po].toInt() and 0xFF)
        if (s + 12 >= off + TS_SIZE) return -1
        if ((p[s].toInt() and 0xFF) != 0x00) return -1
        val sectionLength = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val end = s + 3 + sectionLength - 4
        var q = s + 8
        while (q + 3 < end && q + 3 < off + TS_SIZE) {
            val program = ((p[q].toInt() and 0xFF) shl 8) or (p[q + 1].toInt() and 0xFF)
            val pid = ((p[q + 2].toInt() and 0x1F) shl 8) or (p[q + 3].toInt() and 0xFF)
            if (program != 0) return pid
            q += 4
        }
        return -1
    }

    /** 任意 PID 上若承载 PMT section（table_id=0x02），返回 section 起点；否则 -1。 */
    fun pmtSectionAt(p: ByteArray, off: Int): Int {
        if (!payloadUnitStart(p, off) || !hasPayload(p, off)) return -1
        val po = payloadOffset(p, off)
        if (po < 0) return -1
        val s = po + 1 + (p[po].toInt() and 0xFF)
        if (s + 12 >= off + TS_SIZE) return -1
        return if ((p[s].toInt() and 0xFF) == 0x02) s else -1
    }

    /** PMT 里声明的 PCR_PID（不可用 → -1）。 */
    fun pmtPcrPidAt(p: ByteArray, off: Int): Int {
        val s = pmtSectionAt(p, off)
        if (s < 0) return -1
        return ((p[s + 8].toInt() and 0x1F) shl 8) or (p[s + 9].toInt() and 0xFF)
    }

    /** PMT 里的 ES 列表（stream_type + PID）。 */
    fun pmtStreamsAt(p: ByteArray, off: Int): List<PmtStream> {
        val s = pmtSectionAt(p, off)
        if (s < 0) return emptyList()
        val sectionLength = ((p[s + 1].toInt() and 0x0F) shl 8) or (p[s + 2].toInt() and 0xFF)
        val end = s + 3 + sectionLength - 4
        val progInfoLen = ((p[s + 10].toInt() and 0x0F) shl 8) or (p[s + 11].toInt() and 0xFF)
        var q = s + 12 + progInfoLen
        val out = ArrayList<PmtStream>()
        while (q + 4 < end && q + 4 < off + TS_SIZE) {
            val st = p[q].toInt() and 0xFF
            val pid = ((p[q + 1].toInt() and 0x1F) shl 8) or (p[q + 2].toInt() and 0xFF)
            val esInfo = ((p[q + 3].toInt() and 0x0F) shl 8) or (p[q + 4].toInt() and 0xFF)
            out.add(PmtStream(st, pid))
            q += 5 + esInfo
        }
        return out
    }

    /** PES 起始包里的 PTS（33 位，90kHz）；不是 PES 起始/无 PTS → null。 */
    fun readPesPts(p: ByteArray, off: Int): Long? {
        val po = payloadOffset(p, off)
        if (po < 0 || po + 13 >= off + TS_SIZE) return null
        if ((p[po].toInt() and 0xFF) != 0x00 || (p[po + 1].toInt() and 0xFF) != 0x00 ||
            (p[po + 2].toInt() and 0xFF) != 0x01
        ) return null
        val flags = p[po + 7].toInt() and 0xFF
        if ((flags and 0x80) == 0) return null
        if (((flags shr 6) and 0x3) < 2) return null
        return readTsField(p, po + 9)
    }

    private fun readTsField(p: ByteArray, at: Int): Long {
        val b0 = p[at].toLong() and 0xFF
        val b1 = p[at + 1].toLong() and 0xFF
        val b2 = p[at + 2].toLong() and 0xFF
        val b3 = p[at + 3].toLong() and 0xFF
        val b4 = p[at + 4].toLong() and 0xFF
        return (((b0 shr 1) and 0x07) shl 30) or (b1 shl 22) or (((b2 shr 1) and 0x7F) shl 15) or
            (b3 shl 7) or ((b4 shr 1) and 0x7F)
    }

    private fun writeTsField(p: ByteArray, at: Int, prefix: Int, value: Long) {
        val v = value and 0x1FFFFFFFFL
        p[at] = (((prefix and 0xF) shl 4) or (((v shr 30) and 0x07).toInt() shl 1) or 0x01).toByte()
        p[at + 1] = ((v shr 22) and 0xFF).toByte()
        p[at + 2] = (((((v shr 15) and 0x7F).toInt() shl 1) or 0x01)).toByte()
        p[at + 3] = ((v shr 7) and 0xFF).toByte()
        p[at + 4] = (((v and 0x7F).toInt() shl 1) or 0x01).toByte()
    }

    /**
     * t54 实验 E：把 PES 头的 PTS（有 DTS 时连 DTS）减去 [basePts90k]，让时间轴从 ~0 开始。
     * 只改 PTS/DTS 字段（保留 marker bits、33 位掩码、头长度），其余字节不动。
     */
    fun rewritePesTimestamps(p: ByteArray, off: Int, basePts90k: Long): Boolean {
        val po = payloadOffset(p, off)
        if (po < 0 || po + 13 >= off + TS_SIZE) return false
        if ((p[po].toInt() and 0xFF) != 0x00 || (p[po + 1].toInt() and 0xFF) != 0x00 ||
            (p[po + 2].toInt() and 0xFF) != 0x01
        ) return false
        val flags = p[po + 7].toInt() and 0xFF
        if ((flags and 0x80) == 0) return false
        val ptsDts = (flags shr 6) and 0x3
        if (ptsDts < 2) return false
        val pts = readTsField(p, po + 9)
        writeTsField(p, po + 9, ptsDts, (pts - basePts90k).coerceAtLeast(0L))
        if (ptsDts == 3) {
            val dts = readTsField(p, po + 14)
            writeTsField(p, po + 14, 1, (dts - basePts90k).coerceAtLeast(0L))
        }
        return true
    }

    /** t54 实验 D：清掉 adaptation field 里的 PCR_flag（包长不变，那 6 字节变成有效填充）。 */
    fun stripPcrFlag(p: ByteArray, off: Int): Boolean {
        if (!pcrFlag(p, off)) return false
        p[off + 5] = (p[off + 5].toInt() and 0xEF).toByte()
        return true
    }

    /**
     * t54 实验 G：返回第一个「PAT 包（PID 0 + payloadUnitStart + table_id 0x00）」的包起点；
     * 找不到返回 -1。用于丢掉首个 PAT/PMT 之前的前导字节。
     */
    fun firstPatOffset(bytes: ByteArray, len: Int): Int {
        var i = 0
        while (i + TS_SIZE <= len) {
            if ((bytes[i].toInt() and 0xFF) != TS_SYNC) {
                i++
                continue
            }
            if (packetPid(bytes, i) == 0x0000 && payloadUnitStart(bytes, i)) {
                val po = payloadOffset(bytes, i)
                if (po > 0 && po + 1 < i + TS_SIZE) {
                    val s = po + 1 + (bytes[po].toInt() and 0xFF)
                    if (s < i + TS_SIZE && (bytes[s].toInt() and 0xFF) == 0x00) return i
                }
            }
            i += TS_SIZE
        }
        return -1
    }

    /** t54 Probe：只读扫一遍已缓存的字节，产出诊断（不改变任何字节）。 */
    fun probeTs(bytes: ByteArray, len: Int): TsProbe {
        var pmtPid = -1
        var pcrPid = -1
        var pcrPresent = false
        var streams: List<PmtStream> = emptyList()
        var videoPid = -1
        var audioPid = -1
        var videoPts: Long? = null
        var audioPts: Long? = null
        var ccErrors = 0
        var packets = 0
        var nullPackets = 0
        var pesLenZero = 0
        var pesLenNonZero = 0
        var lastPmtPacket = -1
        var pmtGaps = 0
        var pmtGapCount = 0
        val counts = HashMap<Int, Int>()
        val lastCc = HashMap<Int, Int>()
        var i = 0
        while (i + TS_SIZE <= len) {
            if ((bytes[i].toInt() and 0xFF) != TS_SYNC) {
                i++
                continue
            }
            val off = i
            i += TS_SIZE
            packets++
            val pid = packetPid(bytes, off)
            counts[pid] = (counts[pid] ?: 0) + 1
            if (pmtPid < 0) {
                val v = patPmtPid(bytes, off)
                if (v >= 0) pmtPid = v
            }
            if (pcrFlag(bytes, off)) pcrPresent = true
            if (pcrPid < 0 && pcrPresent) {
                val v = pmtPcrPidAt(bytes, off)
                if (v >= 0) pcrPid = v
            }
            if (streams.isEmpty() && pmtSectionAt(bytes, off) >= 0) {
                streams = pmtStreamsAt(bytes, off)
                for (s in streams) when (s.streamType) {
                    0x1B, 0x24, 0x01, 0x02 -> if (videoPid < 0) videoPid = s.pid
                    0x03, 0x04, 0x0F, 0x11 -> if (audioPid < 0) audioPid = s.pid
                }
            }
            if (pid == PID_NULL) nullPackets++
            if (hasPayload(bytes, off)) {
                val cc = bytes[off + 3].toInt() and 0x0F
                val prev = lastCc[pid]
                if (prev != null && cc != ((prev + 1) and 0x0F)) ccErrors++
                lastCc[pid] = cc
            }
            // PES 声明长度分布：睛彩非 0、CCTV 0（PC 对照结论）。
            if (payloadUnitStart(bytes, off)) {
                val po2 = payloadOffset(bytes, off)
                if (po2 >= 0 && po2 + 5 < off + TS_SIZE &&
                    (bytes[po2].toInt() and 0xFF) == 0x00 && (bytes[po2 + 1].toInt() and 0xFF) == 0x00 &&
                    (bytes[po2 + 2].toInt() and 0xFF) == 0x01
                ) {
                    val len = ((bytes[po2 + 4].toInt() and 0xFF) shl 8) or (bytes[po2 + 5].toInt() and 0xFF)
                    if (len == 0) pesLenZero++ else pesLenNonZero++
                }
            }
            if (pmtPid >= 0 && pid == pmtPid) {
                if (lastPmtPacket >= 0) {
                    val gap = packets - lastPmtPacket
                    pmtGaps += gap
                    pmtGapCount++
                }
                lastPmtPacket = packets
            }
            if (videoPts == null && (videoPid < 0 || pid == videoPid)) {
                val pts = readPesPts(bytes, off)
                if (pts != null && (videoPid >= 0 || pid != 0)) videoPts = pts
            }
            if (audioPts == null && audioPid >= 0 && pid == audioPid) {
                audioPts = readPesPts(bytes, off)
            }
        }
        val top = counts.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }
        return TsProbe(
            pmtPid = pmtPid,
            pcrPid = pcrPid,
            pcrPresent = pcrPresent,
            streams = streams,
            videoPid = videoPid,
            audioPid = audioPid,
            videoPts90k = videoPts,
            audioPts90k = audioPts,
            pidTop = top,
            ccErrors = ccErrors,
            packets = packets,
            nullPackets = nullPackets,
            pesLenZero = pesLenZero,
            pesLenNonZero = pesLenNonZero,
            pmtIntervalPackets = if (pmtGapCount > 0) pmtGaps / pmtGapCount else -1,
        )
    }

}
