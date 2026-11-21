package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t7（docs/PERF_OPT_V2.md）：性能开关的纯逻辑断言 —— 跑马灯圈数、紧急预取集合截断、jank 门禁口径。
 * 这些值一旦被改回"无限动画 / 无上限预取"，这里会先红。
 */
class PerfPolicyTest {

    @Test
    fun marqueeIsBoundedAndOnlyRunsWhenVisibleAndFocused() {
        // 关键回归：绝不能回到 Int.MAX_VALUE（抽屉打开期间永久请求下一帧 → 100% janky）。
        assertEquals(2, PerfPolicy.MARQUEE_ITERATIONS)
        assertTrue("圈数必须有限且很小", PerfPolicy.MARQUEE_ITERATIONS in 1..3)
        assertEquals(2, PerfPolicy.marqueeIterations(focused = true, drawerVisible = true))
        assertEquals("抽屉关闭时不得挂动画", 0, PerfPolicy.marqueeIterations(focused = true, drawerVisible = false))
        assertEquals("未聚焦行不得挂动画", 0, PerfPolicy.marqueeIterations(focused = false, drawerVisible = true))
        assertEquals(0, PerfPolicy.marqueeIterations(focused = false, drawerVisible = false))
    }

    @Test
    fun urgentPrefetchIsCappedAndStartsAfterTheOpenAnimation() {
        assertTrue("预取必须在开合之后才开始", PerfPolicy.URGENT_PREFETCH_DELAY_MS >= 300L)
        assertEquals(12, PerfPolicy.MAX_URGENT_PREFETCH)
        assertTrue(PerfPolicy.MAX_URGENT_PREFETCH in 1..24)
    }

    @Test
    fun urgentPrefetchPrefersSelectionNeighbourhoodThenVisibleWindow() {
        val indices = PerfPolicy.urgentPrefetchIndices(
            selectedIndex = 100,
            visibleIndices = listOf(0, 1, 2, 98, 99, 100, 101),
            channelCount = 500,
        )
        assertEquals("邻域 92..108 先占满 12 个上限", 12, indices.size)
        assertEquals("第一个是邻域起点", 92, indices.first())
        assertEquals("邻域连续", (92..103).toList(), indices)
    }

    @Test
    fun urgentPrefetchFallsBackToVisibleWindowNearListEdges() {
        val indices = PerfPolicy.urgentPrefetchIndices(
            selectedIndex = 1,
            visibleIndices = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14),
            channelCount = 100,
        )
        assertEquals(12, indices.size)
        assertEquals("从 0 开始（邻域被左边界截断）", 0, indices.first())
        assertTrue("不得越过 list 末尾", indices.all { it in 0 until 100 })
    }

    @Test
    fun urgentPrefetchHandlesEmptyAndOutOfRangeSelection() {
        assertTrue(PerfPolicy.urgentPrefetchIndices(0, emptyList(), 0).isEmpty())
        assertTrue(
            "没有可视窗口且选中越界 → 空",
            PerfPolicy.urgentPrefetchIndices(999, emptyList(), 10).isEmpty()
        )
        val fallback = PerfPolicy.urgentPrefetchIndices(-1, listOf(0, 1, 2), 10)
        assertEquals(listOf(0, 1, 2), fallback)
    }

    @Test
    fun jankGateMatchesTheAgreedBudgetAndTarget() {
        assertEquals(16L, PerfPolicy.FRAME_BUDGET_MS)
        assertEquals(20.0, PerfPolicy.TARGET_JANKY_PERCENT, 1e-9)
        assertFalse(PerfPolicy.isJankyFrame(16))
        assertTrue(PerfPolicy.isJankyFrame(17))
        assertEquals(100.0, PerfPolicy.jankyPercent(33, 33), 1e-9)
        assertEquals(0.0, PerfPolicy.jankyPercent(0, 0), 1e-9)
        assertEquals(82.775, PerfPolicy.jankyPercent(173, 209), 1e-3)
        // 基线 100% 不达标；66ms 窗口内 4/20 才达标。
        assertFalse(PerfPolicy.meetsJankTarget(33, 33))
        assertFalse(PerfPolicy.meetsJankTarget(173, 209))
        assertTrue(PerfPolicy.meetsJankTarget(4, 20))
        assertFalse(PerfPolicy.meetsJankTarget(5, 20))
    }

    @Test
    fun settingsPagesAreNotAnimated() {
        assertFalse("层内直接替换，避免转场动画带来的 jank", PerfPolicy.SETTINGS_PAGE_ANIMATED)
    }

    @Test
    fun epgCacheIsDeferredAfterTheFirstFrame() {
        // t7（PERF-04）：首帧后 2s 再读 EPG 缓存（22s 解析不要压住起播缓冲恢复）。
        assertEquals(2_000L, ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS)
        assertTrue("延迟不得把节目单拖太久", ColdStartPolicy.EPG_POST_FIRST_FRAME_DELAY_MS in 0L..5_000L)
        assertEquals(10_000L, ColdStartPolicy.EPG_CACHE_FALLBACK_MS)
        // 回退：改 0 即恢复旧行为。
        assertTrue(ColdStartPolicy.EPG_AFTER_FIRST_FRAME)
    }
}
