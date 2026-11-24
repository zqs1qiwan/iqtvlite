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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, end = 36.dp, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                // 左列：当前台大台标 + 底部清晰无裁切的「← 频道列表」提示
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .height(142.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start,
                ) {
                    ChannelLogo(
                        logoUrl = channel?.logoUrl,
                        fallbackTitle = channel?.title ?: "",
                        modifier = Modifier
                            .size(width = 136.dp, height = 84.dp)
                            .testTag("info_card_logo"),
                        width = 136.dp,
                        height = 84.dp,
                    )
                    EntryHintLeft()
                }

                Spacer(modifier = Modifier.width(20.dp))

                // 中列：频道标题与分组 + 最近观看操作提示 + 向下延展正好容纳 5 个台的大台标+台名卡片条
                Column(
                    modifier = Modifier
                        .weight(0.56f)
                        .heightIn(min = 142.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = titleWithNumber,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = 24.sp,
                                    lineHeight = 28.sp,
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
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
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

                        if (showRecent) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (focused) "最近观看 · ← → 选择 · OK 快速切台" else "最近观看 · 按 OK 选择快速切台",
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                                color = if (focused) Color(0xFFCBD4E3) else Color(0xFF94A1B5),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_recent_tip"),
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
                            onCardFocusChanged = { index, isFocused ->
                                if (isFocused) stripFocusIndex = index
                                else if (stripFocusIndex == index) stripFocusIndex = -1
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.width(24.dp))

                // 右列：顶部技术参数徽标（含实时真帧率）+ 节目信息与进度条 + 底部清晰无裁切的「→ 设置」提示
                Column(
                    modifier = Modifier
                        .weight(0.44f)
                        .height(142.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    if (compactBadgeText.isNotEmpty()) {
                        Text(
                            text = compactBadgeText,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = Color(0xFFCBD4E3),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_badges"),
                        )
                    } else {
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = programText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = if (programTitle.isNullOrBlank()) {
                                TvDesignTokens.TextMuted
                            } else {
                                TvDesignTokens.TextPrimary
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_program"),
                        )
                        if (programTime.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = programTime,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
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
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = Color(0xFFCBD4E3),
        maxLines = 1,
        modifier = Modifier.testTag("info_card_hint_left"),
    )
}

@Composable
private fun EntryHintRight() {
    Text(
        text = InfoCardPolicy.ENTRY_HINT_RIGHT,
        style = MaterialTheme.typography.labelLarge.copy(
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
        ),
        color = Color(0xFFCBD4E3),
        maxLines = 1,
        modifier = Modifier.testTag("info_card_hint_right"),
    )
}

/**
 * 底部扩展最近观看条：充分利用信息卡底部空间，固定 5 槽等宽排布（正好放下 5 个台）；
 * 每张卡片展示放大版横幅台标（68×42dp）+ 下方清晰台名（不显示台号），对称磨砂高亮识别焦点。
 */
@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    focused: Boolean,
    logicalFocusIndex: Int,
    requesters: List<FocusRequester>,
    onCardFocusChanged: (Int, Boolean) -> Unit,
) {
    val cardShape = remember { RoundedCornerShape(6.dp) }
    val idlePlateColor = Color(0x14FFFFFF)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
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
                        .height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasLogo) {
                        ChannelLogo(
                            logoUrl = item.logoUrl,
                            fallbackTitle = "",
                            width = 72.dp,
                            height = 44.dp,
                        )
                    } else {
                        Text(
                            text = item.title.take(4),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
                        fontSize = 11.5.sp,
                        lineHeight = 14.sp,
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
