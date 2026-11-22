package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** FULLFIX 复审 B2（U5）：播放台标记 + 节目单空态三分类的纯逻辑用例。 */
class DrawerUiPolicyTest {

    // ── V4 §5：播放台变色（不再 ▶ 文本） ────────────────────────────────────
    @Test
    fun channelLabelHasNoPlayingMark() {
        assertEquals("1. CCTV-1", DrawerUiPolicy.channelLabel(1, "CCTV-1"))
        assertEquals("13. 湖南卫视", DrawerUiPolicy.channelLabel(13, "湖南卫视"))
        assertFalse("标签不得含 ▶", DrawerUiPolicy.channelLabel(1, "CCTV-1").contains("▶"))
        assertFalse("标签不得含 播放中", DrawerUiPolicy.channelLabel(1, "CCTV-1").contains("播放中"))
    }

    @Test
    fun playingNameColorUsesBrandBlueOnlyForPlayingChannel() {
        assertEquals(
            DrawerUiPolicy.NameColor.PLAYING,
            DrawerUiPolicy.playingNameColor(true),
        )
        assertEquals(
            DrawerUiPolicy.NameColor.NORMAL,
            DrawerUiPolicy.playingNameColor(false),
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
