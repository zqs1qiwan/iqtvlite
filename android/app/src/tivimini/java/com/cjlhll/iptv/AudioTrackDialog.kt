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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * t175 (F4): audio track picker. Flat (0dp), focus = surfaceVariant (no blue frame), OK applies,
 * BACK closes only this dialog.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioTrackDialog(
    visible: Boolean,
    items: List<AudioTrackUi.Item>,
    onSelect: (AudioTrackUi.Item) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
    val shape = RoundedCornerShape(0.dp)
    val firstRequester = remember { FocusRequester() }

    LaunchedEffect(visible, items.size) {
        if (visible && items.isNotEmpty()) firstRequester.requestFocus()
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
                    .width(560.dp)
                    .heightIn(max = 460.dp)
                    .background(container, shape)
                    .padding(20.dp)
                    .testTag("audio_dialog")
                    // t176: expose the test tags to uiautomator (resource-id) so BACK can be asserted.
                    .semantics { testTagsAsResourceId = true }
            ) {
                Text(
                    text = "音轨选择",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.padding(6.dp))

                if (items.isEmpty()) {
                    Text(
                        text = "当前频道没有可选音轨",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp).testTag("audio_empty")
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        items.forEachIndexed { index, item ->
                            AudioRow(
                                item = item,
                                focusRequester = if (index == 0) firstRequester else null,
                                onClick = { onSelect(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioRow(
    item: AudioTrackUi.Item,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(if (item.isAuto) "audio_row_auto" else "audio_row_${item.groupIndex}_${item.trackIndex}")
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = if (item.selected) "▶ ${item.text}" else item.text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (focused) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
