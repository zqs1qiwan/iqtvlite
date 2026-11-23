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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

data class SourceVariantUi(
    val label: String,
    val latencyMs: Int?,
    val probing: Boolean = false,
    val selected: Boolean
)

/**
 * 源/线路选择弹窗（统一曜石模态风格）：
 * - 修复 P0-10：仅在弹窗打开瞬间（key = visible）请求初始焦点，测速结果刷新延迟不再抢回焦点；
 * - 使用 SafeInitialFocus 防止首帧未挂载崩溃，支持 BringIntoView 自动滚动。
 */
@Composable
fun QualitySelectorDialog(
    visible: Boolean,
    channelTitle: String,
    variants: List<SourceVariantUi>,
    onSelect: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val itemRequesters = remember(variants.size) {
        List(variants.size) { FocusRequester() }
    }

    // 关键修复：只监听 visible（不监听 variants 内容变化），测速延迟更新绝不抢焦点！
    SafeInitialFocus(active = visible && variants.isNotEmpty(), variants.size) {
        val selectedIndex = variants.indexOfFirst { it.selected }.coerceAtLeast(0)
        itemRequesters.getOrNull(selectedIndex)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)) + scaleIn(tween(140), initialScale = 0.97f),
        exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.97f),
        modifier = modifier.fillMaxSize()
    ) {
        TvModalContainer(
            width = 440.dp,
            maxHeight = 480.dp,
            modifier = Modifier.onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Back) {
                    onClose()
                    true
                } else false
            },
        ) {
            Text(
                text = "线路选择",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                color = TvDesignTokens.TextPrimary
            )
            if (channelTitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = channelTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TvDesignTokens.TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                variants.forEachIndexed { index, variant ->
                    SourceOptionItem(
                        label = variant.label,
                        latencyText = when {
                            variant.probing && variant.latencyMs == null -> "测速中"
                            variant.latencyMs != null -> "${variant.latencyMs}ms"
                            else -> "超时"
                        },
                        selected = variant.selected,
                        focusRequester = itemRequesters.getOrNull(index),
                        onClick = { onSelect(index) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SourceOptionItem(
    label: String,
    latencyText: String,
    selected: Boolean,
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
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg, TvDesignTokens.ShapeFlat)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) scope.launch { runCatching { bringIntoView.bringIntoView() } }
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (focused) {
            Box(
                modifier = Modifier
                    .width(TvDesignTokens.AccentBarWidth)
                    .height(18.dp)
                    .background(TvDesignTokens.AccentPlaying)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Text(
            text = if (selected) "$label · 当前" else label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (selected) BrandFocus.BlueColor else TvDesignTokens.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = latencyText,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> BrandFocus.BlueColor
                focused -> TvDesignTokens.TextPrimary
                else -> TvDesignTokens.TextSecondary
            }
        )
    }
}
