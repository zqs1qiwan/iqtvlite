package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    catchupConfiguration: CatchupConfiguration? = null,
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

    // 进入聚焦时把焦点交给对应小卡；只有在“曾经聚焦过”时才清焦点——
    // 否则卡片隐藏（打开抽屉/设置）时会把焦点从覆盖层上抢走。
    var wasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(state, focusIndex, recentCount) {
        if (focused && recentCount > 0) {
            val target = requesters.getOrNull(focusIndex.coerceIn(0, recentCount - 1))
            runCatching { target?.requestFocus() }
            wasFocused = true
        } else if (wasFocused) {
            runCatching { focusManager.clearFocus(force = true) }
            wasFocused = false
        }
    }

    val techLine = InfoCardPolicy.techLine(
        audioLabel = audioLabel,
        width = videoFormat?.width ?: 0,
        height = videoFormat?.height ?: 0,
        bitrate = videoFormat?.bitrate ?: -1,
        channelCount = videoFormat?.channelCount ?: -1,
        sampleMimeType = videoFormat?.sampleMimeType,
    )

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
                        Key.DirectionUp, Key.DirectionDown, Key.Back -> { onExitFocus(); true }
                        else -> false
                    }
                }
        ) {
            Column {
                if (catchupConfiguration != null) {
                    CatchupProgressRow(
                        startMillis = catchupConfiguration.startMillis,
                        endMillis = catchupConfiguration.endMillis,
                        positionMs = catchupConfiguration.positionMs
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = 32.dp,
                            end = 32.dp,
                            top = if (catchupConfiguration != null) 4.dp else 24.dp,
                            bottom = if (showRecent) 16.dp else 24.dp
                        )
                ) {
                    ChannelLogo(
                        logoUrl = channel?.logoUrl,
                        fallbackTitle = channel?.title ?: "",
                        modifier = Modifier
                            .size(width = 96.dp, height = 60.dp)
                            .testTag("info_card_logo"),
                        width = 96.dp,
                        height = 60.dp
                    )

                    Spacer(modifier = Modifier.width(24.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // 频道号改成独立 overline，台名不再带 "N. " 前缀（§2.1）。
                        if (channelNumber > 0) {
                            Text(
                                text = "频道 $channelNumber",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.80f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_number")
                            )
                        }
                        Text(
                            text = channel?.title ?: "未知频道",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("info_card_title")
                        )

                        if (!programTitle.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = programTitle,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_program")
                            )

                            if (programProgress != null && catchupConfiguration == null) {
                                BannerProgressBar(
                                    progress = programProgress,
                                    modifier = Modifier.testTag("info_card_progress")
                                )
                            }
                        }

                        if (techLine.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = techLine,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag("info_card_tech")
                            )
                        }
                    }
                }

                // ── 底部：最近观看快跳（≤6；空/回看/快进中整条不渲染） ──────────────
                if (showRecent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 32.dp, bottom = 8.dp)
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
                        focusIndex = focusIndex,
                        focused = focused,
                        requesters = requesters,
                        onMoveFocus = onMoveFocus,
                        onSwitchRecent = onSwitchRecent,
                        onExitFocus = onExitFocus,
                        onFocusedChanged = onFocusedChanged,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 32.dp, bottom = 16.dp)
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
 * 按键由小卡自身 onPreviewKeyEvent 处理（UP/DOWN/BACK 退聚焦），避免与 Activity 守卫重复。
 */
@Composable
private fun RecentStrip(
    items: List<RecentChannelUi>,
    focusIndex: Int,
    focused: Boolean,
    requesters: List<FocusRequester>,
    onMoveFocus: (Int) -> Unit,
    onSwitchRecent: (Int) -> Unit,
    onExitFocus: () -> Unit,
    onFocusedChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp)
            .testTag("info_card_recent_row")
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
                        if (it.isFocused) onFocusedChanged(true)
                    }
                    .onPreviewKeyEvent { event ->
                        if (!focused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> { onMoveFocus(-1); true }
                            Key.DirectionRight -> { onMoveFocus(1); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onSwitchRecent(index); true }
                            Key.DirectionUp, Key.DirectionDown, Key.Back -> { onExitFocus(); true }
                            else -> false
                        }
                    }
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

private val catchupTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun CatchupProgressRow(
    startMillis: Long,
    endMillis: Long,
    positionMs: Long
) {
    val total = (endMillis - startMillis).coerceAtLeast(1L)
    val progress = (positionMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val zone = ZoneId.systemDefault()
    val startText = catchupTimeFormatter.format(Instant.ofEpochMilli(startMillis).atZone(zone))
    val endText = catchupTimeFormatter.format(Instant.ofEpochMilli(endMillis).atZone(zone))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 4.dp)
    ) {
        Text(
            text = startText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
        ) {
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f), RoundedCornerShape(0.dp))
            )

            // Progress Fill
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(0.dp))
            )

            // Dot
            val bias = if (progress.isNaN()) -1f else (progress * 2) - 1
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(BiasAlignment(bias.coerceIn(-1f, 1f), 0f))
                    .background(Color.White, CircleShape)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = endText,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )
    }
}

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
