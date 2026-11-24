package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
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
    ASPECT,
    AUDIO,
    SUBTITLE,
    PIP,
    SETTINGS,
}

data class InfoCardQuickAction(
    val id: InfoCardQuickActionId,
    val label: String,
    val description: String = label,
)

/**
 * 双层式播放器底部信息卡：
 * 1. **换台轻量通透态**（`!manual && !focused`）：更通透深色底（0.64），整行铺满当前频道与节目进度，不展示最近观看与按钮；
 * 2. **确认主动控制台**（`manual || focused`）：稳定沉静不透明度（0.92），上方保持当前频道展示条，
 *    下方铺满一整行：左侧最近观看 5 个频道（Logo + 台名）+ 右侧快速设置（线路/画面比例/音轨/字幕/画中画/设置）。
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
    animate: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tScale = uiScale.textScale
    val lScale = uiScale.logoScale
    val lang by AppStrings.langFlow.collectAsState()
    val visible = InfoCardPolicy.isVisible(state, seekOverlayVisible)
    val focused = state == InfoCardPolicy.State.FOCUSED
    val isActiveConsole = manual || focused

    val containerAlpha = if (isActiveConsole) TvDesignTokens.ALPHA_INFO_CARD else 0.64f
    val containerColor = TvDesignTokens.SurfaceObsidian.copy(alpha = containerAlpha)

    val recentCount = recentCards.size
    val showRecent = isActiveConsole && InfoCardPolicy.showRecentRow(
        recentCount = recentCount,
        catchup = catchupConfiguration != null,
        seekOverlayVisible = seekOverlayVisible,
    )
    val visibleQuickActions = if (isActiveConsole && !seekOverlayVisible) quickActions else emptyList()
    val showBottomRow = showRecent || visibleQuickActions.isNotEmpty()

    val recentSlotCount = if (showRecent) minOf(recentCount, InfoCardPolicy.MAX_RECENT) else 0
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
            // ── 第一层（通用，铺满下方）：大台标 + 频道名/分组 + 节目名/时间/进度 + 右侧实时参数 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 36.dp,
                        end = 36.dp,
                        top = 13.dp,
                        bottom = if (showBottomRow) 11.dp else 14.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val mainLogoW = (146f * lScale).dp
                val mainLogoH = (84f * lScale).dp
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

                Spacer(modifier = Modifier.width(20.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
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
                                    fontSize = (23f * tScale).sp,
                                    lineHeight = (27f * tScale).sp,
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

                        if (compactBadgeText.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(16.dp))
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
                        } else {
                            Box(modifier = Modifier.testTag("info_card_badges"))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = programText,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = (17.5f * tScale).sp,
                                lineHeight = (21f * tScale).sp,
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
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = programTime,
                                style = MaterialTheme.typography.labelMedium.copy(fontSize = (12f * tScale).sp),
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
                        Spacer(modifier = Modifier.height(6.dp))
                        BannerProgressBar(
                            progress = progressValue,
                            modifier = Modifier.testTag("info_card_progress"),
                        )
                    } else {
                        Box(modifier = Modifier.testTag("info_card_progress"))
                    }
                }
            }

            // ── 第二层（仅 OK 主动呼出时显示）：整行左侧 5 个最近观看 + 右侧本台快捷控制 ──
            if (showBottomRow) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.08f))
                )

                val rowHeight = (68f * lScale).dp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, end = 36.dp, top = 10.dp, bottom = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showRecent) {
                        RecentStrip(
                            items = recentCards,
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
                        Spacer(modifier = Modifier.width(16.dp))
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
    val idlePlateColor = Color(0x14FFFFFF)
    val logoW = (68f * uiScale.logoScale).dp
    val logoH = (38f * uiScale.logoScale).dp

    Row(
        modifier = modifier
            .height(rowHeight)
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
                    .let { m -> requesters.getOrNull(index)?.let { r -> m.focusRequester(r) } ?: m }
                    .onFocusChanged {
                        nodeFocused = it.isFocused
                        onCardFocusChanged(index, it.isFocused)
                    }
                    .focusable()
                    .background(if (isHighlighted) BrandFocus.FocusBgColor else idlePlateColor, cardShape)
                    .clickable { onClick(index) }
                    .padding(horizontal = 6.dp, vertical = 5.dp),
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
                                fontSize = (12.5f * uiScale.textScale).sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = if (isHighlighted) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (11f * uiScale.textScale).sp,
                        lineHeight = (13f * uiScale.textScale).sp,
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
    val tileWidth = (66f * textScale.coerceIn(0.92f, 1.12f)).dp

    Row(
        modifier = Modifier
            .height(rowHeight)
            .testTag("info_card_actions_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { idx, action ->
            val slotIndex = slotOffset + idx
            var nodeFocused by remember(action.id) { mutableStateOf(false) }
            val isHighlighted = focused && (nodeFocused || slotIndex == logicalFocusIndex)
            val iconColor = if (isHighlighted) TvDesignTokens.TextPrimary else Color(0xFFD6DEEC)

            Column(
                modifier = Modifier
                    .width(tileWidth)
                    .fillMaxHeight()
                    .testTag("info_card_action_${action.id.name.lowercase()}")
                    .semantics { contentDescription = action.description }
                    .let { m -> requesters.getOrNull(idx)?.let { r -> m.focusRequester(r) } ?: m }
                    .onFocusChanged {
                        nodeFocused = it.isFocused
                        onFocusChanged(slotIndex, it.isFocused)
                    }
                    .focusable()
                    .background(if (isHighlighted) BrandFocus.FocusBgColor else idlePlateColor, tileShape)
                    .clickable { onTrigger(action.id) }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                QuickActionGlyph(
                    id = action.id,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = (11f * textScale.coerceAtMost(1.06f)).sp,
                        lineHeight = (13f * textScale).sp,
                        fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Medium,
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
                // 齿轮设置图标
                val center = Offset(w * 0.50f, h * 0.50f)
                drawCircle(tint, radius = w * 0.16f, center = center, style = stroke)
                val innerR = w * 0.27f
                val outerR = w * 0.38f
                for (i in 0 until 6) {
                    val angle = i * (PI / 3.0)
                    val sx = center.x + (innerR * cos(angle)).toFloat()
                    val sy = center.y + (innerR * sin(angle)).toFloat()
                    val ex = center.x + (outerR * cos(angle)).toFloat()
                    val ey = center.y + (outerR * sin(angle)).toFloat()
                    drawLine(tint, Offset(sx, sy), Offset(ex, ey), strokeWidth = stroke.width, cap = StrokeCap.Round)
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
