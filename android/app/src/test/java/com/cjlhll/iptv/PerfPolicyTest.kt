package com.cjlhll.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        // t20：第二轮直接关掉动画（0），因为只要还有一圈在跑，弱机每帧都要重录制整个抽屉层
        // （framestats Draw 118–162ms）。抽屉静止后不再产生任何帧。
        assertEquals(0, PerfPolicy.MARQUEE_ITERATIONS)
        assertTrue("圈数必须在 0..3（0 = 完全不挂动画）", PerfPolicy.MARQUEE_ITERATIONS in 0..3)
        assertEquals("第二轮：聚焦行也不再跑马灯", 0, PerfPolicy.marqueeIterations(focused = true, drawerVisible = true))
        assertEquals(0, PerfPolicy.marqueeIterations(focused = true, drawerVisible = false))
        assertEquals(0, PerfPolicy.marqueeIterations(focused = false, drawerVisible = true))
    }

    @Test
    fun t32EpgColumnNarrowedAndMarqueeBounded() {
        // t32 Bug4：节目单列收窄 ~30%（300→210），时间列 96→68。
        assertEquals(210, PerfPolicy.DRAWER_PROGRAM_COLUMN_DP)
        assertEquals(68, PerfPolicy.DRAWER_PROGRAM_TIME_COLUMN_DP)
        // 超长聚焦滚动：有限圈数、仅聚焦+可见时跑；绝不 Int.MAX_VALUE。
        assertEquals(2, PerfPolicy.EPG_MARQUEE_ITERATIONS)
        assertTrue(PerfPolicy.EPG_MARQUEE_ITERATIONS in 1..3)
        assertEquals(2, PerfPolicy.epgMarqueeIterations(true, true))
        assertEquals(0, PerfPolicy.epgMarqueeIterations(true, false))
        assertEquals(0, PerfPolicy.epgMarqueeIterations(false, true))
    }

    @Test
    fun drawerUsesOpaquePaintWithCachedLayerAlpha() {
        // FULLFIX §3：paint 仍不透明（容器 surface 无 copy(alpha)），半透明只加在已缓存的
        // 静态背景 layer 上；DRAWER_OPAQUE_CONTAINER 现在只决定 layer alpha（true=1f 回退）。
        assertTrue("容器 paint 走不透明 surface，不给每个 draw command 加 alpha", PerfPolicy.DRAWER_OPAQUE_CONTAINER == false)
        assertEquals(0.92f, PerfPolicy.DRAWER_CONTAINER_ALPHA)
        assertEquals(1f, PerfPolicy.drawerContainerAlpha(true))
        assertEquals(0.92f, PerfPolicy.drawerContainerAlpha(false))
        // t29（D4）：不再延迟——从第 0 帧起分帧增量组合，首屏 10 行、每帧 +3 行。
        assertEquals("去掉 120ms 延迟，从第 0 帧开始", 0L, PerfPolicy.DRAWER_DEFER_CONTENT_MS)
        assertTrue(PerfPolicy.DRAWER_INCREMENTAL_ROWS)
        assertEquals("t58：首帧 3 行（回 vc10187）", 3, PerfPolicy.DRAWER_FIRST_FRAME_ROWS)
        assertEquals("t58：每帧 2 行（回 vc10187 最佳）", 2, PerfPolicy.DRAWER_ROWS_PER_FRAME)
        assertEquals("FULLFIX §1：行高 56→64", 64, PerfPolicy.DRAWER_ROW_HEIGHT_DP)
        assertEquals("D1：不额外预组合（严格可见区）", 0, PerfPolicy.DRAWER_OVERSCAN_ITEMS)
    }

    @Test
    fun fullfixRichRowsAndInitialIndex() {
        // FULLFIX §1：所有行（聚焦/非聚焦）都组合副标题。
        assertTrue("非聚焦行也显示副标题", PerfPolicy.rowSubtitleVisible(false))
        assertTrue("聚焦行显示副标题", PerfPolicy.rowSubtitleVisible(true))

        // FULLFIX §2：初始索引优先上次浏览焦点，其次播放台，越界/空值回 0。
        val idx = mapOf("a1" to 0, "a2" to 12, "b1" to 40)
        assertEquals(40, PerfPolicy.drawerInitialIndex("b1", "a1", idx))
        assertEquals(0, PerfPolicy.drawerInitialIndex(null, "a1", idx))
        assertEquals(12, PerfPolicy.drawerInitialIndex("a2", null, idx))
        assertEquals(0, PerfPolicy.drawerInitialIndex("gone", "a1", idx)) // 上次焦点不在当前组 → 0（与规格公式一致）
        assertEquals(0, PerfPolicy.drawerInitialIndex(null, null, idx))
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
    fun revealedRowsGrowPerFrameAndStopAtTotal() {
        // 首帧：0 → 3（t58 回 vc10187）
        assertEquals(3, PerfPolicy.nextRevealedRows(0, 147))
        // 之后每帧 +2
        assertEquals(5, PerfPolicy.nextRevealedRows(3, 147))
        assertEquals(7, PerfPolicy.nextRevealedRows(5, 147))
        // 到顶后钳住
        assertEquals(147, PerfPolicy.nextRevealedRows(146, 147))
        assertEquals(147, PerfPolicy.nextRevealedRows(147, 147))
        // 空/小列表
        assertEquals(0, PerfPolicy.nextRevealedRows(0, 0))
        assertEquals(2, PerfPolicy.nextRevealedRows(0, 2))
        assertEquals(2, PerfPolicy.nextRevealedRows(1, 2))
        // t52 §④：lookahead 收进一屏内（16→8，8×64=512dp < 672dp），滚动节奏均匀
        assertEquals(8, PerfPolicy.DRAWER_LOOKAHEAD_ROWS)
        assertEquals(12, PerfPolicy.DRAWER_SCREEN_ROWS)
        assertEquals("t58：首屏窗口 28（回 vc10187）", 28, PerfPolicy.DRAWER_OPEN_WINDOW_ROWS)
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

    // ── S1：小分组抽屉崩溃回归 ──────────────────────────────────────────────
    // 旧实现 `coerceIn(FIRST_FRAME_ROWS, channels.size)` 在 0/1/2 行时 lo>hi 抛异常。
    // 这里断言抽出的纯函数在所有边界都不越界、不崩。

    @Test
    fun s1DrawerFirstRowsClampsToActualRowCount() {
        assertEquals("0 行 → 首帧 0", 0, PerfPolicy.drawerFirstRows(0))
        assertEquals("1 行 → 首帧 1（不得用 3 越界）", 1, PerfPolicy.drawerFirstRows(1))
        assertEquals("2 行 → 首帧 2", 2, PerfPolicy.drawerFirstRows(2))
        assertEquals("3 行 → 首帧 3", 3, PerfPolicy.drawerFirstRows(3))
        assertEquals("大组 → 首帧仍 3", 3, PerfPolicy.drawerFirstRows(147))
        assertEquals("负数 defensive → 0", 0, PerfPolicy.drawerFirstRows(-1))
    }

    @Test
    fun s1SafeOpenTargetNeverExceedsRowCountForSmallGroups() {
        // 0 行：旧实现抛异常，新实现 first=0、target=0。
        assertEquals(0, PerfPolicy.drawerSafeOpenTarget(0, 0))
        assertEquals(0, PerfPolicy.drawerSafeOpenTarget(50, 0))
        // 1/2 行：target 被钳在行数内，且 >= first。
        assertEquals(1, PerfPolicy.drawerSafeOpenTarget(0, 1))
        assertEquals(1, PerfPolicy.drawerSafeOpenTarget(999, 1))
        assertEquals(2, PerfPolicy.drawerSafeOpenTarget(0, 2))
        assertEquals(2, PerfPolicy.drawerSafeOpenTarget(999, 2))
        assertEquals("3 行起步 → 目标 3", 3, PerfPolicy.drawerSafeOpenTarget(0, 3))
    }

    @Test
    fun s1SafeOpenTargetPreservesLargeGroupWindowSemantics() {
        // 大组仍按“选中行 + 工作窗口，且不少于首帧 3 行”计算，行为不变。
        assertEquals(28, PerfPolicy.drawerSafeOpenTarget(0, 147))
        assertEquals(3, PerfPolicy.drawerSafeOpenTarget(0, 3))
        assertEquals(68, PerfPolicy.drawerSafeOpenTarget(40, 147)) // 40+28=68，仍 <= 147
    }

    @Test
    fun s1SafeOpenTargetStaysWithinBoundsForAnyInput() {
        // 对 0..200 行、初始索引 0..200、越界负数做全量边界扫描：
        // 结果必须始终落在 [firstRows(n), n]，且不抛异常。
        for (n in 0..200) {
            val first = PerfPolicy.drawerFirstRows(n)
            for (idx in -5..200) {
                val target = PerfPolicy.drawerSafeOpenTarget(idx, n)
                assertTrue("n=$n idx=$idx target=$target 不得小于 first=$first", target >= first)
                assertTrue("n=$n idx=$idx target=$target 不得大于 n=$n", target <= n)
            }
        }
    }

    @Test
    fun s1LargeGroupToSmallGroupTargetAndFocusStayValid() {
        // 模拟“打开抽屉后从大组切到 2 行小组”：
        // 大组打开时选中行 100 → 目标在窗口内；切组后 focusedChannelUrl 被清空、
        // selectedChannelUrl 在小组中不存在 → initialScrollIndex 回落到 0。
        val largeOpenTarget = PerfPolicy.drawerSafeOpenTarget(100, 147)
        assertEquals(128, largeOpenTarget)
        val smallCount = 2
        val smallInitialScroll = 0 // channelIndexByUrl[旧 selectedUrl] ?: 0
        val smallOpenTarget = PerfPolicy.drawerSafeOpenTarget(smallInitialScroll, smallCount)
        assertEquals("小组目标钳到 2，不崩", 2, smallOpenTarget)
        // 非空小组聚焦第一项（focusTargetIndex=0），索引必须有效且首帧能覆盖到它。
        assertTrue("非空小组落焦索引 0 有效", PerfPolicy.drawerFocusIndexInRange(0, smallCount))
        assertTrue("首帧揭示行数必须覆盖落焦行", PerfPolicy.drawerFirstRows(smallCount) > 0)
        assertTrue("小组越界索引无效", !PerfPolicy.drawerFocusIndexInRange(2, smallCount))
        assertFalse("空组没有有效落焦索引", PerfPolicy.drawerFocusIndexInRange(0, 0))
    }

    @Test
    fun s1FirstVisibleIndexClampHandlesEmptyAndSmallGroups() {
        // 旧实现 coerceIn(0, channelCount-1) 在 count=0 时抛异常。
        assertEquals("空组 → 0 占位（不抛）", 0, PerfPolicy.drawerSafeFirstVisibleIndex(0, 0))
        assertEquals("空组越界索引 → 0 占位", 0, PerfPolicy.drawerSafeFirstVisibleIndex(99, 0))
        assertEquals("1 行 → 0", 0, PerfPolicy.drawerSafeFirstVisibleIndex(5, 1))
        assertEquals("2 行 → 1", 1, PerfPolicy.drawerSafeFirstVisibleIndex(99, 2))
        assertEquals("大组越界 → 末尾", 146, PerfPolicy.drawerSafeFirstVisibleIndex(999, 147))
    }

    // ── t49 §③：换台不重入打开初始化（openAnchor 冻结 + 开窗只抬高） ────────
    @Test
    fun t49OpenAnchorFrozenAgainstZap() {
        // 打开瞬间 anchor=播放台 A；换台到 B 后 frozen 仍为 A → anchor 不变。
        assertEquals("A", PerfPolicy.drawerOpenAnchor(null, null, "A"))
        assertEquals("A", PerfPolicy.drawerOpenAnchor("A", null, "B"))
        // 无冻结时：上次焦点优先于播放台。
        assertEquals("F", PerfPolicy.drawerOpenAnchor(null, "F", "A"))
        assertEquals("", PerfPolicy.drawerOpenAnchor(null, null, null))
    }


    // ── t91：架构重构 —— firstVisibleForFocus 首帧直接定位（无滚动 worker） ────
    @Test
    fun t78VisibleRowsFromPxAndStepSymmetry() {
        // 动态可视行数：视口 640px/64px=10 行（不写死 12）。
        assertEquals(10, PerfPolicy.drawerVisibleRowsFromPx(640, 64))
        assertEquals(12, PerfPolicy.drawerVisibleRowsFromPx(768, 64))
        assertEquals(1, PerfPolicy.drawerVisibleRowsFromPx(32, 64)) // 钳下限
        assertEquals(12, PerfPolicy.drawerVisibleRowsFromPx(640, 0)) // 行高 0 回退默认
    }

    @Test
    fun t91FirstVisibleForFocusCentersAndClamps() {
        val v = 11
        // 居中：focus=30 → first=30-half=30-5=25。
        assertEquals(25, PerfPolicy.firstVisibleForFocus(30, 147, v))
        // 顶边：focus=2 → first=0；底边：focus=145 → first=147-11=136。
        assertEquals(0, PerfPolicy.firstVisibleForFocus(2, 147, v))
        assertEquals(136, PerfPolicy.firstVisibleForFocus(145, 147, v))
        // 非边缘：first + half == focus（焦点行恰在高亮框）。
        assertEquals(30, PerfPolicy.firstVisibleForFocus(30, 147, v) + PerfPolicy.drawerCenteredHalf(v))
        // 小分组(count<=V)：返回 0（列表不可滚，全部可见）。
        assertEquals(0, PerfPolicy.firstVisibleForFocus(2, 5, v))
        assertEquals(0, PerfPolicy.firstVisibleForFocus(0, 11, v))
        // 空组 / 越界焦点 / 非法 viewport → 0。
        assertEquals(0, PerfPolicy.firstVisibleForFocus(2, 0, v))
        assertEquals(0, PerfPolicy.firstVisibleForFocus(-1, 147, v))
        assertEquals(0, PerfPolicy.firstVisibleForFocus(999, 147, v))
        assertEquals(0, PerfPolicy.firstVisibleForFocus(30, 147, 0))
        // 每步 ±1：焦点 ±1 → first ±1（非边缘区），瞬时到位无波动。
        val f0 = PerfPolicy.firstVisibleForFocus(60, 147, v)
        assertEquals(f0 + 1, PerfPolicy.firstVisibleForFocus(61, 147, v))
        assertEquals(f0 - 1, PerfPolicy.firstVisibleForFocus(59, 147, v))
    }

    @Test
    fun t91InitialRevealAndHighlightSameSource() {
        val v = 11
        // 首帧一次揭示可视窗口（覆盖 [0, first+viewport]），不逐帧 +2。
        val first = PerfPolicy.firstVisibleForFocus(30, 147, v) // 25
        assertEquals(minOf(147, maxOf(v, first + v)), PerfPolicy.initialRevealRows(30, 147, v))
        assertEquals(5, PerfPolicy.initialRevealRows(2, 5, v)) // 小分组=count
        assertEquals(0, PerfPolicy.initialRevealRows(2, 0, v))
        // 高亮同源：中心框 top = half*rowH；小分组跟随焦点行实际 y（唯一例外）。
        val rowH = 64
        assertEquals(PerfPolicy.drawerCenteredHalf(v) * rowH, PerfPolicy.drawerHighlightTopPx(v, rowH))
        assertEquals(5 * rowH, PerfPolicy.highlightTopFor(30, 147, v, rowH)) // 非小分组：half*rowH
        assertEquals(2 * rowH, PerfPolicy.highlightTopFor(2, 5, v, rowH))    // 小分组：focus*rowH
    }

    // ── t75：焦点静态覆盖层 + 抽屉无操作自动收回（产品值 7s） ─────────────────
    @Test
    fun t75CenterHighlightAndAutoClose() {
        assertTrue("焦点高亮用固定中心覆盖层", PerfPolicy.DRAWER_CENTER_HIGHLIGHT)
        assertEquals("t91：产品值恢复 7s", 7_000L, PerfPolicy.DRAWER_AUTO_CLOSE_MS)
        val now = 50_000L
        assertTrue(PerfPolicy.shouldAutoCloseDrawer(true, now - 7_000, now))
        assertFalse(PerfPolicy.shouldAutoCloseDrawer(true, now - 3_000, now))
        assertFalse(PerfPolicy.shouldAutoCloseDrawer(false, now - 30_000, now))
    }




    @Test
    fun t69OpenAnchorPlayingChannelFirst() {
        val known = setOf("c16", "c15", "browse63")
        // 播放台有效 → 第一优先（不再被 browse 残留覆盖）。
        assertEquals("c16", PerfPolicy.drawerOpenPositionAnchor("c16", "browse63", known))
        // 播放台无效/空 → browse 记忆 fallback。
        assertEquals("browse63", PerfPolicy.drawerOpenPositionAnchor(null, "browse63", known))
        assertEquals("browse63", PerfPolicy.drawerOpenPositionAnchor("gone", "browse63", known))
        // 两者都无效 → null（调用方取第一台）。
        assertNull(PerfPolicy.drawerOpenPositionAnchor("gone", "gone", known))
    }
}
