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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.iqtvlite.app.BuildConfig

// t13：check_settings_scroll.py 依赖字面量 settings_list（门禁不改），必须与 SettingsIA.LIST_TAG 一致。
private const val SETTINGS_LIST_TAG = "settings_list"

/**
 * t3（docs/SETTINGS_IA_V2.md）：设置抽屉改为「一级入口 + 6 个二级页」。
 *
 * - 一级：6 行（标题 + 当前值摘要），OK/RIGHT 进页，BACK/LEFT 关抽屉。
 * - 二级：每页 ≤6 个可聚焦行；BACK/LEFT 回一级并把焦点放回入口行，RIGHT 被消费。
 * - 滚动沿用 `verticalScroll` + 每行 `BringIntoViewRequester`（不引入 LazyColumn）。
 * - 所有既有设置项、tag、行为与帮助文案原样保留（唯一合并：旧「更新EPG」=「立即更新 EPG」）。
 *
 * 纯逻辑（页面枚举、tag 表、Back 状态机、摘要格式化）在 `SettingsIA.kt`，可 JVM 单测。
 */
@Composable
fun SettingsDrawer(
    visible: Boolean,
    onSourceConfigClick: () -> Unit,
    // t3: 旧「更新EPG」与「立即刷新 EPG」合并为一行（同一动作 requestManualEpg），参数名保留兼容。
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
    modifier: Modifier = Modifier,
    /**
     * t3: 把「二级页存在时先消费 BACK」的能力交给 PlayerActivity 的 dispatchKeyEvent。
     * 返回 true 表示已处理（二级 → 一级），false 表示一级（由调用方关闭抽屉）。
     */
    onBackHandlerReady: ((() -> Boolean)?) -> Unit = {},
) {
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val scrim = Color.Transparent

    // ── 会话内导航状态（不持久化，规格 §5.2） ─────────────────────────────
    // null = 一级；否则当前二级页。抽屉关闭时复位，重新打开总落在一级。
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    // 一级焦点行：首次进程打开 = 0（播放与画面，D-S3/cap·R2）；会话内记忆上次停留。
    var rootFocusIndex by remember { mutableStateOf(0) }
    // 每页上次停留的行 tag（会话内记忆）。
    val pageLastFocus = remember { mutableMapOf<String, String>() }
    // tag → FocusRequester：行在首次组合时创建，页面切换不重建。
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

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

    // 条件行（线路选择只在多线路时存在）不参与焦点落点计算，避免“焦点掉到不存在的节点”。
    fun availableTags(target: SettingsPage): List<String> =
        SettingsIA.itemTags(target).filter { tag ->
            tag != "settings_quality_menu" || qualityMenuEnabled
        }

    fun focusTagFor(target: SettingsPage): String {
        val available = availableTags(target)
        val remembered = pageLastFocus[target.id]
        return if (remembered != null && available.contains(remembered)) remembered else available.first()
    }

    fun openPage(target: SettingsPage) {
        rootFocusIndex = SettingsNavPolicy.rootIndexFor(target)
        page = target
    }

    // Back/Left 层级：二级 → 一级（焦点回入口行）；一级 → 关抽屉。只退一层。
    LaunchedEffect(visible, page) {
        if (!visible) {
            // 关闭后复位到一级：重新打开时不会停在旧二级页。
            page = null
            return@LaunchedEffect
        }
        val targetTag = if (page == null) {
            SettingsIA.rootTag(SettingsIA.ROOT_ORDER[rootFocusIndex.coerceIn(0, SettingsIA.ROOT_ORDER.lastIndex)])
        } else {
            focusTagFor(page!!)
        }
        runCatching { requesterOf(targetTag).requestFocus() }
    }

    // 一级列表的滚动位置在会话内保留（不能被重组重置）。
    val rootScroll = rememberScrollState()

    fun navState(): SettingsNavPolicy.State = page?.let {
        SettingsNavPolicy.State.Page(it, focusTagFor(it))
    } ?: SettingsNavPolicy.State.Root(rootFocusIndex)

    fun applyNav(next: SettingsNavPolicy.State) {
        when (next) {
            SettingsNavPolicy.State.Closed -> onClose()
            is SettingsNavPolicy.State.Root -> {
                rootFocusIndex = next.focus.coerceIn(0, SettingsIA.ROOT_ORDER.lastIndex)
                page = null
            }
            is SettingsNavPolicy.State.Page -> {
                rootFocusIndex = SettingsNavPolicy.rootIndexFor(next.page)
                page = next.page
            }
        }
    }

    // 交给 Activity 的 BACK 处理器：二级页存在时先退到一级（消费按键），否则交回 Activity 关抽屉。
    // 状态机就是 SettingsBackStackTest 覆盖的 SettingsNavPolicy（UI 与测试共用同一份规则）。
    // 用 rememberUpdatedState 包一层，避免 remember 捕获到首帧的参数（qualityMenuEnabled/onClose）。
    val onBackHandlerReadyState = rememberUpdatedState(onBackHandlerReady)
    val backActionState = rememberUpdatedState<() -> Boolean> {
        val (next, consumed) = SettingsNavPolicy.onKey(navState(), SettingsNavPolicy.Key.BACK)
        if (consumed) applyNav(next)
        consumed
    }
    val backHandler: () -> Boolean = remember { { backActionState.value() } }
    DisposableEffect(Unit) {
        onBackHandlerReadyState.value(backHandler)
        onDispose { onBackHandlerReadyState.value(null) }
    }

    // t7（PERF-03）：一级摘要只在输入变化时重算，避免父级重组（信息卡计时 tick 等）时每帧做
    // 字符串格式化 + 台标统计正则。
    val rootSummaries = remember(
        playbackBufferLabel,
        zapPreconnectEnabled,
        multicastPrewarmEnabled,
        jingcaiExperimentLabel,
        remoteUpDownLabel,
        remoteOkLabel,
        audioSummary,
        autoUpdatePlaylistLabel,
        autoUpdateEpgLabel,
        logoCacheSummary,
    ) {
        mapOf(
            SettingsPage.PLAYBACK to SettingsSummary.playback(
                bufferLabel = playbackBufferLabel,
                anyExperimentOn = zapPreconnectEnabled || multicastPrewarmEnabled || jingcaiExperimentLabel != "关闭",
            ),
            SettingsPage.REMOTE to SettingsSummary.remote(remoteUpDownLabel, remoteOkLabel),
            SettingsPage.AUDIO to SettingsSummary.audio(audioSummary),
            SettingsPage.DATA to SettingsSummary.data(autoUpdatePlaylistLabel, autoUpdateEpgLabel),
            SettingsPage.LOGO to SettingsSummary.logoFromCacheSummary(logoCacheSummary),
            SettingsPage.ABOUT to SettingsSummary.about(BuildConfig.VERSION_CODE),
        )
    }
    val rootEntries = remember(rootSummaries) { SettingsIA.rootEntries(rootSummaries) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(animationSpec = tween(160), initialOffsetX = { it }),
        exit = slideOutHorizontally(animationSpec = tween(140), targetOffsetX = { it }),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(SettingsIA.ROOT_CONTAINER_TAG)
                .background(scrim)
                .onPreviewKeyEvent { event ->
                    // 只处理按下事件：KeyUp 重复触发会让层级跳两次。
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    // 与 SettingsNavPolicy 同一份规则（UI 与 SettingsBackStackTest 不漂移）。
                    val key = when (event.key) {
                        Key.Back -> SettingsNavPolicy.Key.BACK
                        Key.DirectionLeft -> SettingsNavPolicy.Key.LEFT
                        Key.DirectionRight -> SettingsNavPolicy.Key.RIGHT
                        Key.DirectionUp -> SettingsNavPolicy.Key.UP
                        Key.DirectionDown -> SettingsNavPolicy.Key.DOWN
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> SettingsNavPolicy.Key.OK
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    // 页内的上下移动交给 Compose 焦点系统（NavPolicy 对 UP/DOWN 返回未消费）。
                    val (next, consumed) = SettingsNavPolicy.onKey(navState(), key)
                    if (!consumed) return@onPreviewKeyEvent false
                    applyNav(next)
                    true
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .align(Alignment.CenterEnd)
                    .background(container)
                    .padding(24.dp)
            ) {
                when (page) {
                    null -> SettingsRootPage(
                        entries = rootEntries,
                        focusedIndex = rootFocusIndex,
                        scroll = rootScroll,
                        requesterOf = ::requesterOf,
                        onFocusIndex = { rootFocusIndex = it },
                        onOpenPage = ::openPage,
                    )
                    SettingsPage.PLAYBACK -> SettingsPlaybackPage(
                        bufferLabel = playbackBufferLabel,
                        onCycleBuffer = onCyclePlaybackBuffer,
                        qualityMenuEnabled = qualityMenuEnabled,
                        onQualityMenuClick = onQualityMenuClick,
                        zapPreconnectEnabled = zapPreconnectEnabled,
                        onToggleZapPreconnect = onToggleZapPreconnect,
                        multicastPrewarmEnabled = multicastPrewarmEnabled,
                        onToggleMulticastPrewarm = onToggleMulticastPrewarm,
                        jingcaiLabel = jingcaiExperimentLabel,
                        onCycleJingcai = onCycleJingcaiExperiment,
                        scroll = scrollFor(SettingsPage.PLAYBACK),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.PLAYBACK.id] = it },
                    )
                    SettingsPage.REMOTE -> SettingsRemotePage(
                        upDownLabel = remoteUpDownLabel,
                        okLabel = remoteOkLabel,
                        backLabel = remoteBackLabel,
                        digitsLabel = remoteDigitsLabel,
                        channelKeysEnabled = remoteChannelKeysEnabled,
                        onCycleUpDown = onCycleRemoteUpDown,
                        onCycleOk = onCycleRemoteOk,
                        onCycleBack = onCycleRemoteBack,
                        onCycleDigits = onCycleRemoteDigits,
                        onToggleChannelKeys = onToggleRemoteChannelKeys,
                        onReset = onResetRemoteHabits,
                        scroll = scrollFor(SettingsPage.REMOTE),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.REMOTE.id] = it },
                    )
                    SettingsPage.AUDIO -> SettingsAudioPage(
                        summary = audioSummary,
                        entryLabel = audioEntryLabel,
                        selectable = audioSelectable,
                        onAudioTrackClick = onAudioTrackClick,
                        scroll = scrollFor(SettingsPage.AUDIO),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.AUDIO.id] = it },
                    )
                    SettingsPage.DATA -> SettingsDataPage(
                        onSourceConfigClick = onSourceConfigClick,
                        autoUpdatePlaylistLabel = autoUpdatePlaylistLabel,
                        autoUpdateEpgLabel = autoUpdateEpgLabel,
                        lastUpdateSummary = lastUpdateSummary,
                        recentCount = recentCount,
                        onCyclePlaylistInterval = onCyclePlaylistInterval,
                        onCycleEpgInterval = onCycleEpgInterval,
                        onRefreshPlaylistNow = onRefreshPlaylistNow,
                        onRefreshEpgNow = onRefreshEpgNow,
                        onOpenRecent = onOpenRecent,
                        scroll = scrollFor(SettingsPage.DATA),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.DATA.id] = it },
                    )
                    SettingsPage.LOGO -> SettingsLogoPage(
                        logoCacheSummary = logoCacheSummary,
                        logoPrefetchEnabled = logoPrefetchEnabled,
                        logoLoadEnabled = logoLoadEnabled,
                        onToggleLogoPrefetch = onToggleLogoPrefetch,
                        onToggleLogoLoad = onToggleLogoLoad,
                        onRefreshLogos = onRefreshLogos,
                        onClearLogoCache = onClearLogoCache,
                        scroll = scrollFor(SettingsPage.LOGO),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.LOGO.id] = it },
                    )
                    SettingsPage.ABOUT -> SettingsAboutPage(
                        onCheckUpdateClick = onCheckUpdateClick,
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
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .testTag(SettingsIA.ROOT_TITLE_TAG)
                .padding(bottom = 20.dp)
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
                Spacer(modifier = Modifier.padding(8.dp))
            }
        }
        // captain 要求（§12 cap·R1）：OK 默认改为“显示信息卡”后，频道列表入口必须在设置里可见。
        HelpText(
            text = "关闭后按 ← 打开频道列表",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
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
    val bg = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
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
                focusRequester.requestFocus()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.page.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
            text = "起播缓冲：$bufferLabel",
            focusRequester = requesterOf("settings_playback_buffer"),
            tag = "settings_playback_buffer",
            onFocused = onFocused,
            onClick = onCycleBuffer
        )
        HelpText("快=起播更快但弱网更易卡顿；稳=更抗抖动。切换后下次切台生效。")
        // 线路选择只在多线路频道出现（现状行为：不适用时整行不出现，不是置灰）。
        if (qualityMenuEnabled) {
            SettingsItem(
                text = "线路选择（当前频道）",
                focusRequester = requesterOf("settings_quality_menu"),
                tag = "settings_quality_menu",
                onFocused = onFocused,
                onClick = onQualityMenuClick
            )
        }
        RowGap()
        SettingsItem(
            text = if (zapPreconnectEnabled) "预连接相邻频道（实验）：开" else "预连接相邻频道（实验）：关",
            focusRequester = requesterOf("settings_zap_preconnect"),
            tag = "settings_zap_preconnect",
            onFocused = onFocused,
            onClick = onToggleZapPreconnect
        )
        HelpText("开启后会在列表打开或空闲时预热相邻频道的网络连接（每台约 1 个连接、2 秒上限）。默认关闭；如出现卡顿或占用升高请关回。")
        RowGap()
        SettingsItem(
            text = if (multicastPrewarmEnabled) "组播预热（实验）：开" else "组播预热（实验）：关",
            focusRequester = requesterOf("settings_multicast_prewarm"),
            tag = "settings_multicast_prewarm",
            onFocused = onFocused,
            onClick = onToggleMulticastPrewarm
        )
        HelpText("仅对 rtp/udp 组播源生效：预加入相邻频道的组播组并读取少量首个数据包（上限 3MB / 2.5 秒），用于提前完成组播加入。默认关闭；如内存/稳定性变差请关回。")
        RowGap()
        SettingsItem(
            text = "睛彩诊断实验：$jingcaiLabel",
            focusRequester = requesterOf("settings_jingcai_experiment"),
            tag = "settings_jingcai_experiment",
            onFocused = onFocused,
            onClick = onCycleJingcai
        )
        HelpText("单个实验逐一切换（默认关闭，改回「关闭」即恢复原行为）：E1 TS 时间戳搜索 1MB→4MB；E2 改用系统 HTTP 数据源；E3 禁用 CEA608 字幕注入。仅供 117/118/119 定位用。")
    }
}

@Composable
private fun SettingsRemotePage(
    upDownLabel: String,
    okLabel: String,
    backLabel: String,
    digitsLabel: String,
    channelKeysEnabled: Boolean,
    onCycleUpDown: () -> Unit,
    onCycleOk: () -> Unit,
    onCycleBack: () -> Unit,
    onCycleDigits: () -> Unit,
    onToggleChannelKeys: () -> Unit,
    onReset: () -> Unit,
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
            text = "Back键：$backLabel",
            focusRequester = requesterOf("settings_remote_back"),
            tag = "settings_remote_back",
            onFocused = onFocused,
            onClick = onCycleBack
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
            text = "恢复默认",
            focusRequester = requesterOf("settings_remote_reset"),
            tag = "settings_remote_reset",
            onFocused = onFocused,
            onClick = onReset
        )
        HelpText("默认：换台 / 显示信息卡 / 关闭抽屉 / 直接跳台 / 频道键启用")
        HelpText("频道列表：← 　设置：→")
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
            // t187: the label already says 仅 1 条 / 无音轨 when the picker cannot help.
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
        SettingsGroupHeader(text = "列表")
        SettingsItem(
            text = "源配置",
            focusRequester = requesterOf("settings_source_config"),
            tag = "settings_source_config",
            onFocused = onFocused,
            onClick = onSourceConfigClick
        )
        RowGap()
        SettingsItem(
            text = "自动更新播放列表：$autoUpdatePlaylistLabel",
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
        SettingsGroupHeader(text = "EPG")
        SettingsItem(
            text = "自动更新 EPG：$autoUpdateEpgLabel",
            focusRequester = requesterOf("settings_auto_epg"),
            tag = "settings_auto_epg",
            onFocused = onFocused,
            onClick = onCycleEpgInterval
        )
        RowGap()
        // t3 (D-S2): 旧「更新EPG」与「立即刷新 EPG」合并成这一行，tag 保留 settings_refresh_epg。
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
            HelpText(logoCacheSummary, start = 0.dp)
        }
        SettingsItem(
            tag = "settings_logo_prefetch",
            text = if (logoPrefetchEnabled) "台标预取：开" else "台标预取：关",
            focusRequester = requesterOf("settings_logo_prefetch"),
            onFocused = onFocused,
            onClick = { onToggleLogoPrefetch(!logoPrefetchEnabled) }
        )
        HelpText("关闭后只加载可视台标，可降低低配设备负载")
        RowGap()
        SettingsItem(
            tag = "settings_logo_load",
            text = if (logoLoadEnabled) "台标显示：开" else "台标显示：关",
            focusRequester = requesterOf("settings_logo_load"),
            onFocused = onFocused,
            onClick = { onToggleLogoLoad(!logoLoadEnabled) }
        )
        HelpText("关闭后不加载/解码台标，只显示占位")
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp)
        )
        Text(
            text = "版本号 ${BuildConfig.VERSION_CODE} · 包名 ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)
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
        text = "设置",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(bottom = 2.dp)
    )
    Text(
        text = page.title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .testTag(SettingsIA.pageTitleTag(page))
            .padding(bottom = 16.dp)
    )
}

@Composable
private fun RowGap() {
    Spacer(modifier = Modifier.padding(8.dp))
}

@Composable
private fun HelpText(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
    start: androidx.compose.ui.unit.Dp = 16.dp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.padding(start = start, top = 2.dp, bottom = 6.dp)
    )
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
    onFocused: ((String) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // t192: when the D-pad moves focus to a row that is outside the viewport, scroll it into view.
    // Every row gets its own requester, so no call site has to know about scrolling.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)

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
