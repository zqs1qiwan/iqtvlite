package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** One row of the recent-channels list (already resolved against the current playlist). */
data class RecentChannelUi(
    val title: String,
    val number: Int,
    val logoUrl: String?,
    val selected: Boolean,
    // t4 (INFO_CARD_V2 §5.1)：信息卡底部小卡直接按索引切台，避免再按 url 二次解析。
    // 默认值保证既有调用点（对话框）零改动。
    val groupIndex: Int = -1,
    val variantIndex: Int = 0,
)

/**
 * t133: TV-friendly "最近观看" list.
 *
 * - OK switches channel (caller closes the drawer),
 * - Back closes only this list (playback continues),
 * - focus is the flat surfaceVariant highlight (no blue border), logos reuse [ChannelLogo]'s cache.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecentChannelsDialog(
    visible: Boolean,
    items: List<RecentChannelUi>,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    val shape = RoundedCornerShape(0.dp)

    val firstRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }

    LaunchedEffect(visible, items.size) {
        if (!visible) return@LaunchedEffect
        // UI-01: deterministic focus even when the list is empty / just cleared.
        if (items.isNotEmpty()) firstRequester.requestFocus() else clearRequester.requestFocus()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(120)) + scaleIn(animationSpec = tween(120), initialScale = 0.98f),
        exit = fadeOut(animationSpec = tween(100)) + scaleOut(animationSpec = tween(100), targetScale = 0.98f),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.Back) {
                        onClose()
                        true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .heightIn(max = 460.dp)
                    .background(container, shape)
                    .padding(20.dp)
                    // t167: stable ids for uiautomator/instrumentation (see RecentDialogTags).
                    .testTag(RecentDialogTags.DIALOG)
                    // t176: expose the test tags to uiautomator (resource-id) so BACK can be asserted.
                    .semantics {
                        contentDescription = "最近观看"
                        testTagsAsResourceId = true
                    }
            ) {
                Text(
                    text = "最近观看",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.padding(6.dp))

                if (items.isEmpty()) {
                    Text(
                        text = "暂无最近观看",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .testTag(RecentDialogTags.EMPTY)
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag(RecentDialogTags.LIST)
                    ) {
                        items.forEachIndexed { index, item ->
                            RecentRow(
                                item = item,
                                index = index,
                                focusRequester = if (index == 0) firstRequester else null,
                                onClick = { onSelect(index) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.padding(6.dp))
                RecentActionRow(
                    text = "清空最近观看",
                    tag = RecentDialogTags.CLEAR,
                    focusRequester = clearRequester,
                    onClick = onClear
                )
                RecentActionRow(
                    text = "关闭",
                    tag = RecentDialogTags.CLOSE,
                    focusRequester = null,
                    onClick = onClose
                )
            }
        }
    }
}

@Composable
private fun RecentRow(
    item: RecentChannelUi,
    index: Int,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .testTag(RecentDialogTags.row(index))
            .semantics { contentDescription = "${item.number}. ${item.title}" }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(
            logoUrl = item.logoUrl,
            fallbackTitle = item.title,
            width = 64.dp,
            height = 40.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = buildString {
                if (item.selected) append("▶ ")
                append(item.number).append(". ").append(item.title)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun RecentActionRow(
    text: String,
    tag: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = text }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
