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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
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
    uiScale: UiScalePreset = UiScalePreset.DEFAULT,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tScale = uiScale.textScale
    val lScale = uiScale.logoScale
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 36.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 左区（52%）：当前播放完整展示区（垂直居中放大主台标 + 台名/分组 + 节目名 + 时间 + 进度条）
                Row(
                    modifier = Modifier.weight(0.52f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mainLogoW = (160f * lScale).dp
                    val mainLogoH = (98f * lScale).dp
                    Box(
                        modifier = Modifier
                            .size(width = mainLogoW + 4.dp, height = mainLogoH + 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChannelLogo(
                            logoUrl = channel?.logoUrl,
                            fallbackTitle = channel?.title ?: "",
                            modifier = Modifier
                                .size(width = mainLogoW, height = mainLogoH)
                                .testTag("info_card_logo"),
                            width = mainLogoW,
                            height = mainLogoH,
                        )
                    }

                    Spacer(modifier = Modifier.width(22.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = titleWithNumber,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = (24f * tScale).sp,
                                    lineHeight = (28f * tScale).sp,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = TvDesignTokens.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .testTag("info_card_title"),
                            )
                            if (smallMeta.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .padding(bottom = 2.dp)
                                        .testTag("info_card_group"),
                                ) {
                                    Text(
                                        text = smallMeta,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (12.5f * tScale).sp),
                                        color = TvDesignTokens.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("info_card_number"),
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.testTag("info_card_group")) {
                                    Box(modifier = Modifier.testTag("info_card_number"))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = programText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = (18.5f * tScale).sp,
                                lineHeight = (22f * tScale).sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = if (programTitle.isNullOrBlank()) {
                                TvDesignTokens.TextMuted
                            } else {
                                Color(0xFFE4EAF4)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_program"),
                        )

                        if (programTime.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = programTime,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = (12.5f * tScale).sp),
                                color = TvDesignTokens.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_program_time"),
                            )
                        }

                        if (progressValue != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            BannerProgressBar(
                                progress = progressValue,
                                modifier = Modifier.testTag("info_card_progress"),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(28.dp))

                // 右区（48%）：顶部（聚焦时轻提示 + 右对齐实时技术参数）+ 底部 5 格大台标与台名最近观看快切
                Column(
                    modifier = Modifier
                        .weight(0.48f)
                        .heightIn(min = (104f * lScale).dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (focused && showRecent) {
                            Text(
                                text = "← → 选择 · OK 切台",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = (11.5f * tScale).sp),
                                color = Color(0xFFB4C0D4),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_recent_tip"),
                            )
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        if (compactBadgeText.isNotEmpty()) {
                            Text(
                                text = compactBadgeText,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontSize = (12.5f * tScale).sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = Color(0xFFCBD4E3),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_badges"),
                            )
                        }
                    }

                    if (showRecent) {
                        Spacer(modifier = Modifier.height(8.dp))
                        RecentStrip(
                            items = recentCards,
                            focused = focused,
                            logicalFocusIndex = focusIndex,
                            requesters = requesters,
                            uiScale = uiScale,
                            onCardFocusChanged = { index, isFocused ->
                                if (isFocused) stripFocusIndex = index
                                else if (stripFocusIndex == index) stripFocusIndex = -1
                            },
                        )
                    }
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

/**
 * 右下角 5 格最近观看快切条：固定 5 槽等宽排布（正好容纳 5 个台）；
 * 每张卡片展示大号横幅台标（76×46dp）+ 下方清晰台名（无台号），对称磨砂高亮识别焦点。
 */
@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    focused: Boolean,
    logicalFocusIndex: Int,
    requesters: List<FocusRequester>,
    uiScale: UiScalePreset,
    onCardFocusChanged: (Int, Boolean) -> Unit,
) {
    val cardShape = remember { RoundedCornerShape(6.dp) }
    val idlePlateColor = Color(0x14FFFFFF)
    val stripHeight = (80f * uiScale.logoScale).dp
    val logoW = (76f * uiScale.logoScale).dp
    val logoH = (46f * uiScale.logoScale).dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripHeight)
            .clipToBounds()
            .testTag("info_card_recent_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (slot in 0 until InfoCardPolicy.MAX_RECENT) {
            val item = items.getOrNull(slot)
            if (item == null) {
                Spacer(modifier = Modifier.weight(1f))
                continue
            }
            val index = slot
            var nodeFocused by remember(item.title, index) { mutableStateOf(false) }
            val isHighlighted = focused && (nodeFocused || index == logicalFocusIndex)
            val hasLogo = item.logoUrl?.isNotBlank() == true

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .testTag("info_card_recent_$index")
                    .semantics { contentDescription = "最近观看第${index + 1}个 ${item.title}" }
                    .focusRequester(requesters[index])
                    .onFocusChanged {
                        nodeFocused = it.isFocused
                        onCardFocusChanged(index, it.isFocused)
                    }
                    .focusable()
                    .background(if (isHighlighted) BrandFocus.FocusBgColor else idlePlateColor, cardShape)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(logoH),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasLogo) {
                        ChannelLogo(
                            logoUrl = item.logoUrl,
                            fallbackTitle = "",
                            width = logoW,
                            height = logoH,
                        )
                    } else {
                        Text(
                            text = item.title.take(4),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontSize = (13f * uiScale.textScale).sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = if (isHighlighted) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (11.5f * uiScale.textScale).sp,
                        lineHeight = (14f * uiScale.textScale).sp,
                        fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (isHighlighted) TvDesignTokens.TextPrimary else Color(0xFFB8C3D4),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
