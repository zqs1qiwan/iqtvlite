package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * t4（docs/INFO_CARD_V2.md）：播放器底部信息卡 v2。
 *
 * - 顶部：96×60 大台标 + 频道号 overline + `headlineLarge` 32sp 大台名；
 * - 中部：`titleLarge` 当前节目 + 3dp 进度条 + 单行技术参数（音轨 · 分辨率 · 码率）；
 * - 底部：`最近观看` 横向小卡（≤6、40×40 台标、12sp 台名、可左右聚焦、OK 切台）；
 * - 三态（HIDDEN/IDLE/FOCUSED）由 [InfoCardPolicy] 裁决，空闲 3s 自动隐藏、聚焦不隐藏；
 * - 0dp / 无边框 / 无阴影 / 焦点只用 `surfaceVariant` 底色；未聚焦时不拦截任何按键。
 *
 * 零拷贝红线：本文件只画 Compose 覆盖层，不触碰 PlayerView/SurfaceView。
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalComposeUiApi::class)
fun ChannelInfoBanner(
    state: InfoCardPolicy.State,
    seekOverlayVisible: Boolean,
    channel: Channel?,
    programTitle: String?,
    channelNumber: Int = -1,
    programProgress: Float? = null,
    videoFormat: Format?,
    // t175: current audio track (language · codec · channels); omitted entirely when null.
    audioLabel: String? = null,
    // FULLFIX §4.2②：声道数取实际播放音轨 Format；码率/帧率主值缺失时的回退（来自当前 Format）。
    audioChannelCount: Int = -1,
    videoBitrateFallback: Int = -1,
    frameRateFallback: Float = -1f,
    catchupConfiguration: CatchupConfiguration? = null,
    // t24（INFO_CARD_V3）：右侧节目行所需信息（起止/当前时刻/分组小字）。
    programStartMs: Long = 0L,
    programEndMs: Long = 0L,
    nowMillis: Long = System.currentTimeMillis(),
    groupLabel: String? = null,
    // t4：底部最近观看快跳（已排除当前台、最多 6 条）。
    recentCards: List<RecentChannelUi> = emptyList(),
    focusIndex: Int = 0,
    onMoveFocus: (Int) -> Unit = {},
    onSwitchRecent: (Int) -> Unit = {},
    onExitFocus: () -> Unit = {},
    onFocusedChanged: (Boolean) -> Unit = {},
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // UI风格和抽屉保持一致：抽屉是 surface alpha 0.92f；0dp、无边框、无阴影。
    val containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val shape = RoundedCornerShape(0.dp)
    val visible = InfoCardPolicy.isVisible(state, seekOverlayVisible)
    val focused = state == InfoCardPolicy.State.FOCUSED

    val recentCount = recentCards.size
    val showRecent = InfoCardPolicy.showRecentRow(
        recentCount = recentCount,
        catchup = catchupConfiguration != null,
        seekOverlayVisible = seekOverlayVisible,
    )

    val focusManager = LocalFocusManager.current
    val requesters = remember(recentCount) { List(recentCount) { FocusRequester() } }

    // t16（F1）：聚合“最近观看条里到底有没有节点真的持有焦点”。
    // 旧实现只在获得焦点时回调 onFocusedChanged(true)，从不回调 false → Activity 的
    // isInfoCardFocused 镜像可能长期为 true；一旦节点被后台刷新/换源重建或窗口焦点被抢，
    // 守卫会继续吞 DPAD/OK/BACK，BACK 落到系统 finish() 退出播放器。
    var stripFocusIndex by remember { mutableStateOf(-1) }
    var wasFocused by remember { mutableStateOf(false) }
    var lastState by remember { mutableStateOf(state) }

    // F1/F2 状态机：自愈 + 只在“用户主动退聚焦”时清焦点。
    LaunchedEffect(state, focusIndex, recentCount, stripFocusIndex) {
        when (InfoCardPolicy.focusRecoveryAction(state, recentCount)) {
            InfoCardPolicy.FocusRecovery.EXIT_FOCUS -> {
                // FOCUSED 但 0 条 → 绝不允许存在；退回 IDLE（Activity 镜像随之为 false）。
                wasFocused = false
                onExitFocus()
            }
            InfoCardPolicy.FocusRecovery.REQUEST_FOCUS -> {
                // FULLFIX §5（U1）：焦点必须跟随高亮 focusIndex。旧的 `if (stripFocusIndex < 0)`
                // 守卫使得真实焦点已在卡 0 时，RIGHT 改了 focusIndex 也不会重新请求卡 1 的焦点
                // （高亮=卡1、真焦点=卡0、OK 目标=卡0，三处不一致 → 用户「不能切换」）。
                // 现在每次 focusIndex 变化都请求目标卡；先同步 stripFocusIndex 再请求，
                // 避免 aggregateNodeFocus 短暂为 false 触发镜像抖动。
                val target = InfoCardPolicy.focusTargetIndex(focusIndex, recentCount)
                if (target >= 0) {
                    stripFocusIndex = target
                    runCatching { requesters.getOrNull(target)?.requestFocus() }
                }
                wasFocused = true
            }
            InfoCardPolicy.FocusRecovery.NONE -> {
                // F2：只有 FOCUSED→IDLE（用户主动退聚焦）才 clearFocus；
                // FOCUSED→HIDDEN（覆盖层/计时器）不清——否则会清掉设置抽屉刚拿到的焦点。
                if (InfoCardPolicy.shouldClearFocusOnTransition(lastState, state) && wasFocused) {
                    runCatching { focusManager.clearFocus(force = true) }
                }
                wasFocused = false
            }
        }
        lastState = state
    }

    // 镜像同步：条内有节点聚焦才算“聚焦”。
    LaunchedEffect(stripFocusIndex) {
        onFocusedChanged(InfoCardPolicy.aggregateNodeFocus(stripFocusIndex))
    }
    // 兜底：卡片隐藏时镜像一律为 false（覆盖层接管键盘）。
    LaunchedEffect(state) {
        if (state == InfoCardPolicy.State.HIDDEN) onFocusedChanged(false)
    }

    // t24（INFO_CARD_V3）：右上徽标 / 技术行 / 节目行 / 小字（全部来自 InfoCardPolicy 纯函数）。
    // FULLFIX §4.2②：帧率/码率用主值优先、回退补齐；声道用音频 Format 的 channelCount（不再取 vf）。
    val vf = videoFormat
    val frameRate = InfoCardPolicy.takePositive(vf?.frameRate ?: -1f, frameRateFallback)
    val bitrate = InfoCardPolicy.takePositive(vf?.bitrate ?: -1, videoBitrateFallback)
    val badgePlan = InfoCardPolicy.planBadges(
        InfoCardPolicy.badgeCandidates(
            vf?.width ?: 0, vf?.height ?: 0, frameRate ?: -1f, bitrate ?: -1, audioChannelCount,
        )
    )
    val videoCodec = InfoCardPolicy.videoCodecLabel(vf?.sampleMimeType, vf?.codecs)
    val audioCodec = InfoCardPolicy.audioCodecLabel(audioLabel)
    val colorInfo = vf?.colorInfo
    val dynamicRange = InfoCardPolicy.dynamicRangeLabel(colorInfo?.colorTransfer ?: -1, colorInfo?.hdrStaticInfo != null)
    val techText = InfoCardPolicy.techLine(videoCodec, audioCodec, dynamicRange, badgePlan.overflow)
    val programText = InfoCardPolicy.programTitleOrPlaceholder(programTitle)
    val programTime = InfoCardPolicy.programTimeLine(
        startMillis = programStartMs,
        endMillis = programEndMs,
        nowMillis = nowMillis,
        catchup = catchupConfiguration != null,
    )
    // FULLFIX §4.2①：台标下只留「频道 N · 分组」，编码已移到技术行。
    val smallMeta = InfoCardPolicy.smallMetaLine(channelNumber, groupLabel)
    val progressValue: Float? = programProgress ?: catchupConfiguration?.let { c ->
        val d = (c.endMillis - c.startMillis).toFloat()
        if (d <= 0f) null else ((c.positionMs - c.startMillis).toFloat() / d).coerceIn(0f, 1f)
    }

    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("info_card")
                .semantics {
                    testTagsAsResourceId = true
                    contentDescription = "频道信息 ${if (channelNumber > 0) "$channelNumber " else ""}${channel?.title.orEmpty()}"
                }
                .background(containerColor, shape)
                .onPreviewKeyEvent { event ->
                    // 只有聚焦态接管方向键/OK/BACK；其余状态一律不拦截（红线 §0.2-3）。
                    if (!focused) return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onMoveFocus(-1); true }
                        Key.DirectionRight -> { onMoveFocus(1); true }
                        // FULLFIX §5（U1）：OK 目标统一取高亮 focusIndex（不是事件来源卡 index）。
                        // 放在根部统一处理，空/1 张也安全（resolveRecentTarget 返回 null → 不切台）。
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (showRecent) onSwitchRecent(InfoCardPolicy.focusTargetIndex(focusIndex, recentCount).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown, Key.Back -> { onExitFocus(); true }
                        else -> false
                    }
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 16.dp)
            ) {
                // ── 右上徽标行（单行、右对齐、固定 24dp、绝不换行） ──────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (badgePlan.visible.isNotEmpty()) {
                        Text(
                            text = badgePlan.visible.joinToString("  ·  "),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_badges")
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // ── 主行：台标(160×96) + 台名(34sp) 同行；右侧节目列 ────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChannelLogo(
                        logoUrl = channel?.logoUrl,
                        fallbackTitle = channel?.title ?: "",
                        modifier = Modifier
                            .size(width = 160.dp, height = 96.dp)
                            .testTag("info_card_logo"),
                        width = 160.dp,
                        height = 96.dp
                    )

                    Spacer(modifier = Modifier.width(24.dp))

                    Column(modifier = Modifier.weight(0.44f)) {
                        Text(
                            text = channel?.title ?: "未知频道",
                            style = MaterialTheme.typography.headlineLarge.copy(fontSize = 34.sp, lineHeight = 42.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_title")
                        )
                        Row(modifier = Modifier.height(16.dp).testTag("info_card_group")) {
                            Text(
                                text = smallMeta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_number")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(28.dp))

                    Column(modifier = Modifier.weight(0.56f)) {
                        Text(
                            text = programText,
                            style = MaterialTheme.typography.titleLarge,
                            color = if (programTitle.isNullOrBlank()) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_program")
                        )
                        if (programTime.isNotEmpty()) {
                            Text(
                                text = programTime,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .height(16.dp)
                                    .testTag("info_card_program_time")
                            )
                        }
                        if (progressValue != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            BannerProgressBar(
                                progress = progressValue,
                                modifier = Modifier.testTag("info_card_progress")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // ── 技术行（单行 12sp α0.60；编码·音频·HDR + 溢出徽标） ────────────
                if (techText.isNotEmpty()) {
                    Text(
                        text = techText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .testTag("info_card_tech")
                    )
                }

                // FULLFIX §4.2③：技术→最近标签 6dp（替代原隐式留白）
                Spacer(modifier = Modifier.height(6.dp))

                // ── 底部：最近观看快跳（≤6；空/回看/快进中整条不渲染） ──────────────
                if (showRecent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // FULLFIX §4.2③：去掉内层 40dp 左右 padding（父 Column 已有），只留 bottom 间距。
                            .padding(bottom = 6.dp)
                    ) {
                        Text(
                            text = "最近观看",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f),
                            modifier = Modifier.testTag("info_card_recent_label")
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        EntryHint()
                    }
                    RecentStrip(
                        items = recentCards,
                        requesters = requesters,
                        onCardFocusChanged = { index, isFocused ->
                            if (isFocused) stripFocusIndex = index
                            else if (stripFocusIndex == index) stripFocusIndex = -1
                        },
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            // FULLFIX §4.2③：无最近条时同样去掉内层左右 40dp。
                            .padding(bottom = 16.dp)
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        EntryHint()
                    }
                }
            }
        }
    }

    if (animate) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = modifier.fillMaxWidth()
        ) {
            content()
        }
    } else {
        if (!visible) return
        Box(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }
}

/** 入口提示：OK 默认改为“显示信息卡”后，频道列表/设置的入口必须在卡片上可见（不可聚焦）。 */
@Composable
private fun EntryHint() {
    Text(
        text = InfoCardPolicy.ENTRY_HINT,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        maxLines = 1,
        modifier = Modifier.testTag("info_card_hint")
    )
}

/**
 * 最近观看小卡行：普通 Row（≤6，无回收抖动），焦点只改底色/文字色，不改尺寸 → 无 remeasure。
 * FULLFIX §5（U1）：按键统一由 banner 根部 onPreviewKeyEvent 处理（高亮=焦点=OK 目标同一真源），
 * 这里只负责渲染 + 上报真实焦点，不再自带一套按键路径。
 */
@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    requesters: List<FocusRequester>,
    onCardFocusChanged: (Int, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // FULLFIX §4.2③/U1：去掉内层 40dp 左右 padding（父 Column 已有）；固定高 + clip 防末尾贴边溢出。
            .height(64.dp)
            .clipToBounds()
            .testTag("info_card_recent_row"),
        verticalArrangement = Arrangement.Start,
    ) {
        items.forEachIndexed { index, item ->
            var isFocused by remember(item.title, index) { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .size(width = 104.dp, height = 64.dp)
                    .testTag("info_card_recent_$index")
                    .semantics { contentDescription = "最近观看第${index + 1}个 ${item.number}. ${item.title}" }
                    .focusRequester(requesters[index])
                    .onFocusChanged {
                        isFocused = it.isFocused
                        onCardFocusChanged(index, it.isFocused)
                    }
                    // FULLFIX §5（U1）：卡片自身不再处理按键（原来这里用事件来源卡 index 调
                    // onSwitchRecent(index)，与高亮 focusIndex 不一致 → OK 切错台）。
                    // 所有按键统一在 banner 根部 onPreviewKeyEvent 处理；卡片只上报焦点。
                    .focusable()
                    .background(
                        if (isFocused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    )
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ChannelLogo(
                    logoUrl = item.logoUrl,
                    fallbackTitle = item.title,
                    width = 40.dp,
                    height = 40.dp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isFocused) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (index != items.lastIndex) Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

data class CatchupConfiguration(
    val startMillis: Long,
    val endMillis: Long,
    val positionMs: Long
)

// t24：v3 把回看进度并入右侧节目行（三态同高），独立 CatchupProgressRow 已删除。

@Composable
fun TechSpecText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
}

@Composable
private fun BannerProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f)
    Box(
        modifier = modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .height(3.dp)
            .background(track, RoundedCornerShape(0.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .background(fill, RoundedCornerShape(0.dp))
        )
    }
}
