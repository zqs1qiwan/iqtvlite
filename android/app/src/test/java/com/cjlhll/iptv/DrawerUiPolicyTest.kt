package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

/** FULLFIX 复审 B2（U5）：播放台标记 + 节目单空态三分类的纯逻辑用例。 */
class DrawerUiPolicyTest {

    // ── B2①：播放台 ▶ 标记与浏览焦点正交 ──────────────────────────────────
    @Test
    fun channelLabelMarksOnlyThePlayingChannel() {
        assertEquals("1. CCTV-1", DrawerUiPolicy.channelLabel(false, 1, "CCTV-1"))
        assertEquals("▶ 1. CCTV-1", DrawerUiPolicy.channelLabel(true, 1, "CCTV-1"))
        assertEquals("▶ 13. 湖南卫视", DrawerUiPolicy.channelLabel(true, 13, "湖南卫视"))
        // 两个维度正交：selected 只决定 ▶ 前缀，与焦点状态无关（焦点是 surfaceVariant 底色）。
        assertEquals(
            "播放台标记与焦点无关",
            "▶ 2. 央视二套",
            DrawerUiPolicy.channelLabel(true, 2, "央视二套"),
        )
    }

    // ── B2③：节目单空态三分类 ──────────────────────────────────────────────
    @Test
    fun programEmptyTextClassifiesThreeStatesPlusLoading() {
        assertEquals("加载中", DrawerUiPolicy.programEmptyText(true, false, true))
        assertEquals("未配置 EPG 源", DrawerUiPolicy.programEmptyText(false, false, false))
        assertEquals("EPG 加载失败", DrawerUiPolicy.programEmptyText(true, true, false))
        assertEquals("暂无节目单", DrawerUiPolicy.programEmptyText(true, false, false))
        // 优先级：未配置 > 加载失败（未配置时不可能有加载失败）；loading 最高。
        assertEquals("未配置 EPG 源", DrawerUiPolicy.programEmptyText(false, true, false))
        assertEquals("加载中", DrawerUiPolicy.programEmptyText(false, true, true))
    }

    @Test
    fun threeEmptyStatesHaveDistinctTexts() {
        val texts = setOf(
            DrawerUiPolicy.programEmptyText(false, false, false),
            DrawerUiPolicy.programEmptyText(true, true, false),
            DrawerUiPolicy.programEmptyText(true, false, false),
        )
        assertEquals("三种空态文案必须互不相同", 3, texts.size)
    }
}
