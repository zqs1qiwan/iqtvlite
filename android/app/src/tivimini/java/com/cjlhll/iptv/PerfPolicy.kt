package com.cjlhll.iptv

/**
 * t7（docs/PERF_OPT_V2.md）：弱机性能收口的**纯逻辑**开关，全部可单变量 A/B、可一行回退。
 *
 * 这里不 import Compose/Android，任何取值都能在 JVM 单测里断言（`PerfPolicyTest`）。
 * 设备侧改前/改后同口径数据由 acceptance(t9) 用 `gfxinfo` / `/proc/stat` 差分测（见文档 §5）。
 */
object PerfPolicy {

    // ── 1) 抽屉开合（基线 Janky 100% / 中位 46–48ms） ────────────────────────
    /**
     * 跑马灯（`basicMarquee`）的最大圈数。**0 = 完全不挂动画**。
     *
     * 第一轮（t7）把它从 `Int.MAX_VALUE` 降到 2，但 t18 真机 A/B 显示 50th 反而 46→81–89ms：
     * 因为只要还有一圈动画在跑，弱机每帧就要把整个抽屉层（约 147 个布局节点）重新录制一次
     * （framestats：Draw 118–162ms/帧）。于是第二轮直接把动画关掉：抽屉静止后不再产生任何帧。
     *
     * 回退：改成 1..3 恢复“跑几圈就停”。
     */
    const val MARQUEE_ITERATIONS = 0

    /** 只有"抽屉打开 + 该行聚焦"时才跑跑马灯；`MARQUEE_ITERATIONS=0` 时永远不跑。 */
    fun marqueeIterations(focused: Boolean, drawerVisible: Boolean): Int =
        if (focused && drawerVisible) MARQUEE_ITERATIONS else 0

    /**
     * t20（E4）：抽屉容器 paint 保持**不透明** surface，避免每帧对 1080p 面板做整层 alpha 合成
     * （framestats 里 Draw 118–162ms）。
     *
     * FULLFIX §3：该开关现在只决定**背景静态 layer** 是否叠加半透明：
     * - `true`  → layer alpha = 1f（完全回到旧的实心面板，单变量回退用）；
     * - `false` → layer alpha = [DRAWER_CONTAINER_ALPHA]（0.92f，与信息卡/设置抽屉一致）。
     *
     * 注意：paint 本身始终是不透明的 `surface`，半透明只作用在已录制好的一张 layer 上，
     * 不是把 `surface.copy(alpha=...)` 交给每个 draw command（那正是 t20 的卡顿成因）。
     */
    const val DRAWER_OPAQUE_CONTAINER = false

    /** FULLFIX §3：抽屉背景静态 layer 的半透明度（与信息卡一致）。 */
    const val DRAWER_CONTAINER_ALPHA = 0.92f

    /** FULLFIX §3：把「不透明绘制」开关翻译成 layer alpha；true = 1f，false = 0.92f。 */
    fun drawerContainerAlpha(opaque: Boolean): Float =
        if (opaque) 1f else DRAWER_CONTAINER_ALPHA

    /**
     * t29（D4）：打开抽屉后的内容组合方式 —— **分帧增量组合**，不再"延迟后一次性组合"。
     *
     * 演进：
     * - t22：打开那一帧组合+绘制整个抽屉 → `drawer_open 314–408ms`、3–4 个 300–385ms 巨帧；
     * - t27：延迟 120ms 再组合 → 首帧 `composed=0`（达标）但 120ms 后**一次性同步大块组合**，
     *   t28 实测仍有 **5 个 300+ms 巨帧（332–368ms）**；
     * - t29（本版）：从**第 0 帧**开始分帧增量组合，每帧只加 [DRAWER_ROWS_PER_FRAME] 行，
     *   首屏先出 [DRAWER_FIRST_FRAME_ROWS] 行 → 没有"先空后炸"，也没有 300ms 巨帧。
     *
     * 回退：`DRAWER_INCREMENTAL_ROWS = false`（回到 t27 的 120ms 延迟整块组合）。
     */
    const val DRAWER_INCREMENTAL_ROWS = true

    /** 首帧先组合多少行（t39：6→3，专门消掉 t37 残留的 304ms 巨帧；3 行仍足以"有内容"）。 */
    const val DRAWER_FIRST_FRAME_ROWS = 3

    /**
     * S1：按实际行数计算首帧揭示行数。
     *
     * 旧实现在 PlayerDrawer 里直接 `DRAWER_FIRST_FRAME_ROWS.coerceIn(0, channels.size)`，
     * 空/小分组（size < 3）时 lo>hi 会抛 IllegalArgumentException；这里改写成
     * `minOf(FIRST, n)`，保证结果始终落在 0..n，且 0 行返回 0。
     */
    fun drawerFirstRows(channelCount: Int): Int =
        if (channelCount <= 0) 0 else minOf(DRAWER_FIRST_FRAME_ROWS, channelCount)

    /**
     * S1：抽屉打开时的目标窗口，按实际行数计算，避免 `coerceIn(lo, hi)` 在 lo>hi 时崩溃。
     *
     * 语义与旧实现一致（大组：首帧 3 行起步、目标选中行 + OPEN_WINDOW），
     * 但对 0/1/2 行的小分组安全：first=minOf(FIRST, n)，target 落在 first..n。
     */
    fun drawerSafeOpenTarget(initialScrollIndex: Int, channelCount: Int): Int {
        val n = channelCount.coerceAtLeast(0)
        val first = drawerFirstRows(n)
        val start = initialScrollIndex.coerceAtLeast(0)
        return maxOf(first, minOf(start + DRAWER_OPEN_WINDOW_ROWS, n))
    }

    /**
     * S1：频道列落焦索引的安全钳制。
     *
     * 旧实现 `firstVisibleItemIndex.coerceIn(0, channelCount - 1)` 在 channelCount=0 时
     * lo=0 > hi=-1 抛异常；这里对 <=0 的列表返回 0（调用方已用 channelCount>0 守卫按键分支，
     * 返回 0 仅作为不可能被使用的安全占位），其余钳到 0..(count-1)。
     */
    fun drawerSafeFirstVisibleIndex(firstVisibleItemIndex: Int, channelCount: Int): Int =
        if (channelCount <= 0) 0 else firstVisibleItemIndex.coerceIn(0, channelCount - 1)

    /** S1：焦点索引是否落在非空频道列表内（大组切小组后 focusedChannelUrl 已清空时用 0 落焦）。 */
    fun drawerFocusIndexInRange(index: Int, channelCount: Int): Boolean =
        channelCount > 0 && index in 0 until channelCount

    /**
     * t15（滚动校正）：目标行是否已被分帧揭示到足以执行 scrollToItem。
     *
     * 根因：打开首帧 revealedRows=3 时就 scrollToItem(126)，被 LazyColumn 钳到 0；
     * 之后分帧补到 147 行却没再校正滚动 → 焦点 idx126、可见 first=1。
     * 这里给出门控条件：只有当揭示行数覆盖 targetScroll 那一行（+1，0 基索引需 count>=index+1）
     * 时，scrollToItem 才不会被钳到 0，才应执行一次校正。
     */
    fun drawerScrollCorrected(revealedRows: Int, targetScrollIndex: Int): Boolean =
        targetScrollIndex >= 0 && revealedRows >= targetScrollIndex + 1

    /**
     * t15 / V4 §4：把焦点行索引换算成 `scrollToItem` 的目标（留 2 行前置上下文）。
     *
     * V4 §4：上限用 `channelCount - 2`（而非 n-1），使越界 focus=999、count=147 → 145
     * （与 PerfPolicyTest.t15ScrollTargetUsesSafeFirstVisibleClamp 一致，修复 CI #216）。
     * 语义：末尾目标仍留 2 行上下文，符合"留 2 行前置"注释；空/1 组 → 0。
     */
    /**
     * t49 §③：开窗目标只抬高不回落（防止换台重入时 revealTarget 收缩导致可见行闪烁）。
     */
    fun drawerRevealTarget(current: Int, openTarget: Int): Int = maxOf(current, openTarget)

    /**
     * t49 §③：抽屉"打开锚点"语义 —— 换台（playingUrl 变化）不改变已冻结的 openAnchor。
     * 打开瞬间 anchor = lastFocusedUrl ?: playingUrl；之后 playingUrl 变化应被忽略。
     */
    fun drawerOpenAnchor(frozen: String?, lastFocusedUrl: String?, playingUrl: String?): String =
        frozen ?: (lastFocusedUrl ?: playingUrl ?: "")

    fun drawerScrollTargetIndex(focusIndex: Int, channelCount: Int): Int {
        if (channelCount <= 0) return 0
        val hi = channelCount - 2
        if (hi <= 0) return 0 // 1/2 行时无前置 2 行上下文，安全返回 0（避免 coerceIn(0,-1) 抛异常）
        return (focusIndex - 2).coerceIn(0, hi)
    }

    /**
     * t26（V4 残留）：revealed 已满时关→重开，焦点行若不在可见区，需要补一次滚动校正。
     *
     * 条件：焦点索引有效（>=0 且 < count）、揭示行数已覆盖焦点行（否则交给 t15 的门控，避免钳制）、
     * 且焦点索引不在 [firstVisible, lastVisible] 可视窗口内。
     * - 焦点可见 → false（不触发，防抖/防重滚）。
     * - revealed 未满（焦点行还没组合出来）→ false（交给 t15 pending 门控，等揭示覆盖后再校正）。
     */
    fun drawerFocusNeedsRevealCorrection(
        focusIndex: Int,
        channelCount: Int,
        revealedRows: Int,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
    ): Boolean {
        if (focusIndex < 0 || focusIndex >= channelCount) return false
        if (!drawerScrollCorrected(revealedRows, drawerScrollTargetIndex(focusIndex, channelCount))) return false
        if (firstVisibleIndex < 0 || lastVisibleIndex < firstVisibleIndex) return false
        return focusIndex < firstVisibleIndex || focusIndex > lastVisibleIndex
    }

    /**
     * t29（单一真源）：统一滚动校正决策。
     *
     * 意图 [desiredFocusIndex] 是"希望处于可视区的焦点行索引"（=-1 无意图），并非首可见行本身；
     * 真正 scrollToItem 的索引由 [drawerScrollTargetIndex] 派生。给定当前揭示行数与可见窗口，
     * 返回唯一动作：
     * - SCROLL：意图有效 + 揭示已覆盖焦点行 + 焦点行不在可视区 → 执行一次 scrollToItem；
     * - CLEAR：焦点行已可见（无需滚，历史回归"焦点已可见不滚"）或意图无效 → 清意图不滚；
     * - WAIT：意图有效但揭示尚未覆盖焦点行 → 等分帧揭示覆盖后再判（不滚、不清）。
     */
    enum class ScrollDecision { SCROLL, CLEAR, WAIT }

    fun drawerScrollDecision(
        desiredFocusIndex: Int,
        channelCount: Int,
        revealedRows: Int,
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
    ): ScrollDecision {
        if (desiredFocusIndex < 0 || desiredFocusIndex >= channelCount) return ScrollDecision.CLEAR
        if (!drawerScrollCorrected(revealedRows, drawerScrollTargetIndex(desiredFocusIndex, channelCount))) {
            return ScrollDecision.WAIT
        }
        if (firstVisibleIndex < 0 || lastVisibleIndex < firstVisibleIndex) return ScrollDecision.WAIT
        // 焦点的"目标首可见行"已落在可视窗口 → 视为已就位、清意图不滚（防过冲回滚）。
        return if (desiredFocusIndex in firstVisibleIndex..lastVisibleIndex) {
            ScrollDecision.CLEAR
        } else {
            ScrollDecision.SCROLL
        }
    }

    // ── t32 Bug4：节目单列收窄 ~30% + 聚焦跑马灯（有限圈数） ────────────────
    /** 节目单列宽：300dp → 210dp（-30%）。 */
    const val DRAWER_PROGRAM_COLUMN_DP = 210
    /** 节目时间列宽：96dp → 68dp（同比收窄）。 */
    const val DRAWER_PROGRAM_TIME_COLUMN_DP = 68
    /**
     * t32 Bug4：超长节目名/时间的跑马灯圈数（有限，绝不 Int.MAX_VALUE）。
     * 与抽屉行一致：只在该行聚焦 + 抽屉可见时跑有限圈，弱机无每帧重绘 jank。
     */
    const val EPG_MARQUEE_ITERATIONS = 2

    fun epgMarqueeIterations(focused: Boolean, drawerVisible: Boolean): Int =
        if (focused && drawerVisible) EPG_MARQUEE_ITERATIONS else 0

    /** 之后每帧追加多少行（t33：3→2，把单帧成本再压一半）。 */
    const val DRAWER_ROWS_PER_FRAME = 2


    /** 抽屉行固定高度（t33：固定行高，避免逐行测量/文字重排）。 */
    const val DRAWER_ROW_HEIGHT_DP = 64

    // ── FULLFIX §1/§2：选台行丰富度 + 上次位置恢复（单变量、纯函数） ──────────

    /**
     * FULLFIX §1：频道行副标题是否组合。
     *
     * 旧 t44 实现只有聚焦行才组合副标题（`DRAWER_FOCUSED_SUBTITLE && focused`），
     * 导致非聚焦行「被精简过度」。现在所有行都组合，focused 只影响颜色/进度强调。
     * 仍保留总开关 [DRAWER_FOCUSED_SUBTITLE]，可一行回退到「完全无副标题」。
     */
    fun rowSubtitleVisible(focused: Boolean): Boolean = DRAWER_FOCUSED_SUBTITLE

    /**
     * FULLFIX §2：抽屉初始滚动索引的单一真源。
     *
     * 优先用上次浏览焦点（按组记忆），其次播放台，都没有则 0。
     * 只做 URL→索引查表，不抛异常；调用方继续使用 [drawerSafeOpenTarget] 做安全钳制。
     */
    fun drawerInitialIndex(
        lastFocusedUrl: String?,
        selectedUrl: String?,
        indexByUrl: Map<String, Int>,
    ): Int {
        val url = lastFocusedUrl ?: selectedUrl
        if (url.isNullOrBlank()) return 0
        return indexByUrl[url] ?: 0
    }

    /**
     * t39（滚动跟随）：焦点走到底部时一次多揭示几行，保证连续 DOWN 不会“撞到列表末尾”。
     * （之前只按 +2/帧补行，快速连按时目标行还没长出来 → 焦点停在原地。）
     */
    const val DRAWER_LOOKAHEAD_ROWS = 16

    /** t39：一屏大约行数（56dp 行 × 12 ≈ 672dp 可视高度）。 */
    const val DRAWER_SCREEN_ROWS = 12

    /**
     * 打开时的工作窗口行数。**最终值 28**（t52 回退）：
     * - t47（=28）：巨帧 3、50th 53ms —— 当前最佳；
     * - t48/t50（=18）：实测**更差**（巨帧 3→4、50th 53→85–89ms、Draw 2.5→16.5ms），故回退。
     * 说明：窗口越小 → 单帧帧数虽少，但每帧累积的重组/测量在弱机上反而更贵，28 是实测拐点。
     */
    const val DRAWER_OPEN_WINDOW_ROWS = 28

    /**
     * t44：**只在聚焦行**显示节目副标题 + 2dp 进度（信息不减法的折中）。
     * 非聚焦行保持 t42 的精简行（台标 + 序号 + 台名，约 2 个节点），聚焦行每帧最多 1 行。
     * 回退：改 false → 回到 t42 的“全行无副标题”。
     */
    const val DRAWER_FOCUSED_SUBTITLE = true

    /**
     * 分帧组合的每帧上限保护：一帧内最多再追加这么多"帧"，避免主线程被长循环占住。
     * （实际由 withFrameNanos 驱动，每帧只跑一次。）
     */
    const val DRAWER_MAX_FRAMES = 64

    /** 打开后延迟多少毫秒才开始组合（0 = 从第 0 帧就开始；分帧模式下不再需要 120ms）。 */
    const val DRAWER_DEFER_CONTENT_MS = 0L

    /**
     * 已就绪行数：首帧给 [DRAWER_FIRST_FRAME_ROWS]，之后每帧 +[DRAWER_ROWS_PER_FRAME]，到 total 为止。
     *
     * t36：**去掉超时加速**。t34 真机证明加速会把单帧组合量抬到 6–12 行，反而制造 300ms 级大帧；
     * 现在恒定 2 行/帧，靠“焦点跟随”按需提前揭示，而不是靠加速追进度。
     */
    fun nextRevealedRows(current: Int, total: Int): Int {
        if (total <= 0) return 0
        if (current <= 0) return DRAWER_FIRST_FRAME_ROWS.coerceAtMost(total)
        return (current + DRAWER_ROWS_PER_FRAME).coerceAtMost(total)
    }

    /** t27（D1）：列表 overscan —— 0 表示严格只组合可见区（不给 LazyColumn 额外预组合）。 */
    const val DRAWER_OVERSCAN_ITEMS = 0

    /**
     * 打开抽屉后延迟多久再启动"紧急台标预取"（毫秒）。
     * 基线根因：原实现 200ms 后就把选中±8 + 整个可视窗口（约 17 个）台标丢给 2 路并发解码，
     * 正好和开抽屉的那几帧抢 CPU。推迟到开合动画结束后再做，单变量可测。
     */
    const val URGENT_PREFETCH_DELAY_MS = 600L

    /** 紧急预取最多覆盖多少个台标（原实现无上限，可视窗口在长列表上可能很大）。 */
    const val MAX_URGENT_PREFETCH = 12

    /** 台标预取集合：选中邻域优先，再补可视窗口，最后截断到 [MAX_URGENT_PREFETCH]。 */
    fun urgentPrefetchIndices(
        selectedIndex: Int,
        visibleIndices: Collection<Int>,
        channelCount: Int,
        radius: Int = 8,
        limit: Int = MAX_URGENT_PREFETCH,
    ): List<Int> {
        if (channelCount <= 0) return emptyList()
        val out = LinkedHashSet<Int>()
        if (selectedIndex in 0 until channelCount) {
            for (i in (selectedIndex - radius)..(selectedIndex + radius)) {
                if (i in 0 until channelCount) out.add(i)
                if (out.size >= limit) return out.toList()
            }
        }
        visibleIndices.filter { it in 0 until channelCount }.forEach { i ->
            if (out.size < limit) out.add(i)
        }
        return out.toList()
    }

    // ── 2) 设置滚动（t13 后每页 ≤6 行，旧 82.78% 基线已过期） ───────────────
    /** 设置页切换时不做转场动画（层内直接替换），保持焦点确定性与零 jank。 */
    const val SETTINGS_PAGE_ANIMATED = false

    // ── 3) 冷启 rebuffer（基线 1 次 / 4288ms） ──────────────────────────────
    /**
     * 冷启后允许使用"较大 start buffer"的探测窗口（毫秒）。
     * 仅用于文档/诊断标注；真正的起播缓冲仍由 `LoadControlTuning` 决定，本轮**不改默认值**
     * （未在设备侧 A/B 前改播放器缓冲会显著拉长 TTFF，违反 t7 约束）。
     */
    const val COLD_START_REBUFFER_WATCH_MS = 6_000L

    // ── 4) 门禁/报告阈值（与 t7 目标一致） ─────────────────────────────────
    /** 单帧预算 16.7ms；`frameMs > 16` 记为 janky（与 gfxinfo 口径一致）。 */
    const val FRAME_BUDGET_MS = 16L

    /** t7 目标：抽屉/设置 Janky ≤20%、50th ≤16ms。 */
    const val TARGET_JANKY_PERCENT = 20.0

    fun isJankyFrame(frameMs: Long, budgetMs: Long = FRAME_BUDGET_MS): Boolean = frameMs > budgetMs

    fun jankyPercent(jankyFrames: Int, totalFrames: Int): Double =
        if (totalFrames <= 0) 0.0 else jankyFrames * 100.0 / totalFrames

    fun meetsJankTarget(jankyFrames: Int, totalFrames: Int): Boolean =
        jankyPercent(jankyFrames, totalFrames) <= TARGET_JANKY_PERCENT
}
