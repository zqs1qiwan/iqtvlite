package com.cjlhll.iptv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.iqtvlite.app.BuildConfig

private const val SETTINGS_LIST_TAG = "settings_list"

/** 设置抽屉不可变展示状态（替代 45+ 散落参数，显著降低重组开销与耦合）。 */
data class SettingsUiState(
    val visible: Boolean,
    val page: SettingsPage?,
    val rootFocusIndex: Int,
    val qualityMenuEnabled: Boolean,
    val recentCount: Int,
    val logoCacheSummary: String,
    val logoPrefetchEnabled: Boolean,
    val logoLoadEnabled: Boolean,
    val audioSummary: String?,
    val audioEntryLabel: String,
    val audioSelectable: Boolean,
    val playbackBufferLabel: String,
    val zapPreconnectEnabled: Boolean,
    val multicastPrewarmEnabled: Boolean,
    val jingcaiExperimentLabel: String,
    val infoOkModeLabel: String,
    val remoteBindings: List<KeyBinding>,
    val remoteDigitsLabel: String,
    val captureRequest: Pair<RemoteAction, Boolean>?,
    val autoUpdatePlaylistLabel: String,
    val autoUpdateEpgLabel: String,
    val lastUpdateSummary: String,
)

/** 设置抽屉动作集合。 */
data class SettingsActions(
    val onOpenPage: (SettingsPage) -> Unit,
    val onBackToRoot: () -> Unit,
    val onRootFocusChanged: (Int) -> Unit,
    val onClose: () -> Unit,
    val onSourceConfigClick: () -> Unit,
    val onQualityMenuClick: () -> Unit,
    val onCheckUpdateClick: () -> Unit,
    val onOpenRecent: () -> Unit,
    val onRefreshLogos: () -> Unit,
    val onClearLogoCache: () -> Unit,
    val onToggleLogoPrefetch: (Boolean) -> Unit,
    val onToggleLogoLoad: (Boolean) -> Unit,
    val onCyclePlaybackBuffer: () -> Unit,
    val onToggleZapPreconnect: () -> Unit,
    val onToggleMulticastPrewarm: () -> Unit,
    val onCycleJingcaiExperiment: () -> Unit,
    val onCycleInfoOkMode: () -> Unit,
    val onAudioTrackClick: () -> Unit,
    val onCycleRemoteUpDown: () -> Unit,
    val onCycleRemoteOk: () -> Unit,
    val onCycleRemoteDigits: () -> Unit,
    val onToggleRemoteChannelKeys: () -> Unit,
    val onStartCapture: (RemoteAction, Boolean) -> Unit,
    val onCancelCapture: () -> Unit,
    val onResetRemoteBindings: () -> Unit,
    val onCyclePlaylistInterval: () -> Unit,
    val onCycleEpgInterval: () -> Unit,
    val onRefreshPlaylistNow: () -> Unit,
    val onRefreshEpgNow: () -> Unit,
)

/**
 * 重构后的 TiviMate 风格右侧设置抽屉：
 * - 层级状态完全由 `PlayerUiController` 的 `PlayerOverlay.Settings` 驱动（零双向镜像、零 Back 副作用）；
 * - 统一曜石深色静态 `graphicsLayer` 背景 + 左侧品牌蓝焦点微条，视觉与选台抽屉完全统一；
 * - 使用 `SafeInitialFocus` 等待首帧挂载，切换一级/二级页绝不丢焦点；
 * - 遥控设置彻底统一到 `RemoteBindings` 单源（快速预设与自定义按键 100% 联动生效）。
 */
@Composable
fun SettingsDrawer(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    val page = state.page
    val rootFocusIndex = state.rootFocusIndex

    val pageLastFocus = remember { mutableMapOf<String, String>() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    val rootScroll = rememberScrollState()
    val playbackScroll = rememberScrollState()
    val remoteScroll = rememberScrollState()
    val audioScroll = rememberScrollState()
    val dataScroll = rememberScrollState()
    val logoScroll = rememberScrollState()
    val aboutScroll = rememberScrollState()

    fun scrollFor(target: SettingsPage): ScrollState = when (target) {
        SettingsPage.PLAYBACK -> playbackScroll
        SettingsPage.REMOTE -> remoteScroll
        SettingsPage.AUDIO -> audioScroll
        SettingsPage.DATA -> dataScroll
        SettingsPage.LOGO -> logoScroll
        SettingsPage.ABOUT -> aboutScroll
    }

    fun requesterOf(tag: String): FocusRequester = requesters.getOrPut(tag) { FocusRequester() }

    fun availableTags(target: SettingsPage): List<String> =
        SettingsIA.focusableTags(target).filter { tag ->
            tag != "settings_quality_menu" || state.qualityMenuEnabled
        }

    fun focusTagFor(target: SettingsPage): String {
        val available = availableTags(target)
        val remembered = pageLastFocus[target.id]
        return if (remembered != null && available.contains(remembered)) remembered else available.first()
    }

    // 帧安全焦点定位：一级 / 二级页切换总能稳稳拿到焦点
    SafeInitialFocus(active = visible, page) {
        val targetTag = if (page == null) {
            SettingsIA.rootTag(SettingsIA.ROOT_ORDER[rootFocusIndex.coerceIn(0, SettingsIA.ROOT_ORDER.lastIndex)])
        } else {
            focusTagFor(page)
        }
        requesterOf(targetTag)
    }

    // 离开遥控页或关闭抽屉时自动取消按键捕获态
    LaunchedEffect(visible, page) {
        if ((!visible || page != SettingsPage.REMOTE) && state.captureRequest != null) {
            actions.onCancelCapture()
        }
    }

    // 一级摘要直接从真实的 remoteBindings 推导（与实际行为 100% 一致）
    val remoteUpDownLabel = remember(state.remoteBindings) {
        RemoteBindings.deriveUpDownHabit(state.remoteBindings).label
    }
    val remoteOkLabel = remember(state.remoteBindings) {
        RemoteBindings.deriveOkHabit(state.remoteBindings).label
    }
    val remoteChannelKeysEnabled = remember(state.remoteBindings) {
        RemoteBindings.deriveChannelKeysEnabled(state.remoteBindings)
    }

    val rootSummaries = remember(
        state.playbackBufferLabel,
        state.zapPreconnectEnabled,
        state.multicastPrewarmEnabled,
        state.jingcaiExperimentLabel,
        remoteUpDownLabel,
        remoteOkLabel,
        state.audioSummary,
        state.autoUpdatePlaylistLabel,
        state.autoUpdateEpgLabel,
        state.logoCacheSummary,
    ) {
        mapOf(
            SettingsPage.PLAYBACK to SettingsSummary.playback(
                bufferLabel = state.playbackBufferLabel,
                anyExperimentOn = state.zapPreconnectEnabled || state.multicastPrewarmEnabled || state.jingcaiExperimentLabel != "关闭",
            ),
            SettingsPage.REMOTE to SettingsSummary.remote(remoteUpDownLabel, remoteOkLabel),
            SettingsPage.AUDIO to SettingsSummary.audio(state.audioSummary),
            SettingsPage.DATA to SettingsSummary.data(state.autoUpdatePlaylistLabel, state.autoUpdateEpgLabel),
            SettingsPage.LOGO to SettingsSummary.logoFromCacheSummary(state.logoCacheSummary),
            SettingsPage.ABOUT to SettingsSummary.about(BuildConfig.VERSION_CODE),
        )
    }
    val rootEntries = remember(rootSummaries) { SettingsIA.rootEntries(rootSummaries) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(150), initialOffsetX = { it }),
        exit = slideOutHorizontally(animationSpec = tween(120), targetOffsetX = { it }),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SettingsIA.ROOT_CONTAINER_TAG)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val navState = page?.let { SettingsNavPolicy.State.Page(it, focusTagFor(it)) }
                        ?: SettingsNavPolicy.State.Root(rootFocusIndex)
                    val key = when (event.key) {
                        Key.Back -> SettingsNavPolicy.Key.BACK
                        Key.DirectionLeft -> SettingsNavPolicy.Key.LEFT
                        Key.DirectionRight -> SettingsNavPolicy.Key.RIGHT
                        Key.DirectionUp -> SettingsNavPolicy.Key.UP
                        Key.DirectionDown -> SettingsNavPolicy.Key.DOWN
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> SettingsNavPolicy.Key.OK
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    val (next, consumed) = SettingsNavPolicy.onKey(navState, key)
                    if (!consumed) return@onPreviewKeyEvent false
                    when (next) {
                        SettingsNavPolicy.State.Closed -> actions.onClose()
                        is SettingsNavPolicy.State.Root -> {
                            actions.onRootFocusChanged(next.focus)
                            actions.onBackToRoot()
                        }
                        is SettingsNavPolicy.State.Page -> actions.onOpenPage(next.page)
                    }
                    true
                }
        ) {
            // 静态缓存背景层
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { alpha = TvDesignTokens.ALPHA_SETTINGS }
                    .background(TvDesignTokens.SurfaceObsidian)
            )
            // 内容层
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 22.dp, vertical = 22.dp)
            ) {
                when (page) {
                    null -> SettingsRootPage(
                        entries = rootEntries,
                        focusedIndex = rootFocusIndex,
                        scroll = rootScroll,
                        requesterOf = ::requesterOf,
                        onFocusIndex = actions.onRootFocusChanged,
                        onOpenPage = actions.onOpenPage,
                    )
                    SettingsPage.PLAYBACK -> SettingsPlaybackPage(
                        bufferLabel = state.playbackBufferLabel,
                        onCycleBuffer = actions.onCyclePlaybackBuffer,
                        qualityMenuEnabled = state.qualityMenuEnabled,
                        onQualityMenuClick = actions.onQualityMenuClick,
                        zapPreconnectEnabled = state.zapPreconnectEnabled,
                        onToggleZapPreconnect = actions.onToggleZapPreconnect,
                        multicastPrewarmEnabled = state.multicastPrewarmEnabled,
                        onToggleMulticastPrewarm = actions.onToggleMulticastPrewarm,
                        jingcaiLabel = state.jingcaiExperimentLabel,
                        onCycleJingcai = actions.onCycleJingcaiExperiment,
                        infoOkModeLabel = state.infoOkModeLabel,
                        onCycleInfoOkMode = actions.onCycleInfoOkMode,
                        scroll = scrollFor(SettingsPage.PLAYBACK),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.PLAYBACK.id] = it },
                    )
                    SettingsPage.REMOTE -> SettingsRemotePage(
                        upDownLabel = remoteUpDownLabel,
                        okLabel = remoteOkLabel,
                        digitsLabel = state.remoteDigitsLabel,
                        channelKeysEnabled = remoteChannelKeysEnabled,
                        onCycleUpDown = actions.onCycleRemoteUpDown,
                        onCycleOk = actions.onCycleRemoteOk,
                        onCycleDigits = actions.onCycleRemoteDigits,
                        onToggleChannelKeys = actions.onToggleRemoteChannelKeys,
                        bindings = state.remoteBindings,
                        captureRequest = state.captureRequest,
                        onStartCapture = actions.onStartCapture,
                        onResetBindings = actions.onResetRemoteBindings,
                        scroll = scrollFor(SettingsPage.REMOTE),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.REMOTE.id] = it },
                    )
                    SettingsPage.AUDIO -> SettingsAudioPage(
                        summary = state.audioSummary,
                        entryLabel = state.audioEntryLabel,
                        selectable = state.audioSelectable,
                        onAudioTrackClick = actions.onAudioTrackClick,
                        scroll = scrollFor(SettingsPage.AUDIO),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.AUDIO.id] = it },
                    )
                    SettingsPage.DATA -> SettingsDataPage(
                        onSourceConfigClick = actions.onSourceConfigClick,
                        autoUpdatePlaylistLabel = state.autoUpdatePlaylistLabel,
                        autoUpdateEpgLabel = state.autoUpdateEpgLabel,
                        lastUpdateSummary = state.lastUpdateSummary,
                        recentCount = state.recentCount,
                        onCyclePlaylistInterval = actions.onCyclePlaylistInterval,
                        onCycleEpgInterval = actions.onCycleEpgInterval,
                        onRefreshPlaylistNow = actions.onRefreshPlaylistNow,
                        onRefreshEpgNow = actions.onRefreshEpgNow,
                        onOpenRecent = actions.onOpenRecent,
                        scroll = scrollFor(SettingsPage.DATA),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.DATA.id] = it },
                    )
                    SettingsPage.LOGO -> SettingsLogoPage(
                        logoCacheSummary = state.logoCacheSummary,
                        logoPrefetchEnabled = state.logoPrefetchEnabled,
                        logoLoadEnabled = state.logoLoadEnabled,
                        onToggleLogoPrefetch = actions.onToggleLogoPrefetch,
                        onToggleLogoLoad = actions.onToggleLogoLoad,
                        onRefreshLogos = actions.onRefreshLogos,
                        onClearLogoCache = actions.onClearLogoCache,
                        scroll = scrollFor(SettingsPage.LOGO),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.LOGO.id] = it },
                    )
                    SettingsPage.ABOUT -> SettingsAboutPage(
                        onCheckUpdateClick = actions.onCheckUpdateClick,
                        scroll = scrollFor(SettingsPage.ABOUT),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.ABOUT.id] = it },
                    )
                }
            }
        }
    }
}

// ─── 一级 ──────────────────────────────────────────────────────────────────

@Composable
private fun SettingsRootPage(
    entries: List<SettingsRootEntry>,
    focusedIndex: Int,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocusIndex: (Int) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TvDesignTokens.TextPrimary,
            modifier = Modifier
                .testTag(SettingsIA.ROOT_TITLE_TAG)
                .padding(bottom = 18.dp, start = 4.dp)
        )
        entries.forEachIndexed { index, entry ->
            SettingsRootItem(
                entry = entry,
                focusRequester = requesterOf(entry.tag),
                selected = index == focusedIndex,
                onFocus = { onFocusIndex(index) },
                onClick = { onOpenPage(entry.page) },
            )
            if (index != entries.lastIndex) {
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        HelpText(
            text = "全屏按 ← 选台 · 按 → 或菜单键打开设置",
            color = TvDesignTokens.TextMuted,
            start = 12.dp,
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsRootItem(
    entry: SettingsRootEntry,
    focusRequester: FocusRequester,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(entry.tag)
            .semantics { contentDescription = "${entry.page.title} ${entry.summary}" }
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoView)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    if (!selected) onFocus()
                    scope.launch { runCatching { bringIntoView.bringIntoView() } }
                }
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester.requestFocus() }
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (focused) {
            Box(
                modifier = Modifier
                    .width(TvDesignTokens.AccentBarWidth)
                    .height(26.dp)
                    .background(TvDesignTokens.AccentPlaying)
            )
            Spacer(modifier = Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.page.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextPrimary.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextMuted
        )
    }
}

// ─── 二级页 ────────────────────────────────────────────────────────────────

@Composable
private fun SettingsPlaybackPage(
    bufferLabel: String,
    onCycleBuffer: () -> Unit,
    qualityMenuEnabled: Boolean,
    onQualityMenuClick: () -> Unit,
    zapPreconnectEnabled: Boolean,
    onToggleZapPreconnect: () -> Unit,
    multicastPrewarmEnabled: Boolean,
    onToggleMulticastPrewarm: () -> Unit,
    jingcaiLabel: String,
    onCycleJingcai: () -> Unit,
    infoOkModeLabel: String,
    onCycleInfoOkMode: () -> Unit,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.PLAYBACK)
        SettingsItem(
            text = "信息卡 OK 行为：$infoOkModeLabel",
            focusRequester = requesterOf("settings_info_ok_mode"),
            tag = "settings_info_ok_mode",
            onFocused = onFocused,
            onClick = onCycleInfoOkMode
        )
        HelpText("直接选最近观看：OK 出卡后立即左右选台；两段式：再按一次 OK 进入选择。")
        RowGap()
        SettingsItem(
            text = "起播缓冲：$bufferLabel",
            focusRequester = requesterOf("settings_playback_buffer"),
            tag = "settings_playback_buffer",
            onFocused = onFocused,
            onClick = onCycleBuffer
        )
        HelpText("快=切台更快；稳=弱网更抗卡顿。下次切台生效。")
        if (qualityMenuEnabled) {
            RowGap()
            SettingsItem(
                text = "线路选择（当前频道）",
                focusRequester = requesterOf("settings_quality_menu"),
                tag = "settings_quality_menu",
                onFocused = onFocused,
                onClick = onQualityMenuClick
            )
        }

        SettingsGroupHeader("高级诊断（实验）")
        SettingsItem(
            text = if (zapPreconnectEnabled) "预连接相邻频道：开" else "预连接相邻频道：关",
            focusRequester = requesterOf("settings_zap_preconnect"),
            tag = "settings_zap_preconnect",
            onFocused = onFocused,
            onClick = onToggleZapPreconnect
        )
        RowGap()
        SettingsItem(
            text = if (multicastPrewarmEnabled) "组播预热：开" else "组播预热：关",
            focusRequester = requesterOf("settings_multicast_prewarm"),
            tag = "settings_multicast_prewarm",
            onFocused = onFocused,
            onClick = onToggleMulticastPrewarm
        )
        RowGap()
        SettingsItem(
            text = "码流兼容模式：$jingcaiLabel",
            focusRequester = requesterOf("settings_jingcai_experiment"),
            tag = "settings_jingcai_experiment",
            onFocused = onFocused,
            onClick = onCycleJingcai
        )
        HelpText("默认关闭；仅针对特殊无 AUD 稀疏 PCR 组播流调试使用。")
    }
}

@Composable
private fun SettingsRemotePage(
    upDownLabel: String,
    okLabel: String,
    digitsLabel: String,
    channelKeysEnabled: Boolean,
    onCycleUpDown: () -> Unit,
    onCycleOk: () -> Unit,
    onCycleDigits: () -> Unit,
    onToggleChannelKeys: () -> Unit,
    bindings: List<KeyBinding>,
    captureRequest: Pair<RemoteAction, Boolean>?,
    onStartCapture: (RemoteAction, Boolean) -> Unit,
    onResetBindings: () -> Unit,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.REMOTE)
        SettingsGroupHeader("快速习惯（即时生效）")
        SettingsItem(
            text = "上下键：$upDownLabel",
            focusRequester = requesterOf("settings_remote_updown"),
            tag = "settings_remote_updown",
            onFocused = onFocused,
            onClick = onCycleUpDown
        )
        RowGap()
        SettingsItem(
            text = "OK键：$okLabel",
            focusRequester = requesterOf("settings_remote_ok"),
            tag = "settings_remote_ok",
            onFocused = onFocused,
            onClick = onCycleOk
        )
        RowGap()
        SettingsItem(
            text = "数字键：$digitsLabel",
            focusRequester = requesterOf("settings_remote_digits"),
            tag = "settings_remote_digits",
            onFocused = onFocused,
            onClick = onCycleDigits
        )
        RowGap()
        SettingsItem(
            text = if (channelKeysEnabled) "频道键 166/167：启用" else "频道键 166/167：禁用",
            focusRequester = requesterOf("settings_remote_channel_keys"),
            tag = "settings_remote_channel_keys",
            onFocused = onFocused,
            onClick = onToggleChannelKeys
        )
        RowGap()
        SettingsItem(
            text = "恢复默认遥控配置",
            focusRequester = requesterOf("settings_remote_reset"),
            tag = "settings_remote_reset",
            onFocused = onFocused,
            onClick = onResetBindings
        )

        SettingsGroupHeader("自定义按键（短按 / 长按）")
        if (captureRequest != null) {
            HelpText(
                text = "请按遥控器任意键完成绑定（按 Back 取消）…",
                color = BrandFocus.BlueColor
            )
        }
        for (action in RemoteBindings.CONFIGURABLE_ACTIONS) {
            val binding = bindings.firstOrNull { it.action == action }
            val capturingShort = captureRequest?.let { it.first == action && !it.second } == true
            val capturingLong = captureRequest?.let { it.first == action && it.second } == true
            val shortText = if (capturingShort) "请按键…" else RemoteBindings.bindingSummary(binding, false)
            val longText = if (capturingLong) "请按键…" else RemoteBindings.bindingSummary(binding, true)
            SettingsItem(
                text = "${action.label} · 短[$shortText]",
                focusRequester = requesterOf("settings_remote_action_${action.name}"),
                tag = "settings_remote_action_${action.name}",
                onFocused = onFocused,
                onClick = { onStartCapture(action, false) },
            )
            SettingsItem(
                text = if (capturingLong) "  ↳ 长按：请按任意键…" else "  ↳ 长按 [$longText]",
                focusRequester = requesterOf("settings_remote_bind_long_${action.name}"),
                tag = "settings_remote_bind_long_${action.name}",
                onFocused = onFocused,
                onClick = { onStartCapture(action, true) },
            )
            RowGap()
        }
        SettingsItem(
            text = "重置全部自定义映射",
            focusRequester = requesterOf("settings_remote_bindings_reset"),
            tag = "settings_remote_bindings_reset",
            onFocused = onFocused,
            onClick = onResetBindings,
        )
    }
}

@Composable
private fun SettingsAudioPage(
    summary: String?,
    entryLabel: String,
    selectable: Boolean,
    onAudioTrackClick: () -> Unit,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.AUDIO)
        SettingsItem(
            text = if (selectable) entryLabel else "$entryLabel（不可选）",
            focusRequester = requesterOf("settings_audio_track"),
            tag = "settings_audio_track",
            onFocused = onFocused,
            onClick = onAudioTrackClick
        )
        if (!summary.isNullOrBlank()) {
            HelpText("当前：$summary")
        }
    }
}

@Composable
private fun SettingsDataPage(
    onSourceConfigClick: () -> Unit,
    autoUpdatePlaylistLabel: String,
    autoUpdateEpgLabel: String,
    lastUpdateSummary: String,
    recentCount: Int,
    onCyclePlaylistInterval: () -> Unit,
    onCycleEpgInterval: () -> Unit,
    onRefreshPlaylistNow: () -> Unit,
    onRefreshEpgNow: () -> Unit,
    onOpenRecent: () -> Unit,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.DATA)
        SettingsGroupHeader(text = "播放列表")
        SettingsItem(
            text = "源配置",
            focusRequester = requesterOf("settings_source_config"),
            tag = "settings_source_config",
            onFocused = onFocused,
            onClick = onSourceConfigClick
        )
        RowGap()
        SettingsItem(
            text = "自动更新列表：$autoUpdatePlaylistLabel",
            focusRequester = requesterOf("settings_auto_playlist"),
            tag = "settings_auto_playlist",
            onFocused = onFocused,
            onClick = onCyclePlaylistInterval
        )
        RowGap()
        SettingsItem(
            text = "立即刷新播放列表",
            focusRequester = requesterOf("settings_refresh_playlist"),
            tag = "settings_refresh_playlist",
            onFocused = onFocused,
            onClick = onRefreshPlaylistNow
        )
        SettingsGroupHeader(text = "节目单 (EPG)")
        SettingsItem(
            text = "自动更新 EPG：$autoUpdateEpgLabel",
            focusRequester = requesterOf("settings_auto_epg"),
            tag = "settings_auto_epg",
            onFocused = onFocused,
            onClick = onCycleEpgInterval
        )
        RowGap()
        SettingsItem(
            text = "立即更新 EPG",
            focusRequester = requesterOf("settings_refresh_epg"),
            tag = "settings_refresh_epg",
            onFocused = onFocused,
            onClick = onRefreshEpgNow
        )
        if (lastUpdateSummary.isNotBlank()) {
            HelpText(lastUpdateSummary)
        }
        SettingsGroupHeader(text = "观看记录")
        SettingsItem(
            text = if (recentCount > 0) "最近观看（$recentCount）" else "最近观看",
            focusRequester = requesterOf(RecentDialogTags.SETTINGS_ENTRY),
            tag = RecentDialogTags.SETTINGS_ENTRY,
            onFocused = onFocused,
            onClick = onOpenRecent
        )
    }
}

@Composable
private fun SettingsLogoPage(
    logoCacheSummary: String,
    logoPrefetchEnabled: Boolean,
    logoLoadEnabled: Boolean,
    onToggleLogoPrefetch: (Boolean) -> Unit,
    onToggleLogoLoad: (Boolean) -> Unit,
    onRefreshLogos: () -> Unit,
    onClearLogoCache: () -> Unit,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.LOGO)
        if (logoCacheSummary.isNotBlank()) {
            HelpText(logoCacheSummary, start = 4.dp)
        }
        SettingsItem(
            tag = "settings_logo_prefetch",
            text = if (logoPrefetchEnabled) "台标预取：开" else "台标预取：关",
            focusRequester = requesterOf("settings_logo_prefetch"),
            onFocused = onFocused,
            onClick = { onToggleLogoPrefetch(!logoPrefetchEnabled) }
        )
        HelpText("关闭后仅加载当前可视台标，进一步降低低配盒子负载。")
        RowGap()
        SettingsItem(
            tag = "settings_logo_load",
            text = if (logoLoadEnabled) "台标显示：开" else "台标显示：关",
            focusRequester = requesterOf("settings_logo_load"),
            onFocused = onFocused,
            onClick = { onToggleLogoLoad(!logoLoadEnabled) }
        )
        HelpText("关闭后不加载台标，仅显示文字首字占位。")
        RowGap()
        SettingsItem(
            text = "刷新当前组台标",
            focusRequester = requesterOf("settings_logo_refresh"),
            tag = "settings_logo_refresh",
            onFocused = onFocused,
            onClick = onRefreshLogos
        )
        RowGap()
        SettingsItem(
            text = "清理台标缓存",
            focusRequester = requesterOf("settings_logo_clear"),
            tag = "settings_logo_clear",
            onFocused = onFocused,
            onClick = onClearLogoCache
        )
    }
}

@Composable
private fun SettingsAboutPage(
    onCheckUpdateClick: () -> Unit,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.ABOUT)
        Text(
            text = "IQTV Lite",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = TvDesignTokens.TextPrimary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = TvDesignTokens.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
        )
        Text(
            text = "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            color = TvDesignTokens.TextMuted,
            modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)
        )
        SettingsItem(
            text = "检查更新",
            focusRequester = requesterOf("settings_check_update"),
            tag = "settings_check_update",
            onFocused = onFocused,
            onClick = onCheckUpdateClick
        )
    }
}

// ─── 基础组件 ──────────────────────────────────────────────────────────────

@Composable
private fun SettingsPageHeader(page: SettingsPage) {
    Text(
        text = "← 设置",
        style = MaterialTheme.typography.labelMedium,
        color = TvDesignTokens.TextMuted,
        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
    )
    Text(
        text = page.title,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = TvDesignTokens.TextPrimary,
        modifier = Modifier
            .testTag(SettingsIA.pageTitleTag(page))
            .padding(start = 4.dp, bottom = 14.dp)
    )
}

@Composable
private fun RowGap() {
    Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun HelpText(
    text: String,
    color: Color = TvDesignTokens.TextMuted,
    start: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.padding(start = start, top = 3.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        color = TvDesignTokens.TextSecondary,
        modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp)
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsItem(
    text: String,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    tag: String? = null,
    onFocused: ((String) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (tag != null) m.testTag(tag) else m }
            .semantics { contentDescription = text }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    if (tag != null && onFocused != null) onFocused(tag)
                    scope.launch { runCatching { bringIntoView.bringIntoView() } }
                }
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextPrimary.copy(alpha = 0.86f)
        )
    }
}
