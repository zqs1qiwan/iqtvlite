package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t15（docs/JINGCAI_TIMELINE_DIAGNOSIS.md）：PCR/PTS 时间轴解析与实验 A/B 的纯逻辑回归。
 * 全部用**合成 TS 包**验证，不需要设备/网络。
 */
class JingcaiTimelineTest {

    // ── 合成包工具 ─────────────────────────────────────────────────────────
    private fun packet(pid: Int, afc: Int, cc: Int = 0, payload: ByteArray? = null, adaptation: ByteArray? = null, pusi: Boolean = false): ByteArray {
        val p = ByteArray(JingcaiTimeline.TS_SIZE) { 0xFF.toByte() }
        p[0] = 0x47
        p[1] = ((if (pusi) 0x40 else 0x00) or ((pid shr 8) and 0x1F)).toByte()
        p[2] = (pid and 0xFF).toByte()
        p[3] = ((afc shl 4) or (cc and 0x0F)).toByte()
        var q = 4
        if (adaptation != null) {
            p[q++] = adaptation.size.toByte()
            System.arraycopy(adaptation, 0, p, q, adaptation.size)
            q += adaptation.size
        }
        if (payload != null) System.arraycopy(payload, 0, p, q, payload.size)
        return p
    }

    /** adaptation field with PCR flag. */
    private fun adaptationWithPcr(pcr27: Long): ByteArray {
        val a = ByteArray(7)
        a[0] = 0x10 // PCR_flag
        val base = pcr27 / 300
        val ext = (pcr27 % 300).toInt()
        a[1] = ((base shr 25) and 0xFF).toByte()
        a[2] = ((base shr 17) and 0xFF).toByte()
        a[3] = ((base shr 9) and 0xFF).toByte()
        a[4] = ((base shr 1) and 0xFF).toByte()
        a[5] = (((base and 0x1) shl 7) or ((ext shr 8) and 0x01)).toByte()
        a[6] = (ext and 0xFF).toByte()
        return a
    }

    private fun pmtPacket(pcrPid: Int): ByteArray {
        val sec = ByteArray(16)
        sec[0] = 0x02
        sec[1] = 0xB0.toByte()
        sec[2] = 0x0D
        sec[3] = 0x00; sec[4] = 0x01
        sec[5] = 0xC1.toByte(); sec[6] = 0x00; sec[7] = 0x00
        sec[8] = ((pcrPid shr 8) and 0x1F).toByte(); sec[9] = (pcrPid and 0xFF).toByte()
        sec[10] = 0xF0.toByte(); sec[11] = 0x00
        val crc = JingcaiTimeline.crc32Mpeg2(sec, 0, 12)
        sec[12] = ((crc ushr 24) and 0xFF).toByte()
        sec[13] = ((crc ushr 16) and 0xFF).toByte()
        sec[14] = ((crc ushr 8) and 0xFF).toByte()
        sec[15] = (crc and 0xFF).toByte()
        val payload = ByteArray(1 + sec.size)
        payload[0] = 0x00 // pointer_field
        System.arraycopy(sec, 0, payload, 1, sec.size)
        return packet(JingcaiTimeline.PID_PMT, afc = 1, payload = payload, pusi = true)
    }

    private fun videoPesWithPts(pid: Int, pts: Long): ByteArray {
        val payload = ByteArray(14)
        payload[0] = 0x00; payload[1] = 0x00; payload[2] = 0x01
        payload[3] = 0xE0.toByte() // video stream 0
        payload[4] = 0x00; payload[5] = 0x00 // packet_length (unused)
        payload[6] = 0x80.toByte()          // '10' marker
        payload[7] = 0x80.toByte()          // PTS_DTS_flags = '10' (PTS only)
        payload[8] = 0x05                    // header data length
        payload[9] = ((0x02 shl 4) or (((pts shr 30) and 0x07).toInt() shl 1) or 0x01).toByte()
        payload[10] = ((pts shr 22) and 0xFF).toByte()
        payload[11] = ((((pts shr 15) and 0x7F) shl 1) or 0x01).toByte()
        payload[12] = ((pts shr 7) and 0xFF).toByte()
        payload[13] = ((((pts and 0x7F) shl 1)) or 0x01).toByte()
        return packet(pid, afc = 1, payload = payload, pusi = true)
    }

    // ── 包头 / PCR ─────────────────────────────────────────────────────────
    @Test
    fun parsesTsHeaderFields() {
        val p = packet(0x0104, afc = 2, adaptation = adaptationWithPcr(27_000_000L * 3))
        assertEquals(0x0104, JingcaiTimeline.packetPid(p, 0))
        assertEquals(2, JingcaiTimeline.adaptationFieldControl(p, 0))
        assertFalse(JingcaiTimeline.hasPayload(p, 0))
        assertTrue(JingcaiTimeline.hasAdaptation(p, 0))
        assertTrue(JingcaiTimeline.pcrFlag(p, 0))
    }

    @Test
    fun readsPcrValueInSeconds() {
        // 32152.9s 的 PCR（captain 实测的睛彩 117 首值量级）。
        val pcr27 = (32152.9 * 27_000_000.0).toLong()
        val p = packet(0x0104, afc = 2, adaptation = adaptationWithPcr(pcr27))
        val got = JingcaiTimeline.readPcr27(p, 0)
        assertNotNull(got)
        assertTrue(Math.abs(JingcaiTimeline.pcrSeconds(got!!) - 32152.9) < 0.01)
    }

    @Test
    fun identifiesPcrOnlyPacketsPrecisely() {
        val pcr27 = 27_000_000L * 10
        val only = packet(0x0104, afc = 2, adaptation = adaptationWithPcr(pcr27))
        assertTrue(JingcaiTimeline.isPcrOnlyPacket(only, 0, 0x0104))
        assertFalse("别的 pid 不算", JingcaiTimeline.isPcrOnlyPacket(only, 0, 0x0200))
        // 带 payload 的包不能被当成 PCR-only 丢掉
        val withPayload = packet(0x0104, afc = 3, adaptation = adaptationWithPcr(pcr27), payload = ByteArray(10))
        assertFalse(JingcaiTimeline.isPcrOnlyPacket(withPayload, 0, 0x0104))
    }

    // ── PMT / CRC ─────────────────────────────────────────────────────────
    @Test
    fun readsPmtPcrPidAndValidatesCrc() {
        val p = pmtPacket(104)
        assertEquals(104, JingcaiTimeline.pmtPcrPid(p, 0))
        assertTrue("合成 PMT 的 CRC 必须自洽", JingcaiTimeline.pmtCrcValid(p, 0))
    }

    @Test
    fun rewritesPmtPcrPidToNullPidAndFixesCrc() {
        val p = pmtPacket(104)
        assertTrue(JingcaiTimeline.rewritePmtPcrPid(p, 0, JingcaiTimeline.PID_NULL))
        assertEquals("PCR_PID 必须变成 0x1FFF", 0x1FFF, JingcaiTimeline.pmtPcrPid(p, 0))
        assertTrue("改写后 CRC32 必须仍然有效", JingcaiTimeline.pmtCrcValid(p, 0))
        // 幂等：再改一次仍是有效的 0x1FFF
        assertTrue(JingcaiTimeline.rewritePmtPcrPid(p, 0, JingcaiTimeline.PID_NULL))
        assertTrue(JingcaiTimeline.pmtCrcValid(p, 0))
    }

    @Test
    fun crc32Mpeg2MatchesKnownVectorOnSectionRoundTrip() {
        val p = pmtPacket(0x0100)
        val s = JingcaiTimeline.pmtSectionOffset(p, 0)
        val total = JingcaiTimeline.pmtSectionTotalLength(p, 0)
        assertTrue(s >= 0 && total > 0)
        assertEquals("data||crc 的 MPEG-2 CRC 必须为 0", 0, JingcaiTimeline.crc32Mpeg2(p, s, total))
    }

    // ── PES PTS ───────────────────────────────────────────────────────────
    @Test
    fun readsFirstVideoPts() {
        val pts = 64307L * 90_000 // 64307.0s
        val p = videoPesWithPts(0x0100, pts)
        val got = JingcaiTimeline.readVideoPts(p, 0)
        assertNotNull(got)
        assertEquals(pts, got!!)
        assertTrue(Math.abs(JingcaiTimeline.ptsSeconds(got) - 64307.0) < 0.001)
    }

    @Test
    fun ignoresAudioAndNonPesPackets() {
        val audio = videoPesWithPts(0x0101, 90_000L).copyOf()
        audio[4 + 3] = 0xC0.toByte() // stream_id 0xC0 (audio)
        assertNull("音频 PES 的 PTS 不当成视频 PTS", JingcaiTimeline.readVideoPts(audio, 0))
        val nullPacket = packet(0x1FFF, afc = 1, payload = ByteArray(184))
        assertNull(JingcaiTimeline.readVideoPts(nullPacket, 0))
    }

    // ── 偏差判定 ──────────────────────────────────────────────────────────
    @Test
    fun offsetJudgementUsesTheSixtySecondGate() {
        assertEquals(32154.1, JingcaiTimeline.offsetSeconds(32152.9, 64307.0), 0.001)
        assertTrue("睛彩偏差 8.9 小时 → 必须判定需要修复", JingcaiTimeline.needsTimelineFix(32152.9, 64307.0))
        assertFalse("CCTV-1 量级一致 → 不该动它", JingcaiTimeline.needsTimelineFix(1000.0, 1003.0))
        assertFalse("未知值 → 不动", JingcaiTimeline.needsTimelineFix(null, 64307.0))
        assertFalse(JingcaiTimeline.needsTimelineFix(32152.9, null))
        assertTrue("负向偏差 >60s 也要修", JingcaiTimeline.needsTimelineFix(64307.0, 32152.9))
    }

    // ── 管线：A 丢 PCR-only / OFF 零改动 ──────────────────────────────────
    @Test
    fun experimentADropsOnlyPcrOnlyPackets() {
        val pcrPid = 0x0104
        val packets = listOf(
            pmtPacket(pcrPid),
            packet(pcrPid, afc = 2, adaptation = adaptationWithPcr(32152L * 27_000_000)),
            videoPesWithPts(0x0100, 64307L * 90_000),
            packet(pcrPid, afc = 2, adaptation = adaptationWithPcr(32153L * 27_000_000)),
        )
        val input = packets.reduce { a, b -> a + b }
        val pipeA = JingcaiTsPipeline(JingcaiExperiment.Mode.A)
        pipeA.push(input, 0, input.size)
        val out = ByteArray(input.size)
        val n = pipeA.pop(out, 0, out.size)
        assertEquals("两个 PCR-only 包必须被丢", (packets.size - 2) * JingcaiTimeline.TS_SIZE, n)
        assertNotNull("必须解析到 PCR", pipeA.pcrSeconds)
        assertTrue(pipeA.diagnosticLine().contains("mode=a"))
        assertTrue("应记录丢弃数量", pipeA.diagnosticLine().contains("drop_pcr_only=2"))
    }

    @Test
    fun experimentOffIsByteForBytePassThrough() {
        val pcrPid = 0x0104
        val input = pmtPacket(pcrPid) +
            packet(pcrPid, afc = 2, adaptation = adaptationWithPcr(32152L * 27_000_000)) +
            videoPesWithPts(0x0100, 64307L * 90_000)
        val pipeOff = JingcaiTsPipeline(JingcaiExperiment.Mode.OFF)
        val out = ByteArray(input.size)
        var n = pipeOff.pop(out, 0, out.size)
        if (n == 0) {
            pipeOff.push(input, 0, input.size)
            n = pipeOff.pop(out, 0, out.size)
        } else {
            pipeOff.push(input, 0, input.size)
        }
        assertEquals("OFF 模式必须原样透传（健康频道零影响）", input.size, n)
        assertTrue(out.copyOf(n).contentEquals(input))
        assertFalse(pipeOff.mutates)
    }

    @Test
    fun experimentBOnlyRewritesPmt() {
        val pcrPid = 0x0104
        val input = pmtPacket(pcrPid) + videoPesWithPts(0x0100, 64307L * 90_000)
        val pipeB = JingcaiTsPipeline(JingcaiExperiment.Mode.B)
        pipeB.push(input, 0, input.size)
        val out = ByteArray(input.size)
        val n = pipeB.pop(out, 0, out.size)
        assertEquals("B 不丢包，只改字节", input.size, n)
        // 输出里的 PMT PCR_PID 必须已是 0x1FFF
        assertEquals(0x1FFF, JingcaiTimeline.pmtPcrPid(out, 0))
        assertTrue(JingcaiTimeline.pmtCrcValid(out, 0))
        assertTrue(pipeB.diagnosticLine().contains("mode=b"))
    }

    @Test
    fun autoModeActivatesOnlyWhenOffsetExceedsSixtySeconds() {
        val pcrPid = 0x0104
        // 偏差 ~8.9 小时 → AUTO 必须自己开启修复
        val bad = pmtPacket(pcrPid) +
            packet(pcrPid, afc = 2, adaptation = adaptationWithPcr(32152L * 27_000_000)) +
            videoPesWithPts(0x0100, 64307L * 90_000) +
            packet(pcrPid, afc = 2, adaptation = adaptationWithPcr(32153L * 27_000_000))
        val pipeAuto = JingcaiTsPipeline(JingcaiExperiment.Mode.AUTO)
        pipeAuto.push(bad, 0, bad.size)
        val out = ByteArray(bad.size)
        val n = pipeAuto.pop(out, 0, out.size)
        assertTrue("偏差 >60s，AUTO 应自动开启修复", pipeAuto.autoActivated)
        assertEquals("开启后 PCR-only 包被丢", bad.size - JingcaiTimeline.TS_SIZE, n)
        assertTrue(pipeAuto.diagnosticLine().contains("mode=auto-on"))

        // 健康流 → AUTO 保持透传
        val good = pmtPacket(pcrPid) +
            packet(pcrPid, afc = 2, adaptation = adaptationWithPcr(1000L * 27_000_000)) +
            videoPesWithPts(0x0100, 1003L * 90_000)
        val pipeGood = JingcaiTsPipeline(JingcaiExperiment.Mode.AUTO)
        pipeGood.push(good, 0, good.size)
        val out2 = ByteArray(good.size)
        val n2 = pipeGood.pop(out2, 0, out2.size)
        assertFalse("健康流不该被改", pipeGood.autoActivated)
        assertEquals(good.size, n2)
    }

    @Test
    fun misalignedStreamFallsBackToPassThrough() {
        // 没有 0x47 同步 → 永不改写，严格透传
        val junk = ByteArray(JingcaiTimeline.TS_SIZE * 60) { 0x11 }
        val pipe = JingcaiTsPipeline(JingcaiExperiment.Mode.A)
        pipe.push(junk, 0, junk.size)
        val out = ByteArray(junk.size)
        val n = pipe.pop(out, 0, out.size)
        assertEquals(junk.size, n)
        assertTrue(out.copyOf(n).contentEquals(junk))
    }
}
