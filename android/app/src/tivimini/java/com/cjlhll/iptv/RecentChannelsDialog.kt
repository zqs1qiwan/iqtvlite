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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

data class RecentChannelUi(
    val title: String,
    val number: Int,
    val logoUrl: String?,
    val selected: Boolean,
    val groupIndex: Int = -1,
    val variantIndex: Int = 0,
)

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
    val firstRequester = remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    SafeInitialFocus(active = visible, items.isEmpty()) {
        if (items.isNotEmpty()) firstRequester else clearRequester
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(130)) + scaleIn(tween(130), initialScale = 0.97f),
        exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.97f),
        modifier = modifier.fillMaxSize()
    ) {
        TvModalContainer(
            width = 520.dp,
            maxHeight = 480.dp,
            modifier = Modifier.onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onClose()
                    true
                } else false
            },
            panelModifier = Modifier
                .testTag(RecentDialogTags.DIALOG)
                .semantics {
                    contentDescription = "最近观看"
                    testTagsAsResourceId = true
                }
        ) {
            Text(
                text = "最近观看",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TvDesignTokens.TextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (items.isEmpty()) {
                Text(
                    text = "暂无最近观看",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvDesignTokens.TextMuted,
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .testTag(RecentDialogTags.EMPTY)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 310.dp)
                        .verticalScroll(scrollState)
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

            Spacer(modifier = Modifier.height(10.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentRow(
    item: RecentChannelUi,
    index: Int,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(RecentDialogTags.row(index))
            .semantics { contentDescription = "${item.number}. ${item.title}" }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) scope.launch { runCatching { bringIntoView.bringIntoView() } }
            }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(
            logoUrl = item.logoUrl,
            fallbackTitle = item.title,
            width = 56.dp,
            height = 36.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "${item.number}. ${item.title}",
            style = MaterialTheme.typography.bodyLarge.copy(
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

@Composable
private fun RecentActionRow(
    text: String,
    tag: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
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
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary
        )
    }
}
