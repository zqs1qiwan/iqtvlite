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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
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
    /** t187 (AUDIO-SINGLE-02): false for single-track / no-audio channels → the row is inert. */
    audioSelectable: Boolean = true,
    // t205: start-up buffer tier
    playbackBufferLabel: String = "稳(500ms)",
    onCyclePlaybackBuffer: () -> Unit = {},
    // t218: neighbour pre-connect experiment (default off)
    zapPreconnectEnabled: Boolean = false,
    onToggleZapPreconnect: () -> Unit = {},
    // t220: multicast pre-warm experiment (default off)
    multicastPrewarmEnabled: Boolean = false,
    onToggleMulticastPrewarm: () -> Unit = {},
    // t231: 睛彩 diagnosis experiment (off / e1 / e2 / e3)
    jingcaiExperimentLabel: String = "关闭",
    onCycleJingcaiExperiment: () -> Unit = {},
    onAudioTrackClick: () -> Unit = {},
    // t186 (F1): remote habits
    remoteUpDownLabel: String = "换台",
    remoteOkLabel: String = "打开频道列表",
    remoteBackLabel: String = "关闭抽屉",
    remoteDigitsLabel: String = "直接跳台",
    remoteChannelKeysEnabled: Boolean = true,
    onCycleRemoteUpDown: () -> Unit = {},
    onCycleRemoteOk: () -> Unit = {},
    onCycleRemoteBack: () -> Unit = {},
    onCycleRemoteDigits: () -> Unit = {},
    onToggleRemoteChannelKeys: () -> Unit = {},
    onResetRemoteHabits: () -> Unit = {},
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
    val remoteUpDownRequester = remember { FocusRequester() }
    val remoteOkRequester = remember { FocusRequester() }
    val remoteBackRequester = remember { FocusRequester() }
    val remoteDigitsRequester = remember { FocusRequester() }
    val remoteChannelKeysRequester = remember { FocusRequester() }
    val remoteResetRequester = remember { FocusRequester() }
    val playbackBufferRequester = remember { FocusRequester() }

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
                    // t192: the settings list scrolls. Before this, rows past ~y=980 (立即刷新EPG /
                    // 自动更新EPG / 上次更新) were unreachable on a 1080p/240dpi TV: DOWN moved focus
                    // off-screen and neither the D-pad nor a swipe scrolled the column.
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag("settings_list")
                ) {
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    // t176 (GROUP-01): 基础 opens the group and covers 源配置/更新EPG/源选择/检查更新/最近观看.
                    SettingsGroupHeader(text = "基础")
                    SettingsItem(
                        text = "源配置",
                        focusRequester = sourceConfigRequester,
                        tag = "settings_source_config",
                        onClick = onSourceConfigClick
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    SettingsItem(
                        text = "更新EPG",
                        focusRequester = epgSettingsRequester,
                        tag = "settings_epg_source",
                        onClick = onEpgSettingsClick
                    )

                    if (qualityMenuEnabled) {
                        Spacer(modifier = Modifier.padding(8.dp))

                        SettingsItem(
                            text = "源选择",
                            focusRequester = qualityMenuRequester,
                            tag = "settings_quality_menu",
                            onClick = onQualityMenuClick
                        )
                    }

                    Spacer(modifier = Modifier.padding(8.dp))

                    SettingsItem(
                        text = "检查更新",
                        focusRequester = checkUpdateRequester,
                        tag = "settings_check_update",
                        onClick = onCheckUpdateClick
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    // t133: 最近观看 entry (belongs to the 基础 group above).
                    SettingsItem(
                        text = if (recentCount > 0) "最近观看（$recentCount）" else "最近观看",
                        focusRequester = recentRequester,
                        tag = RecentDialogTags.SETTINGS_ENTRY,
                        onClick = onOpenRecent
                    )

                    Spacer(modifier = Modifier.padding(8.dp))

                    // t205: 播放与显示 group — currently the start-up buffer tier. The change takes
                    // effect on the next player creation / channel switch (see the note row).
                    SettingsGroupHeader(text = "播放与显示")
                    SettingsItem(
                        text = "起播缓冲：$playbackBufferLabel",
                        focusRequester = playbackBufferRequester,
                        tag = "settings_playback_buffer",
                        onClick = onCyclePlaybackBuffer
                    )
                    Text(
                        text = "快=起播更快但弱网更易卡顿；稳=更抗抖动。切换后下次切台生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)
                    )
                    SettingsItem(
                        text = if (zapPreconnectEnabled) "预连接相邻频道（实验）：开" else "预连接相邻频道（实验）：关",
                        focusRequester = null,
                        tag = "settings_zap_preconnect",
                        onClick = onToggleZapPreconnect
                    )
                    Text(
                        text = "开启后会在列表打开或空闲时预热相邻频道的网络连接（每台约 1 个连接、2 秒上限）。默认关闭；如出现卡顿或占用升高请关回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)
                    )
                    SettingsItem(
                        text = if (multicastPrewarmEnabled) "组播预热（实验）：开" else "组播预热（实验）：关",
                        focusRequester = null,
                        tag = "settings_multicast_prewarm",
                        onClick = onToggleMulticastPrewarm
                    )
                    SettingsItem(
                        text = "睛彩诊断实验：$jingcaiExperimentLabel",
                        focusRequester = null,
                        tag = "settings_jingcai_experiment",
                        onClick = onCycleJingcaiExperiment
                    )
                    Text(
                        text = "单个实验逐一切换（默认关闭，改回「关闭」即恢复原行为）：E1 TS 时间戳搜索 1MB→4MB；E2 改用系统 HTTP 数据源；E3 禁用 CEA608 字幕注入。仅供 117/118/119 定位用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)
                    )
                    Text(
                        text = "仅对 rtp/udp 组播源生效：预加入相邻频道的组播组并读取少量首个数据包（上限 3MB / 2.5 秒），用于提前完成组播加入。默认关闭；如内存/稳定性变差请关回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 6.dp)
                    )
                    Spacer(modifier = Modifier.padding(8.dp))

                    // t186 (F1): 遥控器与操作 group — every row cycles its own value, defaults keep
                    // the shipped behaviour.
                    SettingsGroupHeader(text = "遥控器与操作")
                    SettingsItem(
                        text = "上下键：$remoteUpDownLabel",
                        focusRequester = remoteUpDownRequester,
                        tag = "settings_remote_updown",
                        onClick = onCycleRemoteUpDown
                    )
                    SettingsItem(
                        text = "OK键：$remoteOkLabel",
                        focusRequester = remoteOkRequester,
                        tag = "settings_remote_ok",
                        onClick = onCycleRemoteOk
                    )
                    SettingsItem(
                        text = "Back键：$remoteBackLabel",
                        focusRequester = remoteBackRequester,
                        tag = "settings_remote_back",
                        onClick = onCycleRemoteBack
                    )
                    SettingsItem(
                        text = "数字键：$remoteDigitsLabel",
                        focusRequester = remoteDigitsRequester,
                        tag = "settings_remote_digits",
                        onClick = onCycleRemoteDigits
                    )
                    SettingsItem(
                        text = if (remoteChannelKeysEnabled) "频道键 166/167：启用" else "频道键 166/167：禁用",
                        focusRequester = remoteChannelKeysRequester,
                        tag = "settings_remote_channel_keys",
                        onClick = onToggleRemoteChannelKeys
                    )
                    SettingsItem(
                        text = "恢复默认",
                        focusRequester = remoteResetRequester,
                        tag = "settings_remote_reset",
                        onClick = onResetRemoteHabits
                    )
                    Spacer(modifier = Modifier.padding(8.dp))

                    // t175: 音频与字幕 group (audio track picker; summary line when known).
                    SettingsGroupHeader(text = "音频与字幕")
                    SettingsItem(
                        // t187: the label already says 仅 1 条 / 无音轨 when the picker cannot help.
                        text = if (audioSelectable) audioEntryLabel else "$audioEntryLabel（不可选）",
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
                        tag = "settings_logo_prefetch",
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
                        tag = "settings_logo_load",
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
                        tag = "settings_logo_clear",
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
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsItem(
    text: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    tag: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // t192: when the D-pad moves focus to a row that is outside the viewport, scroll it into view.
    // Every row gets its own requester, so no call site has to know about scrolling.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
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
            .bringIntoViewRequester(bringIntoView)
            .background(bg, RoundedCornerShape(0.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    scope.launch { runCatching { bringIntoView.bringIntoView() } }
                }
            }
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
