package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SubtitleTrackDialog(
    visible: Boolean,
    items: List<SubtitleTrackUi.Item>,
    hasSubtitles: Boolean = items.isNotEmpty(),
    onSelect: (SubtitleTrackUi.Item) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
) {
    val scrollState = rememberScrollState()
    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }

    SafeInitialFocus(active = visible && hasSubtitles && items.isNotEmpty(), items.size) {
        val selectedIdx = items.indexOfFirst { it.selected }.coerceAtLeast(0)
        requesters.getOrNull(selectedIdx)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(130)) + scaleIn(tween(130), initialScale = 0.97f),
        exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.97f),
        modifier = modifier.fillMaxSize()
    ) {
        TvModalContainer(
            width = 540.dp,
            maxHeight = 460.dp,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onClose()
                    true
                } else false
            },
            panelModifier = Modifier
                .testTag("subtitle_dialog")
                .semantics { testTagsAsResourceId = true }
        ) {
            Text(
                text = tr("字幕选择", "Subtitles"),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = (22f * textScale).sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = TvDesignTokens.TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!hasSubtitles || items.isEmpty()) {
                Text(
                    text = tr("当前频道没有内置字幕轨", "No built-in subtitles in this stream"),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (14f * textScale).sp),
                    color = TvDesignTokens.TextSecondary,
                    modifier = Modifier.padding(vertical = 12.dp).testTag("subtitle_empty")
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(scrollState)
                ) {
                    items.forEachIndexed { index, item ->
                        SubtitleRow(
                            item = item,
                            textScale = textScale,
                            focusRequester = requesters.getOrNull(index),
                            onClick = { onSelect(item) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubtitleRow(
    item: SubtitleTrackUi.Item,
    textScale: Float,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(if (item.isOff) "subtitle_row_off" else "subtitle_row_${item.groupIndex}_${item.trackIndex}")
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) scope.launch { runCatching { bringIntoView.bringIntoView() } }
            }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Text(
            text = if (item.selected) "▶ ${item.text}" else item.text,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (15.5f * textScale).sp,
                fontWeight = if (focused || item.selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                item.selected -> BrandFocus.BlueColor
                focused -> TvDesignTokens.TextPrimary
                else -> TvDesignTokens.TextSecondary
            },
            maxLines = 1
        )
    }
}
