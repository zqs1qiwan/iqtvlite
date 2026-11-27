package com.cjlhll.iptv

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.iqtvlite.app.BuildConfig
import tv.iqtvlite.app.R

private const val SETTINGS_LIST_TAG = "settings_list"

/** 三级展开单选项数据模型（点击展开列表单选，取代盲目循环切换）。 */
data class SettingsPickerOption(
    val id: String,
    val title: String,
    val description: String = "",
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/** 设置「关于」面板内联检查更新状态（无弹窗、无 Toast）。 */
sealed interface InlineUpdateState {
    data object Idle : InlineUpdateState
    data object Checking : InlineUpdateState
    data class UpToDate(val latestTag: String?) : InlineUpdateState
    data class Available(val info: UpdateChecker.UpdateInfo) : InlineUpdateState
    data class Downloading(val info: UpdateChecker.UpdateInfo, val progress: Int) : InlineUpdateState
    data class Failed(
        val messageZh: String,
        val messageEn: String,
        val info: UpdateChecker.UpdateInfo? = null,
    ) : InlineUpdateState
}

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
    val autoCheckUpdate: Boolean = true,
    val updateState: InlineUpdateState = InlineUpdateState.Idle,
    val notice: String? = null,
    // v0.2.0 7-category settings state
    val autoStartOnBoot: Boolean = false,
    val resumeLastChannel: Boolean = true,
    val homePipEnabled: Boolean = false,
    val doubleBackExitEnabled: Boolean = true,
    val epgRetentionDays: Int = 7,
    val epgUpdateOnPlaylistChange: Boolean = true,
    val uiTransparencyPct: Int = 18,
    val infoPanelTimeoutSec: Int = 5,
    val showInfoOnZap: Boolean = true,
    val showClockInPlayer: Boolean = true,
    val showExactResolution: Boolean = false,
    val showFavoritesGroup: Boolean = true,
    val showAllChannelsGroup: Boolean = true,
    val videoDecoderMode: String = "HARDWARE",
    val rebuildOn4kZap: Boolean = false,
    val defaultSubtitlesEnabled: Boolean = true,
    val subtitlePreferredLang: String = "auto",
    val allGroupNames: List<String> = emptyList(),
    val activeGroupConfig: Prefs.SourceGroupConfig = Prefs.SourceGroupConfig(),
    val ipStackPreference: IpStackPreference = IpStackPreference.IPV6_FIRST,
    val dnsMode: DnsMode = DnsMode.SYSTEM,
    val epgOffsetMinutes: Int = 0,
    val currentChannelTitle: String = "",
    val channelDecoderOverride: String = "auto",
    val autoFrameRateEnabled: Boolean = false,
    val audioOutputRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER,
    val audioBoostPct: Int = AudioPcmMath.DEFAULT_BOOST_PCT,
    val audioDelayMs: Int = 0,
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
    val onToggleAutoCheckUpdate: (Boolean) -> Unit = {},
    val onCheckUpdateClick: () -> Unit,
    val onDownloadUpdateClick: () -> Unit = {},
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
    // v0.2.0 7-category actions
    val onToggleAutoStart: (Boolean) -> Unit = {},
    val onToggleResumeLast: (Boolean) -> Unit = {},
    val onToggleHomePip: (Boolean) -> Unit = {},
    val onToggleDoubleBackExit: (Boolean) -> Unit = {},
    val onSelectEpgRetentionDays: (Int) -> Unit = {},
    val onToggleEpgOnPlaylistChange: (Boolean) -> Unit = {},
    val onSelectUiTransparencyPct: (Int) -> Unit = {},
    val onSelectInfoPanelTimeoutSec: (Int) -> Unit = {},
    val onToggleShowInfoOnZap: (Boolean) -> Unit = {},
    val onToggleShowClock: (Boolean) -> Unit = {},
    val onToggleShowExactResolution: (Boolean) -> Unit = {},
    val onToggleShowFavoritesGroup: (Boolean) -> Unit = {},
    val onToggleShowAllGroup: (Boolean) -> Unit = {},
    val onSelectVideoDecoderMode: (String) -> Unit = {},
    val onToggleRebuild4kZap: (Boolean) -> Unit = {},
    val onToggleDefaultSubtitles: (Boolean) -> Unit = {},
    val onSelectSubtitlePreferredLang: (String) -> Unit = {},
    val onUpdateSourceGroupConfig: (String, Prefs.SourceGroupConfig) -> Unit = { _, _ -> },
    val onWebStateSynced: () -> Unit = {},
    val onWebPushSource: (String, String) -> Unit = { _, _ -> },
    val onResetAllSettings: () -> Unit = {},
    val onSelectIpStackPreference: (IpStackPreference) -> Unit = {},
    val onSelectDnsMode: (DnsMode) -> Unit = {},
    val onSelectEpgOffsetMinutes: (Int) -> Unit = {},
    val onSelectChannelDecoderOverride: (String) -> Unit = {},
    val onToggleAutoFrameRate: (Boolean) -> Unit = {},
    val onSelectAudioBoostPct: (Int) -> Unit = {},
    val onSelectAudioDelayMs: (Int) -> Unit = {},
    val onResetCustomBindings: () -> Unit = {},
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
    var focusedGroupManageName by remember { mutableStateOf<String?>(null) }

    val rootScroll = rememberScrollState()
    val generalScroll = rememberScrollState()
    val playlistsScroll = rememberScrollState()
    val epgScroll = rememberScrollState()
    val appearanceScroll = rememberScrollState()
    val playbackScroll = rememberScrollState()
    val remoteScroll = rememberScrollState()
    val aboutScroll = rememberScrollState()
    val pickerScroll = rememberScrollState()

    fun scrollFor(target: SettingsPage): ScrollState = when (target) {
        SettingsPage.GENERAL -> generalScroll
        SettingsPage.PLAYLISTS -> playlistsScroll
        SettingsPage.EPG -> epgScroll
        SettingsPage.APPEARANCE -> appearanceScroll
        SettingsPage.PLAYBACK -> playbackScroll
        SettingsPage.REMOTE -> remoteScroll
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
        state.updateState,
    ) {
        val aboutSummary = when (val u = state.updateState) {
            is InlineUpdateState.Available -> s("发现新版本 ${u.info.latestTag}", "Update ${u.info.latestTag} available")
            is InlineUpdateState.Downloading -> s("正在下载 ${u.info.latestTag}… ${u.progress}%", "Downloading ${u.info.latestTag}… ${u.progress}%")
            else -> SettingsSummary.about(BuildConfig.VERSION_NAME, lang)
        }
        mapOf(
            SettingsPage.GENERAL to SettingsSummary.clamp(
                if (state.homePipEnabled) s("主页画中画 · WebUI 备份", "Home PiP · WebUI Backup")
                else s("启动与请求头", "Startup & Headers")
            ),
            SettingsPage.PLAYLISTS to SettingsSummary.clamp(
                "${state.sources.count { it.enabled }}/${state.sources.size} · ${state.autoUpdatePlaylist.localizedLabel(lang)}"
            ),
            SettingsPage.EPG to SettingsSummary.clamp(
                s("保留 ${state.epgRetentionDays} 天 · ${state.autoUpdateEpg.localizedLabel(lang)}", "Keep ${state.epgRetentionDays}d · ${state.autoUpdateEpg.localizedLabel(lang)}")
            ),
            SettingsPage.APPEARANCE to SettingsSummary.clamp(
                "${lang.nativeName} · ${state.uiScale.localizedLabel(lang)} · ${state.uiTransparencyPct}%"
            ),
            SettingsPage.PLAYBACK to SettingsSummary.playback(
                bufferLabel = playbackBufferLabel,
                anyExperimentOn = state.rebuildOn4kZap || state.zapPreconnectEnabled || state.multicastPrewarmEnabled || state.jingcaiExperiment != JingcaiExperiment.Mode.OFF,
                lang = lang,
            ),
            SettingsPage.REMOTE to SettingsSummary.remote(remoteUpDownHabit.localizedLabel(lang), remoteOkHabit.localizedLabel(lang)),
            SettingsPage.ABOUT to aboutSummary,
        )
    }
    val hasAvailableUpdate = state.updateState is InlineUpdateState.Available ||
        state.updateState is InlineUpdateState.Downloading
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
                        if (picker == PICKER_GROUP_MANAGE &&
                            state.activeGroupConfig.sortMode == "MANUAL" &&
                            focusedGroupManageName != null &&
                            (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)
                        ) {
                            val sid = state.activeSourceId ?: "default"
                            val cfg = state.activeGroupConfig
                            val currentOrder = if (cfg.manualOrder.isNotEmpty()) {
                                val existing = cfg.manualOrder.filter { it in state.allGroupNames }
                                existing + state.allGroupNames.filter { it !in existing }
                            } else {
                                state.allGroupNames
                            }
                            val idx = currentOrder.indexOf(focusedGroupManageName)
                            if (idx >= 0) {
                                val delta = if (event.key == Key.DirectionLeft) -1 else 1
                                val nextIdx = (idx + delta).coerceIn(0, currentOrder.lastIndex)
                                if (nextIdx != idx) {
                                    val mutable = currentOrder.toMutableList()
                                    val item = mutable.removeAt(idx)
                                    mutable.add(nextIdx, item)
                                    actions.onUpdateSourceGroupConfig(sid, cfg.copy(manualOrder = mutable))
                                }
                                return@onPreviewKeyEvent true
                            }
                        }
                        if (event.key == Key.DirectionLeft || event.key == Key.Back) {
                            focusedGroupManageName = null
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
            // 动态透明度背景层（响应“用户界面透明度”设置）
            val drawerAlpha = Prefs.uiSurfaceAlpha(state.uiTransparencyPct)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .align(Alignment.CenterEnd)
                    .graphicsLayer { alpha = drawerAlpha }
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
                            onGroupFocusChanged = { focusedGroupManageName = it },
                        )
                    }
                    page == null -> SettingsRootPage(
                        lang = lang,
                        entries = rootEntries,
                        focusedIndex = rootFocusIndex,
                        hasAvailableUpdate = hasAvailableUpdate,
                        textScale = textScale,
                        scroll = rootScroll,
                        requesterOf = ::requesterOf,
                        onFocusIndex = actions.onRootFocusChanged,
                        onOpenPage = actions.onOpenPage,
                    )
                    page == SettingsPage.GENERAL -> SettingsGeneralPage(
                        lang = lang,
                        autoStartOnBoot = state.autoStartOnBoot,
                        resumeLastChannel = state.resumeLastChannel,
                        homePipEnabled = state.homePipEnabled,
                        doubleBackExitEnabled = state.doubleBackExitEnabled,
                        ipStackPreference = state.ipStackPreference,
                        dnsMode = state.dnsMode,
                        globalUserAgent = state.globalUserAgent,
                        globalCustomHeaders = state.globalCustomHeaders,
                        onToggleAutoStart = actions.onToggleAutoStart,
                        onToggleResumeLast = actions.onToggleResumeLast,
                        onToggleHomePip = actions.onToggleHomePip,
                        onToggleDoubleBackExit = actions.onToggleDoubleBackExit,
                        onOpenIpStackPicker = { actions.onOpenPicker(PICKER_IP_STACK) },
                        onOpenDnsModePicker = { actions.onOpenPicker(PICKER_DNS_MODE) },
                        onOpenGlobalUaPicker = { actions.onOpenPicker(PICKER_GLOBAL_UA) },
                        onOpenGlobalHeadersPicker = { actions.onOpenPicker(PICKER_GLOBAL_HEADERS) },
                        onSourceConfigClick = actions.onSourceConfigClick,
                        onOpenResetDefaults = { actions.onOpenPicker(PICKER_RESET_DEFAULTS) },
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.GENERAL),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.GENERAL.id] = it },
                    )
                    page == SettingsPage.PLAYLISTS -> SettingsPlaylistsPage(
                        lang = lang,
                        sources = state.sources,
                        activeSourceId = state.activeSourceId,
                        autoUpdatePlaylistLabel = state.autoUpdatePlaylist.localizedLabel(lang),
                        recentCount = state.recentCount,
                        onOpenSourceManage = { actions.onOpenPicker(PICKER_SOURCES_MANAGE) },
                        onOpenGroupManage = { actions.onOpenPicker(PICKER_GROUP_MANAGE) },
                        onRefreshPlaylistNow = actions.onRefreshPlaylistNow,
                        onOpenPlaylistIntervalPicker = { actions.onOpenPicker(PICKER_AUTO_PLAYLIST) },
                        onOpenRecent = actions.onOpenRecent,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.PLAYLISTS),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.PLAYLISTS.id] = it },
                    )
                    page == SettingsPage.EPG -> SettingsEpgPage(
                        lang = lang,
                        epgRetentionDays = state.epgRetentionDays,
                        autoUpdateEpgLabel = state.autoUpdateEpg.localizedLabel(lang),
                        epgOffsetMinutes = state.epgOffsetMinutes,
                        epgUpdateOnPlaylistChange = state.epgUpdateOnPlaylistChange,
                        lastUpdateSummary = state.lastUpdateSummary,
                        onOpenRetentionPicker = { actions.onOpenPicker(PICKER_EPG_RETENTION) },
                        onOpenEpgIntervalPicker = { actions.onOpenPicker(PICKER_AUTO_EPG) },
                        onOpenOffsetPicker = { actions.onOpenPicker(PICKER_EPG_OFFSET) },
                        onToggleEpgOnPlaylistChange = actions.onToggleEpgOnPlaylistChange,
                        onRefreshEpgNow = actions.onRefreshEpgNow,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.EPG),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.EPG.id] = it },
                    )
                    page == SettingsPage.APPEARANCE -> SettingsAppearancePage(
                        lang = lang,
                        uiScaleLabel = state.uiScale.localizedLabel(lang),
                        uiTransparencyPct = state.uiTransparencyPct,
                        infoPanelTimeoutSec = state.infoPanelTimeoutSec,
                        showInfoOnZap = state.showInfoOnZap,
                        showClockInPlayer = state.showClockInPlayer,
                        showExactResolution = state.showExactResolution,
                        showFavoritesGroup = state.showFavoritesGroup,
                        showAllChannelsGroup = state.showAllChannelsGroup,
                        infoOkModeLabel = infoOkModeLabel,
                        logoCacheSummary = state.logoCacheSummary,
                        logoPrefetchEnabled = state.logoPrefetchEnabled,
                        logoLoadEnabled = state.logoLoadEnabled,
                        onOpenLanguagePicker = { actions.onOpenPicker(PICKER_LANGUAGE) },
                        onOpenUiScalePicker = { actions.onOpenPicker(PICKER_UI_SCALE) },
                        onOpenTransparencyPicker = { actions.onOpenPicker(PICKER_UI_TRANSPARENCY) },
                        onOpenInfoTimeoutPicker = { actions.onOpenPicker(PICKER_INFO_TIMEOUT) },
                        onToggleShowInfoOnZap = actions.onToggleShowInfoOnZap,
                        onToggleShowClock = actions.onToggleShowClock,
                        onToggleShowExactResolution = actions.onToggleShowExactResolution,
                        onToggleShowFavoritesGroup = actions.onToggleShowFavoritesGroup,
                        onToggleShowAllGroup = actions.onToggleShowAllGroup,
                        onOpenInfoOkPicker = { actions.onOpenPicker(PICKER_INFO_OK_MODE) },
                        onToggleLogoPrefetch = actions.onToggleLogoPrefetch,
                        onToggleLogoLoad = actions.onToggleLogoLoad,
                        onRefreshLogos = actions.onRefreshLogos,
                        onClearLogoCache = actions.onClearLogoCache,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.APPEARANCE),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.APPEARANCE.id] = it },
                    )
                    page == SettingsPage.PLAYBACK -> SettingsPlaybackPage(
                        lang = lang,
                        bufferLabel = playbackBufferLabel,
                        videoDecoderMode = state.videoDecoderMode,
                        currentChannelTitle = state.currentChannelTitle,
                        channelDecoderOverride = state.channelDecoderOverride,
                        autoFrameRateEnabled = state.autoFrameRateEnabled,
                        rebuildOn4kZap = state.rebuildOn4kZap,
                        defaultSubtitlesEnabled = state.defaultSubtitlesEnabled,
                        subtitlePreferredLang = state.subtitlePreferredLang,
                        audioSummary = state.audioSummary,
                        audioEntryLabel = state.audioEntryLabel,
                        audioSelectable = state.audioSelectable,
                        audioOutputRoute = state.audioOutputRoute,
                        audioBoostPct = state.audioBoostPct,
                        audioDelayMs = state.audioDelayMs,
                        qualityMenuEnabled = state.qualityMenuEnabled,
                        zapPreconnectEnabled = state.zapPreconnectEnabled,
                        multicastPrewarmEnabled = state.multicastPrewarmEnabled,
                        jingcaiLabel = state.jingcaiExperiment.localizedLabel(lang),
                        onOpenBufferPicker = { actions.onOpenPicker(PICKER_PLAYBACK_BUFFER) },
                        onOpenVideoDecoderPicker = { actions.onOpenPicker(PICKER_VIDEO_DECODER) },
                        onOpenChannelDecoderPicker = { actions.onOpenPicker(PICKER_CHANNEL_DECODER) },
                        onToggleAutoFrameRate = actions.onToggleAutoFrameRate,
                        onToggleRebuild4kZap = actions.onToggleRebuild4kZap,
                        onToggleDefaultSubtitles = actions.onToggleDefaultSubtitles,
                        onOpenSubtitleLangPicker = { actions.onOpenPicker(PICKER_SUBTITLE_LANG) },
                        onAudioTrackClick = actions.onAudioTrackClick,
                        onOpenAudioBoostPicker = { actions.onOpenPicker(PICKER_AUDIO_BOOST) },
                        onOpenAudioDelayPicker = { actions.onOpenPicker(PICKER_AUDIO_DELAY) },
                        onQualityMenuClick = actions.onQualityMenuClick,
                        onToggleZapPreconnect = actions.onToggleZapPreconnect,
                        onToggleMulticastPrewarm = actions.onToggleMulticastPrewarm,
                        onOpenJingcaiPicker = { actions.onOpenPicker(PICKER_JINGCAI) },
                        onOpenStreamDiag = { actions.onOpenPicker(PICKER_STREAM_DIAG) },
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
                        onResetCustomBindings = actions.onResetCustomBindings,
                        onResetBindings = actions.onResetRemoteBindings,
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.REMOTE),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.REMOTE.id] = it },
                    )
                    page == SettingsPage.ABOUT -> SettingsAboutPage(
                        lang = lang,
                        autoCheckUpdate = state.autoCheckUpdate,
                        updateState = state.updateState,
                        onToggleAutoCheckUpdate = actions.onToggleAutoCheckUpdate,
                        onCheckUpdateClick = actions.onCheckUpdateClick,
                        onDownloadUpdateClick = actions.onDownloadUpdateClick,
                        onOpenProjectInfo = { actions.onOpenPicker(PICKER_ABOUT_PROJECT) },
                        textScale = textScale,
                        scroll = scrollFor(SettingsPage.ABOUT),
                        requesterOf = ::requesterOf,
                        onFocused = { pageLastFocus[SettingsPage.ABOUT.id] = it },
                    )
                }

                // 面板内联操作提示气泡（非聚焦、不遮挡底部按键提示）
                val noticeText = state.notice?.takeIf { it.isNotBlank() }
                if (noticeText != null) {
                    val noticeShape = remember { RoundedCornerShape(8.dp) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 2.dp, end = 2.dp, bottom = 54.dp)
                            .testTag("settings_notice")
                            .clip(noticeShape)
                            .background(Color(0xFF1F2937).copy(alpha = 0.92f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), noticeShape)
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = noticeText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = (13f * textScale).sp,
                                lineHeight = (18f * textScale).sp,
                            ),
                            color = TvDesignTokens.TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
private const val PICKER_EPG_RETENTION = "epg_retention"
private const val PICKER_UI_TRANSPARENCY = "ui_transparency"
private const val PICKER_INFO_TIMEOUT = "info_timeout"
private const val PICKER_VIDEO_DECODER = "video_decoder"
private const val PICKER_SUBTITLE_LANG = "subtitle_lang"
private const val PICKER_GROUP_MANAGE = "group_manage"
private const val PICKER_GROUP_SORT = "group_sort"
private const val PICKER_WEB_QR = "web_qr"
private const val PICKER_IP_STACK = "ip_stack"
private const val PICKER_DNS_MODE = "dns_mode"
private const val PICKER_EPG_OFFSET = "epg_offset"
private const val PICKER_CHANNEL_DECODER = "channel_decoder"
private const val PICKER_AUDIO_BOOST = "audio_boost"
private const val PICKER_AUDIO_DELAY = "audio_delay"
private const val PICKER_STREAM_DIAG = "stream_diag"
private const val PICKER_ABOUT_PROJECT = "about_project"
private const val PICKER_RESET_DEFAULTS = "reset_defaults"

private data class UaPreset(
    val value: String,
    val titleZh: String,
    val titleEn: String,
    val descZh: String,
    val descEn: String,
)

private val GLOBAL_UA_PRESETS: List<UaPreset> = listOf(
    UaPreset("auto", "自动", "Auto", "跟随直播源配置，未指定时自动匹配通用标识", "Follows playlist setting when present"),
    UaPreset("VLC/3.0.20 LibVLC/3.0.20", "VLC 播放器", "VLC Player", "兼容绝大多数常规直播源", "Compatible with most standard streams"),
    UaPreset("aliplayer", "阿里云播放器", "Aliplayer", "适合部分需专用播放器标识的直播源", "For streams requiring aliplayer"),
    UaPreset("AptvPlayer/1.4.2", "APTV 播放器", "APTV Player", "AptvPlayer/1.4.2", "AptvPlayer/1.4.2"),
    UaPreset("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36", "Chrome 浏览器", "Chrome Browser", "适合校验浏览器访问的直链", "For streams requiring a browser User-Agent"),
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
    onGroupFocusChanged: (String?) -> Unit = {},
) {
    val lang = state.language
    fun t(zh: String, en: String): String = if (lang == AppLang.EN) en else zh

    if (pickerId == PICKER_WEB_QR) {
        SettingsWebQrView(
            lang = lang,
            textScale = textScale,
            scroll = scroll,
            requesterOf = requesterOf,
            onClose = actions.onClosePicker,
            onWebStateSynced = actions.onWebStateSynced,
            onWebPushSource = actions.onWebPushSource,
        )
        return
    }
    if (pickerId == PICKER_ABOUT_PROJECT) {
        SettingsAboutProjectView(
            lang = lang,
            textScale = textScale,
            scroll = scroll,
            requesterOf = requesterOf,
            onClose = actions.onClosePicker,
        )
        return
    }
    if (pickerId == PICKER_STREAM_DIAG) {
        SettingsStreamDiagView(
            lang = lang,
            textScale = textScale,
            scroll = scroll,
            requesterOf = requesterOf,
            onClose = actions.onClosePicker,
        )
        return
    }
    if (pickerId == PICKER_RESET_DEFAULTS) {
        SettingsResetDefaultsView(
            lang = lang,
            textScale = textScale,
            scroll = scroll,
            requesterOf = requesterOf,
            onConfirm = {
                actions.onResetAllSettings()
                actions.onClosePicker()
            },
            onCancel = actions.onClosePicker,
        )
        return
    }
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
                title = t("两段式", "Two-step"),
                description = t("按一次确认键显示频道信息，再按一次进入最近观看快切", "First OK shows channel info, second OK opens recent channels"),
                selected = state.infoOkMode == InfoCardPolicy.InfoOkMode.TWO_STEP,
                onSelect = {
                    actions.onSelectInfoOkMode(InfoCardPolicy.InfoOkMode.TWO_STEP)
                    actions.onClosePicker()
                },
            ),
            SettingsPickerOption(
                id = "direct",
                title = t("直达最近观看", "Direct Recent Strip"),
                description = t("按一次确认键直接聚焦最近观看与快捷控制", "Single OK jumps directly to recent channels and quick controls"),
                selected = state.infoOkMode == InfoCardPolicy.InfoOkMode.DIRECT,
                onSelect = {
                    actions.onSelectInfoOkMode(InfoCardPolicy.InfoOkMode.DIRECT)
                    actions.onClosePicker()
                },
            ),
        )
        PICKER_PLAYBACK_BUFFER -> t("起播缓冲", "Playback Buffer") to LoadControlTuning.TIERS.map { tier ->
            val desc = when (tier) {
                LoadControlTuning.TIER_FAST_MS -> t("切台最快，适合高速稳定网络", "Fastest channel switching on stable networks")
                LoadControlTuning.TIER_BALANCED_MS -> t("兼顾切台速度与播放稳定性", "Balanced speed and stability")
                else -> t("弱网或高码率 4K 更不易卡顿", "More resilient on slower networks or 4K streams")
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
        PICKER_IP_STACK -> t("网络协议优先级", "IP Protocol Priority") to IpStackPreference.entries.map { pref ->
            SettingsPickerOption(
                id = pref.id,
                title = pref.shortLabel(lang),
                description = if (pref == IpStackPreference.IPV6_FIRST) {
                    t("默认优先 IPv6 连接，不可用时自动回退 IPv4", "Prefer IPv6 first and automatically fall back to IPv4")
                } else {
                    t("优先 IPv4 连接，不可用时自动回退 IPv6", "Prefer IPv4 first and automatically fall back to IPv6")
                },
                selected = state.ipStackPreference == pref,
                onSelect = {
                    actions.onSelectIpStackPreference(pref)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_DNS_MODE -> t("加密 DNS (DoH)", "Encrypted DNS (DoH)") to DnsMode.entries.map { mode ->
            SettingsPickerOption(
                id = mode.id,
                title = mode.shortLabel(lang),
                description = mode.description(lang),
                selected = state.dnsMode == mode,
                onSelect = {
                    actions.onSelectDnsMode(mode)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_EPG_OFFSET -> t("EPG 时区偏移", "EPG Timezone Offset") to listOf(
            -720, -480, -240, -120, -60, -30, 0, 30, 60, 120, 240, 480, 720
        ).map { mins ->
            SettingsPickerOption(
                id = "epg_off_$mins",
                title = formatEpgOffsetLabel(mins, lang),
                description = if (mins == 0) t("使用节目单原始时间戳", "Use original EPG timestamps") else t("整体平移 EPG 显示与当前节目匹配窗口", "Shift displayed EPG schedule times"),
                selected = state.epgOffsetMinutes == mins,
                onSelect = {
                    actions.onSelectEpgOffsetMinutes(mins)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_CHANNEL_DECODER -> {
            val chName = state.currentChannelTitle.ifBlank { t("当前频道", "Current Channel") }
            val opts = listOf(
                Triple("auto", t("跟随全局设置", "Follow Global Setting"), t("使用「视频解码器」全局偏好（默认硬件优先）", "Use global decoder setting (HW preferred)")),
                Triple("HARDWARE", t("仅此频道强制硬解", "Force Hardware (This Channel)"), t("即使失败也不降级，直接优先 MediaCodec 硬解", "Prefer MediaCodec hardware decoder for this channel")),
                Triple("SOFTWARE", t("仅此频道强制软解", "Force Software (This Channel)"), t("切到此频道立即启用软件/FFmpeg 解码，免除等待", "Use software/FFmpeg decoder directly for this channel")),
            ).map { (modeId, titleTxt, descTxt) ->
                SettingsPickerOption(
                    id = "ch_dec_$modeId",
                    title = titleTxt,
                    description = descTxt,
                    selected = state.channelDecoderOverride.equals(modeId, ignoreCase = true),
                    onSelect = {
                        actions.onSelectChannelDecoderOverride(modeId)
                        actions.onClosePicker()
                    },
                )
            }
            t("当前频道解码：$chName", "Channel Decoder: $chName") to opts
        }
        PICKER_AUDIO_BOOST -> t("人声/音量增益", "Audio Volume Boost") to listOf(100, 110, 120, 135, 150).map { pct ->
            SettingsPickerOption(
                id = "boost_$pct",
                title = if (pct == 100) t("100%（原声旁路）", "100% (Bypass)") else "$pct%",
                description = if (pct == 100) t("零额外音频处理开销", "Zero DSP overhead bypass") else t("软限幅响度增强，防止爆音", "Soft-clipped PCM gain boost"),
                selected = state.audioBoostPct == pct,
                onSelect = {
                    actions.onSelectAudioBoostPct(pct)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_AUDIO_DELAY -> {
            val routeTitle = state.audioOutputRoute.localizedLabel(lang)
            val delays = listOf(-500, -300, -200, -100, -50, 0, 50, 100, 150, 200, 300, 500)
            t("音画同步延迟 ($routeTitle)", "Audio Delay ($routeTitle)") to delays.map { ms ->
                val label = when {
                    ms == 0 -> t("0 ms（关闭）", "0 ms (Off)")
                    ms > 0 -> "+$ms ms"
                    else -> "$ms ms"
                }
                SettingsPickerOption(
                    id = "delay_$ms",
                    title = label,
                    description = when {
                        ms > 0 -> t("音频延后输出（声音比画面快时选正值）", "Delay audio playback")
                        ms < 0 -> t("音频提前输出（蓝牙音画滞后时选负值）", "Advance audio playback")
                        else -> t("保持原始音画同步时间轴", "Keep original A/V sync")
                    },
                    selected = state.audioDelayMs == ms,
                    onSelect = {
                        actions.onSelectAudioDelayMs(ms)
                        actions.onClosePicker()
                    },
                )
            }
        }
        PICKER_JINGCAI -> t("码流兼容模式", "Stream Compatibility") to JingcaiExperiment.Mode.entries
            .filter { mode ->
                // C 与 H 为纯占位/手动对照模式，默认隐藏，除非当前已经选中
                (mode != JingcaiExperiment.Mode.C && mode != JingcaiExperiment.Mode.H) || state.jingcaiExperiment == mode
            }
            .map { mode ->
                SettingsPickerOption(
                    id = mode.id,
                    title = mode.localizedLabel(lang),
                    description = if (mode == JingcaiExperiment.Mode.OFF) {
                        t("常规频道保持关闭即可", "Keep off for normal channels")
                    } else {
                        t("仅当个别频道只出声不出画或卡死时尝试，下次切台生效", "Try only if a stream has audio without video or freezes; applies on next tune")
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
        PICKER_AUTO_EPG -> t("更新频率", "Update Frequency") to AutoUpdateConfig.EPG_HOURS_ORDER.map { iv ->
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
        PICKER_EPG_RETENTION -> t("保存节目预告天数", "EPG Retention Days") to listOf(1, 3, 5, 7, 14, 21).map { d ->
            SettingsPickerOption(
                id = "epg_days_$d",
                title = t("$d 天", "$d days"),
                selected = state.epgRetentionDays == d,
                onSelect = {
                    actions.onSelectEpgRetentionDays(d)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_UI_TRANSPARENCY -> t("用户界面透明度", "UI Transparency") to listOf(0, 10, 18, 30, 45, 60).map { pct ->
            SettingsPickerOption(
                id = "alpha_$pct",
                title = "$pct%",
                description = if (pct == 18) t("默认通透质感", "Default balanced opacity") else "",
                selected = state.uiTransparencyPct == pct,
                onSelect = {
                    actions.onSelectUiTransparencyPct(pct)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_INFO_TIMEOUT -> t("信息面板消失时间", "Info Panel Timeout") to listOf(3, 5, 8, 12, 15).map { sec ->
            SettingsPickerOption(
                id = "timeout_$sec",
                title = t("${sec} 秒", "${sec}s"),
                selected = state.infoPanelTimeoutSec == sec,
                onSelect = {
                    actions.onSelectInfoPanelTimeoutSec(sec)
                    actions.onClosePicker()
                },
            )
        }
        PICKER_VIDEO_DECODER -> t("视频解码器", "Video Decoder") to listOf(
            SettingsPickerOption(
                id = "HARDWARE",
                title = t("硬件解码", "Hardware"),
                description = t("省电流畅，适合绝大多数电视与盒子", "Smooth and power-efficient for most devices"),
                selected = state.videoDecoderMode != "SOFTWARE",
                onSelect = {
                    actions.onSelectVideoDecoderMode("HARDWARE")
                    actions.onClosePicker()
                },
            ),
            SettingsPickerOption(
                id = "SOFTWARE",
                title = t("软件解码", "Software"),
                description = t("兼容极少数特殊视频格式，耗电与占用较高", "Fallback for uncommon video formats; higher CPU usage"),
                selected = state.videoDecoderMode == "SOFTWARE",
                onSelect = {
                    actions.onSelectVideoDecoderMode("SOFTWARE")
                    actions.onClosePicker()
                },
            ),
        )
        PICKER_SUBTITLE_LANG -> t("首选字幕语言", "Preferred Subtitle Language") to listOf(
            SettingsPickerOption(
                id = "auto",
                title = t("自动", "Auto"),
                selected = state.subtitlePreferredLang == "auto",
                onSelect = { actions.onSelectSubtitlePreferredLang("auto"); actions.onClosePicker() },
            ),
            SettingsPickerOption(
                id = "zh",
                title = t("中文", "Chinese"),
                selected = state.subtitlePreferredLang == "zh",
                onSelect = { actions.onSelectSubtitlePreferredLang("zh"); actions.onClosePicker() },
            ),
            SettingsPickerOption(
                id = "en",
                title = t("英文", "English"),
                selected = state.subtitlePreferredLang == "en",
                onSelect = { actions.onSelectSubtitlePreferredLang("en"); actions.onClosePicker() },
            ),
        )
        PICKER_GROUP_SORT -> t("频道组排序方式", "Group Sorting") to listOf(
            "DEFAULT" to t("按节目列表顺序", "Playlist Order"),
            "NAME" to t("按名称排序", "By Name"),
            "MANUAL" to t("手动排序", "Manual Order"),
        ).map { (mode, label) ->
            SettingsPickerOption(
                id = mode,
                title = label,
                selected = state.activeGroupConfig.sortMode == mode,
                onSelect = {
                    val sid = state.activeSourceId ?: "default"
                    actions.onUpdateSourceGroupConfig(sid, state.activeGroupConfig.copy(sortMode = mode))
                    actions.onClosePicker()
                },
            )
        }
        PICKER_GROUP_MANAGE -> {
            val sid = state.activeSourceId ?: "default"
            val cfg = state.activeGroupConfig
            val sortLabel = when (cfg.sortMode) {
                "NAME" -> t("按名称排序", "By Name")
                "MANUAL" -> t("手动排序", "Manual Order")
                else -> t("按节目列表顺序", "Playlist Order")
            }
            val opts = buildList {
                add(
                    SettingsPickerOption(
                        id = "grp_sort_mode",
                        title = t("分组排序：$sortLabel", "Sort: $sortLabel"),
                        selected = false,
                        onSelect = { actions.onOpenPicker(PICKER_GROUP_SORT) },
                    )
                )
                add(
                    SettingsPickerOption(
                        id = "grp_show_all",
                        title = t("显示所有频道组", "Show All Groups"),
                        selected = cfg.hiddenGroups.isEmpty(),
                        onSelect = {
                            actions.onUpdateSourceGroupConfig(sid, cfg.copy(hiddenGroups = emptySet()))
                        },
                    )
                )
                add(
                    SettingsPickerOption(
                        id = "grp_hide_all",
                        title = t("隐藏所有频道组", "Hide All Groups"),
                        selected = state.allGroupNames.isNotEmpty() && cfg.hiddenGroups.containsAll(state.allGroupNames),
                        onSelect = {
                            actions.onUpdateSourceGroupConfig(sid, cfg.copy(hiddenGroups = state.allGroupNames.toSet()))
                        },
                    )
                )
                val orderedNames = if (cfg.sortMode == "MANUAL" && cfg.manualOrder.isNotEmpty()) {
                    val kept = cfg.manualOrder.filter { it in state.allGroupNames }
                    kept + state.allGroupNames.filter { it !in kept }
                } else {
                    state.allGroupNames
                }
                orderedNames.forEach { gName ->
                    val visible = gName !in cfg.hiddenGroups
                    val desc = if (cfg.sortMode == "MANUAL") {
                        val visLabel = if (visible) t("可见 · OK 隐藏", "Visible · OK hide") else t("已隐藏 · OK 显示", "Hidden · OK show")
                        "$visLabel · ${t("←/→ 调整顺序", "←/→ Reorder")}"
                    } else {
                        if (visible) t("当前可见（点击隐藏）", "Visible (click to hide)") else t("已隐藏（点击显示）", "Hidden (click to show)")
                    }
                    add(
                        SettingsPickerOption(
                            id = "grp_toggle_$gName",
                            title = gName,
                            description = desc,
                            selected = visible,
                            onSelect = {
                                val nextHidden = if (visible) cfg.hiddenGroups + gName else cfg.hiddenGroups - gName
                                actions.onUpdateSourceGroupConfig(sid, cfg.copy(hiddenGroups = nextHidden))
                            },
                        )
                    )
                }
            }
            t("管理频道组", "Manage Channel Groups") to opts
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
        onOptionFocused = { id ->
            val grp = if (id.startsWith("grp_toggle_")) id.removePrefix("grp_toggle_") else null
            onGroupFocusChanged(grp)
        },
    )
}

@Composable
private fun SettingsOptionPickerView(
    title: String,
    options: List<SettingsPickerOption>,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onOptionFocused: (String) -> Unit = {},
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
                onFocus = { onOptionFocused(opt.id) },
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
                valueColor = if (isActive) TvDesignTokens.BrandLiteBlue else null,
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
            valueColor = if (source.enabled) TvDesignTokens.BrandLiteBlue else null,
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

/** 内嵌扫码 WebUI 配置页（直接在设置抽屉内生成二维码，替代笨重控制台）。 */
@Composable
private fun SettingsWebQrView(
    lang: AppLang,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onClose: () -> Unit,
    onWebStateSynced: () -> Unit,
    onWebPushSource: (String, String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val context = LocalContext.current
    val appContext = context.applicationContext
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var lanUrl by remember { mutableStateOf<String?>(null) }
    var lanError by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(appContext) {
        var server: SourceConfigLanServer? = null
        val disposed = java.util.concurrent.atomic.AtomicBoolean(false)
        val serverLock = Any()
        val thread = Thread {
            val created = SourceConfigLanServer(
                appContext = appContext,
                onPush = { live, epg ->
                    mainHandler.post { onWebPushSource(live, epg) }
                },
                onStateChanged = {
                    mainHandler.post { onWebStateSynced() }
                },
                onSwitchAndPlay = { src ->
                    mainHandler.post { onWebPushSource(src.url, src.epgUrl) }
                },
            )
            val started = runCatching { created.start() }
            synchronized(serverLock) {
                if (disposed.get()) {
                    runCatching { created.stop() }
                } else {
                    server = created
                    mainHandler.post {
                        started.onSuccess { info ->
                            lanUrl = info.url
                            lanError = null
                        }.onFailure { e ->
                            lanUrl = null
                            lanError = e.message ?: t("局域网服务启动失败", "LAN server start failed")
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        onDispose {
            val toStop = synchronized(serverLock) {
                disposed.set(true)
                server.also { server = null }
            }
            runCatching { toStop?.stop() }
        }
    }

    LaunchedEffect(lanUrl) {
        val url = lanUrl
        qrBitmap = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) {
                runCatching { QrCode.toBitmap(url, 520) }.getOrNull()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopTitle(title = t("扫码网页配置", "Scan QR · WebUI"), tag = "settings_picker_title", textScale = textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bmp = qrBitmap
            if (bmp != null) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = t("局域网配置二维码", "LAN WebUI QR Code"),
                        modifier = Modifier.size(192.dp),
                    )
                }
            } else if (lanError != null) {
                HelpText(lanError!!, color = Color(0xFFFCA5A5), start = 4.dp)
            } else {
                HelpText(t("正在启动局域网配置服务…", "Starting LAN WebUI server…"), start = 4.dp)
            }

            if (!lanUrl.isNullOrBlank()) {
                Text(
                    text = lanUrl!!,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (13.5f * textScale).sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = TvDesignTokens.BrandLiteBlue,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            HelpText(
                text = t(
                    "请确保手机与电视连接同一 Wi-Fi / 局域网，扫码或浏览器打开上方地址即可管理播放源、请求头、遥控映射与配置备份恢复。",
                    "Connect your phone to the same LAN/Wi-Fi and scan or open the URL above to manage playlists, headers, remote bindings & backups.",
                ),
                start = 4.dp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsItem(
                text = t("返回上一级", "Back"),
                textScale = textScale,
                focusRequester = requesterOf("picker_opt_0"),
                tag = "picker_opt_0",
                onClick = onClose,
            )
        }
    }
}

/** 关于项目与开源许可证详情页。 */
@Composable
private fun SettingsResetDefaultsView(
    lang: AppLang,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = t("还原默认设置", "Restore Default Settings"), tag = "settings_picker_title", textScale = textScale)
        HelpText(
            text = t(
                "将外观、播放解码、遥控按键等全部设置恢复为出厂默认状态。已添加的节目列表、收藏与观看记录不会被删除。",
                "Resets appearance, playback, and remote settings back to defaults. Your playlists, favorites, and watch history will be kept.",
            ),
            color = TvDesignTokens.TextSecondary,
            start = 4.dp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingsItem(
            text = t("确认还原默认设置", "Confirm Restore Defaults"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_0"),
            tag = "picker_opt_0",
            onClick = onConfirm,
        )
        RowGap()
        SettingsItem(
            text = t("取消", "Cancel"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_1"),
            tag = "picker_opt_1",
            onClick = onCancel,
        )
    }
}

@Composable
private fun SettingsAboutProjectView(
    lang: AppLang,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onClose: () -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val versionDisplay = if (BuildConfig.VERSION_NAME.startsWith("v", ignoreCase = true)) {
        BuildConfig.VERSION_NAME
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopTitle(title = t("关于与开源协议", "Project & License"), tag = "settings_picker_title", textScale = textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG),
        ) {
            SettingsAboutBrandHeader(
                lang = lang,
                versionDisplay = versionDisplay,
                textScale = textScale,
            )
            SettingsGroupHeader(t("项目仓库", "Repository"))
            HelpText(
                text = "https://github.com/zqs1qiwan/iqtvlite",
                color = TvDesignTokens.BrandLiteBlue,
                start = 12.dp,
            )
            SettingsGroupHeader(t("开源许可证", "Open Source License"))
            HelpText(
                text = "GNU General Public License v3.0 (GPL-3.0)",
                color = TvDesignTokens.TextPrimary,
                start = 12.dp,
            )
            HelpText(
                text = t(
                    "IQTV Lite 为轻量级 Android TV 开源直播播放器，源码与二进制分发遵循 GPL-3.0 开源协议。",
                    "IQTV Lite is an open-source lightweight Android TV live player distributed under GPL-3.0.",
                ),
                start = 12.dp,
            )
            Spacer(modifier = Modifier.height(10.dp))
            SettingsItem(
                text = t("返回上一级", "Back"),
                textScale = textScale,
                focusRequester = requesterOf("picker_opt_0"),
                tag = "picker_opt_0",
                onClick = onClose,
            )
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
            valueColor = if (capturingShort) TvDesignTokens.BrandLiteBlue else null,
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
            valueColor = if (capturingLong) TvDesignTokens.BrandLiteBlue else null,
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
                color = TvDesignTokens.BrandLiteBlue,
            )
        }
    }
}

@Composable
private fun SettingsStreamDiagView(
    lang: AppLang,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            tick++
        }
    }
    val rows = remember(lang, tick) { PlaybackDiagCenter.uiRows(context, lang) }
    val events = remember(tick) { PlaybackDiagCenter.recentEvents().takeLast(6).reversed() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .testTag(SETTINGS_LIST_TAG)
    ) {
        SettingsTopTitle(title = t("实时码流诊断", "Stream Diagnostics"), tag = "settings_picker_title", textScale = textScale)
        rows.forEach { (k, v) ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                Text(
                    text = k,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (11f * textScale).sp),
                    color = TvDesignTokens.TextMuted,
                )
                Text(
                    text = v,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = (13.5f * textScale).sp),
                    color = TvDesignTokens.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (events.isNotEmpty()) {
            SettingsGroupHeader(t("最近事件", "Recent Events"))
            events.forEach { ev ->
                HelpText("[${ev.tag}] ${ev.message}", start = 4.dp)
            }
        }
        HelpText(t("也可在手机浏览器打开局域网控制台导出完整 /api/diag JSON 日志。", "Full /api/diag JSON export is also available in LAN WebUI."), start = 4.dp)
        RowGap()
        SettingsItem(
            text = t("返回", "Back"),
            textScale = textScale,
            focusRequester = requesterOf("picker_opt_0"),
            tag = "picker_opt_0",
            onClick = onClose,
        )
    }
}

private fun formatEpgOffsetLabel(minutes: Int, lang: AppLang): String = when {
    minutes == 0 -> if (lang == AppLang.EN) "0 (Original)" else "0（原始时间）"
    minutes % 60 == 0 -> {
        val h = minutes / 60
        val sign = if (h > 0) "+" else ""
        if (lang == AppLang.EN) "$sign${h}h" else "$sign${h} 小时"
    }
    else -> {
        val sign = if (minutes > 0) "+" else ""
        if (lang == AppLang.EN) "$sign$minutes min" else "$sign$minutes 分钟"
    }
}

// ─── 一级根页（含固定置底的大号 [ ← ] / [ → ] 呼出提示） ─────────────────────

@Composable
private fun SettingsRootPage(
    lang: AppLang,
    entries: List<SettingsRootEntry>,
    focusedIndex: Int,
    hasAvailableUpdate: Boolean,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocusIndex: (Int) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsTopTitle(
            title = if (lang == AppLang.EN) "Settings" else "设置",
            tag = SettingsIA.ROOT_TITLE_TAG,
            textScale = textScale,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            entries.forEachIndexed { index, entry ->
                SettingsRootItem(
                    lang = lang,
                    entry = entry,
                    accentSummary = entry.page == SettingsPage.ABOUT && hasAvailableUpdate,
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
                SettingsKeyArrowIcon(directionLeft = true, color = TvDesignTokens.TextPrimary)
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
                SettingsKeyArrowIcon(directionLeft = false, color = TvDesignTokens.TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (lang == AppLang.EN) "Prev Channel" else "切换上一频道",
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
private fun SettingsKeyArrowIcon(directionLeft: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val cy = h / 2f
        val stroke = 2.dp.toPx()
        val tipX = if (directionLeft) w * 0.16f else w * 0.84f
        val tailX = if (directionLeft) w * 0.84f else w * 0.16f
        val wingX = if (directionLeft) w * 0.48f else w * 0.52f

        drawLine(
            color = color,
            start = Offset(tailX, cy),
            end = Offset(tipX, cy),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val head = Path().apply {
            moveTo(wingX, h * 0.22f)
            lineTo(tipX, cy)
            lineTo(wingX, h * 0.78f)
        }
        drawPath(
            path = head,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsRootItem(
    lang: AppLang,
    entry: SettingsRootEntry,
    accentSummary: Boolean = false,
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
                color = when {
                    accentSummary -> TvDesignTokens.BrandLiteBlue
                    focused -> TvDesignTokens.TextSecondary
                    else -> TvDesignTokens.TextMuted
                },
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
private fun SettingsGeneralPage(
    lang: AppLang,
    autoStartOnBoot: Boolean,
    resumeLastChannel: Boolean,
    homePipEnabled: Boolean,
    doubleBackExitEnabled: Boolean,
    ipStackPreference: IpStackPreference,
    dnsMode: DnsMode,
    globalUserAgent: String,
    globalCustomHeaders: String,
    onToggleAutoStart: (Boolean) -> Unit,
    onToggleResumeLast: (Boolean) -> Unit,
    onToggleHomePip: (Boolean) -> Unit,
    onToggleDoubleBackExit: (Boolean) -> Unit,
    onOpenIpStackPicker: () -> Unit,
    onOpenDnsModePicker: () -> Unit,
    onOpenGlobalUaPicker: () -> Unit,
    onOpenGlobalHeadersPicker: () -> Unit,
    onSourceConfigClick: () -> Unit,
    onOpenResetDefaults: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val globalUaSummary = globalUserAgent.trim().ifBlank { t("自动", "Auto") }
    val customHeadersCount = remember(globalCustomHeaders) {
        PlaylistSources.parseCustomHeaders(globalCustomHeaders).size
    }
    val globalHeadersSummary = if (customHeadersCount > 0) {
        t("已配置 $customHeadersCount 条", "$customHeadersCount configured")
    } else {
        t("未配置，可扫码添加", "None (Scan QR to add)")
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.GENERAL, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsGroupHeader(t("启动与系统", "Startup & System"))
            SettingsToggleRow(
                title = t("开机时自动启动", "Auto-start on boot"),
                checked = autoStartOnBoot,
                textScale = textScale,
                focusRequester = requesterOf("settings_auto_start"),
                tag = "settings_auto_start",
                onFocused = onFocused,
                onClick = { onToggleAutoStart(!autoStartOnBoot) },
            )
            RowGap()
            SettingsToggleRow(
                title = t("启动后打开上次观看频道", "Turn on last channel on start"),
                checked = resumeLastChannel,
                textScale = textScale,
                focusRequester = requesterOf("settings_resume_last"),
                tag = "settings_resume_last",
                onFocused = onFocused,
                onClick = { onToggleResumeLast(!resumeLastChannel) },
            )
            RowGap()
            SettingsToggleRow(
                title = t("按主页键切换到“画中画”模式", "Switch to PiP on Home key"),
                checked = homePipEnabled,
                textScale = textScale,
                focusRequester = requesterOf("settings_home_pip"),
                tag = "settings_home_pip",
                onFocused = onFocused,
                onClick = { onToggleHomePip(!homePipEnabled) },
            )
            RowGap()
            SettingsToggleRow(
                title = t("双击返回键退出", "Confirm exit by second Back"),
                checked = doubleBackExitEnabled,
                textScale = textScale,
                focusRequester = requesterOf("settings_double_back_exit"),
                tag = "settings_double_back_exit",
                onFocused = onFocused,
                onClick = { onToggleDoubleBackExit(!doubleBackExitEnabled) },
            )
        SettingsGroupHeader(t("网络与备份", "Network & Backup"))
        SettingsChoiceRow(
            title = t("网络协议优先级", "IP Protocol Priority"),
            value = ipStackPreference.shortLabel(lang),
            textScale = textScale,
            focusRequester = requesterOf("settings_ip_stack"),
            tag = "settings_ip_stack",
            onFocused = onFocused,
            onClick = onOpenIpStackPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("加密 DNS (DoH)", "Encrypted DNS (DoH)"),
            value = dnsMode.shortLabel(lang),
            textScale = textScale,
            focusRequester = requesterOf("settings_dns_mode"),
            tag = "settings_dns_mode",
            onFocused = onFocused,
            onClick = onOpenDnsModePicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("User-Agent 设置", "User-Agent"),
            value = globalUaSummary,
            textScale = textScale,
            focusRequester = requesterOf("settings_global_ua"),
            tag = "settings_global_ua",
            onFocused = onFocused,
            onClick = onOpenGlobalUaPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("其他 Header 设置", "Custom Headers"),
            value = globalHeadersSummary,
            textScale = textScale,
            focusRequester = requesterOf("settings_global_headers"),
            tag = "settings_global_headers",
            onFocused = onFocused,
            onClick = onOpenGlobalHeadersPicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("手机扫码管理与备份", "Phone QR Setup & Backup"),
            value = t("手机扫码管理播放源与备份恢复", "Manage playlists & backup via phone"),
            textScale = textScale,
            focusRequester = requesterOf("settings_source_config"),
            tag = "settings_source_config",
            onFocused = onFocused,
            onClick = onSourceConfigClick,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("还原默认设置", "Restore Default Settings"),
            value = t("恢复出厂设置，保留节目列表与收藏", "Reset preferences while keeping playlists & favorites"),
            textScale = textScale,
            focusRequester = requesterOf("settings_reset_defaults"),
            tag = "settings_reset_defaults",
            onFocused = onFocused,
            onClick = onOpenResetDefaults,
        )
        }
    }
}

@Composable
private fun SettingsPlaylistsPage(
    lang: AppLang,
    sources: List<PlaylistSource>,
    activeSourceId: String?,
    autoUpdatePlaylistLabel: String,
    recentCount: Int,
    onOpenSourceManage: () -> Unit,
    onOpenGroupManage: () -> Unit,
    onRefreshPlaylistNow: () -> Unit,
    onOpenPlaylistIntervalPicker: () -> Unit,
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
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.PLAYLISTS, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsGroupHeader(t("当前节目列表", "Playlists"))
        SettingsChoiceRow(
            title = t("节目列表管理", "Manage Playlists"),
            value = sourceManageSummary,
            textScale = textScale,
            focusRequester = requesterOf("settings_source_manage"),
            tag = "settings_source_manage",
            onFocused = onFocused,
            onClick = onOpenSourceManage,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("频道分组管理", "Channel Groups"),
            value = t("设置分组排列顺序与显示状态", "Adjust group order and visibility"),
            textScale = textScale,
            focusRequester = requesterOf("settings_group_manage"),
            tag = "settings_group_manage",
            onFocused = onFocused,
            onClick = onOpenGroupManage,
        )
        SettingsGroupHeader(t("更新与记录", "Updates & History"))
        SettingsItem(
            text = t("立即更新所有节目列表", "Update All Playlists"),
            textScale = textScale,
            focusRequester = requesterOf("settings_refresh_playlist"),
            tag = "settings_refresh_playlist",
            onFocused = onFocused,
            onClick = onRefreshPlaylistNow,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("自动更新节目列表", "Auto-update Playlists"),
            value = autoUpdatePlaylistLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_auto_playlist"),
            tag = "settings_auto_playlist",
            onFocused = onFocused,
            onClick = onOpenPlaylistIntervalPicker,
        )
        RowGap()
        SettingsItem(
            text = if (recentCount > 0) t("最近观看（$recentCount）", "Recent Channels ($recentCount)") else t("最近观看", "Recent Channels"),
            textScale = textScale,
            focusRequester = requesterOf(RecentDialogTags.SETTINGS_ENTRY),
            tag = RecentDialogTags.SETTINGS_ENTRY,
            onFocused = onFocused,
            onClick = onOpenRecent,
        )
        }
    }
}

@Composable
private fun SettingsEpgPage(
    lang: AppLang,
    epgRetentionDays: Int,
    autoUpdateEpgLabel: String,
    epgOffsetMinutes: Int,
    epgUpdateOnPlaylistChange: Boolean,
    lastUpdateSummary: String,
    onOpenRetentionPicker: () -> Unit,
    onOpenEpgIntervalPicker: () -> Unit,
    onOpenOffsetPicker: () -> Unit,
    onToggleEpgOnPlaylistChange: (Boolean) -> Unit,
    onRefreshEpgNow: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.EPG, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsGroupHeader(t("节目预告策略", "EPG Schedule & Retention"))
            SettingsChoiceRow(
                title = t("保存之前节目预告的天数", "Past Days to Keep EPG"),
                value = t("$epgRetentionDays 天", "$epgRetentionDays days"),
                textScale = textScale,
                focusRequester = requesterOf("settings_epg_retention"),
                tag = "settings_epg_retention",
                onFocused = onFocused,
                onClick = onOpenRetentionPicker,
            )
            RowGap()
            SettingsChoiceRow(
                title = t("更新频率", "Update Interval"),
                value = autoUpdateEpgLabel,
                textScale = textScale,
                focusRequester = requesterOf("settings_auto_epg"),
                tag = "settings_auto_epg",
                onFocused = onFocused,
                onClick = onOpenEpgIntervalPicker,
            )
            RowGap()
            SettingsChoiceRow(
                title = t("EPG 时区偏移", "EPG Timezone Offset"),
                value = formatEpgOffsetLabel(epgOffsetMinutes, lang),
                textScale = textScale,
                focusRequester = requesterOf("settings_epg_offset"),
                tag = "settings_epg_offset",
                onFocused = onFocused,
                onClick = onOpenOffsetPicker,
            )
            RowGap()
            SettingsToggleRow(
                title = t("节目列表更改时更新", "Update on playlist change"),
                checked = epgUpdateOnPlaylistChange,
                textScale = textScale,
                focusRequester = requesterOf("settings_epg_on_playlist_change"),
                tag = "settings_epg_on_playlist_change",
                onFocused = onFocused,
                onClick = { onToggleEpgOnPlaylistChange(!epgUpdateOnPlaylistChange) },
            )
            SettingsGroupHeader(t("手动更新", "Manual Update"))
            SettingsItem(
                text = t("立即更新节目预告", "Update EPG Now"),
                textScale = textScale,
                focusRequester = requesterOf("settings_refresh_epg"),
                tag = "settings_refresh_epg",
                onFocused = onFocused,
                onClick = onRefreshEpgNow,
            )
            HelpText(lastUpdateSummary, start = 4.dp)
        }
    }
}

@Composable
private fun SettingsAppearancePage(
    lang: AppLang,
    uiScaleLabel: String,
    uiTransparencyPct: Int,
    infoPanelTimeoutSec: Int,
    showInfoOnZap: Boolean,
    showClockInPlayer: Boolean,
    showExactResolution: Boolean,
    showFavoritesGroup: Boolean,
    showAllChannelsGroup: Boolean,
    infoOkModeLabel: String,
    logoCacheSummary: String,
    logoPrefetchEnabled: Boolean,
    logoLoadEnabled: Boolean,
    onOpenLanguagePicker: () -> Unit,
    onOpenUiScalePicker: () -> Unit,
    onOpenTransparencyPicker: () -> Unit,
    onOpenInfoTimeoutPicker: () -> Unit,
    onToggleShowInfoOnZap: (Boolean) -> Unit,
    onToggleShowClock: (Boolean) -> Unit,
    onToggleShowExactResolution: (Boolean) -> Unit,
    onToggleShowFavoritesGroup: (Boolean) -> Unit,
    onToggleShowAllGroup: (Boolean) -> Unit,
    onOpenInfoOkPicker: () -> Unit,
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
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.APPEARANCE, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsGroupHeader(t("语言与界面", "Language & UI"))
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
            title = t("字体与台标大小", "Font & Logo Size"),
            value = uiScaleLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_ui_scale"),
            tag = "settings_ui_scale",
            onFocused = onFocused,
            onClick = onOpenUiScalePicker,
        )
        RowGap()
        SettingsChoiceRow(
            title = t("用户界面透明度", "UI Transparency"),
            value = "$uiTransparencyPct%",
            textScale = textScale,
            focusRequester = requesterOf("settings_ui_transparency"),
            tag = "settings_ui_transparency",
            onFocused = onFocused,
            onClick = onOpenTransparencyPicker,
        )
        SettingsGroupHeader(t("播放器与信息面板", "Player & Info Panel"))
        SettingsChoiceRow(
            title = t("信息面板消失时间", "Info Panel Timeout"),
            value = t("${infoPanelTimeoutSec} 秒", "${infoPanelTimeoutSec}s"),
            textScale = textScale,
            focusRequester = requesterOf("settings_info_panel_timeout"),
            tag = "settings_info_panel_timeout",
            onFocused = onFocused,
            onClick = onOpenInfoTimeoutPicker,
        )
        RowGap()
        SettingsToggleRow(
            title = t("换台显示信息面板", "Show info panel on zap"),
            checked = showInfoOnZap,
            textScale = textScale,
            focusRequester = requesterOf("settings_show_info_on_zap"),
            tag = "settings_show_info_on_zap",
            onFocused = onFocused,
            onClick = { onToggleShowInfoOnZap(!showInfoOnZap) },
        )
        RowGap()
        SettingsToggleRow(
            title = t("播放器时钟显示", "Show clock in player"),
            checked = showClockInPlayer,
            textScale = textScale,
            focusRequester = requesterOf("settings_show_clock"),
            tag = "settings_show_clock",
            onFocused = onFocused,
            onClick = { onToggleShowClock(!showClockInPlayer) },
        )
        RowGap()
        SettingsToggleRow(
            title = t("显示精确分辨率", "Show exact resolution"),
            checked = showExactResolution,
            textScale = textScale,
            focusRequester = requesterOf("settings_show_exact_resolution"),
            tag = "settings_show_exact_resolution",
            onFocused = onFocused,
            onClick = { onToggleShowExactResolution(!showExactResolution) },
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
        SettingsGroupHeader(t("频道组与台标", "Groups & Logos"))
        SettingsToggleRow(
            title = t("显示“收藏夹”分组", "Show Favorites group"),
            checked = showFavoritesGroup,
            textScale = textScale,
            focusRequester = requesterOf("settings_show_favorites_group"),
            tag = "settings_show_favorites_group",
            onFocused = onFocused,
            onClick = { onToggleShowFavoritesGroup(!showFavoritesGroup) },
        )
        RowGap()
        SettingsToggleRow(
            title = t("显示“全部频道”分组", "Show All Channels group"),
            checked = showAllChannelsGroup,
            textScale = textScale,
            focusRequester = requesterOf("settings_show_all_group"),
            tag = "settings_show_all_group",
            onFocused = onFocused,
            onClick = { onToggleShowAllGroup(!showAllChannelsGroup) },
        )
        RowGap()
        SettingsToggleRow(
            tag = "settings_logo_prefetch",
            title = t("台标预取", "Logo Prefetch"),
            checked = logoPrefetchEnabled,
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_prefetch"),
            onFocused = onFocused,
            onClick = { onToggleLogoPrefetch(!logoPrefetchEnabled) },
        )
        RowGap()
        SettingsToggleRow(
            tag = "settings_logo_load",
            title = t("台标显示", "Show Logos"),
            checked = logoLoadEnabled,
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_load"),
            onFocused = onFocused,
            onClick = { onToggleLogoLoad(!logoLoadEnabled) },
        )
        RowGap()
        SettingsItem(
            text = t("刷新当前组台标", "Refresh Current Group Logos"),
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_refresh"),
            tag = "settings_logo_refresh",
            onFocused = onFocused,
            onClick = onRefreshLogos,
        )
        RowGap()
        SettingsItem(
            text = t("清理台标缓存", "Clear Logo Cache"),
            textScale = textScale,
            focusRequester = requesterOf("settings_logo_clear"),
            tag = "settings_logo_clear",
            onFocused = onFocused,
            onClick = onClearLogoCache,
        )
        if (logoCacheSummary.isNotBlank()) {
            HelpText(logoCacheSummary, start = 4.dp)
        }
        }
    }
}

@Composable
private fun SettingsPlaybackPage(
    lang: AppLang,
    bufferLabel: String,
    videoDecoderMode: String,
    currentChannelTitle: String,
    channelDecoderOverride: String,
    autoFrameRateEnabled: Boolean,
    rebuildOn4kZap: Boolean,
    defaultSubtitlesEnabled: Boolean,
    subtitlePreferredLang: String,
    audioSummary: String?,
    audioEntryLabel: String,
    audioSelectable: Boolean,
    audioOutputRoute: AudioOutputRoute,
    audioBoostPct: Int,
    audioDelayMs: Int,
    qualityMenuEnabled: Boolean,
    zapPreconnectEnabled: Boolean,
    multicastPrewarmEnabled: Boolean,
    jingcaiLabel: String,
    onOpenBufferPicker: () -> Unit,
    onOpenVideoDecoderPicker: () -> Unit,
    onOpenChannelDecoderPicker: () -> Unit,
    onToggleAutoFrameRate: (Boolean) -> Unit,
    onToggleRebuild4kZap: (Boolean) -> Unit,
    onToggleDefaultSubtitles: (Boolean) -> Unit,
    onOpenSubtitleLangPicker: () -> Unit,
    onAudioTrackClick: () -> Unit,
    onOpenAudioBoostPicker: () -> Unit,
    onOpenAudioDelayPicker: () -> Unit,
    onQualityMenuClick: () -> Unit,
    onToggleZapPreconnect: () -> Unit,
    onToggleMulticastPrewarm: () -> Unit,
    onOpenJingcaiPicker: () -> Unit,
    onOpenStreamDiag: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val decoderLabel = if (videoDecoderMode == "SOFTWARE") t("软件解码", "Software") else t("硬件解码", "Hardware")
    val chDecLabel = when (channelDecoderOverride.uppercase()) {
        "HARDWARE" -> t("强制硬解", "Force HW")
        "SOFTWARE" -> t("强制软解", "Force SW")
        else -> t("跟随全局", "Follow Global")
    }
    val subLangLabel = when (subtitlePreferredLang) {
        "zh" -> t("中文", "Chinese")
        "en" -> t("英文", "English")
        else -> t("自动", "Auto")
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.PLAYBACK, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsGroupHeader(t("解码与缓冲", "Decoder & Buffer"))
            SettingsChoiceRow(
                title = t("缓冲大小", "Buffer Size"),
                value = bufferLabel,
                textScale = textScale,
                focusRequester = requesterOf("settings_playback_buffer"),
                tag = "settings_playback_buffer",
                onFocused = onFocused,
                onClick = onOpenBufferPicker,
            )
            RowGap()
            SettingsChoiceRow(
                title = t("视频解码器", "Video Decoder"),
                value = decoderLabel,
                textScale = textScale,
                focusRequester = requesterOf("settings_video_decoder"),
                tag = "settings_video_decoder",
                onFocused = onFocused,
                onClick = onOpenVideoDecoderPicker,
            )
            RowGap()
            SettingsChoiceRow(
                title = if (currentChannelTitle.isNotBlank()) t("本频道解码：$currentChannelTitle", "Channel Decoder: $currentChannelTitle") else t("当前频道解码覆盖", "Channel Decoder Override"),
                value = chDecLabel,
                textScale = textScale,
                focusRequester = requesterOf("settings_channel_decoder_override"),
                tag = "settings_channel_decoder_override",
                onFocused = onFocused,
                onClick = onOpenChannelDecoderPicker,
            )
            RowGap()
            SettingsToggleRow(
                title = t("自动帧率匹配 (AFR)", "Auto Frame Rate (AFR)"),
                subtitle = t("防抖后匹配 25/50/60Hz 刷新率（换台略停顿）", "Matches TV refresh rate after stabilize"),
                checked = autoFrameRateEnabled,
                textScale = textScale,
                focusRequester = requesterOf("settings_auto_frame_rate"),
                tag = "settings_auto_frame_rate",
                onFocused = onFocused,
                onClick = { onToggleAutoFrameRate(!autoFrameRateEnabled) },
            )
            RowGap()
            SettingsToggleRow(
                title = t("4K 切台防花屏", "4K Anti-Artifacting"),
                subtitle = t("仅在切换 4K 频道出现花屏或绿屏时开启", "Turn on only if switching 4K channels causes visual artifacts"),
                checked = rebuildOn4kZap,
                textScale = textScale,
                focusRequester = requesterOf("settings_rebuild_4k_zap"),
                tag = "settings_rebuild_4k_zap",
                onFocused = onFocused,
                onClick = { onToggleRebuild4kZap(!rebuildOn4kZap) },
            )
            SettingsGroupHeader(t("音轨与字幕", "Audio & Subtitles"))
            SettingsToggleRow(
                title = t("默认加载字幕", "Auto-load subtitles"),
                checked = defaultSubtitlesEnabled,
                textScale = textScale,
                focusRequester = requesterOf("settings_default_subtitles"),
                tag = "settings_default_subtitles",
                onFocused = onFocused,
                onClick = { onToggleDefaultSubtitles(!defaultSubtitlesEnabled) },
            )
            RowGap()
            SettingsChoiceRow(
                title = t("首选字幕语言", "Preferred Subtitle Language"),
                value = subLangLabel,
                textScale = textScale,
                focusRequester = requesterOf("settings_subtitle_lang"),
                tag = "settings_subtitle_lang",
                onFocused = onFocused,
                onClick = onOpenSubtitleLangPicker,
            )
            RowGap()
            SettingsItem(
                text = audioEntryLabel,
                textScale = textScale,
                focusRequester = requesterOf("settings_audio_track"),
                tag = "settings_audio_track",
                onFocused = onFocused,
                onClick = onAudioTrackClick,
            )
            if (!audioSummary.isNullOrBlank()) {
                HelpText(t("当前：$audioSummary", "Current: $audioSummary"))
            }
            RowGap()
            SettingsChoiceRow(
                title = t("音量增益 (最大 150%)", "Volume Boost (Max 150%)"),
                value = if (audioBoostPct == 100) t("100%（关闭）", "100% (Off)") else "$audioBoostPct%",
                textScale = textScale,
                focusRequester = requesterOf("settings_audio_boost"),
                tag = "settings_audio_boost",
                onFocused = onFocused,
                onClick = onOpenAudioBoostPicker,
            )
            RowGap()
            SettingsChoiceRow(
                title = t("音画延迟 (${audioOutputRoute.localizedLabel(lang)})", "Audio Delay (${audioOutputRoute.localizedLabel(lang)})"),
                value = if (audioDelayMs == 0) "0 ms" else if (audioDelayMs > 0) "+$audioDelayMs ms" else "$audioDelayMs ms",
                textScale = textScale,
                focusRequester = requesterOf("settings_audio_delay"),
                tag = "settings_audio_delay",
                onFocused = onFocused,
                onClick = onOpenAudioDelayPicker,
            )
            if (qualityMenuEnabled) {
                RowGap()
                SettingsItem(
                    text = t("切换当前频道线路", "Switch Channel Source"),
                    textScale = textScale,
                    focusRequester = requesterOf("settings_quality_menu"),
                    tag = "settings_quality_menu",
                    onFocused = onFocused,
                    onClick = onQualityMenuClick,
                )
            }
            SettingsGroupHeader(t("高级优化", "Advanced"))
            SettingsToggleRow(
                title = t("预连接相邻频道", "Neighbour Preconnect"),
                subtitle = t("提前连接上下台加快换台，限流源建议关闭", "Pre-connects adjacent channels for faster zapping; keep off on strict servers"),
                checked = zapPreconnectEnabled,
                textScale = textScale,
                focusRequester = requesterOf("settings_zap_preconnect"),
                tag = "settings_zap_preconnect",
                onFocused = onFocused,
                onClick = onToggleZapPreconnect,
            )
            RowGap()
            SettingsToggleRow(
                title = t("组播预热", "Multicast Prewarm"),
                subtitle = t("仅家庭宽带内网组播源需要，普通网络请保持关闭", "Only for home ISP multicast streams; keep off for normal internet sources"),
                checked = multicastPrewarmEnabled,
                textScale = textScale,
                focusRequester = requesterOf("settings_multicast_prewarm"),
                tag = "settings_multicast_prewarm",
                onFocused = onFocused,
                onClick = onToggleMulticastPrewarm,
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
            RowGap()
            SettingsItem(
                text = t("实时码流诊断与日志", "Live Stream Diagnostics"),
                textScale = textScale,
                focusRequester = requesterOf("settings_stream_diag"),
                tag = "settings_stream_diag",
                onFocused = onFocused,
                onClick = onOpenStreamDiag,
            )
        }
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
    onResetCustomBindings: () -> Unit,
    onResetBindings: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.REMOTE, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsGroupHeader(t("功能按键指定", "Key Assignments"))
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
        SettingsToggleRow(
            title = t("遥控器频道 +/- 键", "Remote Channel +/- Keys"),
            checked = channelKeysEnabled,
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
            text = t("重置全部自定义映射", "Reset Custom Key Bindings"),
            textScale = textScale,
            focusRequester = requesterOf("settings_remote_bindings_reset"),
            tag = "settings_remote_bindings_reset",
            onFocused = onFocused,
            onClick = onResetCustomBindings,
        )
        }
    }
}

@Composable
private fun SettingsAboutBrandHeader(
    lang: AppLang,
    versionDisplay: String,
    textScale: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(id = R.drawable.brand_wordmark_white),
            contentDescription = "IQTV Lite Logo",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "IQTV Lite",
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = (17f * textScale).sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = TvDesignTokens.TextPrimary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (lang == AppLang.EN) "Version $versionDisplay" else "版本 $versionDisplay",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = (12.5f * textScale).sp),
            color = TvDesignTokens.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun SettingsAboutPage(
    lang: AppLang,
    autoCheckUpdate: Boolean,
    updateState: InlineUpdateState,
    onToggleAutoCheckUpdate: (Boolean) -> Unit,
    onCheckUpdateClick: () -> Unit,
    onDownloadUpdateClick: () -> Unit,
    onOpenProjectInfo: () -> Unit,
    textScale: Float,
    scroll: ScrollState,
    requesterOf: (String) -> FocusRequester,
    onFocused: (String) -> Unit,
) {
    fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
    val versionDisplay = if (BuildConfig.VERSION_NAME.startsWith("v", ignoreCase = true)) {
        BuildConfig.VERSION_NAME
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsPageHeader(SettingsPage.ABOUT, lang, textScale)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .testTag(SETTINGS_LIST_TAG)
        ) {
            SettingsAboutBrandHeader(
                lang = lang,
                versionDisplay = versionDisplay,
                textScale = textScale,
            )

            SettingsToggleRow(
                title = t("启动时自动检查更新", "Auto-check Updates"),
                checked = autoCheckUpdate,
                textScale = textScale,
                focusRequester = requesterOf("settings_auto_check_update"),
                tag = "settings_auto_check_update",
                onFocused = onFocused,
                onClick = { onToggleAutoCheckUpdate(!autoCheckUpdate) },
            )
            HelpText(t("开启后每次打开应用会自动在后台检查 GitHub 新版本。", "Checks GitHub Releases automatically when launching the app."))
            RowGap()

        val primaryLabel: String
        val primaryAction: () -> Unit
        when (updateState) {
            InlineUpdateState.Idle -> {
                primaryLabel = t("检查更新", "Check for Updates")
                primaryAction = onCheckUpdateClick
            }
            InlineUpdateState.Checking -> {
                primaryLabel = t("正在检查更新…", "Checking for updates…")
                primaryAction = {}
            }
            is InlineUpdateState.UpToDate -> {
                primaryLabel = t("重新检查更新", "Check Again")
                primaryAction = onCheckUpdateClick
            }
            is InlineUpdateState.Available -> {
                primaryLabel = t("下载并安装 ${updateState.info.latestTag}", "Download & Install ${updateState.info.latestTag}")
                primaryAction = onDownloadUpdateClick
            }
            is InlineUpdateState.Downloading -> {
                primaryLabel = t(
                    "正在下载 ${updateState.info.latestTag}… ${updateState.progress}%",
                    "Downloading ${updateState.info.latestTag}… ${updateState.progress}%"
                )
                primaryAction = {}
            }
            is InlineUpdateState.Failed -> {
                if (updateState.info != null) {
                    primaryLabel = t("重试下载 ${updateState.info.latestTag}", "Retry Download ${updateState.info.latestTag}")
                    primaryAction = onDownloadUpdateClick
                } else {
                    primaryLabel = t("重试检查更新", "Retry Update Check")
                    primaryAction = onCheckUpdateClick
                }
            }
        }

        SettingsItem(
            text = primaryLabel,
            textScale = textScale,
            focusRequester = requesterOf("settings_check_update"),
            tag = "settings_check_update",
            onFocused = onFocused,
            onClick = primaryAction,
        )

        val showRecheck = updateState is InlineUpdateState.Available ||
            (updateState is InlineUpdateState.Failed && updateState.info != null)
        if (showRecheck) {
            RowGap()
            SettingsItem(
                text = t("重新检查", "Check Again"),
                textScale = textScale,
                focusRequester = requesterOf("settings_recheck_update"),
                tag = "settings_recheck_update",
                onFocused = onFocused,
                onClick = onCheckUpdateClick,
            )
        }

        when (updateState) {
            InlineUpdateState.Idle -> Unit
            InlineUpdateState.Checking -> {
                HelpText(
                    text = t("正在连接 GitHub Releases，请稍候…", "Contacting GitHub Releases…"),
                    color = TvDesignTokens.TextSecondary,
                    start = 14.dp,
                )
            }
            is InlineUpdateState.UpToDate -> {
                val suffix = updateState.latestTag?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                InlineUpdateStatusBox(
                    text = t("当前已是最新版本$suffix", "Already up to date$suffix"),
                    bg = Color(0xFF16A34A).copy(alpha = 0.14f),
                    fg = Color(0xFF86EFAC),
                    textScale = textScale,
                )
            }
            is InlineUpdateState.Available -> {
                val info = updateState.info
                InlineUpdateStatusBox(
                    text = t(
                        "发现新版本 ${info.latestTag}（当前 ${info.currentVersion}）",
                        "Update ${info.latestTag} available (current ${info.currentVersion})"
                    ),
                    bg = TvDesignTokens.BrandLiteBlue.copy(alpha = 0.16f),
                    fg = TvDesignTokens.BrandLiteBlue,
                    textScale = textScale,
                )
            }
            is InlineUpdateState.Downloading -> {
                val pct = updateState.progress.coerceIn(0, 100)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.14f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(pct / 100f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(TvDesignTokens.BrandLiteBlue)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = t("下载完成后将自动拉起系统安装界面", "System installer will open automatically after download"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (11.5f * textScale).sp),
                        color = TvDesignTokens.TextMuted,
                    )
                }
            }
            is InlineUpdateState.Failed -> {
                InlineUpdateStatusBox(
                    text = t(updateState.messageZh, updateState.messageEn),
                    bg = Color(0xFFEF4444).copy(alpha = 0.14f),
                    fg = Color(0xFFFCA5A5),
                    textScale = textScale,
                )
            }
        }
            RowGap()
            SettingsChoiceRow(
                title = t("关于项目与开源协议", "About Project & License"),
                value = "GitHub · GPL-3.0",
                textScale = textScale,
                focusRequester = requesterOf("settings_about_project"),
                tag = "settings_about_project",
                onFocused = onFocused,
                onClick = onOpenProjectInfo,
            )
        }
    }
}

@Composable
private fun InlineUpdateStatusBox(
    text: String,
    bg: Color,
    fg: Color,
    textScale: Float,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = (12.5f * textScale).sp,
                lineHeight = (18f * textScale).sp,
            ),
            color = fg,
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            ),
            color = TvDesignTokens.BrandLiteBlue.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
        )
    }
}

/** 标准 Android TV 右对齐胶囊式开关行。 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    textScale: Float,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    tag: String? = null,
    onFocused: ((String) -> Unit)? = null,
    subtitle: String = "",
) {
    var focused by remember { mutableStateOf(false) }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent
    val shape = remember { RoundedCornerShape(6.dp) }
    val trackColor = if (checked) TvDesignTokens.BrandLiteBlue else Color.White.copy(alpha = 0.24f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (tag != null) m.testTag(tag) else m }
            .semantics { contentDescription = "$title ${if (checked) "开" else "关"}" }
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
                color = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextPrimary.copy(alpha = 0.90f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = (11.5f * textScale).sp),
                    color = if (focused) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
                .padding(horizontal = 3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
        }
    }
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
            val chevronColor = if (focused) TvDesignTokens.TextPrimary else TvDesignTokens.TextMuted
            Canvas(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(14.dp),
            ) {
                val w = size.width
                val h = size.height
                val p = Path().apply {
                    moveTo(w * 0.34f, h * 0.22f)
                    lineTo(w * 0.66f, h * 0.50f)
                    lineTo(w * 0.34f, h * 0.78f)
                }
                drawPath(
                    path = p,
                    color = chevronColor,
                    style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
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
    onFocus: () -> Unit = {},
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
                if (it.isFocused) {
                    onFocus()
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
        Text(
            text = if (selected) "●" else "○",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (14f * textScale).sp),
            color = if (selected) TvDesignTokens.BrandLiteBlue else TvDesignTokens.TextMuted,
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
                    selected -> TvDesignTokens.BrandLiteBlue
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
