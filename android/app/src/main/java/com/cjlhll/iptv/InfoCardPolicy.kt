package com.cjlhll.iptv

/**
 * t4（INFO_CARD_V2）：信息卡 v2 的**纯逻辑**策略。
 *
 * 这里不 import 任何 Compose / Android 类型：卡片的三态、自动隐藏、最近观看条的可见性与焦点
 * 移动、外部换台、技术参数行都在 JVM 单测里直接验证（`InfoCardPolicyTest` / `InfoCardRecentTest`）。
 * 规格见 docs/INFO_CARD_V2.md，接线见 docs/INFO_CARD_V2_WIRING.md。
 */
object InfoCardPolicy {

    /** V4 §6：卡片底部「最近观看」最多 6 个频道。 */
    const val MAX_RECENT = 6

    /** 静默自动隐藏：手动 OK / 聚焦态为 7s；换台触发较短为 4s。 */
    const val AUTO_HIDE_MS = 7000L
    const val ZAP_AUTO_HIDE_MS = 4000L

    fun autoHideTimeoutMs(manual: Boolean, focused: Boolean): Long =
        if (manual || focused) AUTO_HIDE_MS else ZAP_AUTO_HIDE_MS

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

    /** t52 §⑦：FOCUSED 也 7s 静止自动收起；HIDDEN 无需计时。 */
    const val FOCUSED_IDLE_HIDE_MS = 7_000L

    /** 自动隐藏毫秒：IDLE/FOCUSED 都是 7000ms（FOCUSED 仅静止时 arm），HIDDEN=null。 */
    fun autoHideMs(state: State): Long? = when (state) {
        State.IDLE -> AUTO_HIDE_MS
        State.FOCUSED -> FOCUSED_IDLE_HIDE_MS
        State.HIDDEN -> null
    }

    /**
     * t52 §⑦：是否 arm 自动隐藏计时。距最后一次交互 ≥7s 且无进行中的焦点操作时才 arm。
     * 正在操作（move/switch/按键）刷新 lastInteractionAt → 不 arm，避免操作中途卡片消失。
     */
    fun shouldArmAutoHide(
        state: State,
        lastInteractionAt: Long,
        now: Long,
        interactionInFlight: Boolean = false,
        timeoutMs: Long = FOCUSED_IDLE_HIDE_MS,
    ): Boolean {
        if (state == State.HIDDEN || interactionInFlight) return false
        return now - lastInteractionAt >= timeoutMs
    }

    /**
     * t32 Bug1：主动按 OK 打开信息卡后的行为模式（设置项 pref_info_ok_mode）。
     * - DIRECT（默认）：一次 OK 出卡即进入 FOCUSED，左右键立即选最近观看、OK 跳台；
     * - TWO_STEP：恢复旧的两段式（第一次 OK 出卡 IDLE，第二次 OK 才进 FOCUSED）。
     */
    enum class InfoOkMode { DIRECT, TWO_STEP }

    fun parseInfoOkMode(raw: String?): InfoOkMode =
        InfoOkMode.entries.firstOrNull { it.name == raw } ?: InfoOkMode.TWO_STEP

    /** 主动 OK 出卡后是否直接进入可聚焦态：DIRECT=true；空/1张/回看/快进由调用方单独守卫。 */
    fun openCardFocusesDirectly(mode: InfoOkMode): Boolean = mode == InfoOkMode.DIRECT

    /**
     * t35：FOCUSED 态按键的确定性闭环动作（与 Android KeyEvent 解耦，可 JVM 单测）。
     * Activity 在 isInfoCardFocused 时用它决定"Compose 未消费时的兜底动作"，
     * 无论哪种动作都**消费按键**（绝不穿透到 OPEN_SETTINGS/换台/OSD）。
     */
    enum class FocusedKeyAction { MOVE_LEFT, MOVE_RIGHT, SWITCH, EXIT_FOCUS, NONE }

    /**
     * @param keyCode Android KeyEvent.keyCode 数值（这里用整数避免依赖 Android 类型）。
     * @param isCenter 是否属于确认键（DPAD_CENTER/ENTER/NUMPAD_ENTER，调用方按绑定判定）。
     */
    fun focusedKeyAction(keyCode: Int, isCenter: Boolean): FocusedKeyAction = when {
        isCenter -> FocusedKeyAction.SWITCH
        keyCode == 21 -> FocusedKeyAction.MOVE_LEFT     // KEYCODE_DPAD_LEFT
        keyCode == 22 -> FocusedKeyAction.MOVE_RIGHT    // KEYCODE_DPAD_RIGHT
        keyCode == 4 -> FocusedKeyAction.EXIT_FOCUS     // KEYCODE_BACK
        // FOCUSED 态 UP/DOWN 退聚焦（与 banner 根部 onPreviewKeyEvent 一致）。
        keyCode == 19 || keyCode == 20 -> FocusedKeyAction.EXIT_FOCUS
        else -> FocusedKeyAction.NONE
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

    /**
     * FULLFIX §5（U1）：唯一真源的焦点目标索引。
     * 高亮 / 真实焦点 / OK 目标都必须用这个结果；count<=0 → -1（无目标，不请求焦点）。
     */
    fun focusTargetIndex(focusIndex: Int, count: Int): Int =
        if (count <= 0) -1 else focusIndex.coerceIn(0, count - 1)

    /** FULLFIX §5（U1）：OK 切台目标 = 高亮 focusIndex 指向的那张卡；越界/null 不崩、不误切台。
     *  注意这里**不做 clamp**：clamp 会把 99 变成末尾卡导致误切台；越界应直接无目标。 */
    fun <T> resolveRecentTarget(items: List<T>, focusIndex: Int): T? =
        items.getOrNull(focusIndex)
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

    /** 帧率标签：仅真实帧率 > 0 时返回整值 + "fps"（29.97→30、59.94→60）；未知返回 null（不占位）。 */
    fun frameRateLabel(frameRate: Float): String? {
        if (frameRate.isNaN() || frameRate <= 0f) return null
        return "${Math.round(frameRate)}fps"
    }

    /**
     * 码率：Format.bitrate 单位为 bit/s；<=0 → null；8_300_000 → "8.3Mbps"、950_000 → "0.9Mbps"。
     * 用 HALF_DOWN 保留一位小数（与规格 §10.1 的 950k→0.9 一致；Java 的 "%.1f" 会把它进位成 1.0）。
     */
    fun bitrateLabel(bitrate: Int): String? {
        if (bitrate <= 0) return null
        val mbps = java.math.BigDecimal(bitrate).divide(
            java.math.BigDecimal(1_000_000), 1, java.math.RoundingMode.HALF_DOWN,
        )
        return "${mbps.toPlainString()}Mbps"
    }

    /**
     * t32 Bug2：声道用数字表达（单声道=1.0、立体声=2.0、6ch=5.1、8ch=7.1；其它=N.0）。
     * <=0 → null（隐藏）。注意与老的 [channelLabel]（2ch/单声道）区分，徽标改用本函数。
     */
    fun channelNumberLabel(channelCount: Int): String? = when {
        channelCount <= 0 -> null
        channelCount == 1 -> "1.0"
        channelCount == 2 -> "2.0"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        channelCount == 4 -> "4.0"
        else -> "${channelCount}.0"
    }

    /** 视频编码标签（H.264 / H.265 / AV1 / VP9 / MPEG2）；未知 null。 */
    fun videoCodecShort(mime: String?, codecs: String?): String? {
        val s = "${mime.orEmpty()} ${codecs.orEmpty()}".lowercase()
        return when {
            s.contains("avc") || s.contains("h264") -> "H.264"
            s.contains("hevc") || s.contains("hvc1") || s.contains("hev1") || s.contains("h265") -> "H.265"
            s.contains("av01") || s.contains("av1") -> "AV1"
            s.contains("vp9") -> "VP9"
            s.contains("mpeg2") || s.contains("mp2v") -> "MPEG2"
            else -> null
        }
    }

    /** 声道：1→单声道、2→2ch、6→5.1、8→7.1、其它→Nch；<=0 → null。 */
    fun channelLabel(channelCount: Int): String? = when {
        channelCount <= 0 -> null
        channelCount == 1 -> "单声道"
        channelCount == 2 -> "2ch"
        channelCount == 6 -> "5.1"
        channelCount == 8 -> "7.1"
        else -> "${channelCount}ch"
    }

    /** 分辨率显示：exact=true 且宽高均有效时返回 "1920x1080"，否则返回档位短标（FHD/4K）。 */
    fun resolutionLabel(width: Int, height: Int, exact: Boolean = false): String? {
        if (exact && width > 0 && height > 0) return "${width}x${height}"
        return resolutionTier(width, height)?.label
    }

    /**
     * 右上角信息 Tag：分辨率、真实帧率、声道数字、真实动态范围、视频编码。
     * 只有真实获取到该维度数据时才加入，未知项直接省略、不填占位符。
     */
    fun compactBadges(
        width: Int,
        height: Int,
        frameRate: Float,
        channelCount: Int,
        colorTransfer: Int,
        hasHdrStaticInfo: Boolean,
        mime: String?,
        codecs: String?,
        exactResolution: Boolean = false,
    ): List<String> {
        val out = ArrayList<String>(5)
        resolutionLabel(width, height, exactResolution)?.let(out::add)
        frameRateLabel(frameRate)?.let(out::add)
        channelNumberLabel(channelCount)?.let(out::add)
        dynamicRangeLabel(colorTransfer, hasHdrStaticInfo)?.let(out::add)
        videoCodecShort(mime, codecs)?.let(out::add)
        return out
    }

    /**
     * V4 §6：徽标候选固定三项（分辨率 > 帧率 > 声道），**不再显示码率**（bitrate 参数保留兼容但忽略）。
     * 未知即隐藏、不补 `—`。`bitrateLabel` 仍保留供诊断/日志使用。
     */
    fun badgeCandidates(width: Int, height: Int, frameRate: Float, bitrate: Int, channelCount: Int): List<BadgeCandidate> {
        val out = ArrayList<BadgeCandidate>(3)
        resolutionTier(width, height)?.let { out.add(BadgeCandidate("resolution", it.label)) }
        frameRateLabel(frameRate)?.let { out.add(BadgeCandidate("fps", it)) }
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

    /**
     * t69 ③：5 个 64dp 大 logo 内联；主行 = 台名32 + 分组12 + 最近64 = 108，
     * + 上下 padding 各 4 + 入口提示行 12 = 128dp ≤140。
     */
    const val INFO_CARD_COMPACT_HEIGHT_DP = 128

    fun infoCardHeightV4(showRecent: Boolean): Int =
        32 + 12 + (if (showRecent) 64 else 0) + 4 + 4 + 12

    /** 动态范围：仅在真实 ColorTransfer 可得时返回；未知返回 null（不占位）。 */
    fun dynamicRangeLabel(colorTransfer: Int, hasHdrStaticInfo: Boolean): String? = when (colorTransfer) {
        6 -> "HDR10"
        7 -> "HLG"
        2, 3, 10 -> "SDR"
        else -> if (hasHdrStaticInfo) "HDR" else null
    }

    /** 技术行：视频编码 · 音频编码 · 动态范围 (+ 溢出徽标)，空项省略。 */
    fun techLine(videoCodec: String?, audioCodec: String?, dynamicRange: String?, overflow: List<String>): String =
        (listOfNotNull(videoCodec, audioCodec, dynamicRange) + overflow)
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")

    fun programTitleOrPlaceholder(title: String?): String =
        programTitleOrPlaceholder(title, AppLang.ZH)

    fun programTitleOrPlaceholder(title: String?, lang: AppLang): String =
        title?.takeIf { it.isNotBlank() } ?: if (lang == AppLang.EN) "No program info" else "暂无节目信息"

    /**
     * 节目时间行：end<=0 → ""；直播 `HH:mm–HH:mm · 剩余 N 分钟`（0 → "<1 分钟"）；
     * 回看只给起止（剩余由进度条表达）。
     */
    fun programTimeLine(startMillis: Long, endMillis: Long, nowMillis: Long, catchup: Boolean): String =
        programTimeLine(startMillis, endMillis, nowMillis, catchup, AppLang.ZH)

    fun formatRemainingMinutes(endMillis: Long, nowMillis: Long, lang: AppLang): String {
        if (endMillis <= 0L || endMillis <= nowMillis) return ""
        val remainingMin = (endMillis - nowMillis) / 60_000L
        return if (lang == AppLang.EN) {
            val remaining = if (remainingMin <= 0L) "<1 min" else "$remainingMin min"
            "$remaining left"
        } else {
            val remaining = if (remainingMin <= 0L) "<1 分钟" else "$remainingMin 分钟"
            "剩余 $remaining"
        }
    }

    fun programTimeLine(
        startMillis: Long,
        endMillis: Long,
        nowMillis: Long,
        catchup: Boolean,
        lang: AppLang,
    ): String {
        if (endMillis <= 0L) return ""
        val range = "${clockHm(startMillis)}–${clockHm(endMillis)}"
        if (catchup) return range
        val remainingLabel = formatRemainingMinutes(endMillis, nowMillis, lang)
        if (remainingLabel.isEmpty()) {
            return if (lang == AppLang.EN) "$range · <1 min left" else "$range · 剩余 <1 分钟"
        }
        return "$range · $remainingLabel"
    }

    private val cardClockFormatter: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")

    fun formatClockTime(millis: Long): String =
        if (millis <= 0L) "" else clockHm(millis)

    private fun clockHm(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(cardClockFormatter)

    /**
     * V4 §6：台标下分组小字只显示本台分组名（无「频道 N」前缀、无编码）。
     * 台名由 UI 层单独渲染为 `"$number. $title"`。group 为空 → ""。
     */
    fun groupLine(group: String?): String = group?.takeIf { it.isNotBlank() }?.trim() ?: ""

    /** 保留兼容：台标下小字（V4 不再使用，UI 改用 groupLine）。 */
    fun smallMetaLine(number: Int, group: String?): String {
        val parts = ArrayList<String>(2)
        if (number > 0) parts.add("频道 $number")
        group?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        return parts.joinToString(" · ")
    }

    /**
     * FULLFIX §4.2②：徽标数据源的主值/回退取值。
     * 主值 > 0 用主值，否则用回退值（仍 <=0 返回 null → 策略层隐藏，不显示 "—"）。
     */
    fun takePositive(primary: Float, fallback: Float): Float? =
        if (primary > 0f) primary else fallback.takeIf { it > 0f }

    /** FULLFIX §4.2②：码率版 takePositive（Int）。 */
    fun takePositive(primary: Int, fallback: Int): Int? =
        if (primary > 0) primary else fallback.takeIf { it > 0 }

    /** t59 ③：入口提示分两侧（Row SpaceBetween）：最左频道列表、最右设置。 */
    const val ENTRY_HINT_LEFT = "← 频道列表"
    const val ENTRY_HINT_RIGHT = "→ 设置"
    /** 兼容旧引用（门禁/日志）：拼接文案，UI 已改用左右两个常量。 */
    const val ENTRY_HINT = "$ENTRY_HINT_LEFT　·　$ENTRY_HINT_RIGHT"
}
