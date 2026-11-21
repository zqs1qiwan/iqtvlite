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
     * t20（E4）：抽屉容器用**不透明** surface，而不是 `surface.copy(alpha=0.92f)`。
     * 半透明层会迫使每一帧对 1080p 面板做整层 alpha 合成（弱机 GPU 上很贵）。
     */
    const val DRAWER_OPAQUE_CONTAINER = true

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

    /** 之后每帧追加多少行（t33：3→2，把单帧成本再压一半）。 */
    const val DRAWER_ROWS_PER_FRAME = 2


    /** 抽屉行固定高度（t33：固定行高，避免逐行测量/文字重排）。 */
    const val DRAWER_ROW_HEIGHT_DP = 56

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
