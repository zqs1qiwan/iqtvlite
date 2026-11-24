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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import tv.iqtvlite.app.BuildConfig

private const val SETTINGS_LIST_TAG = "settings_list"

/** 三级展开单选项数据模型（TiviMate 风格：点击展开列表单选，取代盲目循环切换）。 */
data class SettingsPickerOption(
    val id: String,
    val title: String,
    val description: String = "",
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/** 设置抽屉不可变展示状态。 */
data class SettingsUiState(
    val visible: Boolean,
    val page: SettingsPage?,
    val picker: String?,
    val rootFocusIndex: Int,
    val uiScale: UiScalePreset,
    val infoOkMode: InfoCardPolicy.InfoOkMode,
    val playbackBufferMin: Int,
    val qualityMenuEnabled: Boolean,
    val recentCount: Int,
    val logoCacheSummary: String,
    val logoPrefetchEnabled: Boolean,
    val logoLoadEnabled: Boolean,
    val audioSummary: String?,
    val audioEntryLabel: String,
    val audioSelectable: Boolean,
    val zapPreconnectEnabled: Boolean,
    val multicastPrewarmEnabled: Boolean,
    val jingcaiExperiment: JingcaiExperiment.Mode,
    val remoteBindings: List<KeyBinding>,
    val remoteDigits: RemoteHabits.Digits,
    val captureRequest: Pair<RemoteAction, Boolean>?,
    val autoUpdatePlaylist: AutoUpdateConfig.Interval,
    val autoUpdateEpg: AutoUpdateConfig.Interval,
    val lastUpdateSummary: String,
)

/** 设置抽屉动作集合。 */
data class SettingsActions(
    val onOpenPage: (SettingsPage) -> Unit,
    val onOpenPicker: (String) -> Unit,
    val onClosePicker: () -> Unit,
    val onBackToRoot: () -> Unit,
    val onRootFocusChanged: (Int) -> Unit,
    val onClose: () -> Unit,
    val onSelectUiScale: (UiScalePreset) -> Unit,
    val onSelectInfoOkMode: (InfoCardPolicy.InfoOkMode) -> Unit,
    val onSelectPlaybackBuffer: (Int) -> Unit,
    val onSelectJingcaiExperiment: (JingcaiExperiment.Mode) -> Unit,
    val onSelectPlaylistInterval: (AutoUpdateConfig.Interval) -> Unit,
    val onSelectEpgInterval: (AutoUpdateConfig.Interval) -> Unit,
    val onSelectRemoteUpDown: (RemoteHabits.UpDown) -> Unit,
    val onSelectRemoteOk: (RemoteHabits.Ok) -> Unit,
    val onSelectRemoteDigits: (RemoteHabits.Digits) -> Unit,
    val onSourceConfigClick: () -> Unit,
    val onQualityMenuClick: () -> Unit,
    val onCheckUpdateClick: () -> Unit,
    val onOpenRecent: () -> Unit,
    val onRefreshLogos: () -> Unit,
    val onClearLogoCache: () -> Unit,
    val onToggleLogoPrefetch: (Boolean) -> Unit,
    val onToggleLogoLoad: (Boolean) -> Unit,
    val onToggleZapPreconnect: () -> Unit,
    val onToggleMulticastPrewarm: () -> Unit,
    val onAudioTrackClick: () -> Unit,
    val onToggleRemoteChannelKeys: () -> Unit,
    val onStartCapture: (RemoteAction, Boolean) -> Unit,
    val onClearBinding: (RemoteAction, Boolean) -> Unit,
    val onCancelCapture: () -> Unit,
    val onResetRemoteBindings: () -> Unit,
    val onRefreshPlaylistNow: () -> Unit,
    val onRefreshEpgNow: () -> Unit,
)

/**
 * TiviMate 风格右侧设置抽屉：
 * 1. **三级标题零位移**：一级「设置」、二级页标题、三级选项选择器全部处于同一顶部基线，无多余「← 设置」挤走布局；
 * 2. **点击展开选项单选**：多值设置项统一点击展开单选列表（带单选圆点与说明），告别盲按循环；
 * 3. **TiviMate 式功能→按键指定**：遥控器页直接列出每个功能及其当前短按/长按绑定，点击展开即可指定或清除按键；
 * 4. **界面文字+台标大小（小/中/大）**：实时生效于选台抽屉、信息卡与设置抽屉；
 * 5. **底部大号按键提示**：设置根页最底端常驻大号 `[ ← ]` / `[ → ]` 按键帽提示，3 米外一目了然。
 */
@Composable
fun SettingsDrawer(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val visible = state.visible
    val page = state.page
    val picker = state.picker
    val rootFocusIndex = state.rootFocusIndex
    val textScale = state.uiScale.textScale

    val pageLastFocus = remember { mutableMapOf<String, String>() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }

    val rootScroll = rememberScrollState()
    val playbackScroll = rememberScrollState()
    val remoteScroll = rememberScrollState()
    val audioScroll = rememberScrollState()
    val dataScroll = rememberScrollState()
    val logoScroll = rememberScrollState()
    val aboutScroll = rememberScrollState()
    val pickerScroll = rememberScrollState()

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

    // 帧安全焦点定位：一级 / 二级 / 三级选择器切换绝不丢焦点
    SafeInitialFocus(active = visible, page, picker) {
        val targetTag = when {
            picker != null -> "picker_opt_0"
            page == null -> SettingsIA.rootTag(SettingsIA.ROOT_ORDER[rootFocusIndex.coerceIn(0, SettingsIA.ROOT_ORDER.lastIndex)])
            else -> focusTagFor(page)
        }
        requesterOf(targetTag)
    }

    // 离开遥控页或关闭抽屉时自动取消按键捕获态
    LaunchedEffect(visible, page, picker) {
        if ((!visible || page != SettingsPage.REMOTE) && state.captureRequest != null) {
            actions.onCancelCapture()
        }
    }

    val remoteUpDownHabit = remember(state.remoteBindings) {
        RemoteBindings.deriveUpDownHabit(state.remoteBindings)
    }
    val remoteOkHabit = remember(state.remoteBindings) {
        RemoteBindings.deriveOkHabit(state.remoteBindings)
    }
    val remoteChannelKeysEnabled = remember(state.remoteBindings) {
        RemoteBindings.deriveChannelKeysEnabled(state.remoteBindings)
    }
    val playbackBufferLabel = remember(state.playbackBufferMin) {
        LoadControlTuning.tierLabel(state.playbackBufferMin)
    }
    val infoOkModeLabel = if (state.infoOkMode == InfoCardPolicy.InfoOkMode.DIRECT) "直达最近观看" else "两段式"

    val rootSummaries = remember(
        state.uiScale,
        playbackBufferLabel,
        state.zapPreconnectEnabled,
        state.multicastPrewarmEnabled,
        state.jingcaiExperiment,
        remoteUpDownHabit,
        remoteOkHabit,
        state.audioSummary,
        state.autoUpdatePlaylist,
        state.autoUpdateEpg,
        state.logoCacheSummary,
    ) {
        mapOf(
            SettingsPage.PLAYBACK to "界面 ${state.uiScale.label} · ${SettingsSummary.playback(
                bufferLabel = playbackBufferLabel,
                anyExperimentOn = state.zapPreconnectEnabled || state.multicastPrewarmEnabled || state.jingcaiExperiment != JingcaiExperiment.Mode.OFF,
            )}",
            SettingsPage.REMOTE to SettingsSummary.remote(remoteUpDownHabit.label, remoteOkHabit.label),
            SettingsPage.AUDIO to SettingsSummary.audio(state.audioSummary),
            SettingsPage.DATA to SettingsSummary.data(state.autoUpdatePlaylist.label, state.autoUpdateEpg.label),
            SettingsPage.LOGO to SettingsSummary.logoFromCacheSummary(state.logoCacheSummary),
            SettingsPage.ABOUT to SettingsSummary.about(BuildConfig.VERSION_CODE),
        )
    }
    val rootEntries = remember(rootSummaries) { SettingsIA.rootEntries(rootSummaries) }

    val drawerWidth = when (state.uiScale) {
        UiScalePreset.SMALL -> 332.dp
        UiScalePreset.MEDIUM -> 352.dp
        UiScalePreset.LARGE -> 384.dp
    }

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
                    // 三级选择器内按 LEFT / BACK 返回二级页，绝不关掉整个设置抽屉
                    if (picker != null) {
                        if (event.key == Key.DirectionLeft || event.key == Key.Back) {
                            actions.onClosePicker()
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
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
                    .width(drawerWidth)
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { alpha = TvDesignTokens.ALPHA_SETTINGS }
                    .background(TvDesignTokens.SurfaceObsidian)
            )
            // 内容层
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .align(Alignment.CenterEnd)
                    .padding(horizontal = 22.dp, vertical = 22.dp)
            ) {
                when {
                    picker != null -> {
                        SettingsPickerRouter(
                            pickerId = picker,
                            state = state,
                            actions = actions,
                            remoteUpDownHabit = remoteUpDownHabit,
                            remoteOkHabit = remoteOkHabit,
                            textScale = textScale,
                            scroll = pickerScroll,
                            requesterOf = ::requesterOf,
                        )
                    }
                    page == null -> SettingsRootPage(
                        entries = rootEntries,
                        focusedIndex = rootFocusIndex,
                        textScale = textScale,
                        scroll = rootScroll,
                        requesterOf = ::requesterOf,
                        onFocusIndex = actions.onRootFocusChanged,
                        onOpenPage = actions.onOpenPage,
                    )
                    page == SettingsPage.PLAYBACK -> SettingsPlaybackPage(
                        uiScaleLabel = state.uiScale.label,
                        onOpenUiScalePicker = { actions.onOpenPicker(PICKER_UI_SCALE) },
                        bufferLabel = playbackBufferLabel,
                        onOpenBufferPicker = { actions.onOpenPicker(PICKER_PLAYBACK_BUFFER) },
                        qualityMenuEnabled = state.qualityMenuEnabled,
                        onQualityMenuClick = actions.onQualityMenuClick,
                        zapPreconnectEnabled = state.zapPreconnectEnabled,
                        onToggleZapPreconnect = actions.onToggleZapPreconnect,
                        multicastPrewarmEnabled = state.multicastPrewarmEnabled,
                        onToggleMulticastPrewarm = actions.onToggleMulticastPrewarm,
                        jingcaiLabel = state.jingcaiExperiment.label,
                        onOpenJingcaiPicker = { actions.onOpenPicker(PICKER_JINGCAI) },
                        infoOkModeLabel = infoOkModeLabel,
                        onOpenInfoOkPicker = { actions.onOpenPicker(PICKER_INFO_OK_MODE) },
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.PLAYBACK),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.PLAYBACK.id] = it },
                    )
                    page == SettingsPage.REMOTE -> SettingsRemotePage(
                        upDownLabel = remoteUpDownHabit.label,
                        okLabel = remoteOkHabit.label,
                        digitsLabel = state.remoteDigits.label,
                        channelKeysEnabled = remoteChannelKeysEnabled,
                        onOpenUpDownPicker = { actions.onOpenPicker(PICKER_REMOTE_UPDOWN) },
                        onOpenOkPicker = { actions.onOpenPicker(PICKER_REMOTE_OK) },
                        onOpenDigitsPicker = { actions.onOpenPicker(PICKER_REMOTE_DIGITS) },
                        onToggleChannelKeys = actions.onToggleRemoteChannelKeys,
                        bindings = state.remoteBindings,
                        onOpenActionPicker = { action -> actions.onOpenPicker("$PICKER_REMOTE_ACTION_PREFIX${action.name}") },
                        onResetBindings = actions.onResetRemoteBindings,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.REMOTE),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.REMOTE.id] = it },
                    )
                    page == SettingsPage.AUDIO -> SettingsAudioPage(
                        summary = state.audioSummary,
                        entryLabel = state.audioEntryLabel,
                        selectable = state.audioSelectable,
                        onAudioTrackClick = actions.onAudioTrackClick,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.AUDIO),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.AUDIO.id] = it },
                    )
                    page == SettingsPage.DATA -> SettingsDataPage(
                        onSourceConfigClick = actions.onSourceConfigClick,
                        autoUpdatePlaylistLabel = state.autoUpdatePlaylist.label,
                        autoUpdateEpgLabel = state.autoUpdateEpg.label,
                        lastUpdateSummary = state.lastUpdateSummary,
                        recentCount = state.recentCount,
                        onOpenPlaylistIntervalPicker = { actions.onOpenPicker(PICKER_AUTO_PLAYLIST) },
                        onOpenEpgIntervalPicker = { actions.onOpenPicker(PICKER_AUTO_EPG) },
                        onRefreshPlaylistNow = actions.onRefreshPlaylistNow,
                        onRefreshEpgNow = actions.onRefreshEpgNow,
                        onOpenRecent = actions.onOpenRecent,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.DATA),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.DATA.id] = it },
                    )
                    page == SettingsPage.LOGO -> SettingsLogoPage(
                        logoCacheSummary = state.logoCacheSummary,
                        logoPrefetchEnabled = state.logoPrefetchEnabled,
                        logoLoadEnabled = state.logoLoadEnabled,
                        onToggleLogoPrefetch = actions.onToggleLogoPrefetch,
                        onToggleLogoLoad = actions.onToggleLogoLoad,
                        onRefreshLogos = actions.onRefreshLogos,
                        onClearLogoCache = actions.onClearLogoCache,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.LOGO),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.LOGO.id] = it },
                    )
                    page == SettingsPage.ABOUT -> SettingsAboutPage(
                        onCheckUpdateClick = actions.onCheckUpdateClick,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.ABOUT),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.ABOUT.id] = it },
                    )
                }
            }
        }
    }
}

// ─── 三级展开选择器路由 ────────────────────────────────────────────────────

private const val PICKER_UI_SCALE = "ui_scale"
private const val PICKER_INFO_OK_MODE = "info_ok_mode"
private const val PICKER_PLAYBACK_BUFFER = "playback_buffer"
private const val PICKER_JINGCAI = "jingcai"
private const val PICKER_AUTO_PLAYLIST = "auto_playlist"
private const val PICKER_AUTO_EPG = "auto_epg"
private const val PICKER_REMOTE_UPDOWN = "remote_updown"
private const val PICKER_REMOTE_OK = "remote_ok"
private const val PICKER_REMOTE_DIGITS = "remote_digits"
private const val PICKER_REMOTE_ACTION_PREFIX = "remote_action:"

@Composable
private fun SettingsPickerRouter(
    pickerId: String,
    state: SettingsUiState,
    actions: SettingsActions,
    remoteUpDownHabit: RemoteHabits.UpDown,
    remoteOkHabit: RemoteHabits.Ok,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
) {
    if (pickerId.startsWith(PICKER_REMOTE_ACTION_PREFIX)) {
        val actionName = pickerId.removePrefix(PICKER_REMOTE_ACTION_PREFIX)
        val action = RemoteAction.entries.firstOrNull { it.name == actionName }
        if (action != null) {
            SettingsRemoteActionPickerView(
                action = action,
                binding = state.remoteBindings.firstOrNull { it.action == action },
                captureRequest = state.captureRequest,
                textScale = textScale,
                scroll = scroll,
                requesterOf = requesterOf,
                onStartCapture = { longPress -> actions.onStartCapture(action, longPress) },
                onClearBinding = { longPress -> actions.onClearBinding(action, longPress) },
            )
            return
        }
    }

    val (title, options) = when (pickerId) {
        PICKER_UI_SCALE -> "文字与台标大小" to UiScalePreset.entries.map { preset ->
            SettingsPickerOption(
                id = preset.id,
                title = preset.label,
                description = preset.description,
                selected = state.uiScale == preset,
                onSelect = {
                    actions.onSelectUiScale(preset)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_INFO_OK_MODE -> "信息卡 OK 行为" to listOf(
            SettingsPickerOption(
                id = "two_step",
                title = "两段式（推荐）",
                description = "按一次 OK 显示信息卡，再按一次 OK 进入最近观看快切",
                selected = state.infoOkMode == InfoCardPolicy.InfoOkMode.TWO_STEP,
                onSelect = {
                    actions.onSelectInfoOkMode(InfoCardPolicy.InfoOkMode.TWO_STEP)
                    actions.onClosePicker()
                },
            ),
            SettingsPickerOption(
                id = "direct",
                title = "直达最近观看",
                description = "按一次 OK 出卡后立即聚焦最近观看条，左右即可切台",
                selected = state.infoOkMode == InfoCardPolicy.InfoOkMode.DIRECT,
                onSelect = {
                    actions.onSelectInfoOkMode(InfoCardPolicy.InfoOkMode.DIRECT)
                    actions.onClosePicker()
                },
            ),
        )
        PICKER_PLAYBACK_BUFFER -> "起播缓冲" to LoadControlTuning.TIERS.map { tier ->
            val desc = when (tier) {
                LoadControlTuning.TIER_FAST_MS -> "最快出画，适合高速稳定局域网"
                LoadControlTuning.TIER_BALANCED_MS -> "速度与抗抖动均衡"
                else -> "默认推荐，弱网与高码率 4K 更稳"
            }
            SettingsPickerOption(
                id = "buf_$tier",
                title = LoadControlTuning.tierLabel(tier),
                description = desc,
                selected = LoadControlTuning.sanitize(state.playbackBufferMin) == tier,
                onSelect = {
                    actions.onSelectPlaybackBuffer(tier)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_JINGCAI -> "码流兼容模式" to JingcaiExperiment.Mode.entries.map { mode ->
            SettingsPickerOption(
                id = mode.id,
                title = mode.label,
                description = if (mode == JingcaiExperiment.Mode.OFF) "默认关闭，标准码流无需开启" else "实验性兼容策略，重新选台生效",
                selected = state.jingcaiExperiment == mode,
                onSelect = {
                    actions.onSelectJingcaiExperiment(mode)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_AUTO_PLAYLIST -> "自动更新列表" to AutoUpdateConfig.Interval.entries.map { iv ->
            SettingsPickerOption(
                id = iv.name,
                title = iv.label,
                selected = state.autoUpdatePlaylist == iv,
                onSelect = {
                    actions.onSelectPlaylistInterval(iv)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_AUTO_EPG -> "自动更新 EPG" to AutoUpdateConfig.Interval.entries.map { iv ->
            SettingsPickerOption(
                id = iv.name,
                title = iv.label,
                selected = state.autoUpdateEpg == iv,
                onSelect = {
                    actions.onSelectEpgInterval(iv)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_REMOTE_UPDOWN -> "上下键预设" to RemoteHabits.UpDown.entries.map { h ->
            SettingsPickerOption(
                id = h.name,
                title = h.label,
                description = if (h == RemoteHabits.UpDown.CHANNEL_STEP) "全屏按 ↑ / ↓ 直接切上一台 / 下一台" else "全屏按 ↑ / ↓ 呼出频道列表",
                selected = remoteUpDownHabit == h,
                onSelect = {
                    actions.onSelectRemoteUpDown(h)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_REMOTE_OK -> "OK 键预设" to RemoteHabits.Ok.entries.map { h ->
            SettingsPickerOption(
                id = h.name,
                title = h.label,
                description = if (h == RemoteHabits.Ok.CONFIRM) "全屏按 OK 显示底部信息卡" else "全屏按 OK 直接打开频道列表",
                selected = remoteOkHabit == h,
                onSelect = {
                    actions.onSelectRemoteOk(h)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_REMOTE_DIGITS -> "数字键行为" to RemoteHabits.Digits.entries.map { h ->
            SettingsPickerOption(
                id = h.name,
                title = h.label,
                description = if (h == RemoteHabits.Digits.DIRECT) "输入频道号后停顿自动跳转" else "输入频道号后需按 OK 确认跳转",
                selected = state.remoteDigits == h,
                onSelect = {
                    actions.onSelectRemoteDigits(h)
                    actions.onClosePicker()
                },
            )
        }
        else -> "选项" to emptyList()
    }

    SettingsOptionPickerView(
        title = title,
        options = options,
        textScale = textScale,
        scroll = scroll,
        requesterOf = requesterOf,
    )
}

@Composable
private fun SettingsOptionPickerView(
    title: String,
    options: List<SettingsPickerOption>,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = title, tag = "settings_picker_title", textScale = textScale)
        options.forEachIndexed { index, opt ->
            SettingsRadioRow(
                title = opt.title,
                description = opt.description,
                selected = opt.selected,
                textScale = textScale,
                focusRequester = requesterOf("picker_opt_$index"),
                tag = "picker_opt_$index",
                onClick = opt.onSelect,
            )
            if (index != options.lastIndex) RowGap()
        }
    }
}

/** TiviMate 风格：单个功能的短按/长按指定面板（点击即可监听按键或清除）。 */
@Composable
private fun SettingsRemoteActionPickerView(
    action: RemoteAction,
    binding: KeyBinding?,
    captureRequest: Pair<RemoteAction, Boolean>?,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onStartCapture: (Boolean) -> Unit,
    onClearBinding: (Boolean) -> Unit,
) {
    val capturingShort = captureRequest?.let { it.first == action && !it.second } == true
    val capturingLong = captureRequest?.let { it.first == action && it.second } == true
    val shortSummary = if (capturingShort) "请按下遥控器任意键（按 Back 取消）…" else RemoteBindings.bindingSummary(binding, false)
    val longSummary = if (capturingLong) "请按下遥控器任意键（按 Back 取消）…" else RemoteBindings.bindingSummary(binding, true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = action.label, tag = "settings_picker_title", textScale = textScale)

        SettingsChoiceRow(
            title = "指定短按按键",
            value = shortSummary,
            valueColor = if (capturingShort) BrandFocus.BlueColor else null,
            showChevron = false,
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_0"),
            tag = "picker_opt_0",
            onClick = { onStartCapture(false) },
        )
        RowGap()
        SettingsChoiceRow(
            title = "指定长按按键",
            value = longSummary,
            valueColor = if (capturingLong) BrandFocus.BlueColor else null,
            showChevron = false,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_bind_long_${action.name}"),
            tag = "settings_remote_bind_long_${action.name}",
            onClick = { onStartCapture(true) },
        )
        RowGap()
        SettingsItem(
            text = "清除短按绑定",
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_clear_short"),
            tag = "picker_opt_clear_short",
            onClick = { onClearBinding(false) },
        )
        RowGap()
        SettingsItem(
            text = "清除长按绑定",
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_clear_long"),
            tag = "picker_opt_clear_long",
            onClick = { onClearBinding(true) },
        )
        if (capturingShort || capturingLong) {
            HelpText(
                text = "正在等待按键输入，直接按下目标遥控器按键即可绑定；按返回键取消。",
                color = BrandFocus.BlueColor,
            )
        }
    }
}

// ─── 一级根页（含固定置底的大号 [ ← ] / [ → ] 呼出提示） ─────────────────────

@Composable
private fun SettingsRootPage(
    entries: List<SettingsRootEntry>,
    focusedIndex: Int,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocusIndex: (Int) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsTopTitle(title = "设置", tag = SettingsIA.ROOT_TITLE_TAG, textScale = textScale)
            entries.forEachIndexed { index, entry ->
                SettingsRootItem(
                    entry = entry,
                    textScale = textScale,
                    focusRequester = requesterOf(entry.tag),
                    selected = index == focusedIndex,
                    onFocus = { onFocusIndex(index) },
                    onClick = { onOpenPage(entry.page) },
                )
                if (index != entries.lastIndex) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
        // 固定置底的大号按键提示，直观展示全屏左右呼出菜单方式
        SettingsBottomKeyHint()
    }
}

@Composable
private fun SettingsBottomKeyHint() {
    val keycapShape = remember { RoundedCornerShape(6.dp) }
    val keycapBg = Color(0x28FFFFFF)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 4.dp, end = 4.dp)
            .testTag("settings_bottom_key_hint"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .widthIn(min = 38.dp)
                    .height(30.dp)
                    .background(keycapBg, keycapShape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = TvDesignTokens.TextPrimary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "频道列表",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color(0xFFCBD4E3),
                maxLines = 1,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .widthIn(min = 38.dp)
                    .height(30.dp)
                    .background(keycapBg, keycapShape)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "→",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = TvDesignTokens.TextPrimary,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "设置",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = Color(0xFFCBD4E3),
                maxLines = 1,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsRootItem(
    entry: SettingsRootEntry,
    textScale: Float,
    focusRequester: FocusRequester,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent
    val shape = remember { RoundedCornerShape(6.dp) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(entry.tag)
            .semantics { contentDescription = "${entry.page.title} ${entry.summary}" }
            .focusRequester(focusRequester)
            .bringIntoViewRequester(bringIntoView)
            .background(bg, shape)
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.page.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (16f * textScale).sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextPrimary.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = (12f * textScale).sp),
                color = if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (18f * textScale).sp),
            color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextMuted
        )
    }
}

// ─── 二级页（全部对齐同一顶部标题基线，无挤占提示） ─────────────────────────

@Composable
private fun SettingsPlaybackPage(
    uiScaleLabel: String,
    onOpenUiScalePicker: () -> Unit,
    bufferLabel: String,
    onOpenBufferPicker: () -> Unit,
    qualityMenuEnabled: Boolean,
    onQualityMenuClick: () -> Unit,
    zapPreconnectEnabled: Boolean,
    onToggleZapPreconnect: () -> Unit,
    multicastPrewarmEnabled: Boolean,
    onToggleMulticastPrewarm: () -> Unit,
    jingcaiLabel: String,
    onOpenJingcaiPicker: () -> Unit,
    infoOkModeLabel: String,
    onOpenInfoOkPicker: () -> Unit,
    textScale: Float,
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
        SettingsPageHeader(SettingsPage.PLAYBACK, textScale)
        SettingsChoiceRow(
            title = "文字与台标大小",
            value = uiScaleLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_ui_scale"),
            tag = "settings_ui_scale",
            onFocused = onFocused,
            onClick = onOpenUiScalePicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = "信息卡 OK 行为",
            value = infoOkModeLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_info_ok_mode"),
            tag = "settings_info_ok_mode",
            onFocused = onFocused,
            onClick = onOpenInfoOkPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = "起播缓冲",
            value = bufferLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_playback_buffer"),
            tag = "settings_playback_buffer",
            onFocused = onFocused,
            onClick = onOpenBufferPicker,
        )
        if (qualityMenuEnabled) {
            RowGap()
            SettingsItem(
                text = "线路选择（当前频道）",
                textScale = textScale,
                focusRequester = requesterOf("settings_quality_menu"),
                tag = "settings_quality_menu",
                onFocused = onFocused,
                onClick = onQualityMenuClick
            )
        }

        SettingsGroupHeader("高级诊断（实验）")
        SettingsItem(
            text = if (zapPreconnectEnabled) "预连接相邻频道：开" else "预连接相邻频道：关",
            textScale = textScale,
            focusRequester = requesterOf("settings_zap_preconnect"),
            tag = "settings_zap_preconnect",
            onFocused = onFocused,
            onClick = onToggleZapPreconnect
        )
        RowGap()
        SettingsItem(
            text = if (multicastPrewarmEnabled) "组播预热：开" else "组播预热：关",
            textScale = textScale,
            focusRequester = requesterOf("settings_multicast_prewarm"),
            tag = "settings_multicast_prewarm",
            onFocused = onFocused,
            onClick = onToggleMulticastPrewarm
        )
        RowGap()
        SettingsChoiceRow(
            title = "码流兼容模式",
            value = jingcaiLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_jingcai_experiment"),
            tag = "settings_jingcai_experiment",
            onFocused = onFocused,
            onClick = onOpenJingcaiPicker,
        )
    }
}

@Composable
private fun SettingsRemotePage(
    upDownLabel: String,
    okLabel: String,
    digitsLabel: String,
    channelKeysEnabled: Boolean,
    onOpenUpDownPicker: () -> Unit,
    onOpenOkPicker: () -> Unit,
    onOpenDigitsPicker: () -> Unit,
    onToggleChannelKeys: () -> Unit,
    bindings: List<KeyBinding>,
    onOpenActionPicker: (RemoteAction) -> Unit,
    onResetBindings: () -> Unit,
    textScale: Float,
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
        SettingsPageHeader(SettingsPage.REMOTE, textScale)

        SettingsGroupHeader("功能按键指定（点击配置）")
        for (action in RemoteBindings.CONFIGURABLE_ACTIONS) {
            val binding = bindings.firstOrNull { it.action == action }
            val shortStr = RemoteBindings.bindingSummary(binding, false)
            val longStr = RemoteBindings.bindingSummary(binding, true)
            val summary = if (longStr == "未绑定") "短按：$shortStr" else "短按：$shortStr · 长按：$longStr"
            SettingsChoiceRow(
                title = action.label,
                value = summary,
                textScale = textScale,
                focusRequester = requesterOf("settings_remote_action_${action.name}"),
                tag = "settings_remote_action_${action.name}",
                onFocused = onFocused,
                onClick = { onOpenActionPicker(action) },
            )
            RowGap()
        }

        SettingsGroupHeader("快捷预设与通用按键")
        SettingsChoiceRow(
            title = "上下键预设",
            value = upDownLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_updown"),
            tag = "settings_remote_updown",
            onFocused = onFocused,
            onClick = onOpenUpDownPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = "OK 键预设",
            value = okLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_ok"),
            tag = "settings_remote_ok",
            onFocused = onFocused,
            onClick = onOpenOkPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = "数字键行为",
            value = digitsLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_digits"),
            tag = "settings_remote_digits",
            onFocused = onFocused,
            onClick = onOpenDigitsPicker,
        )
        RowGap()
        SettingsItem(
            text = if (channelKeysEnabled) "频道键 166/167：启用" else "频道键 166/167：禁用",
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_channel_keys"),
            tag = "settings_remote_channel_keys",
            onFocused = onFocused,
            onClick = onToggleChannelKeys,
        )
        RowGap()
        SettingsItem(
            text = "恢复默认遥控配置",
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_reset"),
            tag = "settings_remote_reset",
            onFocused = onFocused,
            onClick = onResetBindings,
        )
        RowGap()
        SettingsItem(
            text = "重置全部自定义映射",
            textScale = textScale,
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
    textScale: Float,
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
        SettingsPageHeader(SettingsPage.AUDIO, textScale)
        SettingsItem(
            text = if (selectable) entryLabel else "$entryLabel（不可选）",
            textScale = textScale,
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
    onOpenPlaylistIntervalPicker: () -> Unit,
    onOpenEpgIntervalPicker: () -> Unit,
    onRefreshPlaylistNow: () -> Unit,
    onRefreshEpgNow: () -> Unit,
    onOpenRecent: () -> Unit,
    textScale: Float,
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
        SettingsPageHeader(SettingsPage.DATA, textScale)
        SettingsGroupHeader(text = "播放列表")
        SettingsItem(
            text = "源配置",
            textScale = textScale,
            focusRequester = requesterOf("settings_source_config"),
            tag = "settings_source_config",
            onFocused = onFocused,
            onClick = onSourceConfigClick
        )
        RowGap()
        SettingsChoiceRow(
            title = "自动更新列表",
            value = autoUpdatePlaylistLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_auto_playlist"),
            tag = "settings_auto_playlist",
            onFocused = onFocused,
            onClick = onOpenPlaylistIntervalPicker,
        )
        RowGap()
        SettingsItem(
            text = "立即刷新播放列表",
            textScale = textScale,
            focusRequester = requesterOf("settings_refresh_playlist"),
            tag = "settings_refresh_playlist",
            onFocused = onFocused,
            onClick = onRefreshPlaylistNow
        )
        SettingsGroupHeader(text = "节目单 (EPG)")
        SettingsChoiceRow(
            title = "自动更新 EPG",
            value = autoUpdateEpgLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_auto_epg"),
            tag = "settings_auto_epg",
            onFocused = onFocused,
            onClick = onOpenEpgIntervalPicker,
        )
        RowGap()
        SettingsItem(
            text = "立即更新 EPG",
            textScale = textScale,
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
            textScale = textScale,
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
    textScale: Float,
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
        SettingsPageHeader(SettingsPage.LOGO, textScale)
        if (logoCacheSummary.isNotBlank()) {
            HelpText(logoCacheSummary, start = 4.dp)
        }
        SettingsItem(
            tag = "settings_logo_prefetch",
            text = if (logoPrefetchEnabled) "台标预取：开" else "台标预取：关",
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_prefetch"),
            onFocused = onFocused,
            onClick = { onToggleLogoPrefetch(!logoPrefetchEnabled) }
        )
        HelpText("关闭后仅加载当前可视台标，进一步降低低配盒子负载。")
        RowGap()
        SettingsItem(
            tag = "settings_logo_load",
            text = if (logoLoadEnabled) "台标显示：开" else "台标显示：关",
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_load"),
            onFocused = onFocused,
            onClick = { onToggleLogoLoad(!logoLoadEnabled) }
        )
        HelpText("关闭后不加载台标，仅显示文字首字占位。")
        RowGap()
        SettingsItem(
            text = "刷新当前组台标",
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_refresh"),
            tag = "settings_logo_refresh",
            onFocused = onFocused,
            onClick = onRefreshLogos
        )
        RowGap()
        SettingsItem(
            text = "清理台标缓存",
            textScale = textScale,
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
    textScale: Float,
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
        SettingsPageHeader(SettingsPage.ABOUT, textScale)
        Text(
            text = "IQTV Lite",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (16f * textScale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = TvDesignTokens.TextPrimary,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (12.5f * textScale).sp),
            color = TvDesignTokens.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, bottom = 2.dp)
        )
        Text(
            text = "Build ${BuildConfig.VERSION_CODE} · ${BuildConfig.APPLICATION_ID}",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (12f * textScale).sp),
            color = TvDesignTokens.TextMuted,
            modifier = Modifier.padding(start = 12.dp, bottom = 14.dp)
        )
        SettingsItem(
            text = "检查更新",
            textScale = textScale,
            focusRequester = requesterOf("settings_check_update"),
            tag = "settings_check_update",
            onFocused = onFocused,
            onClick = onCheckUpdateClick
        )
    }
}

// ─── 基础组件（统一顶部标题基线、对称圆角高亮、TiviMate 展开行与单选行） ────────

@Composable
private fun SettingsTopTitle(title: String, tag: String, textScale: Float) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall.copy(
            fontSize = (22f * textScale).sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = TvDesignTokens.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .testTag(tag)
            .padding(bottom = 18.dp, start = 4.dp)
    )
}

@Composable
private fun SettingsPageHeader(page: SettingsPage, textScale: Float) {
    // 与设置根页保持 100% 相同的顶部标题基线（去掉「← 设置」挤占）
    SettingsTopTitle(title = page.title, tag = SettingsIA.pageTitleTag(page), textScale = textScale)
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
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp)
    )
}

/** TiviMate 风格：点击展开选项列表的两行设置项（标题 + 当前值 + 右箭头）。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsChoiceRow(
    title: String,
    value: String,
    textScale: Float,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    tag: String? = null,
    onFocused: ((String) -> Unit)? = null,
    valueColor: Color? = null,
    showChevron: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent
    val shape = remember { RoundedCornerShape(6.dp) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (tag != null) m.testTag(tag) else m }
            .semantics { contentDescription = "$title $value" }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg, shape)
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
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (15.5f * textScale).sp,
                    fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextPrimary.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = (12f * textScale).sp),
                color = valueColor ?: if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) {
            Text(
                text = "›",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = (18f * textScale).sp),
                color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextMuted,
            )
        }
    }
}

/** 三级选项选择器单选行（对称高亮 + 品牌蓝当前选中点）。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsRadioRow(
    title: String,
    description: String,
    selected: Boolean,
    textScale: Float,
    focusRequester: FocusRequester?,
    tag: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent
    val shape = remember { RoundedCornerShape(6.dp) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .semantics { contentDescription = "$title ${if (selected) "已选中" else ""}" }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) scope.launch { runCatching { bringIntoView.bringIntoView() } }
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "●" else "○",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (14f * textScale).sp),
            color = if (selected) Color(0xFF5C98FF) else TvDesignTokens.TextMuted,
            modifier = Modifier.width(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = (15.5f * textScale).sp,
                    fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = when {
                    selected -> Color(0xFF5C98FF)
                    focused -> TvDesignTokens.TextPrimary
                    else -> TvDesignTokens.TextPrimary.copy(alpha = 0.88f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = (11.5f * textScale).sp),
                    color = if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsItem(
    text: String,
    textScale: Float = 1f,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    tag: String? = null,
    onFocused: ((String) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent
    val shape = remember { RoundedCornerShape(6.dp) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (tag != null) m.testTag(tag) else m }
            .semantics { contentDescription = text }
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .bringIntoViewRequester(bringIntoView)
            .background(bg, shape)
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
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (15.5f * textScale).sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextPrimary.copy(alpha = 0.86f)
        )
    }
}
