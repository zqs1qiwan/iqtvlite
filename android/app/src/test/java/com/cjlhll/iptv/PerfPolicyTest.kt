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

    // ── t15：分帧揭示后校正滚动（冷启首开 / P8b 换台深行） ──────────────────
    @Test
    fun t15ScrollCorrectionIsGatedByRevealedRows() {
        // t72：TiviMate 焦点居中，target=focus-6；焦点 idx126 → 120。
        val target = PerfPolicy.drawerScrollTargetIndex(126, 147)
        assertEquals(120, target)
        assertFalse("首帧 3 行不足以校正深行", PerfPolicy.drawerScrollCorrected(3, target))
        assertFalse("补到 100 行仍不够", PerfPolicy.drawerScrollCorrected(100, target))
        assertTrue("补到 121 行覆盖 target=120", PerfPolicy.drawerScrollCorrected(121, target))
        assertTrue("补满 147 行可校正", PerfPolicy.drawerScrollCorrected(147, target))

        // 浅行：target=0，首帧 revealed=3 已覆盖（>=1）。
        val shallow = PerfPolicy.drawerScrollTargetIndex(0, 147)
        assertEquals(0, shallow)
        assertTrue(PerfPolicy.drawerScrollCorrected(3, shallow))

        // 边界：负数 target 不校正；target=1 需 revealed>=2。
        assertFalse(PerfPolicy.drawerScrollCorrected(147, -1))
        assertFalse(PerfPolicy.drawerScrollCorrected(1, 1))
        assertTrue(PerfPolicy.drawerScrollCorrected(2, 1))
    }

    @Test
    fun t15ScrollTargetUsesSafeFirstVisibleClamp() {
        // t72 焦点居中：target=focus-6，钳到 [0, count-visibleRows=135]；小 count(<12) 安全返回 0。
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(0, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(1, 147))
        assertEquals(34, PerfPolicy.drawerScrollTargetIndex(40, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(5, 0))
        assertEquals(135, PerfPolicy.drawerScrollTargetIndex(999, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(5, 1))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(999, 2))
    }

    // ── t26：revealed 已满但焦点不可见 → 补一次滚动校正 ─────────────────────
    @Test
    fun t26RevealFullFocusInvisibleTriggersCorrection() {
        // 真机残留：revealed=141（已满）、焦点 idx114、可见 first=58 last=67 → 需校正。
        assertTrue(
            "焦点 idx114 不在可见区 58..67 → 触发",
            PerfPolicy.drawerFocusNeedsRevealCorrection(114, 141, 141, 58, 67),
        )
        assertTrue(
            "P8b：焦点 126 不在可见区 58..67 → 触发",
            PerfPolicy.drawerFocusNeedsRevealCorrection(126, 141, 141, 58, 67),
        )
    }

    @Test
    fun t26FocusVisibleDoesNotTriggerCorrection() {
        // 焦点在可见区内 → 不触发（防抖/防重滚）。
        assertFalse(
            "焦点 104 在可见区 100..111 → 不触发",
            PerfPolicy.drawerFocusNeedsRevealCorrection(104, 141, 141, 100, 111),
        )
        assertFalse(
            "焦点在边界 first → 不触发",
            PerfPolicy.drawerFocusNeedsRevealCorrection(58, 141, 141, 58, 67),
        )
        assertFalse(
            "焦点在边界 last → 不触发",
            PerfPolicy.drawerFocusNeedsRevealCorrection(67, 141, 141, 58, 67),
        )
    }

    @Test
    fun t26RevealNotEnoughOrInvalidFocusDoesNotTrigger() {
        // revealed 未满（焦点行还没组合出来）→ 交给 t15 pending 门控，不在这里触发。
        assertFalse(
            "revealed=60 未覆盖焦点 114 → 不触发",
            PerfPolicy.drawerFocusNeedsRevealCorrection(114, 141, 60, 58, 67),
        )
        // 无效焦点索引不触发。
        assertFalse(PerfPolicy.drawerFocusNeedsRevealCorrection(-1, 141, 141, 58, 67))
        assertFalse(PerfPolicy.drawerFocusNeedsRevealCorrection(141, 141, 141, 58, 67))
        // 无布局（first<0）不触发。
        assertFalse(PerfPolicy.drawerFocusNeedsRevealCorrection(114, 141, 141, -1, -1))
    }

    // ── t29：单一真源 drawerScrollDecision（SCROLL / CLEAR / WAIT） ──────────
    @Test
    fun t29SingleSourceDecisionScrollWhenFocusInvisible() {
        // 焦点 114 不在可视区 58..67、revealed 已满 → SCROLL（深行 zap / P8b 关开）。
        assertEquals(
            PerfPolicy.ScrollDecision.SCROLL,
            PerfPolicy.drawerScrollDecision(114, 141, 141, 58, 67),
        )
        assertEquals(
            PerfPolicy.ScrollDecision.SCROLL,
            PerfPolicy.drawerScrollDecision(126, 141, 141, 58, 67),
        )
    }

    @Test
    fun t29FocusAlreadyVisibleClearsWithoutScroll() {
        // 历史回归「焦点已可见仍多滚一次」：焦点在可视区 → CLEAR（清意图不滚）。
        assertEquals(
            PerfPolicy.ScrollDecision.CLEAR,
            PerfPolicy.drawerScrollDecision(104, 141, 141, 100, 111),
        )
        // t52 §④：焦点贴可视边缘（≤2 行）→ SCROLL 提前平滑滚，不再等到贴边才突跳。
        assertEquals(
            "边界 first → SCROLL（提前平滑滚）",
            PerfPolicy.ScrollDecision.SCROLL,
            PerfPolicy.drawerScrollDecision(58, 141, 141, 58, 67),
        )
        assertEquals(
            "边界 last → SCROLL（提前平滑滚）",
            PerfPolicy.ScrollDecision.SCROLL,
            PerfPolicy.drawerScrollDecision(67, 141, 141, 58, 67),
        )
    }

    @Test
    fun t29DecisionWaitAndClearEdgeCases() {
        // 揭示未覆盖焦点行 → WAIT（等分帧揭示，不滚不清）。
        assertEquals(
            PerfPolicy.ScrollDecision.WAIT,
            PerfPolicy.drawerScrollDecision(114, 141, 60, 58, 67),
        )
        // 无布局 → WAIT。
        assertEquals(
            PerfPolicy.ScrollDecision.WAIT,
            PerfPolicy.drawerScrollDecision(114, 141, 141, -1, -1),
        )
        // 无意图(-1) / 越界焦点 → CLEAR（清意图）。
        assertEquals(
            PerfPolicy.ScrollDecision.CLEAR,
            PerfPolicy.drawerScrollDecision(-1, 141, 141, 58, 67),
        )
        assertEquals(
            PerfPolicy.ScrollDecision.CLEAR,
            PerfPolicy.drawerScrollDecision(141, 141, 141, 58, 67),
        )
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

    @Test
    fun t49RevealTargetOnlyRaisesNeverFalls() {
        assertEquals(147, PerfPolicy.drawerRevealTarget(147, 28))
        assertEquals(28, PerfPolicy.drawerRevealTarget(0, 28))
        assertEquals(100, PerfPolicy.drawerRevealTarget(60, 100))
    }

    // ── t72：TiviMate 焦点居中（focus - visibleRows/2）+ 双向对称限幅 ±8 ──────
    @Test
    fun t72ScrollTargetFocusCentered() {
        // 焦点居中：target = focus - DRAWER_SCREEN_ROWS/2 = focus - 6。
        assertEquals(6, PerfPolicy.DRAWER_SCREEN_ROWS / 2)
        assertEquals(120, PerfPolicy.drawerScrollTargetIndex(126, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(0, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(1, 147))
        assertEquals(34, PerfPolicy.drawerScrollTargetIndex(40, 147))
        // 边缘：越界下限钳到 0、上限钳到 count-visibleRows=135（不越界、不崩）。
        assertEquals(135, PerfPolicy.drawerScrollTargetIndex(999, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(-5, 147))
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(5, 0))   // 空组安全
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(5, 1))   // 小count安全
        assertEquals(0, PerfPolicy.drawerScrollTargetIndex(999, 2)) // 小count安全
    }

    @Test
    fun t72FocusStaysCenteredOnStep() {
        // 焦点下移 1 行 → target 也 +1（列表反向滚 1 行，焦点保持居中）。
        val t0 = PerfPolicy.drawerScrollTargetIndex(60, 147)
        val t1 = PerfPolicy.drawerScrollTargetIndex(61, 147)
        val tDown = PerfPolicy.drawerScrollTargetIndex(59, 147)
        assertEquals(t0 + 1, t1)
        assertEquals(t0 - 1, tDown)
        // 长按多步累积：target 随焦点单调推进，对称。
        assertEquals(t0 + 10, PerfPolicy.drawerScrollTargetIndex(70, 147))
    }

    // ── t75：焦点静态覆盖层 + 抽屉无操作自动收回 ─────────────────────────────
    @Test
    fun t75CenterHighlightAndAutoClose() {
        assertTrue("焦点高亮用固定中心覆盖层", PerfPolicy.DRAWER_CENTER_HIGHLIGHT)
        assertEquals(7000L, PerfPolicy.DRAWER_AUTO_CLOSE_MS)
        val now = 50_000L
        // 静止 ≥7s → 收回；抽屉未开不收；有交互（刚刷新时间戳）不收。
        assertTrue(PerfPolicy.shouldAutoCloseDrawer(true, now - 7_000, now))
        assertTrue(PerfPolicy.shouldAutoCloseDrawer(true, now - 9_000, now))
        assertFalse(PerfPolicy.shouldAutoCloseDrawer(true, now - 3_000, now))
        assertFalse(PerfPolicy.shouldAutoCloseDrawer(false, now - 20_000, now))
    }

    @Test
    fun t59ScrollDeltaSymmetricAround8() {
        // 上滚 -8、下滚 +8 对称；超出都钳到 ±8（不再向下整页翻）。
        assertEquals(8, PerfPolicy.DRAWER_SCROLL_MAX_STEP)
        assertEquals(-8, PerfPolicy.drawerScrollDelta(52, 60))
        assertEquals(8, PerfPolicy.drawerScrollDelta(68, 60))
        assertTrue(
            "上下同距离 delta 绝对值一致",
            kotlin.math.abs(PerfPolicy.drawerScrollDelta(52, 60)) ==
                kotlin.math.abs(PerfPolicy.drawerScrollDelta(68, 60)),
        )
        assertEquals(-8, PerfPolicy.drawerScrollDelta(0, 100))   // 上滚钳到 -8
        assertEquals(8, PerfPolicy.drawerScrollDelta(147, 10))   // 下滚钳到 +8（非整页翻）
        assertEquals(0, PerfPolicy.drawerScrollDelta(60, 60))
    }

    // ── t62：打开首次定位瞬时落位 vs 抽屉内焦点跟随 ±8 对称 ──────────────────
    @Test
    fun t62OpenPositionInstantVsFollowClamped() {
        // 打开定位：大 delta 一次到位（瞬时 scrollToItem），不受 ±8 限制 → 快速贴到当前台。
        assertEquals(12, PerfPolicy.drawerScrollStep(openPositioning = true, rawDelta = 12))
        assertEquals(100, PerfPolicy.drawerScrollStep(openPositioning = true, rawDelta = 100))
        // 抽屉内焦点跟随：严格 ±8 对称平滑。
        assertEquals(8, PerfPolicy.drawerScrollStep(openPositioning = false, rawDelta = 12))
        assertEquals(-8, PerfPolicy.drawerScrollStep(openPositioning = false, rawDelta = -100))
        assertEquals(8, PerfPolicy.drawerScrollStep(openPositioning = false, rawDelta = 100))
        assertEquals(0, PerfPolicy.drawerScrollStep(openPositioning = false, rawDelta = 0))
    }

    // ── t65：每次开抽屉都置 instant；换台不置；抽屉内滚动仍 ±8 ───────────────
    @Test
    fun t65EveryOpenArmsInstantZapDoesNot() {
        // 每次关→开（drawerOpened=true）都置位 instant → 秒定位当前台。
        assertTrue(PerfPolicy.shouldArmInstantLocate(drawerOpened = true))
        assertTrue(PerfPolicy.shouldArmInstantLocate(drawerOpened = true)) // 二次开照样置位
        // 换台时抽屉仍开（opened=false）→ 不置位 → smooth 原地保持。
        assertFalse(PerfPolicy.shouldArmInstantLocate(drawerOpened = false))
        // 不置位时抽屉内滚动仍严格 ±8 对称。
        assertEquals(8, PerfPolicy.drawerScrollStep(openPositioning = false, rawDelta = 100))
        assertEquals(-8, PerfPolicy.drawerScrollStep(openPositioning = false, rawDelta = -100))
    }

    // ── t69 ④/⑤：长按滚动解耦 + 播放台优先锚点 ──────────────────────────────
    @Test
    fun t69LongPressCadenceAndMonotonicScroll() {
        // 长按节奏判定：单键慢按 gap>阈值 → smooth；连续快速 gap≤阈值 → longPress 瞬时 jump。
        assertFalse(PerfPolicy.isRapidFocus(500))
        assertTrue(PerfPolicy.isRapidFocus(30))
        assertTrue(PerfPolicy.isRapidFocus(0))
        assertFalse(PerfPolicy.isRapidFocus(-1)) // 无效 gap
        assertTrue(PerfPolicy.isLongPressScroll(PerfPolicy.DRAWER_LONG_PRESS_REPEAT))
        // 单调推进：从"当前实际 first"重算，repeat 应用后位移单调累积到目标（±8 对称）。
        assertEquals(8, PerfPolicy.drawerMonotonicStep(targetIndex = 60, currentFirstIndex = 10))
        assertEquals(-8, PerfPolicy.drawerMonotonicStep(targetIndex = 20, currentFirstIndex = 60)) // 上滚对称
        assertEquals(4, PerfPolicy.drawerMonotonicStep(targetIndex = 14, currentFirstIndex = 10))
        assertEquals(0, PerfPolicy.drawerMonotonicStep(targetIndex = 10, currentFirstIndex = 10))
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
