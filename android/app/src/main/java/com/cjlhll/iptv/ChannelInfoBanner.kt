package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class InfoCardQuickActionId {
    SOURCE,
    FAVORITE,
    ASPECT,
    AUDIO,
    AUDIO_ONLY,
    SUBTITLE,
    PIP,
    SETTINGS,
}

data class InfoCardQuickAction(
    val id: InfoCardQuickActionId,
    val label: String,
    val description: String = label,
    val active: Boolean = false,
)

/**
 * 双层式播放器底部信息卡：
 * 1. **换台轻量通透态**（`!manual && !focused`）：更通透深色底（0.64），整行铺满当前频道与节目进度，不展示最近观看与按钮；
 * 2. **确认主动控制台**（`manual || focused`）：稳定沉静不透明度（0.92），上方保持当前/下一节目双栏展示条，
 *    下方铺满一整行：最左竖排「最近观看」标签 + 6 个最近频道 + 右侧本台快捷控制（线路/画面比例/音轨/字幕/画中画/设置）。
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalComposeUiApi::class)
fun ChannelInfoBanner(
    state: InfoCardPolicy.State,
    manual: Boolean = false,
    seekOverlayVisible: Boolean,
    channel: Channel?,
    programTitle: String?,
    nextProgramTitle: String? = null,
    nextProgramStartMs: Long = 0L,
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
    quickActions: List<InfoCardQuickAction> = emptyList(),
    focusIndex: Int = 0,
    onMoveFocus: (Int) -> Unit = {},
    onSwitchRecent: (Int) -> Unit = {},
    onTriggerQuickAction: (InfoCardQuickActionId) -> Unit = {},
    onExitFocus: () -> Unit = {},
    onFocusedChanged: (Boolean) -> Unit = {},
    uiScale: UiScalePreset = UiScalePreset.DEFAULT,
    uiAlpha: Float = TvDesignTokens.ALPHA_INFO_CARD,
    showExactResolution: Boolean = false,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tScale = uiScale.textScale
    val lScale = uiScale.logoScale
    val lang by AppStrings.langFlow.collectAsState()
    val visible = InfoCardPolicy.isVisible(state, seekOverlayVisible)
    val focused = state == InfoCardPolicy.State.FOCUSED
    val isActiveConsole = focused

    val containerAlpha = if (isActiveConsole) uiAlpha else (uiAlpha * 0.70f).coerceIn(0.40f, 0.72f)
    val containerColor = TvDesignTokens.SurfaceObsidian.copy(alpha = containerAlpha)

    val recentCount = recentCards.size
    val showRecent = isActiveConsole && InfoCardPolicy.showRecentRow(
        recentCount = recentCount,
        catchup = catchupConfiguration != null,
        seekOverlayVisible = seekOverlayVisible,
    )
    val visibleQuickActions = if (isActiveConsole && !seekOverlayVisible) quickActions else emptyList()
    val showBottomRow = showRecent || visibleQuickActions.isNotEmpty()
    val showTwoStepPeek = !focused && manual && !seekOverlayVisible &&
        (InfoCardPolicy.showRecentRow(recentCount, catchupConfiguration != null, false) || quickActions.isNotEmpty())

    val maxRecentDisplay = 5
    val recentSlotCount = if (showRecent) minOf(recentCount, maxRecentDisplay) else 0
    val totalFocusSlots = recentSlotCount + visibleQuickActions.size

    val focusManager = LocalFocusManager.current
    val requesters = remember(totalFocusSlots) { List(totalFocusSlots) { FocusRequester() } }

    var stripFocusIndex by remember { mutableStateOf(-1) }
    var wasFocused by remember { mutableStateOf(false) }
    var lastState by remember { mutableStateOf(state) }

    LaunchedEffect(state, focusIndex, recentCount, totalFocusSlots) {
        when (InfoCardPolicy.focusRecoveryAction(state, totalFocusSlots)) {
            InfoCardPolicy.FocusRecovery.EXIT_FOCUS -> {
                wasFocused = false
                stripFocusIndex = -1
                onExitFocus()
            }
            InfoCardPolicy.FocusRecovery.REQUEST_FOCUS -> {
                val target = if (visibleQuickActions.isEmpty()) {
                    InfoCardPolicy.focusTargetIndex(focusIndex, recentCount)
                } else {
                    InfoCardPolicy.focusTargetIndex(focusIndex, totalFocusSlots)
                }
                if (target >= 0) {
                    stripFocusIndex = target
                    if (runCatching { requesters.getOrNull(target)?.requestFocus() }.isSuccess) {
                        wasFocused = true
                    } else {
                        repeat(4) {
                            withFrameNanos { }
                            if (runCatching { requesters.getOrNull(target)?.requestFocus() }.isSuccess) {
                                return@repeat
                            }
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
    val compactBadgeList = InfoCardPolicy.compactBadges(
        width = vf?.width ?: 0,
        height = vf?.height ?: 0,
        frameRate = frameRate ?: -1f,
        channelCount = audioChannelCount,
        colorTransfer = colorInfo?.colorTransfer ?: -1,
        hasHdrStaticInfo = colorInfo?.hdrStaticInfo != null,
        mime = vf?.sampleMimeType,
        codecs = vf?.codecs,
        exactResolution = showExactResolution,
    )

    val programText = InfoCardPolicy.programTitleOrPlaceholder(programTitle, lang)
    val programTime = InfoCardPolicy.programTimeLine(
        startMillis = programStartMs,
        endMillis = programEndMs,
        nowMillis = nowMillis,
        catchup = catchupConfiguration != null,
        lang = lang,
    )
    val smallMeta = InfoCardPolicy.groupLine(groupLabel)
    val unknownCh = if (lang == AppLang.EN) "Unknown Channel" else "未知频道"
    val titleWithNumber = if (channelNumber > 0) {
        "$channelNumber. ${channel?.title ?: unknownCh}"
    } else {
        channel?.title ?: unknownCh
    }

    // 修复 P0-9：catchupConfiguration.positionMs 已经是相对节目开头的毫秒数（0..duration）
    val progressValue: Float? = programProgress ?: catchupConfiguration?.let { c ->
        val duration = (c.endMillis - c.startMillis).toFloat()
        if (duration <= 0f) null else (c.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    val content: @Composable () -> Unit = {
        Column(
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
                            val slot = InfoCardPolicy.focusTargetIndex(focusIndex, totalFocusSlots)
                            if (slot in 0 until recentSlotCount) {
                                onSwitchRecent(slot)
                            } else if (slot >= recentSlotCount) {
                                visibleQuickActions.getOrNull(slot - recentSlotCount)?.let {
                                    onTriggerQuickAction(it.id)
                                }
                            }
                            true
                        }
                        Key.DirectionUp, Key.DirectionDown, Key.Back -> { onExitFocus(); true }
                        else -> false
                    }
                }
        ) {
            // ── 第一层（通用，铺满下方）：大台标 + 频道名/分组 + 当前/下一节目双栏 + 右侧独立 Tag ──
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 28.dp,
                            end = 28.dp,
                            top = 9.dp,
                            bottom = if (showBottomRow) 7.dp else 13.dp,
                        )
                        .height((84f * maxOf(lScale, tScale)).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mainLogoW = (132f * lScale).dp
                    val mainLogoH = (74f * lScale).dp
                Box(
                    modifier = Modifier.size(width = mainLogoW, height = mainLogoH),
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

                Spacer(modifier = Modifier.width(18.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.Top,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.weight(1f, fill = false),
                        ) {
                            Text(
                                text = titleWithNumber,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontSize = (22f * tScale).sp,
                                    lineHeight = (26f * tScale).sp,
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

                        if (compactBadgeList.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(14.dp))
                            val tagShape = remember { RoundedCornerShape(4.dp) }
                            val tagBg = Color.White.copy(alpha = 0.16f)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.testTag("info_card_badges"),
                            ) {
                                compactBadgeList.forEach { badge ->
                                    Box(
                                        modifier = Modifier
                                            .background(tagBg, tagShape)
                                            .padding(horizontal = 7.dp, vertical = 2.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = badge,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontSize = (11.5f * tScale).sp,
                                                lineHeight = (14f * tScale).sp,
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = Color(0xFFEAF0FA),
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.testTag("info_card_badges"))
                        }
                    }

                    Spacer(modifier = Modifier.height(7.dp))

                    val nextText = nextProgramTitle?.trim().takeIf { !it.isNullOrBlank() && catchupConfiguration == null }
                    val nextTimeText = InfoCardPolicy.formatClockTime(nextProgramStartMs)
                    val hasNextColumn = nextText != null

                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val currentColModifier = when {
                            hasNextColumn -> Modifier.weight(0.56f)
                            catchupConfiguration != null -> Modifier.fillMaxWidth()
                            else -> Modifier.fillMaxWidth(0.68f)
                        }
                        Column(modifier = currentColModifier) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = programText,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = (16.5f * tScale).sp,
                                        lineHeight = (20f * tScale).sp,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = if (programTitle.isNullOrBlank()) {
                                        TvDesignTokens.TextMuted
                                    } else {
                                        Color(0xFFE4EAF4)
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .testTag("info_card_program"),
                                )
                                if (programTime.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = programTime,
                                        style = MaterialTheme.typography.labelMedium.copy(fontSize = (11.5f * tScale).sp),
                                        color = TvDesignTokens.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("info_card_program_time"),
                                    )
                                } else {
                                    Box(modifier = Modifier.testTag("info_card_program_time"))
                                }
                            }

                            if (progressValue != null) {
                                Spacer(modifier = Modifier.height(5.dp))
                                BannerProgressBar(
                                    progress = progressValue,
                                    modifier = Modifier.testTag("info_card_progress"),
                                )
                            } else {
                                Spacer(modifier = Modifier.height(5.dp))
                                Box(modifier = Modifier.height(3.dp).testTag("info_card_progress"))
                            }
                        }

                        if (nextText != null) {
                            Spacer(modifier = Modifier.width(14.dp))
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(26.dp)
                                    .background(Color.White.copy(alpha = 0.12f))
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(0.44f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = if (lang == AppLang.EN) "NEXT" else "下一节目",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = (11f * tScale).sp,
                                            lineHeight = (13f * tScale).sp,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                        color = Color(0xFF8C9BB0),
                                        maxLines = 1,
                                    )
                                    if (nextTimeText.isNotEmpty()) {
                                        Text(
                                            text = nextTimeText,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = (11f * tScale).sp,
                                                lineHeight = (13f * tScale).sp,
                                            ),
                                            color = TvDesignTokens.TextSecondary,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = nextText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = (14.5f * tScale).sp,
                                        lineHeight = (18f * tScale).sp,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                    color = Color(0xFFC5D0E0),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                }

                // 两段式第 1 段动态宽折线引导：嵌入台标信息层最底边细条，不增加额外行高
                if (showTwoStepPeek) {
                    OkPeekCue(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 3.dp),
                    )
                }
            }

            // ── 第二层（展开/聚焦时显示）：最左竖排最近观看 + 5 个紧凑最近频道 + 右侧本台快捷控制 ──
            if (showBottomRow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                val rowHeight = (64f * lScale).dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 28.dp, top = 6.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showRecent) {
                        RecentStrip(
                            items = recentCards,
                            lang = lang,
                            rowHeight = rowHeight,
                            focused = focused,
                            logicalFocusIndex = focusIndex,
                            requesters = requesters.take(recentSlotCount),
                            uiScale = uiScale,
                            onCardFocusChanged = { index, isFocused ->
                                if (isFocused) stripFocusIndex = index
                                else if (stripFocusIndex == index) stripFocusIndex = -1
                            },
                            onClick = { index -> onSwitchRecent(index) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).testTag("info_card_recent_row"))
                    }

                    if (visibleQuickActions.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        QuickActionsStrip(
                            actions = visibleQuickActions,
                            rowHeight = rowHeight,
                            slotOffset = recentSlotCount,
                            focused = focused,
                            logicalFocusIndex = focusIndex,
                            requesters = requesters.drop(recentSlotCount),
                            textScale = tScale,
                            onFocusChanged = { slotIdx, isFocused ->
                                if (isFocused) stripFocusIndex = slotIdx
                                else if (stripFocusIndex == slotIdx) stripFocusIndex = -1
                            },
                            onTrigger = onTriggerQuickAction,
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

@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    lang: AppLang,
    rowHeight: androidx.compose.ui.unit.Dp,
    focused: Boolean,
    logicalFocusIndex: Int,
    requesters: List<FocusRequester>,
    uiScale: UiScalePreset,
    onCardFocusChanged: (Int, Boolean) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = remember { RoundedCornerShape(6.dp) }
    val labelShape = remember { RoundedCornerShape(5.dp) }
    val idlePlateColor = Color(0x14FFFFFF)
    val logoSlotH = (44f * uiScale.logoScale).dp
    val logoScale = uiScale.logoScale
    val textScale = uiScale.textScale

    Row(
        modifier = modifier
            .height(rowHeight)
            .clipToBounds()
            .testTag("info_card_recent_row"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 最左竖排「最近观看」导视标识，不占用横向选台空间
        Box(
            modifier = Modifier
                .width(22.dp)
                .fillMaxHeight()
                .background(Color(0x10FFFFFF), labelShape),
            contentAlignment = Alignment.Center,
        ) {
            val isEn = lang == AppLang.EN
            Text(
                text = if (isEn) "R\nE\nC\nE\nN\nT" else "最\n近\n观\n看",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (isEn) 8.5.sp else 10.sp,
                    lineHeight = if (isEn) 9.5.sp else 12.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color(0xFF9AA8BC),
                textAlign = TextAlign.Center,
            )
        }

        for (slot in 0 until 5) {
            val item = items.getOrNull(slot)
            if (item == null) {
                Spacer(modifier = Modifier.weight(1f))
                continue
            }
            val index = slot
            val isHighlighted = focused && index == logicalFocusIndex
            RecentCardTile(
                index = index,
                title = item.title,
                logoUrl = item.logoUrl,
                isHighlighted = isHighlighted,
                logoSlotH = logoSlotH,
                logoScale = logoScale,
                textScale = textScale,
                cardShape = cardShape,
                idlePlateColor = idlePlateColor,
                focusRequester = requesters.getOrNull(index),
                onFocusChanged = onCardFocusChanged,
                onClick = onClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RecentCardTile(
    index: Int,
    title: String,
    logoUrl: String?,
    isHighlighted: Boolean,
    logoSlotH: androidx.compose.ui.unit.Dp,
    logoScale: Float,
    textScale: Float,
    cardShape: RoundedCornerShape,
    idlePlateColor: Color,
    focusRequester: FocusRequester?,
    onFocusChanged: (Int, Boolean) -> Unit,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasLogo = logoUrl?.isNotBlank() == true
    val cardLogoW = ((if (isHighlighted) 96f else 72f) * logoScale).dp
    val cardLogoH = ((if (isHighlighted) 44f else 36f) * logoScale).dp
    val titleSp = (if (isHighlighted) 12.0f else 10.5f) * textScale

    Column(
        modifier = modifier
            .fillMaxHeight()
            .testTag("info_card_recent_$index")
            .semantics { contentDescription = "最近观看第${index + 1}个 $title" }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .onFocusChanged { onFocusChanged(index, it.isFocused) }
            .focusable()
            .background(if (isHighlighted) BrandFocus.FocusBgColor else idlePlateColor, cardShape)
            .clickable { onClick(index) }
            .padding(horizontal = 3.dp, vertical = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(logoSlotH),
            contentAlignment = Alignment.Center,
        ) {
            if (hasLogo) {
                ChannelLogo(
                    logoUrl = logoUrl,
                    fallbackTitle = "",
                    width = cardLogoW,
                    height = cardLogoH,
                )
            } else {
                Text(
                    text = title.take(4),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = (titleSp + 1.2f).sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (isHighlighted) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = titleSp.sp,
                lineHeight = (titleSp + 2f).sp,
                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (isHighlighted) TvDesignTokens.TextPrimary else Color(0xFFD0D9E8),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QuickActionsStrip(
    actions: List<InfoCardQuickAction>,
    rowHeight: androidx.compose.ui.unit.Dp,
    slotOffset: Int,
    focused: Boolean,
    logicalFocusIndex: Int,
    requesters: List<FocusRequester>,
    textScale: Float,
    onFocusChanged: (Int, Boolean) -> Unit,
    onTrigger: (InfoCardQuickActionId) -> Unit,
) {
    val tileShape = remember { RoundedCornerShape(6.dp) }
    val idlePlateColor = Color(0x14FFFFFF)
    val activePlateColor = TvDesignTokens.BrandLiteBlue.copy(alpha = 0.22f)
    val tileWidth = (58f * textScale.coerceIn(0.92f, 1.10f)).dp

    Row(
        modifier = Modifier
            .height(rowHeight)
            .testTag("info_card_actions_row"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { idx, action ->
            val slotIndex = slotOffset + idx
            val isHighlighted = focused && slotIndex == logicalFocusIndex
            QuickActionTile(
                action = action,
                slotIndex = slotIndex,
                isHighlighted = isHighlighted,
                tileWidth = tileWidth,
                tileShape = tileShape,
                idlePlateColor = idlePlateColor,
                activePlateColor = activePlateColor,
                textScale = textScale,
                focusRequester = requesters.getOrNull(idx),
                onFocusChanged = onFocusChanged,
                onTrigger = onTrigger,
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    action: InfoCardQuickAction,
    slotIndex: Int,
    isHighlighted: Boolean,
    tileWidth: androidx.compose.ui.unit.Dp,
    tileShape: RoundedCornerShape,
    idlePlateColor: Color,
    activePlateColor: Color,
    textScale: Float,
    focusRequester: FocusRequester?,
    onFocusChanged: (Int, Boolean) -> Unit,
    onTrigger: (InfoCardQuickActionId) -> Unit,
) {
    val iconColor = when {
        isHighlighted -> TvDesignTokens.TextPrimary
        action.active -> TvDesignTokens.BrandLiteBlue
        else -> Color(0xFFD6DEEC)
    }
    val tileBg = when {
        isHighlighted -> BrandFocus.FocusBgColor
        action.active -> activePlateColor
        else -> idlePlateColor
    }
    val iconSize = if (isHighlighted) 23.dp else 19.dp
    val labelSp = (if (isHighlighted) 11.5f else 10.5f) * textScale.coerceAtMost(1.06f)

    Column(
        modifier = Modifier
            .width(tileWidth)
            .fillMaxHeight()
            .testTag("info_card_action_${action.id.name.lowercase()}")
            .semantics { contentDescription = action.description }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .onFocusChanged { onFocusChanged(slotIndex, it.isFocused) }
            .focusable()
            .background(tileBg, tileShape)
            .clickable { onTrigger(action.id) }
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(25.dp),
            contentAlignment = Alignment.Center,
        ) {
            QuickActionGlyph(
                id = action.id,
                tint = iconColor,
                modifier = Modifier.size(iconSize),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = labelSp.sp,
                lineHeight = (labelSp + 2f).sp,
                fontWeight = if (isHighlighted || action.active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = when {
                isHighlighted -> TvDesignTokens.TextPrimary
                action.active -> TvDesignTokens.BrandLiteBlue
                else -> Color(0xFFB8C3D4)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QuickActionGlyph(
    id: InfoCardQuickActionId,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (id) {
            InfoCardQuickActionId.SOURCE -> {
                // 双线切换箭头
                val y1 = h * 0.34f
                val y2 = h * 0.68f
                drawLine(tint, Offset(w * 0.16f, y1), Offset(w * 0.84f, y1), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, y1 - h * 0.16f), Offset(w * 0.84f, y1), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.16f, y2), Offset(w * 0.84f, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, y2 + h * 0.16f), Offset(w * 0.16f, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            InfoCardQuickActionId.FAVORITE -> {
                // 五角星收藏图标
                val cx = w * 0.50f
                val cy = h * 0.52f
                val outer = w * 0.38f
                val inner = w * 0.17f
                val pts = Array(10) { idx ->
                    val r = if (idx % 2 == 0) outer else inner
                    val angle = -PI / 2.0 + idx * (PI / 5.0)
                    Offset(cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat())
                }
                for (i in 0 until 10) {
                    drawLine(tint, pts[i], pts[(i + 1) % 10], strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
            InfoCardQuickActionId.ASPECT -> {
                // 画面比例外框 + 对角伸缩角标
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.10f, h * 0.18f),
                    size = Size(w * 0.80f, h * 0.64f),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.28f, h * 0.36f), Offset(w * 0.44f, h * 0.36f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h * 0.36f), Offset(w * 0.28f, h * 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.64f), Offset(w * 0.56f, h * 0.64f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.72f, h * 0.64f), Offset(w * 0.72f, h * 0.50f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            InfoCardQuickActionId.AUDIO -> {
                // 双音符
                val r = w * 0.11f
                drawCircle(tint, radius = r, center = Offset(w * 0.28f, h * 0.72f), style = stroke)
                drawCircle(tint, radius = r, center = Offset(w * 0.70f, h * 0.64f), style = stroke)
                drawLine(tint, Offset(w * 0.39f, h * 0.72f), Offset(w * 0.39f, h * 0.24f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.81f, h * 0.64f), Offset(w * 0.81f, h * 0.18f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.39f, h * 0.24f), Offset(w * 0.81f, h * 0.18f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            InfoCardQuickActionId.AUDIO_ONLY -> {
                // 耳机图标（纯音频熄画听音模式）
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.16f, h * 0.18f),
                    size = Size(w * 0.68f, h * 0.62f),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.48f),
                    size = Size(w * 0.18f, h * 0.34f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.70f, h * 0.48f),
                    size = Size(w * 0.18f, h * 0.34f),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = stroke,
                )
            }
            InfoCardQuickActionId.SUBTITLE -> {
                // 字幕矩形框 + 两行字幕条
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.10f, h * 0.20f),
                    size = Size(w * 0.80f, h * 0.60f),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.24f, h * 0.45f), Offset(w * 0.58f, h * 0.45f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.66f, h * 0.45f), Offset(w * 0.76f, h * 0.45f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, h * 0.63f), Offset(w * 0.76f, h * 0.63f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            InfoCardQuickActionId.PIP -> {
                // 大窗框 + 右下画中画小窗
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.10f, h * 0.18f),
                    size = Size(w * 0.80f, h * 0.64f),
                    cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
                    style = stroke,
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.48f, h * 0.48f),
                    size = Size(w * 0.34f, h * 0.24f),
                    cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx()),
                )
            }
            InfoCardQuickActionId.SETTINGS -> {
                // 8 齿齿轮设置图标
                val center = Offset(w * 0.50f, h * 0.50f)
                drawCircle(tint, radius = w * 0.25f, center = center, style = Stroke(width = 2.2.dp.toPx()))
                drawCircle(tint, radius = w * 0.11f, center = center, style = Stroke(width = 1.4.dp.toPx()))
                val innerR = w * 0.28f
                val outerR = w * 0.41f
                val toothStroke = 2.4.dp.toPx()
                for (i in 0 until 8) {
                    val angle = i * (PI / 4.0)
                    val sx = center.x + (innerR * cos(angle)).toFloat()
                    val sy = center.y + (innerR * sin(angle)).toFloat()
                    val ex = center.x + (outerR * cos(angle)).toFloat()
                    val ey = center.y + (outerR * sin(angle)).toFloat()
                    drawLine(tint, Offset(sx, sy), Offset(ex, ey), strokeWidth = toothStroke, cap = StrokeCap.Butt)
                }
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
private fun OkPeekCue(modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "ok_peek_cue")
    val dyFracState = pulse.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ok_peek_dy",
    )
    val path = remember { Path() }
    Canvas(
        modifier = modifier
            .width(46.dp)
            .height(8.dp),
    ) {
        val dyFrac = dyFracState.value
        val cueAlpha = 0.42f + 0.43f * dyFrac
        val w = size.width
        val h = size.height
        val offsetY = dyFrac * (h * 0.28f)
        path.reset()
        path.moveTo(w * 0.08f, h * 0.22f + offsetY)
        path.lineTo(w * 0.50f, h * 0.72f + offsetY)
        path.lineTo(w * 0.92f, h * 0.22f + offsetY)
        drawPath(
            path = path,
            color = TvDesignTokens.TextPrimary.copy(alpha = cueAlpha),
            style = Stroke(width = 2.0.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
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
