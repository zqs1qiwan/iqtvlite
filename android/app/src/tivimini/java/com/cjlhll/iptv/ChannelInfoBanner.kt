package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Format
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * 重构后的播放器底部信息卡（统一极简曜石暗色风格）：
 * - 修复回看进度条公式 Bug（positionMs 已是相对节目开头的毫秒，不能再减 startMillis）；
 * - 修复最近观看 5 张台标横向裁剪问题（52dp 卡片 + 8dp 间距 = 292dp，在 960dp/1280dp TV 均完整无裁剪）；
 * - 帧安全焦点请求（withFrameNanos 后 requestFocus），逻辑焦点与真实焦点一致，不丢高亮。
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
    audioLabel: String? = null,
    audioChannelCount: Int = -1,
    videoBitrateFallback: Int = -1,
    frameRateFallback: Float = -1f,
    catchupConfiguration: CatchupConfiguration? = null,
    programStartMs: Long = 0L,
    programEndMs: Long = 0L,
    nowMillis: Long = System.currentTimeMillis(),
    groupLabel: String? = null,
    recentCards: List<RecentChannelUi> = emptyList(),
    focusIndex: Int = 0,
    onMoveFocus: (Int) -> Unit = {},
    onSwitchRecent: (Int) -> Unit = {},
    onExitFocus: () -> Unit = {},
    onFocusedChanged: (Boolean) -> Unit = {},
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val containerColor = TvDesignTokens.SurfaceObsidian.copy(alpha = TvDesignTokens.ALPHA_INFO_CARD)
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

    var stripFocusIndex by remember { mutableStateOf(-1) }
    var wasFocused by remember { mutableStateOf(false) }
    var lastState by remember { mutableStateOf(state) }

    LaunchedEffect(state, focusIndex, recentCount) {
        when (InfoCardPolicy.focusRecoveryAction(state, recentCount)) {
            InfoCardPolicy.FocusRecovery.EXIT_FOCUS -> {
                wasFocused = false
                stripFocusIndex = -1
                onExitFocus()
            }
            InfoCardPolicy.FocusRecovery.REQUEST_FOCUS -> {
                val target = InfoCardPolicy.focusTargetIndex(focusIndex, recentCount)
                if (target >= 0) {
                    stripFocusIndex = target
                    repeat(4) {
                        withFrameNanos { }
                        if (runCatching { requesters.getOrNull(target)?.requestFocus() }.isSuccess) {
                            return@repeat
                        }
                    }
                }
                wasFocused = true
            }
            InfoCardPolicy.FocusRecovery.NONE -> {
                if (InfoCardPolicy.shouldClearFocusOnTransition(lastState, state) && wasFocused) {
                    runCatching { focusManager.clearFocus(force = true) }
                }
                stripFocusIndex = -1
                wasFocused = false
            }
        }
        lastState = state
    }

    LaunchedEffect(stripFocusIndex) {
        onFocusedChanged(InfoCardPolicy.aggregateNodeFocus(stripFocusIndex))
    }
    LaunchedEffect(state) {
        if (state == InfoCardPolicy.State.HIDDEN) onFocusedChanged(false)
    }

    val vf = videoFormat
    val frameRate = InfoCardPolicy.takePositive(vf?.frameRate ?: -1f, frameRateFallback)
    val colorInfo = vf?.colorInfo
    val compactBadgeText = InfoCardPolicy.compactBadges(
        width = vf?.width ?: 0,
        height = vf?.height ?: 0,
        frameRate = frameRate ?: -1f,
        channelCount = audioChannelCount,
        colorTransfer = colorInfo?.colorTransfer ?: -1,
        hasHdrStaticInfo = colorInfo?.hdrStaticInfo != null,
        mime = vf?.sampleMimeType,
        codecs = vf?.codecs,
    ).joinToString(" · ")

    val programText = InfoCardPolicy.programTitleOrPlaceholder(programTitle)
    val programTime = InfoCardPolicy.programTimeLine(
        startMillis = programStartMs,
        endMillis = programEndMs,
        nowMillis = nowMillis,
        catchup = catchupConfiguration != null,
    )
    val smallMeta = InfoCardPolicy.groupLine(groupLabel)
    val titleWithNumber = if (channelNumber > 0) {
        "$channelNumber. ${channel?.title ?: "未知频道"}"
    } else {
        channel?.title ?: "未知频道"
    }

    // 修复 P0-9：catchupConfiguration.positionMs 已经是相对节目开头的毫秒数（0..duration）
    val progressValue: Float? = programProgress ?: catchupConfiguration?.let { c ->
        val duration = (c.endMillis - c.startMillis).toFloat()
        if (duration <= 0f) null else (c.positionMs.toFloat() / duration).coerceIn(0f, 1f)
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
                .background(containerColor, TvDesignTokens.ShapeFlat)
                .onPreviewKeyEvent { event ->
                    if (!focused) return@onPreviewKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onMoveFocus(-1); true }
                        Key.DirectionRight -> { onMoveFocus(1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (showRecent) {
                                onSwitchRecent(InfoCardPolicy.focusTargetIndex(focusIndex, recentCount).coerceAtLeast(0))
                            }
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
                    .padding(start = 36.dp, end = 36.dp, top = 10.dp, bottom = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChannelLogo(
                            logoUrl = channel?.logoUrl,
                            fallbackTitle = channel?.title ?: "",
                            modifier = Modifier
                                .size(width = 144.dp, height = 90.dp)
                                .testTag("info_card_logo"),
                            width = 144.dp,
                            height = 90.dp
                        )

                        Spacer(modifier = Modifier.width(24.dp))

                        Column(modifier = Modifier.weight(0.46f)) {
                            Text(
                                text = titleWithNumber,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = 26.sp,
                                    lineHeight = 30.sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = TvDesignTokens.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_title")
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.height(16.dp).testTag("info_card_group")
                            ) {
                                Text(
                                    text = smallMeta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TvDesignTokens.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("info_card_number")
                                )
                            }
                            if (showRecent) {
                                Spacer(modifier = Modifier.height(4.dp))
                                RecentStrip(
                                    items = recentCards,
                                    focused = focused,
                                    logicalFocusIndex = focusIndex,
                                    requesters = requesters,
                                    onCardFocusChanged = { index, isFocused ->
                                        if (isFocused) stripFocusIndex = index
                                        else if (stripFocusIndex == index) stripFocusIndex = -1
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(24.dp))

                        Column(modifier = Modifier.weight(0.54f).padding(top = 20.dp)) {
                            Text(
                                text = programText,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Medium),
                                color = if (programTitle.isNullOrBlank()) {
                                    TvDesignTokens.TextMuted
                                } else {
                                    TvDesignTokens.TextPrimary
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_program")
                            )
                            if (programTime.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = programTime,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TvDesignTokens.TextSecondary,
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

                    // 右上角紧凑技术参数徽标（分辨率 · 帧率 · 声道 · 动态范围 · 编码）
                    if (compactBadgeText.isNotEmpty()) {
                        Text(
                            text = compactBadgeText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = TvDesignTokens.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .testTag("info_card_badges")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.height(14.dp).fillMaxWidth()
                ) {
                    EntryHintLeft()
                    EntryHintRight()
                }
            }
        }
    }

    if (animate) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(animationSpec = tween(150), initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = tween(120), targetOffsetY = { it }),
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

@Composable
private fun EntryHintLeft() {
    Text(
        text = InfoCardPolicy.ENTRY_HINT_LEFT,
        style = MaterialTheme.typography.labelMedium,
        color = TvDesignTokens.TextMuted,
        maxLines = 1,
        modifier = Modifier.testTag("info_card_hint_left")
    )
}

@Composable
private fun EntryHintRight() {
    Text(
        text = InfoCardPolicy.ENTRY_HINT_RIGHT,
        style = MaterialTheme.typography.labelMedium,
        color = TvDesignTokens.TextMuted,
        maxLines = 1,
        modifier = Modifier.testTag("info_card_hint_right")
    )
}

/**
 * 底部最近观看条：5 张 52dp 台标 + 8dp 间距 = 292dp，在 960dp/1280dp 电视均完整展示零裁剪；
 * 聚焦态 = 清晰磨砂底 + 底部 2dp 品牌蓝指示条，逻辑焦点与真实焦点统一。
 */
@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    focused: Boolean,
    logicalFocusIndex: Int,
    requesters: List<FocusRequester>,
    onCardFocusChanged: (Int, Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clipToBounds()
            .testTag("info_card_recent_row"),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            var nodeFocused by remember(item.title, index) { mutableStateOf(false) }
            val isHighlighted = focused && (nodeFocused || index == logicalFocusIndex)
            val hasLogo = item.logoUrl?.isNotBlank() == true
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .testTag("info_card_recent_$index")
                    .semantics { contentDescription = "最近观看第${index + 1}个 ${item.number}. ${item.title}" }
                    .focusRequester(requesters[index])
                    .onFocusChanged {
                        nodeFocused = it.isFocused
                        onCardFocusChanged(index, it.isFocused)
                    }
                    .focusable()
                    .background(if (isHighlighted) BrandFocus.FocusBgColor else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                if (hasLogo) {
                    ChannelLogo(
                        logoUrl = item.logoUrl,
                        fallbackTitle = "",
                        width = 44.dp,
                        height = 44.dp
                    )
                } else {
                    Text(
                        text = item.number.takeIf { it > 0 }?.toString() ?: item.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isHighlighted) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary,
                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
            if (index != items.lastIndex) Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

data class CatchupConfiguration(
    val startMillis: Long,
    val endMillis: Long,
    val positionMs: Long
)

@Composable
fun TechSpecText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = TvDesignTokens.TextSecondary
    )
}

@Composable
private fun BannerProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val track = TvDesignTokens.TextPrimary.copy(alpha = 0.14f)
    val fill = BrandFocus.BlueColor
    Box(
        modifier = modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .height(3.dp)
            .background(track, TvDesignTokens.ShapeFlat)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .background(fill, TvDesignTokens.ShapeFlat)
        )
    }
}
