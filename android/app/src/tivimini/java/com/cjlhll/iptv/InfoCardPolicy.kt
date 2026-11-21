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

    /** t16（F1）：聚焦态自愈动作。 */
    enum class FocusRecovery { NONE, REQUEST_FOCUS, EXIT_FOCUS }

    /**
     * t16（F1，high）：**FOCUSED 但最近观看为 0** 的状态绝不允许存在——
     * 此时 Compose 内已无焦点节点，而 Activity 的 `isInfoCardFocused` 守卫还会继续吞
     * DPAD/OK/BACK（BACK 会落到系统 `finish()` 退出播放器）。所以：
     * - FOCUSED + 0 条 → 自愈退回 IDLE（EXIT_FOCUS）；
     * - FOCUSED + 有条 → 请求（或重试）小卡焦点（REQUEST_FOCUS，焦点被抢/节点重建后自愈）；
     * - 其它状态 → 不动作。
     */
    fun focusRecoveryAction(state: State, recentCount: Int): FocusRecovery = when {
        state != State.FOCUSED -> FocusRecovery.NONE
        recentCount <= 0 -> FocusRecovery.EXIT_FOCUS
        else -> FocusRecovery.REQUEST_FOCUS
    }

    /** t16（F1）：镜像 `isInfoCardFocused` 必须来自“条内是否真的持有焦点”，而不是 state。 */
    fun aggregateNodeFocus(stripFocusIndex: Int): Boolean = stripFocusIndex >= 0

    /**
     * t16（F2，medium）：**只有用户主动退聚焦（FOCUSED→IDLE）才允许 clearFocus**。
     * `FOCUSED→HIDDEN`（覆盖层打开）绝不能清——`LocalFocusManager` 属于整个 ComposeView，
     * `clearFocus(force = true)` 会把设置抽屉刚拿到的焦点一起清掉（MENU→设置后 DOWN 不动）。
     */
    fun shouldClearFocusOnTransition(oldState: State, newState: State): Boolean =
        oldState == State.FOCUSED && newState == State.IDLE

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

    /** 外部换台（数字/166-167/上一台/抽屉选台/音轨切换）→ 丢弃焦点。
     *  t16（F4）：生产代码已不再直接调用（状态迁移统一在 PlayerActivity 里做），保留是给
     *  `InfoCardPolicyTest` 校验「外部换台必须退聚焦」这条不变量，勿删。 */
    fun onExternalZap(state: State): State = when (state) {
        State.FOCUSED -> State.IDLE
        else -> state
    }

    /**
     * 卡片底部的最近观看条目：排除当前台（D-C3，才能 OK 在 A↔B 之间来回）、最多 [MAX_RECENT]、
     * 顺序保持传入顺序（存储层即“最新在前”）。
     *
     * 注意参数顺序：`isCurrent` 放最后，调用点才能用尾随 lambda（`cardRecentItems(items) { it.selected }`）。
     */
    fun <T> cardRecentItems(items: List<T>, limit: Int = MAX_RECENT, isCurrent: (T) -> Boolean): List<T> {
        if (limit <= 0) return emptyList()
        return items.filterNot(isCurrent).take(limit)
    }

    // ── t24（INFO_CARD_V3）：右上徽标 / 技术行 / 节目行 / 小字（全部纯逻辑、Android-free） ──
    enum class ResolutionTier(val label: String) { SD("SD"), HD("HD"), FHD("FHD"), UHD("4K") }
    data class BadgeCandidate(val key: String, val label: String)
    data class BadgePlan(val visible: List<String>, val overflow: List<String>)

    /** 徽标行策略层宽度上限（4 个徽标 + 分隔绰绰有余；不是布局宽度）。 */
    const val BADGE_MAX_WIDTH_DP = 260.0

    /** 分辨率档：h = height>0 ? height : width；h<=0 → null（隐藏）。 */
    fun resolutionTier(width: Int, height: Int): ResolutionTier? {
        val h = if (height > 0) height else width
        return when {
            h <= 0 -> null
            h < 720 -> ResolutionTier.SD
            h < 1080 -> ResolutionTier.HD
            h < 2160 -> ResolutionTier.FHD
            else -> ResolutionTier.UHD
        }
    }

    /** 帧率：<=0 / NaN → null；否则取整 + "fps"（29.97→30、59.94→60）。 */
    fun frameRateLabel(frameRate: Float): String? {
        if (frameRate.isNaN() || frameRate <= 0f) return null
        return "${Math.round(frameRate)}fps"
    }

    /** 码率：Format.bitrate 单位为 bit/s；<=0 → null；8_300_000 → "8.3Mbps"。 */
    fun bitrateLabel(bitrate: Int): String? {
        if (bitrate <= 0) return null
        return String.format(java.util.Locale.ROOT, "%.1fMbps", bitrate / 1_000_000.0)
    }

    /** 声道：1→单声道、2→2ch、6→5.1、8→7.1、其它→Nch；<=0 → null。 */
    fun channelLabel(channelCount: Int): String? = when {
        channelCount <= 0 -> null
        1 -> "单声道"
        2 -> "2ch"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${channelCount}ch"
    }

    /** 徽标候选（优先级：分辨率 > 帧率 > 码率 > 声道；未知跳过、不占位）。 */
    fun badgeCandidates(width: Int, height: Int, frameRate: Float, bitrate: Int, channelCount: Int): List<BadgeCandidate> {
        val out = ArrayList<BadgeCandidate>(4)
        resolutionTier(width, height)?.let { out.add(BadgeCandidate("resolution", it.label)) }
        frameRateLabel(frameRate)?.let { out.add(BadgeCandidate("fps", it)) }
        bitrateLabel(bitrate)?.let { out.add(BadgeCandidate("bitrate", it)) }
        channelLabel(channelCount)?.let { out.add(BadgeCandidate("channels", it)) }
        return out
    }

    /**
     * 把候选塞进一行：按优先级累加估算宽度，超 [maxWidthDp] 的从那一刻起全部进 overflow
     * （保证 visible 是候选前缀、overflow 是后缀，绝不换行）。
     */
    fun planBadges(
        candidates: List<BadgeCandidate>,
        maxWidthDp: Double = BADGE_MAX_WIDTH_DP,
        charWidthDp: Double = 7.0,
        gapDp: Double = 8.0,
        dotDp: Double = 10.0,
    ): BadgePlan {
        val visible = ArrayList<String>()
        val overflow = ArrayList<String>()
        var used = 0.0
        var full = false
        for (c in candidates) {
            val add = c.label.length * charWidthDp + if (visible.isEmpty()) 0.0 else (dotDp + gapDp)
            if (!full && used + add <= maxWidthDp) {
                visible.add(c.label)
                used += add
            } else {
                full = true
                overflow.add(c.label)
            }
        }
        return BadgePlan(visible, overflow)
    }

    /** 视频编码：avc→H.264、hevc→H.265、av01→AV1；未知 null。 */
    fun videoCodecLabel(mime: String?, codecs: String?): String? {
        val s = "${mime.orEmpty()} ${codecs.orEmpty()}".lowercase()
        return when {
            s.contains("avc") || s.contains("h264") -> "H.264"
            s.contains("hevc") || s.contains("hvc1") || s.contains("hev1") || s.contains("h265") -> "H.265"
            s.contains("av01") || s.contains("av1") -> "AV1"
            else -> null
        }
    }

    /** 音频编码：取 AudioTrackUi.summary 的第一段；未知 null。 */
    fun audioCodecLabel(audioSummary: String?): String? {
        val first = audioSummary?.split("·", "|", ",")?.firstOrNull()?.trim().orEmpty().lowercase()
        if (first.isEmpty()) return null
        return when {
            first.contains("mpeg-l2") || first.contains("mpeg-l2") || first.contains("mp2") || first.contains("mpeg2") -> "MPEG-L2"
            first.contains("eac3") -> "EAC3"
            first.contains("ac-3") || first.contains("ac3") -> "AC-3"
            first.contains("aac") -> "AAC"
            first.contains("opus") -> "OPUS"
            first.contains("flac") -> "FLAC"
            first.contains("mp3") -> "MP3"
            else -> null
        }
    }

    /** 动态范围：colorInfo 缺失或非 HDR → SDR；ST2084(6)→HDR10、HLG(7)→HLG。 */
    fun dynamicRangeLabel(colorTransfer: Int, hasHdrStaticInfo: Boolean): String = when (colorTransfer) {
        6 -> "HDR10"
        7 -> "HLG"
        else -> "SDR"
    }

    /** 技术行：视频编码 · 音频编码 · 动态范围 (+ 溢出徽标)，空项省略。 */
    fun techLine(videoCodec: String?, audioCodec: String?, dynamicRange: String?, overflow: List<String>): String =
        (listOfNotNull(videoCodec, audioCodec, dynamicRange) + overflow)
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")

    fun programTitleOrPlaceholder(title: String?): String =
        title?.takeIf { it.isNotBlank() } ?: "暂无节目信息"

    /**
     * 节目时间行：end<=0 → ""；直播 `HH:mm–HH:mm · 剩余 N 分钟`（0 → "<1 分钟"）；
     * 回看只给起止（剩余由进度条表达）。
     */
    fun programTimeLine(startMillis: Long, endMillis: Long, nowMillis: Long, catchup: Boolean): String {
        if (endMillis <= 0L) return ""
        val range = "${clockHm(startMillis)}–${clockHm(endMillis)}"
        if (catchup) return range
        val remainingMin = (endMillis - nowMillis).coerceAtLeast(0L) / 60_000L
        val remaining = if (remainingMin <= 0L) "<1 分钟" else "$remainingMin 分钟"
        return "$range · 剩余 $remaining"
    }

    private val cardClockFormatter: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    private fun clockHm(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(cardClockFormatter)

    /** 台名下小字：`频道 N · 分组 · 音频编码`（存在项才拼接）。 */
    fun smallMetaLine(number: Int, group: String?, audioCodec: String?): String {
        val parts = ArrayList<String>(3)
        if (number > 0) parts.add("频道 $number")
        group?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        audioCodec?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        return parts.joinToString(" · ")
    }

    /** 入口提示（captain 要求：OK 默认改为显示信息卡后，频道列表入口必须可见）。 */
    const val ENTRY_HINT = "← 频道列表　·　→ 设置"
}
