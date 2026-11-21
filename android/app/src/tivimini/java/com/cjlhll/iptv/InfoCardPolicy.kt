package com.cjlhll.iptv

/**
 * t4（INFO_CARD_V2）：信息卡 v2 的**纯逻辑**策略。
 *
 * 这里不 import 任何 Compose / Android 类型：卡片的三态、自动隐藏、最近观看条的可见性与焦点
 * 移动、外部换台、技术参数行都在 JVM 单测里直接验证（`InfoCardPolicyTest` / `InfoCardRecentTest`）。
 * 规格见 docs/INFO_CARD_V2.md，接线见 docs/INFO_CARD_V2_WIRING.md。
 */
object InfoCardPolicy {

    /** 卡片底部「最近观看」最多几张（存储层仍是最多 10 条，这里只做视图截断）。 */
    const val MAX_RECENT = 6

    /** 空闲 3s 自动隐藏（旧版 5000ms）；聚焦时不隐藏。 */
    const val AUTO_HIDE_MS = 3000L

    /** 卡片可见性三态。 */
    enum class State { HIDDEN, IDLE, FOCUSED }

    /** 卡片可执行的动作（供 UI/日志/测试共用）。 */
    enum class Action {
        NONE, SHOW, FOCUS_IN, MOVE_LEFT, MOVE_RIGHT, SWITCH, UNFOCUS, HIDE, TIMER_EXPIRED,
    }

    /** 卡片是否可见：HIDDEN 时只有快进/快退浮层会把卡片带回来（回看进度行）。 */
    fun isVisible(state: State, seekOverlayVisible: Boolean): Boolean =
        state != State.HIDDEN || seekOverlayVisible

    /** 自动隐藏毫秒；null = 不自动隐藏（FOCUSED，以及 HIDDEN 时无需计时）。 */
    fun autoHideMs(state: State): Long? = when (state) {
        State.IDLE -> AUTO_HIDE_MS
        State.FOCUSED, State.HIDDEN -> null
    }

    /** 最近观看条是否渲染：有条、非回看、非快进浮层。 */
    fun showRecentRow(recentCount: Int, catchup: Boolean, seekOverlayVisible: Boolean): Boolean =
        recentCount > 0 && !catchup && !seekOverlayVisible

    /**
     * IDLE 时再按一次 OK 能否进入聚焦（两段式 OK，D-C2）。
     * 条为空 / 回看中 / 快进浮层中 → false（只重置计时，不改状态）。
     */
    fun canFocusRecent(recentCount: Int, catchup: Boolean, seekOverlayVisible: Boolean): Boolean =
        showRecentRow(recentCount, catchup, seekOverlayVisible)

    /** 焦点在 0..count-1 内移动；首尾 clamp，不环绕（count<=0 → 0）。 */
    fun nextFocusIndex(current: Int, count: Int, delta: Int): Int {
        if (count <= 0) return 0
        val target = current + delta
        return target.coerceIn(0, count - 1)
    }

    /** 外部换台（数字/166-167/上一台/抽屉选台/音轨切换）→ 丢弃焦点。 */
    fun onExternalZap(state: State): State = when (state) {
        State.FOCUSED -> State.IDLE
        else -> state
    }

    /**
     * 卡片底部的最近观看条目：排除当前台（D-C3，才能 OK 在 A↔B 之间来回）、最多 [MAX_RECENT]、
     * 顺序保持传入顺序（存储层即“最新在前”）。
     */
    fun <T> cardRecentItems(items: List<T>, isCurrent: (T) -> Boolean, limit: Int = MAX_RECENT): List<T> {
        if (limit <= 0) return emptyList()
        return items.filterNot(isCurrent).take(limit)
    }

    /**
     * 中部技术参数行：`音轨 mpeg-L2 · 2ch · 1920x1080 · 3000 kbps`。
     * 空项省略；全空返回空串（UI 不渲染该行）。audioLabel 存在时不再重复 codec/声道（沿用旧规则）。
     */
    fun techLine(
        audioLabel: String?,
        width: Int,
        height: Int,
        bitrate: Int,
        channelCount: Int = -1,
        sampleMimeType: String? = null,
    ): String {
        val parts = ArrayList<String>(4)
        val hasAudioLabel = !audioLabel.isNullOrBlank()
        if (hasAudioLabel) {
            parts.add("音轨 ${audioLabel!!.trim()}")
        } else {
            val codec = sampleMimeType.orEmpty().substringAfter("/", "").trim()
            if (codec.isNotEmpty()) parts.add(codec)
            if (channelCount > 0) parts.add("${channelCount}ch")
        }
        if (width > 0 && height > 0) parts.add("${width}x$height")
        if (bitrate > 0) parts.add("${bitrate / 1000} kbps")
        return parts.joinToString(" · ")
    }

    /** 入口提示（captain 要求：OK 默认改为显示信息卡后，频道列表入口必须可见）。 */
    const val ENTRY_HINT = "← 频道列表　·　→ 设置"
}
