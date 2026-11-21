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
     * t20（E1）：打开抽屉后隔多少毫秒才组合列表内容（0 = 立即组合，旧行为）。
     * 非 0 时，开合的第一帧只画面板/标题，列表内容延迟组合，把重活移出打开那一帧。
     * 默认 0（保持观感/交互不变）；需要 A/B 时改这一处。
     */
    const val DRAWER_DEFER_CONTENT_MS = 0L

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
