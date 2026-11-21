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
     * 跑马灯（`basicMarquee`）的最大圈数。
     *
     * 基线根因：焦点行的节目名用 `basicMarquee(iterations = Int.MAX_VALUE)`，抽屉一打开就**永远**
     * 在请求下一帧；每帧都要把 1080p 的半透明抽屉层重新合成一次，弱机上单帧 46ms，于是
     * gfxinfo 里出现"33/33 全 janky、直方图没有任何 ≤16ms 帧"。改成有限圈数后，动画跑完就不再
     * invalidate，抽屉静止时不再有帧。
     *
     * 回退：改回 `Int.MAX_VALUE`（一行）。
     */
    const val MARQUEE_ITERATIONS = 2

    /** 只有"抽屉打开 + 该行聚焦"时才跑跑马灯，其余一律不挂动画 modifier（避免隐藏态也动画）。 */
    fun marqueeIterations(focused: Boolean, drawerVisible: Boolean): Int =
        if (focused && drawerVisible) MARQUEE_ITERATIONS else 0

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
