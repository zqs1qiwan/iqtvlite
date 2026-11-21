package com.cjlhll.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/**
 * 播放恢复耗尽后的可操作失败终态（A10）。右侧面板提供“重试 / 选择频道 / 源配置”，
 * Back 关闭回视频但保留一个小型状态条，OK 可重新打开。
 */
@Composable
fun PlaybackFailurePanel(
    visible: Boolean,
    panelWidth: Dp,
    channelTitle: String?,
    reason: String?,
    catchupActive: Boolean = false,
    onRetry: () -> Unit,
    onReturnLive: () -> Unit = {},
    onSelectChannel: () -> Unit,
    onSourceConfig: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val retryRequester = remember { FocusRequester() }
    LaunchedEffect(visible) { runCatching { retryRequester.requestFocus() } }
    BackHandler { onDismiss() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .width(panelWidth)
                .background(TvColors.Panel.copy(alpha = 0.97f), RoundedCornerShape(12.dp))
                .border(1.dp, TvColors.Error.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(24.dp),
        ) {
            Text("暂时无法播放", style = TvType.SectionTitle, color = TvColors.Error)
            Spacer(modifier = Modifier.padding(top = 8.dp))
            if (!channelTitle.isNullOrBlank()) {
                Text(channelTitle, style = TvType.Body, color = TvColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(
                text = reason ?: "连接失败，请重试",
                style = TvType.Aux,
                color = TvColors.TextSecondary,
            )
            Spacer(modifier = Modifier.padding(top = 20.dp))
            FailureButton("重试", retryRequester, onRetry)
            if (catchupActive) {
                Spacer(modifier = Modifier.padding(top = 10.dp))
                FailureButton("返回直播", null, onReturnLive)
            }
            Spacer(modifier = Modifier.padding(top = 10.dp))
            FailureButton("选择频道", null, onSelectChannel)
            Spacer(modifier = Modifier.padding(top = 10.dp))
            FailureButton("源配置", null, onSourceConfig)
        }
    }
}

@Composable
private fun FailureButton(text: String, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (requester != null) it.focusRequester(requester) else it }
            .background(if (focused) TvColors.FocusFill else Color.Transparent, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .heightIn(min = TvDims.SettingsRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = TvType.Item, color = TvColors.TextPrimary)
    }
}

/** 失败面板关闭后的小型状态条：不抢焦点，OK 可重新打开（由 Activity 处理）。 */
@Composable
fun PlaybackFailureStatusBar(
    visible: Boolean,
    channelTitle: String?,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(TvColors.Panel.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                .border(1.dp, TvColors.Error.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text("播放失败", style = TvType.Aux, color = TvColors.Error)
            Spacer(modifier = Modifier.padding(horizontal = 6.dp))
            Text(
                text = channelTitle ?: "",
                style = TvType.Aux,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
