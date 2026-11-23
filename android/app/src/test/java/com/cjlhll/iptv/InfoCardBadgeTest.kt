package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t24（docs/INFO_CARD_V3.md §10.1）：信息卡 v3 的纯逻辑 —— 分辨率档 / 帧率 / 码率 / 声道 /
 * 徽标优先级与溢出 / 技术行编码映射 / 节目行 / 小编号行。全部 Android-free，可 JVM 直测。
 */
class InfoCardBadgeTest {

    // ── 分辨率档边界 ───────────────────────────────────────────────────────
    @Test
    fun resolutionTierBoundaries() {
        assertEquals(InfoCardPolicy.ResolutionTier.SD, InfoCardPolicy.resolutionTier(0, 719))
        assertEquals(InfoCardPolicy.ResolutionTier.HD, InfoCardPolicy.resolutionTier(0, 720))
        assertEquals(InfoCardPolicy.ResolutionTier.HD, InfoCardPolicy.resolutionTier(0, 1079))
        assertEquals(InfoCardPolicy.ResolutionTier.FHD, InfoCardPolicy.resolutionTier(0, 1080))
        assertEquals(InfoCardPolicy.ResolutionTier.FHD, InfoCardPolicy.resolutionTier(1920, 2159))
        assertEquals(InfoCardPolicy.ResolutionTier.UHD, InfoCardPolicy.resolutionTier(0, 2160))
        assertNull("全 0 → 隐藏", InfoCardPolicy.resolutionTier(0, 0))
        // 只有 width 时用 width 判档
        assertEquals(InfoCardPolicy.ResolutionTier.FHD, InfoCardPolicy.resolutionTier(1920, 0))
        assertEquals("1920x1080 → FHD", "FHD", InfoCardPolicy.resolutionTier(1920, 1080)?.label)
    }

    // ── 帧率（t38：无真实帧率兜底 25fps） ──────────────────────────────────
    @Test
    fun frameRateLabelFallsBackTo25WhenInvalid() {
        assertEquals("25fps", InfoCardPolicy.frameRateLabel(0f))
        assertEquals("25fps", InfoCardPolicy.frameRateLabel(-1f))
        assertEquals("25fps", InfoCardPolicy.frameRateLabel(Float.NaN))
        assertEquals(25f, InfoCardPolicy.FALLBACK_FRAME_RATE)
        // 有真实值仍用真实值（29.97→30、59.94→60、23.976→24）。
        assertEquals("25fps", InfoCardPolicy.frameRateLabel(25f))
        assertEquals("30fps", InfoCardPolicy.frameRateLabel(29.97f))
        assertEquals("60fps", InfoCardPolicy.frameRateLabel(59.94f))
        assertEquals("24fps", InfoCardPolicy.frameRateLabel(23.976f))
    }

    // ── 码率（bit/s → Mbps，一位小数） ─────────────────────────────────────
    @Test
    fun bitrateLabelFormatsMbpsAndHidesInvalid() {
        assertNull(InfoCardPolicy.bitrateLabel(0))
        assertNull(InfoCardPolicy.bitrateLabel(-1))
        assertEquals("8.3Mbps", InfoCardPolicy.bitrateLabel(8_300_000))
        assertEquals("0.9Mbps", InfoCardPolicy.bitrateLabel(950_000))
    }

    // ── 声道 ───────────────────────────────────────────────────────────────
    @Test
    fun channelLabelMapsKnownCountsAndHidesInvalid() {
        assertNull(InfoCardPolicy.channelLabel(0))
        assertNull(InfoCardPolicy.channelLabel(-2))
        assertEquals("单声道", InfoCardPolicy.channelLabel(1))
        assertEquals("2ch", InfoCardPolicy.channelLabel(2))
        assertEquals("5.1", InfoCardPolicy.channelLabel(6))
        assertEquals("7.1", InfoCardPolicy.channelLabel(8))
        assertEquals("3ch", InfoCardPolicy.channelLabel(3))
    }

    // ── 徽标候选（V4 §6：三项 分辨率/帧率/声道，无码率） ───────────────────
    @Test
    fun badgeCandidatesFollowPriorityAndHideUnknown() {
        val full = InfoCardPolicy.badgeCandidates(1920, 1080, 60f, 8_300_000, 2)
        assertEquals("V4：三项无码率", listOf("FHD", "60fps", "2ch"), full.map { it.label })
        assertEquals(listOf("resolution", "fps", "channels"), full.map { it.key })
        assertFalse("码率项不进候选", full.any { it.key == "bitrate" })
        // t38：帧数兜底 25fps 始终显示（组播源无 frameRate），不再"全未知隐藏"。
        assertEquals(listOf("25fps"), InfoCardPolicy.badgeCandidates(0, 0, 0f, 0, 0).map { it.label })
        assertEquals(listOf("FHD", "25fps"), InfoCardPolicy.badgeCandidates(1920, 1080, 0f, 0, 0).map { it.label })
    }

    @Test
    fun planBadgesKeepsVisibleAsPrefixAndOverflowAsSuffix() {
        val cand = InfoCardPolicy.badgeCandidates(1920, 1080, 60f, 8_300_000, 2)
        val wide = InfoCardPolicy.planBadges(cand, maxWidthDp = 180.0)
        assertEquals(listOf("FHD", "60fps", "2ch"), wide.visible)
        assertTrue(wide.overflow.isEmpty())

        val mid = InfoCardPolicy.planBadges(cand, maxWidthDp = 100.0)
        assertEquals(listOf("FHD", "60fps"), mid.visible)
        assertEquals(listOf("2ch"), mid.overflow)

        val narrow = InfoCardPolicy.planBadges(cand, maxWidthDp = 60.0)
        assertEquals(listOf("FHD"), narrow.visible)
        assertEquals(listOf("60fps", "2ch"), narrow.overflow)

        val tiny = InfoCardPolicy.planBadges(cand, maxWidthDp = 10.0)
        assertTrue(tiny.visible.isEmpty())
        assertEquals(listOf("FHD", "60fps", "2ch"), tiny.overflow)
    }

    // ── 节目行 ─────────────────────────────────────────────────────────────
    @Test
    fun programTitlePlaceholderAndTimeLine() {
        assertEquals("暂无节目信息", InfoCardPolicy.programTitleOrPlaceholder(null))
        assertEquals("暂无节目信息", InfoCardPolicy.programTitleOrPlaceholder("  "))
        assertEquals("新闻联播", InfoCardPolicy.programTitleOrPlaceholder("新闻联播"))

        assertEquals("end<=0 → 整行隐藏", "", InfoCardPolicy.programTimeLine(0, 0, 1_000, false))

        val start = 3_600_000L
        val end = start + 3_600_000L // 1h
        val now = start + 600_000L   // 已过 10 分钟
        val live = InfoCardPolicy.programTimeLine(start, end, now, catchup = false)
        assertTrue("直播要带剩余", live.endsWith("· 剩余 50 分钟"))
        assertTrue("起止用 – 分隔", live.contains("–"))

        val catchup = InfoCardPolicy.programTimeLine(start, end, now, catchup = true)
        assertFalse("回看不显示剩余", catchup.contains("剩余"))

        // 剩余 <1 分钟
        val almost = InfoCardPolicy.programTimeLine(start, start + 30_000L, start + 10_000L, catchup = false)
        assertTrue(almost.contains("<1 分钟"))
    }

    // ── 台名下小字（FULLFIX §4.2①：只留频道 N / 分组，编码移到技术行） ──────
    @Test
    fun smallMetaLineJoinsOnlyNumberAndGroup() {
        assertEquals("频道 12 · 央视频道", InfoCardPolicy.smallMetaLine(12, "央视频道"))
        assertEquals("频道 12", InfoCardPolicy.smallMetaLine(12, null))
        assertEquals("央视频道", InfoCardPolicy.smallMetaLine(0, "央视频道"))
        assertEquals("", InfoCardPolicy.smallMetaLine(0, null))
        assertEquals("央视频道", InfoCardPolicy.smallMetaLine(0, "  央视频道  "))
        assertEquals("", InfoCardPolicy.smallMetaLine(0, "  "))
    }

    // ── FULLFIX §4.2②：徽标数据源主值/回退 ──────────────────────────────────
    @Test
    fun takePositivePrefersPrimaryThenFallbackThenNull() {
        assertEquals(50f, InfoCardPolicy.takePositive(50f, 25f))
        assertEquals(25f, InfoCardPolicy.takePositive(-1f, 25f))
        assertEquals(25f, InfoCardPolicy.takePositive(0f, 25f))
        assertNull("两者无效 → null（隐藏，不显示 —）", InfoCardPolicy.takePositive(-1f, -1f))
        assertNull(InfoCardPolicy.takePositive(0f, 0f))

        assertEquals(8_300_000, InfoCardPolicy.takePositive(8_300_000, 5_000_000))
        assertEquals(5_000_000, InfoCardPolicy.takePositive(-1, 5_000_000))
        assertNull(InfoCardPolicy.takePositive(0, 0))
    }

    @Test
    fun badgeCandidatesUseCorrectedAudioChannelSource() {
        // 声道来自音频 Format（audioChannelCount），帧率/码率来自 vf + 回退；仍按优先级隐藏未知。
        val cand = InfoCardPolicy.badgeCandidates(1920, 1080, 50f, -1, 2)
        assertEquals(listOf("FHD", "50fps", "2ch"), cand.map { it.label })
        assertFalse("未知码率隐藏、不占位", cand.any { it.key == "bitrate" })
    }

    // ── V4 §6：最近观看 4 个 + 分组小字 ────────────────────────────────────
    @Test
    fun v4RecentCountAndGroupLine() {
        assertEquals("V4：最近观看最多 4 个", 4, InfoCardPolicy.MAX_RECENT)
        assertEquals("央视频道", InfoCardPolicy.groupLine("央视频道"))
        assertEquals("央视频道", InfoCardPolicy.groupLine("  央视频道  "))
        assertEquals("", InfoCardPolicy.groupLine(null))
        assertEquals("", InfoCardPolicy.groupLine("  "))
    }

    // ── t32 Bug2：右上角紧凑徽标（频道数字 / 编码短标 / 全信息一排） ────────
    @Test
    fun channelNumberLabelUsesDecimalNotation() {
        assertEquals("1.0", InfoCardPolicy.channelNumberLabel(1))
        assertEquals("2.0", InfoCardPolicy.channelNumberLabel(2))
        assertEquals("5.1", InfoCardPolicy.channelNumberLabel(6))
        assertEquals("7.1", InfoCardPolicy.channelNumberLabel(8))
        assertEquals("4.0", InfoCardPolicy.channelNumberLabel(4))
        assertNull(InfoCardPolicy.channelNumberLabel(0))
        assertEquals("3.0", InfoCardPolicy.channelNumberLabel(3))
    }

    @Test
    fun videoCodecShortMaps264265() {
        assertEquals("264", InfoCardPolicy.videoCodecShort("video/avc", "avc1.42E01E"))
        assertEquals("264", InfoCardPolicy.videoCodecShort(null, "h264"))
        assertEquals("265", InfoCardPolicy.videoCodecShort("video/hevc", "hvc1"))
        assertEquals("AV1", InfoCardPolicy.videoCodecShort("video/av01", null))
        assertNull(InfoCardPolicy.videoCodecShort(null, null))
    }

    @Test
    fun compactBadgesIncludeAllKnownInfoInOneRow() {
        // 分辨率/帧数/声道数字/动态范围(SDR)/编码(264) 全部一排、未知隐藏。
        val badges = InfoCardPolicy.compactBadges(
            width = 1920, height = 1080, frameRate = 50f, channelCount = 2,
            colorTransfer = 6 /* HDR10 */, hasHdrStaticInfo = true,
            mime = "video/avc", codecs = "avc1",
        )
        assertEquals(listOf("FHD", "50fps", "2.0", "HDR10", "264"), badges)
        // t38：帧数兜底 25fps 仍显示；其它未知项隐藏、动态范围默认 SDR 仍在。
        val minimal = InfoCardPolicy.compactBadges(0, 0, -1f, 0, -1, false, null, null)
        assertEquals(listOf("25fps", "SDR"), minimal)
    }
}
