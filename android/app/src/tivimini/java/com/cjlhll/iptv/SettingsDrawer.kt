package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun SettingsDrawer(
    visible: Boolean,
    onSourceConfigClick: () -> Unit,
    onEpgSettingsClick: () -> Unit,
    qualityMenuEnabled: Boolean = false,
    onQualityMenuClick: () -> Unit = {},
    onCheckUpdateClick: () -> Unit,
    onOpenRecent: () -> Unit = {},
    recentCount: Int = 0,
    onRefreshLogos: () -> Unit = {},
    onClearLogoCache: () -> Unit = {},
    logoCacheSummary: String = "",
    logoPrefetchEnabled: Boolean = true,
    logoLoadEnabled: Boolean = true,
    onToggleLogoPrefetch: (Boolean) -> Unit = {},
    onToggleLogoLoad: (Boolean) -> Unit = {},
    // t172 (F3): automatic playlist/EPG updates
    // t175 (F4): audio & subtitle group
    audioSummary: String? = null,
    audioEntryLabel: String = "音轨选择",
    onAudioTrackClick: () -> Unit = {},
    autoUpdatePlaylistLabel: String = "启动时",
    autoUpdateEpgLabel: String = "启动时",
    lastUpdateSummary: String = "",
    onCyclePlaylistInterval: () -> Unit = {},
    onCycleEpgInterval: () -> Unit = {},
    onRefreshPlaylistNow: () -> Unit = {},
    onRefreshEpgNow: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val scrim = Color.Transparent
    val shape = RoundedCornerShape(0.dp)

    val sourceConfigRequester = remember { FocusRequester() }
    val epgSettingsRequester = remember { FocusRequester() }
    val qualityMenuRequester = remember { FocusRequester() }
    val checkUpdateRequester = remember { FocusRequester() }
    val recentRequester = remember { FocusRequester() }
    val logoRefreshRequester = remember { FocusRequester() }
    val logoClearRequester = remember { FocusRequester() }
    val logoPrefetchRequester = remember { FocusRequester() }
    val logoLoadRequester = remember { FocusRequester() }
    val playlistIntervalRequester = remember { FocusRequester() }
    val epgIntervalRequester = remember { FocusRequester() }
    val audioTrackRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            // t167: land on 最近观看 first so the list/clear flow is reachable with one OK press
            // (and reachable by uiautomator without walking the whole settings column).
            recentRequester.requestFocus()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(160), initialOffsetX = { it }),
        exit = slideOutHorizontally(animationSpec = tween(140), targetOffsetX = { it }),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrim)
                .onPreviewKeyEvent {
                    if (it.key == Key.Back || it.key == Key.DirectionLeft) {
                        onClose()
                        true
                    } else {
                        false
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .align(Alignment.CenterEnd)
                    .background(container, shape)
                    .padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    SettingsItem(
                        text = "源配置",
                        focusRequester = sourceConfigRequester,
                        onClick = onSourceConfigClick
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    SettingsItem(
                        text = "更新EPG",
                        focusRequester = epgSettingsRequester,
                        onClick = onEpgSettingsClick
                    )

                    if (qualityMenuEnabled) {
                        Spacer(modifier = Modifier.padding(8.dp))

                        SettingsItem(
                            text = "源选择",
                            focusRequester = qualityMenuRequester,
                            onClick = onQualityMenuClick
                        )
                    }

                    Spacer(modifier = Modifier.padding(8.dp))

                    SettingsItem(
                        text = "检查更新",
                        focusRequester = checkUpdateRequester,
                        onClick = onCheckUpdateClick
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    // t175: 音频与字幕 group (audio track picker; summary line when known).
                    SettingsGroupHeader(text = "音频与字幕")
                    SettingsItem(
                        text = audioEntryLabel,
                        focusRequester = audioTrackRequester,
                        tag = "settings_audio_track",
                        onClick = onAudioTrackClick
                    )
                    if (!audioSummary.isNullOrBlank()) {
                        Text(
                            text = "当前：$audioSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.padding(8.dp))

                    // t172: 数据与更新 group (auto playlist/EPG refresh + manual refresh).
                    SettingsGroupHeader(text = "数据与更新")
                    SettingsItem(
                        text = "自动更新播放列表：$autoUpdatePlaylistLabel",
                        focusRequester = playlistIntervalRequester,
                        tag = "settings_auto_playlist",
                        onClick = onCyclePlaylistInterval
                    )
                    SettingsItem(
                        text = "立即刷新播放列表",
                        focusRequester = null,
                        tag = "settings_refresh_playlist",
                        onClick = onRefreshPlaylistNow
                    )
                    SettingsItem(
                        text = "自动更新 EPG：$autoUpdateEpgLabel",
                        focusRequester = epgIntervalRequester,
                        tag = "settings_auto_epg",
                        onClick = onCycleEpgInterval
                    )
                    SettingsItem(
                        text = "立即刷新 EPG",
                        focusRequester = null,
                        tag = "settings_refresh_epg",
                        onClick = onRefreshEpgNow
                    )
                    if (lastUpdateSummary.isNotBlank()) {
                        Text(
                            text = lastUpdateSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)
                        )
                    }
                    Spacer(modifier = Modifier.padding(8.dp))

                    SettingsGroupHeader(text = "基础")
                    // t133: 最近观看 entry (list + clear live in RecentChannelsDialog).
                    SettingsItem(
                        text = if (recentCount > 0) "最近观看（$recentCount）" else "最近观看",
                        focusRequester = recentRequester,
                        tag = RecentDialogTags.SETTINGS_ENTRY,
                        onClick = onOpenRecent
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    // t126/t172: 台标与缓存 group
                    SettingsGroupHeader(text = "台标与缓存")
                    if (logoCacheSummary.isNotBlank()) {
                        Text(
                            text = logoCacheSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    // t154: perf-isolation switches (default on; turning them off lowers load on
                    // weak boxes and isolates the cost during A/B measurements).
                    SettingsItem(
                        text = if (logoPrefetchEnabled) "台标预取：开" else "台标预取：关",
                        focusRequester = logoPrefetchRequester,
                        onClick = { onToggleLogoPrefetch(!logoPrefetchEnabled) }
                    )
                    Text(
                        text = "关闭后只加载可视台标，可降低低配设备负载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    SettingsItem(
                        text = if (logoLoadEnabled) "台标显示：开" else "台标显示：关",
                        focusRequester = logoLoadRequester,
                        onClick = { onToggleLogoLoad(!logoLoadEnabled) }
                    )
                    Text(
                        text = "关闭后不加载/解码台标，只显示占位",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
                    )
                    Spacer(modifier = Modifier.padding(4.dp))
                    SettingsItem(
                        text = "刷新当前组台标",
                        focusRequester = logoRefreshRequester,
                        onClick = onRefreshLogos
                    )
                    Spacer(modifier = Modifier.padding(8.dp))
                    SettingsItem(
                        text = "清理台标缓存",
                        focusRequester = logoClearRequester,
                        onClick = onClearLogoCache
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(text: String) {
    // t172: flat group title (0dp, no focus — it is a label, not a control).
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    text: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    tag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (tag != null) m.testTag(tag) else m }
            .semantics { contentDescription = text }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg, RoundedCornerShape(0.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable {
                onClick()
                focusRequester?.requestFocus()
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
