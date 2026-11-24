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

/** 三级展开单选项数据模型（点击展开列表单选，取代盲目循环切换）。 */
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
    val language: AppLang = AppLang.ZH,
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
    val sources: List<PlaylistSource> = emptyList(),
    val activeSourceId: String? = null,
    val globalUserAgent: String = "",
    val globalCustomHeaders: String = "",
)

/** 设置抽屉动作集合。 */
data class SettingsActions(
    val onOpenPage: (SettingsPage) -> Unit,
    val onOpenPicker: (String) -> Unit,
    val onClosePicker: () -> Unit,
    val onBackToRoot: () -> Unit,
    val onRootFocusChanged: (Int) -> Unit,
    val onClose: () -> Unit,
    val onSelectLanguage: (AppLang) -> Unit = {},
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
    val onSwitchSource: (PlaylistSource) -> Unit,
    val onToggleSourceEnabled: (String) -> Unit,
    val onSelectGlobalUserAgent: (String) -> Unit,
    val onClearGlobalCustomHeaders: () -> Unit,
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
 * 极简曜石风格右侧设置抽屉：
 * 1. **三级标题零位移**：一级「设置」、二级页标题、三级选项选择器全部处于同一顶部基线，无多余「← 设置」挤走布局；
 * 2. **点击展开选项单选**：多值设置项统一点击展开单选列表（带单选圆点与说明），告别盲按循环；
 * 3. **功能→按键指定**：遥控器页直接列出每个功能及其当前短按/长按绑定，点击展开即可指定或清除按键；
 * 4. **中英双语 + 界面文字/台标大小（小/中/大）**：实时生效，零重启刷新全部 UI；
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
    val lang = state.language
    val textScale = state.uiScale.textScale
    fun s(zh: String, en: String): String = if (lang == AppLang.EN) en else zh

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
    val playbackBufferLabel = remember(state.playbackBufferMin, lang) {
        LoadControlTuning.tierLabel(state.playbackBufferMin, lang)
    }
    val infoOkModeLabel = if (state.infoOkMode == InfoCardPolicy.InfoOkMode.DIRECT) {
        s("直达最近观看", "Direct recent strip")
    } else {
        s("两段式", "Two-step")
    }

    val rootSummaries = remember(
        lang,
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
            SettingsPage.PLAYBACK to "${lang.nativeName} · ${SettingsSummary.playback(
                bufferLabel = playbackBufferLabel,
                anyExperimentOn = state.zapPreconnectEnabled || state.multicastPrewarmEnabled || state.jingcaiExperiment != JingcaiExperiment.Mode.OFF,
                lang = lang,
            )}",
            SettingsPage.REMOTE to SettingsSummary.remote(remoteUpDownHabit.localizedLabel(lang), remoteOkHabit.localizedLabel(lang)),
            SettingsPage.AUDIO to SettingsSummary.audio(state.audioSummary, lang),
            SettingsPage.DATA to SettingsSummary.data(state.autoUpdatePlaylist.localizedLabel(lang), state.autoUpdateEpg.localizedLabel(lang), lang),
            SettingsPage.LOGO to SettingsSummary.logoFromCacheSummary(state.logoCacheSummary, lang),
            SettingsPage.ABOUT to SettingsSummary.about(BuildConfig.VERSION_CODE, lang),
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
                    // 三级选择器内按 LEFT / BACK 返回上一级（子详情回到播放源列表，其余回二级页）
                    if (picker != null) {
                        if (event.key == Key.DirectionLeft || event.key == Key.Back) {
                            if (picker.startsWith(PICKER_SOURCE_DETAIL_PREFIX)) {
                                actions.onOpenPicker(PICKER_SOURCES_MANAGE)
                            } else {
                                actions.onClosePicker()
                            }
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
                        lang = lang,
                        entries = rootEntries,
                        focusedIndex = rootFocusIndex,
                        textScale = textScale,
                        scroll = rootScroll,
                        requesterOf = ::requesterOf,
                        onFocusIndex = actions.onRootFocusChanged,
                        onOpenPage = actions.onOpenPage,
                    )
                    page == SettingsPage.PLAYBACK -> SettingsPlaybackPage(
                        lang = lang,
                        onOpenLanguagePicker = { actions.onOpenPicker(PICKER_LANGUAGE) },
                        uiScaleLabel = state.uiScale.localizedLabel(lang),
                        onOpenUiScalePicker = { actions.onOpenPicker(PICKER_UI_SCALE) },
                        bufferLabel = playbackBufferLabel,
                        onOpenBufferPicker = { actions.onOpenPicker(PICKER_PLAYBACK_BUFFER) },
                        qualityMenuEnabled = state.qualityMenuEnabled,
                        onQualityMenuClick = actions.onQualityMenuClick,
                        zapPreconnectEnabled = state.zapPreconnectEnabled,
                        onToggleZapPreconnect = actions.onToggleZapPreconnect,
                        multicastPrewarmEnabled = state.multicastPrewarmEnabled,
                        onToggleMulticastPrewarm = actions.onToggleMulticastPrewarm,
                        jingcaiLabel = state.jingcaiExperiment.localizedLabel(lang),
                        onOpenJingcaiPicker = { actions.onOpenPicker(PICKER_JINGCAI) },
                        infoOkModeLabel = infoOkModeLabel,
                        onOpenInfoOkPicker = { actions.onOpenPicker(PICKER_INFO_OK_MODE) },
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.PLAYBACK),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.PLAYBACK.id] = it },
                    )
                    page == SettingsPage.REMOTE -> SettingsRemotePage(
                        lang = lang,
                        upDownLabel = remoteUpDownHabit.localizedLabel(lang),
                        okLabel = remoteOkHabit.localizedLabel(lang),
                        digitsLabel = state.remoteDigits.localizedLabel(lang),
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
                        lang = lang,
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
                        lang = lang,
                        sources = state.sources,
                        activeSourceId = state.activeSourceId,
                        globalUserAgent = state.globalUserAgent,
                        globalCustomHeaders = state.globalCustomHeaders,
                        onOpenSourceManage = { actions.onOpenPicker(PICKER_SOURCES_MANAGE) },
                        onSourceConfigClick = actions.onSourceConfigClick,
                        onOpenGlobalUaPicker = { actions.onOpenPicker(PICKER_GLOBAL_UA) },
                        onOpenGlobalHeadersPicker = { actions.onOpenPicker(PICKER_GLOBAL_HEADERS) },
                        autoUpdatePlaylistLabel = state.autoUpdatePlaylist.localizedLabel(lang),
                        autoUpdateEpgLabel = state.autoUpdateEpg.localizedLabel(lang),
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
                        lang = lang,
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
                        lang = lang,
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

private const val PICKER_LANGUAGE = "language"
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
private const val PICKER_SOURCES_MANAGE = "sources_manage"
private const val PICKER_SOURCE_DETAIL_PREFIX = "source_detail:"
private const val PICKER_GLOBAL_UA = "global_ua"
private const val PICKER_GLOBAL_HEADERS = "global_headers"

private data class UaPreset(
    val value: String,
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String,
)

private val GLOBAL_UA_PRESETS: List<UaPreset> = listOf(
    UaPreset("auto", "自动（跟随源 / 默认 VLC）", "Auto (Source / Default VLC)", "优先使用地址 | 参数与 #EXTINF，未指定时使用默认 VLC", "Uses stream | params & #EXTINF first, falls back to VLC"),
    UaPreset("VLC/3.0.20 LibVLC/3.0.20", "VLC 播放器", "VLC Player", "VLC/3.0.20 LibVLC/3.0.20（通用 IPTV 推荐）", "VLC/3.0.20 LibVLC/3.0.20 (recommended)"),
    UaPreset("aliplayer", "阿里云播放器 (aliplayer)", "Aliplayer (aliplayer)", "适配专享加密与 aliplayer 鉴权线路", "Compatible with streams requiring aliplayer UA"),
    UaPreset("AptvPlayer/1.4.2", "APTV 播放器", "APTV Player", "AptvPlayer/1.4.2", "AptvPlayer/1.4.2"),
    UaPreset("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Chrome 桌面端", "Chrome Desktop", "兼容校验浏览器 UA 的直链", "For streams requiring desktop browser User-Agent"),
)

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
    val lang = state.language
    fun t(zh: String, en: String): String = if (lang == AppLang.EN) en else zh

    if (pickerId == PICKER_SOURCES_MANAGE) {
        SettingsSourcesManageView(
            lang = lang,
            sources = state.sources,
            activeSourceId = state.activeSourceId,
            textScale = textScale,
            scroll = scroll,
            requesterOf = requesterOf,
            onOpenDetail = { src -> actions.onOpenPicker("$PICKER_SOURCE_DETAIL_PREFIX${src.id}") },
            onOpenWebConfig = actions.onSourceConfigClick,
        )
        return
    }
    if (pickerId.startsWith(PICKER_SOURCE_DETAIL_PREFIX)) {
        val id = pickerId.removePrefix(PICKER_SOURCE_DETAIL_PREFIX)
        val source = state.sources.firstOrNull { it.id == id }
        if (source != null) {
            SettingsSourceDetailView(
                lang = lang,
                source = source,
                isActive = source.id == state.activeSourceId,
                textScale = textScale,
                scroll = scroll,
                requesterOf = requesterOf,
                onSwitchActive = {
                    actions.onSwitchSource(source)
                    actions.onClosePicker()
                },
                onToggleEnabled = { actions.onToggleSourceEnabled(source.id) },
                onOpenWebConfig = actions.onSourceConfigClick,
            )
            return
        }
    }
    if (pickerId == PICKER_GLOBAL_HEADERS) {
        SettingsGlobalHeadersView(
            lang = lang,
            rawHeaders = state.globalCustomHeaders,
            textScale = textScale,
            scroll = scroll,
            requesterOf = requesterOf,
            onOpenWebConfig = actions.onSourceConfigClick,
            onClearAll = actions.onClearGlobalCustomHeaders,
        )
        return
    }
    if (pickerId.startsWith(PICKER_REMOTE_ACTION_PREFIX)) {
        val actionName = pickerId.removePrefix(PICKER_REMOTE_ACTION_PREFIX)
        val action = RemoteAction.entries.firstOrNull { it.name == actionName }
        if (action != null) {
            SettingsRemoteActionPickerView(
                lang = lang,
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
        PICKER_LANGUAGE -> t("语言 / Language", "Language / 语言") to AppLang.entries.map { optionLang ->
            SettingsPickerOption(
                id = optionLang.code,
                title = optionLang.nativeName,
                description = if (optionLang == AppLang.ZH) "使用简体中文显示菜单、节目单与网页控制台" else "Use English for TV UI, EPG badges & Web Console",
                selected = state.language == optionLang,
                onSelect = {
                    actions.onSelectLanguage(optionLang)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_UI_SCALE -> t("文字与台标大小", "Text & Logo Size") to UiScalePreset.entries.map { preset ->
            SettingsPickerOption(
                id = preset.id,
                title = preset.localizedLabel(lang),
                description = preset.localizedDescription(lang),
                selected = state.uiScale == preset,
                onSelect = {
                    actions.onSelectUiScale(preset)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_INFO_OK_MODE -> t("信息卡 OK 行为", "Info Card OK Action") to listOf(
            SettingsPickerOption(
                id = "two_step",
                title = t("两段式（推荐）", "Two-step (Recommended)"),
                description = t("按一次 OK 显示信息卡，再按一次 OK 进入最近观看快切", "First OK opens info card, second OK focuses recent channels"),
                selected = state.infoOkMode == InfoCardPolicy.InfoOkMode.TWO_STEP,
                onSelect = {
                    actions.onSelectInfoOkMode(InfoCardPolicy.InfoOkMode.TWO_STEP)
                    actions.onClosePicker()
                },
            ),
            SettingsPickerOption(
                id = "direct",
                title = t("直达最近观看", "Direct Recent Strip"),
                description = t("按一次 OK 出卡后立即聚焦最近观看条，左右即可切台", "Single OK opens info card directly focused on recent strip"),
                selected = state.infoOkMode == InfoCardPolicy.InfoOkMode.DIRECT,
                onSelect = {
                    actions.onSelectInfoOkMode(InfoCardPolicy.InfoOkMode.DIRECT)
                    actions.onClosePicker()
                },
            ),
        )
        PICKER_PLAYBACK_BUFFER -> t("起播缓冲", "Playback Buffer") to LoadControlTuning.TIERS.map { tier ->
            val desc = when (tier) {
                LoadControlTuning.TIER_FAST_MS -> t("最快出画，适合高速稳定局域网", "Fastest zap on low-latency networks")
                LoadControlTuning.TIER_BALANCED_MS -> t("速度与抗抖动均衡", "Balanced speed and stability")
                else -> t("默认推荐，弱网与高码率 4K 更稳", "Recommended for 4K and unstable streams")
            }
            SettingsPickerOption(
                id = "buf_$tier",
                title = LoadControlTuning.tierLabel(tier, lang),
                description = desc,
                selected = LoadControlTuning.sanitize(state.playbackBufferMin) == tier,
                onSelect = {
                    actions.onSelectPlaybackBuffer(tier)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_JINGCAI -> t("码流兼容模式", "Stream Compatibility") to JingcaiExperiment.Mode.entries.map { mode ->
            SettingsPickerOption(
                id = mode.id,
                title = mode.localizedLabel(lang),
                description = if (mode == JingcaiExperiment.Mode.OFF) {
                    t("默认关闭，标准码流无需开启", "Off by default for standard streams")
                } else {
                    t("实验性兼容策略，重新选台生效", "Experimental mode; takes effect on next tune")
                },
                selected = state.jingcaiExperiment == mode,
                onSelect = {
                    actions.onSelectJingcaiExperiment(mode)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_AUTO_PLAYLIST -> t("自动更新列表", "Auto-update Playlist") to AutoUpdateConfig.Interval.entries.map { iv ->
            SettingsPickerOption(
                id = iv.name,
                title = iv.localizedLabel(lang),
                selected = state.autoUpdatePlaylist == iv,
                onSelect = {
                    actions.onSelectPlaylistInterval(iv)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_AUTO_EPG -> t("自动更新 EPG", "Auto-update EPG") to AutoUpdateConfig.Interval.entries.map { iv ->
            SettingsPickerOption(
                id = iv.name,
                title = iv.localizedLabel(lang),
                selected = state.autoUpdateEpg == iv,
                onSelect = {
                    actions.onSelectEpgInterval(iv)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_REMOTE_UPDOWN -> t("上下键预设", "Up / Down Preset") to RemoteHabits.UpDown.entries.map { h ->
            SettingsPickerOption(
                id = h.name,
                title = h.localizedLabel(lang),
                description = if (h == RemoteHabits.UpDown.CHANNEL_STEP) {
                    t("全屏按 ↑ / ↓ 直接切上一台 / 下一台", "Up / Down zaps previous / next channel")
                } else {
                    t("全屏按 ↑ / ↓ 呼出频道列表", "Up / Down opens channel list drawer")
                },
                selected = remoteUpDownHabit == h,
                onSelect = {
                    actions.onSelectRemoteUpDown(h)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_REMOTE_OK -> t("OK 键预设", "OK Key Preset") to RemoteHabits.Ok.entries.map { h ->
            SettingsPickerOption(
                id = h.name,
                title = h.localizedLabel(lang),
                description = if (h == RemoteHabits.Ok.CONFIRM) {
                    t("全屏按 OK 显示底部信息卡", "OK opens bottom info card")
                } else {
                    t("全屏按 OK 直接打开频道列表", "OK opens channel list drawer")
                },
                selected = remoteOkHabit == h,
                onSelect = {
                    actions.onSelectRemoteOk(h)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_REMOTE_DIGITS -> t("数字键行为", "Numeric Keys") to RemoteHabits.Digits.entries.map { h ->
            SettingsPickerOption(
                id = h.name,
                title = h.localizedLabel(lang),
                description = if (h == RemoteHabits.Digits.DIRECT) {
                    t("输入频道号后停顿自动跳转", "Auto-tune shortly after typing digits")
                } else {
                    t("输入频道号后需按 OK 确认跳转", "Press OK to confirm channel number")
                },
                selected = state.remoteDigits == h,
                onSelect = {
                    actions.onSelectRemoteDigits(h)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_GLOBAL_UA -> {
            val current = state.globalUserAgent.trim()
            val presetValues = GLOBAL_UA_PRESETS.map { if (it.value == "auto") "" else it.value }
            val isCustom = current.isNotEmpty() && current !in presetValues
            val baseOpts = GLOBAL_UA_PRESETS.map { preset ->
                val actual = if (preset.value == "auto") "" else preset.value
                SettingsPickerOption(
                    id = "ua_${preset.value}",
                    title = if (lang == AppLang.EN) preset.titleEn else preset.titleZh,
                    description = if (lang == AppLang.EN) preset.descEn else preset.descZh,
                    selected = current == actual,
                    onSelect = {
                        actions.onSelectGlobalUserAgent(actual)
                        actions.onClosePicker()
                    },
                )
            }
            val allOpts = if (isCustom) {
                listOf(
                    SettingsPickerOption(
                        id = "ua_custom",
                        title = t("自定义：$current", "Custom: $current"),
                        description = t("通过扫码网页配置的全局 User-Agent", "Configured via Web Console"),
                        selected = true,
                        onSelect = { actions.onClosePicker() },
                    )
                ) + baseOpts
            } else baseOpts
            t("全局 User-Agent", "Global User-Agent") to allOpts
        }
        else -> t("选项", "Options") to emptyList()
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

/** 多播放源管理列表（查看当前源、隐藏/展示状态、点进配置或扫码新增）。 */
@Composable
private fun SettingsSourcesManageView(
    lang: AppLang,
    sources: List<PlaylistSource>,
    activeSourceId: String?,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onOpenDetail: (PlaylistSource) -> Unit,
    onOpenWebConfig: () -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = t("播放源管理", "Playlist Sources"), tag = "settings_picker_title", textScale = textScale)
        sources.forEachIndexed { index, src ->
            val isActive = src.id == activeSourceId
            val statusText = buildString {
                if (isActive) append(t("● 当前播放 · ", "● Active · "))
                append(if (src.enabled) t("选台列表显示", "Visible in drawer") else t("已在选台列表隐藏", "Hidden in drawer"))
            }
            SettingsChoiceRow(
                title = src.name,
                value = statusText,
                valueColor = if (isActive) BrandFocus.BlueColor else null,
                textScale = textScale,
                focusRequester = requesterOf("picker_opt_$index"),
                tag = "picker_opt_$index",
                onClick = { onOpenDetail(src) },
            )
            RowGap()
        }
        SettingsItem(
            text = t("扫码网页添加 / 编辑播放源", "Add / Edit via Web Console"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_${sources.size}"),
            tag = "picker_opt_${sources.size}",
            onClick = onOpenWebConfig,
        )
        HelpText(t("多源启用 ≥2 个时，在选台列表按 ← 进入分组，再按 ← 展开播放源列表。", "With 2+ enabled sources, press ← from Groups to expand Sources."))
    }
}

/** 单个播放源详情操作页（设为当前源 / 选台列表展示或隐藏）。 */
@Composable
private fun SettingsSourceDetailView(
    lang: AppLang,
    source: PlaylistSource,
    isActive: Boolean,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onSwitchActive: () -> Unit,
    onToggleEnabled: () -> Unit,
    onOpenWebConfig: () -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = source.name, tag = "settings_picker_title", textScale = textScale)
        SettingsRadioRow(
            title = if (isActive) t("当前正在使用此源", "Currently active") else t("切换为此播放源", "Switch to this source"),
            description = source.url,
            selected = isActive,
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_0"),
            tag = "picker_opt_0",
            onClick = onSwitchActive,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("在选台列表显示", "Show in channel drawer"),
            value = if (source.enabled) t("已展示（可在选台分组旁切换）", "Visible (switchable beside Groups)") else t("已隐藏（仅在设置中可见）", "Hidden (settings only)"),
            valueColor = if (source.enabled) BrandFocus.BlueColor else null,
            showChevron = false,
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_1"),
            tag = "picker_opt_1",
            onClick = onToggleEnabled,
        )
        RowGap()
        SettingsItem(
            text = t("在扫码网页编辑或删除", "Edit or delete via Web Console"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_2"),
            tag = "picker_opt_2",
            onClick = onOpenWebConfig,
        )
        if (source.epgUrl.isNotBlank()) {
            HelpText("EPG: ${source.epgUrl}")
        }
    }
}

/** 全局自定义 HTTP Header 管理页（查看生效条数、扫码编辑或清空）。 */
@Composable
private fun SettingsGlobalHeadersView(
    lang: AppLang,
    rawHeaders: String,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onOpenWebConfig: () -> Unit,
    onClearAll: () -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val parsed = remember(rawHeaders) { PlaylistSources.parseCustomHeaders(rawHeaders) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = t("全局自定义 Header", "Global Custom Headers"), tag = "settings_picker_title", textScale = textScale)
        SettingsItem(
            text = t("扫码网页编辑自定义 Header", "Edit Headers via Web Console"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_0"),
            tag = "picker_opt_0",
            onClick = onOpenWebConfig,
        )
        if (parsed.isNotEmpty()) {
            RowGap()
            SettingsItem(
                text = t("清空全部全局 Header（当前 ${parsed.size} 条）", "Clear all global headers (${parsed.size})"),
                textScale = textScale,
                focusRequester = requesterOf("picker_opt_1"),
                tag = "picker_opt_1",
                onClick = onClearAll,
            )
            SettingsGroupHeader(t("当前已配置", "Configured"))
            parsed.forEach { (k, v) ->
                HelpText("$k: $v")
            }
        } else {
            HelpText(t("当前未配置全局自定义 Header。", "No global custom headers configured."))
        }
        HelpText(t("优先级：播放地址 | 后参数 > #EXTINF 属性 > 全局 Header。支持 Referer、Origin、Cookie 等任意字段。", "Priority: stream | params > #EXTINF attributes > global headers."))
    }
}

/** 单个功能的短按/长按指定面板（点击即可监听按键或清除）。 */
@Composable
private fun SettingsRemoteActionPickerView(
    lang: AppLang,
    action: RemoteAction,
    binding: KeyBinding?,
    captureRequest: Pair<RemoteAction, Boolean>?,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onStartCapture: (Boolean) -> Unit,
    onClearBinding: (Boolean) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val capturingShort = captureRequest?.let { it.first == action && !it.second } == true
    val capturingLong = captureRequest?.let { it.first == action && it.second } == true
    val shortSummary = if (capturingShort) t("请按下遥控器任意键（按 Back 取消）…", "Press any remote key (Back to cancel)…") else RemoteBindings.bindingSummary(binding, false, lang)
    val longSummary = if (capturingLong) t("请按下遥控器任意键（按 Back 取消）…", "Press any remote key (Back to cancel)…") else RemoteBindings.bindingSummary(binding, true, lang)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = action.localizedLabel(lang), tag = "settings_picker_title", textScale = textScale)

        SettingsChoiceRow(
            title = t("指定短按按键", "Assign short press"),
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
            title = t("指定长按按键", "Assign long press"),
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
            text = t("清除短按绑定", "Clear short press"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_clear_short"),
            tag = "picker_opt_clear_short",
            onClick = { onClearBinding(false) },
        )
        RowGap()
        SettingsItem(
            text = t("清除长按绑定", "Clear long press"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_clear_long"),
            tag = "picker_opt_clear_long",
            onClick = { onClearBinding(true) },
        )
        if (capturingShort || capturingLong) {
            HelpText(
                text = t("正在等待按键输入，直接按下目标遥控器按键即可绑定；按返回键取消。", "Listening for key press… Press target remote key or Back to cancel."),
                color = BrandFocus.BlueColor,
            )
        }
    }
}

// ─── 一级根页（含固定置底的大号 [ ← ] / [ → ] 呼出提示） ─────────────────────

@Composable
private fun SettingsRootPage(
    lang: AppLang,
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
            SettingsTopTitle(
                title = if (lang == AppLang.EN) "Settings" else "设置",
                tag = SettingsIA.ROOT_TITLE_TAG,
                textScale = textScale,
            )
            entries.forEachIndexed { index, entry ->
                SettingsRootItem(
                    lang = lang,
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
        SettingsBottomKeyHint(lang)
    }
}

@Composable
private fun SettingsBottomKeyHint(lang: AppLang) {
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
                text = if (lang == AppLang.EN) "Channels" else "频道列表",
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
                text = if (lang == AppLang.EN) "Settings" else "设置",
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
    lang: AppLang,
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
    val pageTitle = entry.page.localizedTitle(lang)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(entry.tag)
            .semantics { contentDescription = "$pageTitle ${entry.summary}" }
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
                text = pageTitle,
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
    lang: AppLang,
    onOpenLanguagePicker: () -> Unit,
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
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.PLAYBACK, lang, textScale)
        SettingsChoiceRow(
            title = "语言 / Language",
            value = lang.nativeName,
            textScale = textScale,
            focusRequester = requesterOf("settings_language"),
            tag = "settings_language",
            onFocused = onFocused,
            onClick = onOpenLanguagePicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("文字与台标大小", "Text & Logo Size"),
            value = uiScaleLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_ui_scale"),
            tag = "settings_ui_scale",
            onFocused = onFocused,
            onClick = onOpenUiScalePicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("信息卡 OK 行为", "Info Card OK Action"),
            value = infoOkModeLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_info_ok_mode"),
            tag = "settings_info_ok_mode",
            onFocused = onFocused,
            onClick = onOpenInfoOkPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("起播缓冲", "Playback Buffer"),
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
                text = t("线路选择（当前频道）", "Stream Sources (Current)"),
                textScale = textScale,
                focusRequester = requesterOf("settings_quality_menu"),
                tag = "settings_quality_menu",
                onFocused = onFocused,
                onClick = onQualityMenuClick
            )
        }

        SettingsGroupHeader(t("高级诊断（实验）", "Diagnostics (Experimental)"))
        SettingsItem(
            text = if (zapPreconnectEnabled) t("预连接相邻频道：开", "Neighbour Preconnect: On") else t("预连接相邻频道：关", "Neighbour Preconnect: Off"),
            textScale = textScale,
            focusRequester = requesterOf("settings_zap_preconnect"),
            tag = "settings_zap_preconnect",
            onFocused = onFocused,
            onClick = onToggleZapPreconnect
        )
        RowGap()
        SettingsItem(
            text = if (multicastPrewarmEnabled) t("组播预热：开", "Multicast Prewarm: On") else t("组播预热：关", "Multicast Prewarm: Off"),
            textScale = textScale,
            focusRequester = requesterOf("settings_multicast_prewarm"),
            tag = "settings_multicast_prewarm",
            onFocused = onFocused,
            onClick = onToggleMulticastPrewarm
        )
        RowGap()
        SettingsChoiceRow(
            title = t("码流兼容模式", "Stream Compatibility"),
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
    lang: AppLang,
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
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.REMOTE, lang, textScale)

        SettingsGroupHeader(t("功能按键指定（点击配置）", "Key Assignments (Click to Assign)"))
        for (action in RemoteBindings.CONFIGURABLE_ACTIONS) {
            val binding = bindings.firstOrNull { it.action == action }
            val shortStr = RemoteBindings.bindingSummary(binding, false, lang)
            val longStr = RemoteBindings.bindingSummary(binding, true, lang)
            val hasLong = binding?.longPressKeycodes?.isNotEmpty() == true
            val summary = if (!hasLong) {
                t("短按：$shortStr", "Short: $shortStr")
            } else {
                t("短按：$shortStr · 长按：$longStr", "Short: $shortStr · Long: $longStr")
            }
            SettingsChoiceRow(
                title = action.localizedLabel(lang),
                value = summary,
                textScale = textScale,
                focusRequester = requesterOf("settings_remote_action_${action.name}"),
                tag = "settings_remote_action_${action.name}",
                onFocused = onFocused,
                onClick = { onOpenActionPicker(action) },
            )
            RowGap()
        }

        SettingsGroupHeader(t("快捷预设与通用按键", "Quick Presets"))
        SettingsChoiceRow(
            title = t("上下键预设", "Up / Down Preset"),
            value = upDownLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_updown"),
            tag = "settings_remote_updown",
            onFocused = onFocused,
            onClick = onOpenUpDownPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("OK 键预设", "OK Key Preset"),
            value = okLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_ok"),
            tag = "settings_remote_ok",
            onFocused = onFocused,
            onClick = onOpenOkPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("数字键行为", "Numeric Keys"),
            value = digitsLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_digits"),
            tag = "settings_remote_digits",
            onFocused = onFocused,
            onClick = onOpenDigitsPicker,
        )
        RowGap()
        SettingsItem(
            text = if (channelKeysEnabled) t("频道键 166/167：启用", "Channel +/- (166/167): On") else t("频道键 166/167：禁用", "Channel +/- (166/167): Off"),
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_channel_keys"),
            tag = "settings_remote_channel_keys",
            onFocused = onFocused,
            onClick = onToggleChannelKeys,
        )
        RowGap()
        SettingsItem(
            text = t("恢复默认遥控配置", "Restore Default Remote Settings"),
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_reset"),
            tag = "settings_remote_reset",
            onFocused = onFocused,
            onClick = onResetBindings,
        )
        RowGap()
        SettingsItem(
            text = t("重置全部自定义映射", "Reset All Custom Bindings"),
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
    lang: AppLang,
    summary: String?,
    entryLabel: String,
    selectable: Boolean,
    onAudioTrackClick: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.AUDIO, lang, textScale)
        SettingsItem(
            text = if (selectable) entryLabel else t("$entryLabel（不可选）", "$entryLabel (Unavailable)"),
            textScale = textScale,
            focusRequester = requesterOf("settings_audio_track"),
            tag = "settings_audio_track",
            onFocused = onFocused,
            onClick = onAudioTrackClick
        )
        if (!summary.isNullOrBlank()) {
            HelpText(t("当前：$summary", "Current: $summary"))
        }
    }
}

@Composable
private fun SettingsDataPage(
    lang: AppLang,
    sources: List<PlaylistSource>,
    activeSourceId: String?,
    globalUserAgent: String,
    globalCustomHeaders: String,
    onOpenSourceManage: () -> Unit,
    onSourceConfigClick: () -> Unit,
    onOpenGlobalUaPicker: () -> Unit,
    onOpenGlobalHeadersPicker: () -> Unit,
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
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val activeSourceName = sources.firstOrNull { it.id == activeSourceId }?.name
        ?: sources.firstOrNull()?.name ?: t("未配置", "Not configured")
    val visibleCount = sources.count { it.enabled }
    val sourceManageSummary = if (sources.size > 1) {
        t("$activeSourceName · 显示 $visibleCount/${sources.size}", "$activeSourceName · $visibleCount/${sources.size} shown")
    } else {
        activeSourceName
    }
    val globalUaSummary = globalUserAgent.trim().ifBlank { t("自动（默认 VLC）", "Auto (Default VLC)") }
    val customHeadersCount = remember(globalCustomHeaders) {
        PlaylistSources.parseCustomHeaders(globalCustomHeaders).size
    }
    val globalHeadersSummary = if (customHeadersCount > 0) {
        t("已配置 $customHeadersCount 条", "$customHeadersCount configured")
    } else {
        t("未配置（扫码网页编辑）", "None (Edit via Web Console)")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.DATA, lang, textScale)
        SettingsGroupHeader(text = t("播放源与请求头", "Sources & Headers"))
        SettingsChoiceRow(
            title = t("播放源管理与显示", "Manage & Show Sources"),
            value = sourceManageSummary,
            textScale = textScale,
            focusRequester = requesterOf("settings_source_manage"),
            tag = "settings_source_manage",
            onFocused = onFocused,
            onClick = onOpenSourceManage,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("扫码与多源控制台", "QR & Web Config Console"),
            value = t("手机扫码管理多源、请求头与遥控设置", "Manage sources, headers & remote via phone"),
            textScale = textScale,
            focusRequester = requesterOf("settings_source_config"),
            tag = "settings_source_config",
            onFocused = onFocused,
            onClick = onSourceConfigClick,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("全局 User-Agent", "Global User-Agent"),
            value = globalUaSummary,
            textScale = textScale,
            focusRequester = requesterOf("settings_global_ua"),
            tag = "settings_global_ua",
            onFocused = onFocused,
            onClick = onOpenGlobalUaPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("全局自定义 Header", "Global Custom Headers"),
            value = globalHeadersSummary,
            textScale = textScale,
            focusRequester = requesterOf("settings_global_headers"),
            tag = "settings_global_headers",
            onFocused = onFocused,
            onClick = onOpenGlobalHeadersPicker,
        )
        SettingsGroupHeader(text = t("自动更新", "Auto Update"))
        SettingsChoiceRow(
            title = t("自动更新列表", "Auto-update Playlist"),
            value = autoUpdatePlaylistLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_auto_playlist"),
            tag = "settings_auto_playlist",
            onFocused = onFocused,
            onClick = onOpenPlaylistIntervalPicker,
        )
        RowGap()
        SettingsItem(
            text = t("立即刷新播放列表", "Refresh Playlist Now"),
            textScale = textScale,
            focusRequester = requesterOf("settings_refresh_playlist"),
            tag = "settings_refresh_playlist",
            onFocused = onFocused,
            onClick = onRefreshPlaylistNow
        )
        SettingsGroupHeader(text = t("节目单 (EPG)", "Program Guide (EPG)"))
        SettingsChoiceRow(
            title = t("自动更新 EPG", "Auto-update EPG"),
            value = autoUpdateEpgLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_auto_epg"),
            tag = "settings_auto_epg",
            onFocused = onFocused,
            onClick = onOpenEpgIntervalPicker,
        )
        RowGap()
        SettingsItem(
            text = t("立即更新 EPG", "Refresh EPG Now"),
            textScale = textScale,
            focusRequester = requesterOf("settings_refresh_epg"),
            tag = "settings_refresh_epg",
            onFocused = onFocused,
            onClick = onRefreshEpgNow
        )
        if (lastUpdateSummary.isNotBlank()) {
            HelpText(lastUpdateSummary)
        }
        SettingsGroupHeader(text = t("观看记录", "Watch History"))
        SettingsItem(
            text = if (recentCount > 0) t("最近观看（$recentCount）", "Recent Channels ($recentCount)") else t("最近观看", "Recent Channels"),
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
    lang: AppLang,
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
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.LOGO, lang, textScale)
        if (logoCacheSummary.isNotBlank()) {
            HelpText(logoCacheSummary, start = 4.dp)
        }
        SettingsItem(
            tag = "settings_logo_prefetch",
            text = if (logoPrefetchEnabled) t("台标预取：开", "Logo Prefetch: On") else t("台标预取：关", "Logo Prefetch: Off"),
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_prefetch"),
            onFocused = onFocused,
            onClick = { onToggleLogoPrefetch(!logoPrefetchEnabled) }
        )
        HelpText(t("关闭后仅加载当前可视台标，进一步降低低配盒子负载。", "Disable to load visible channel logos only, reducing memory usage."))
        RowGap()
        SettingsItem(
            tag = "settings_logo_load",
            text = if (logoLoadEnabled) t("台标显示：开", "Show Logos: On") else t("台标显示：关", "Show Logos: Off"),
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_load"),
            onFocused = onFocused,
            onClick = { onToggleLogoLoad(!logoLoadEnabled) }
        )
        HelpText(t("关闭后不加载台标，仅显示文字首字占位。", "Disable to show channel initials instead of logo images."))
        RowGap()
        SettingsItem(
            text = t("刷新当前组台标", "Refresh Current Group Logos"),
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_refresh"),
            tag = "settings_logo_refresh",
            onFocused = onFocused,
            onClick = onRefreshLogos
        )
        RowGap()
        SettingsItem(
            text = t("清理台标缓存", "Clear Logo Cache"),
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
    lang: AppLang,
    onCheckUpdateClick: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsPageHeader(SettingsPage.ABOUT, lang, textScale)
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
            text = t("版本 ${BuildConfig.VERSION_NAME}", "Version ${BuildConfig.VERSION_NAME}"),
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
            text = t("检查更新", "Check for Updates"),
            textScale = textScale,
            focusRequester = requesterOf("settings_check_update"),
            tag = "settings_check_update",
            onFocused = onFocused,
            onClick = onCheckUpdateClick
        )
    }
}

// ─── 基础组件（统一顶部标题基线、对称圆角高亮、展开行与单选行） ────────

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
private fun SettingsPageHeader(page: SettingsPage, lang: AppLang, textScale: Float) {
    // 与设置根页保持 100% 相同的顶部标题基线
    SettingsTopTitle(title = page.localizedTitle(lang), tag = SettingsIA.pageTitleTag(page), textScale = textScale)
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

/** 点击展开选项列表的两行设置项（标题 + 当前值 + 右箭头）。 */
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
