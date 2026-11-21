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

    // ── 帧率 ───────────────────────────────────────────────────────────────
    @Test
    fun frameRateLabelRoundsAndHidesInvalid() {
        assertNull(InfoCardPolicy.frameRateLabel(0f))
        assertNull(InfoCardPolicy.frameRateLabel(-1f))
        assertNull(InfoCardPolicy.frameRateLabel(Float.NaN))
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

    // ── 徽标候选 + 优先级/溢出 ─────────────────────────────────────────────
    @Test
    fun badgeCandidatesFollowPriorityAndHideUnknown() {
        val full = InfoCardPolicy.badgeCandidates(1920, 1080, 60f, 8_300_000, 2)
        assertEquals(listOf("FHD", "60fps", "8.3Mbps", "2ch"), full.map { it.label })
        assertEquals(listOf("resolution", "fps", "bitrate", "channels"), full.map { it.key })
        assertTrue("全未知 → 空（不占位）", InfoCardPolicy.badgeCandidates(0, 0, 0f, 0, 0).isEmpty())
        assertEquals(listOf("FHD"), InfoCardPolicy.badgeCandidates(1920, 1080, 0f, 0, 0).map { it.label })
    }

    @Test
    fun planBadgesKeepsVisibleAsPrefixAndOverflowAsSuffix() {
        val cand = InfoCardPolicy.badgeCandidates(1920, 1080, 60f, 8_300_000, 2)
        val wide = InfoCardPolicy.planBadges(cand, maxWidthDp = 260.0)
        assertEquals(listOf("FHD", "60fps", "8.3Mbps", "2ch"), wide.visible)
        assertTrue(wide.overflow.isEmpty())

        val mid = InfoCardPolicy.planBadges(cand, maxWidthDp = 140.0)
        assertEquals("先挤掉码率/声道", listOf("FHD", "60fps"), mid.visible)
        assertEquals(listOf("8.3Mbps", "2ch"), mid.overflow)

        val narrow = InfoCardPolicy.planBadges(cand, maxWidthDp = 60.0)
        assertEquals(listOf("FHD"), narrow.visible)
        assertEquals(listOf("60fps", "8.3Mbps", "2ch"), narrow.overflow)

        val tiny = InfoCardPolicy.planBadges(cand, maxWidthDp = 10.0)
        assertTrue(tiny.visible.isEmpty())
        assertEquals(listOf("FHD", "60fps", "8.3Mbps", "2ch"), tiny.overflow)
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

    // ── 台名下小字 ─────────────────────────────────────────────────────────
    @Test
    fun smallMetaLineJoinsExistingParts() {
        assertEquals("频道 12 · 央视频道 · MPEG-L2", InfoCardPolicy.smallMetaLine(12, "央视频道", "MPEG-L2"))
        assertEquals("频道 12", InfoCardPolicy.smallMetaLine(12, null, null))
        assertEquals("央视频道", InfoCardPolicy.smallMetaLine(0, "央视频道", null))
        assertEquals("", InfoCardPolicy.smallMetaLine(0, null, null))
        assertEquals("AAC", InfoCardPolicy.smallMetaLine(0, "  ", "AAC"))
    }
}
