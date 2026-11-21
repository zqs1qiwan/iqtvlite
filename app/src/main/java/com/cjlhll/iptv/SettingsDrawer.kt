package com.cjlhll.iptv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import android.view.KeyEvent as AndroidKeyEvent

private enum class SettingsPage {
    ROOT,
    PROGRAM_CLICK,
    PLAYLIST_POLICY,
    EPG_POLICY,
    REMOTE,
    SOURCE_SELECT,
    PLAYBACK_INFO,
}

/**
 * 独立右侧设置面板（A09）。根页与子页在同一 panelWidth 内替换内容；Back 逐级返回。
 * 所有设置持久化到 AppSettings，OK 选择即保存并返回父页，Back 取消未提交选择。
 */
@Composable
fun SettingsDrawer(
    visible: Boolean,
    panelWidth: Dp,
    currentChannelTitle: String?,
    previousChannelTitle: String?,
    catchupActive: Boolean,
    multiSourceEnabled: Boolean,
    sourceVariants: List<SourceVariantUi>,
    videoInfoLines: List<Pair<String, String>>,
    lastPlaylistSuccessText: String?,
    lastEpgSuccessText: String?,
    epgRefreshMessage: String? = null,
    playlistRefreshMessage: String? = null,
    onClose: () -> Unit,
    onSourceConfig: () -> Unit,
    onPreviousChannel: () -> Unit,
    onReturnToLive: () -> Unit,
    onRefreshPlaylist: () -> Unit,
    onRefreshEpg: () -> Unit,
    onSelectSource: (Int) -> Unit,
    onSelectAutoSource: () -> Unit = {},
    onCheckUpdate: () -> Unit,
) {
    if (!visible) return
    val context = LocalContext.current
    val container = TvColors.Panel.copy(alpha = TvColors.PANEL_ALPHA)
    val shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)

    var page by remember { mutableStateOf(SettingsPage.ROOT) }
    var capturing by remember { mutableStateOf<RemoteAction?>(null) }
    var captureError by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var rootFocusId by remember { mutableStateOf("source") }
    val rootListState = rememberLazyListState()

    // 设置状态（持久化值镜像，保证修改即时生效并保留重启）
    var restoreLast by remember { mutableStateOf(AppSettings.isRestoreLastChannel(context)) }
    var programClick by remember { mutableStateOf(AppSettings.getProgramClickMode(context)) }
    var playlistPolicy by remember { mutableStateOf(AppSettings.getPlaylistRefreshPolicy(context)) }
    var epgPolicy by remember { mutableStateOf(AppSettings.getEpgRefreshPolicy(context)) }
    var remoteVersion by remember { mutableStateOf(0) } // 触发遥控器页刷新

    LaunchedEffect(page) {
        if (page == SettingsPage.ROOT) page = SettingsPage.ROOT
    }

    BackHandler(enabled = visible) {
        when {
            confirmReset -> confirmReset = false
            capturing != null -> { capturing = null; captureError = null }
            page != SettingsPage.ROOT -> page = SettingsPage.ROOT
            else -> onClose()
        }
    }

    var rootFocus by remember { mutableStateOf(FocusRequester()) }
    var subFirstFocus by remember { mutableStateOf(FocusRequester()) }
    var captureFocus by remember { mutableStateOf(FocusRequester()) }
    var confirmFocus by remember { mutableStateOf(FocusRequester()) }
    var remoteFocusIndex by remember { mutableStateOf(0) }
    var remoteRequester by remember { mutableStateOf(FocusRequester()) }
    var resetRequester by remember { mutableStateOf(FocusRequester()) }
    var remoteFocusNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, page) {
        if (!visible) return@LaunchedEffect
        // 根页在 RootSettingsPage 内“滚动到锚点后再请求焦点”；此处处理子页首项
        if (page != SettingsPage.ROOT) runCatching { subFirstFocus.requestFocus() }
    }
    LaunchedEffect(capturing) {
        if (capturing != null) runCatching { captureFocus.requestFocus() }
        else if (visible && page == SettingsPage.REMOTE) remoteFocusNonce++ // S04/T02：捕获取消时由 RemotePage 滚动后返焦
    }
    LaunchedEffect(confirmReset) {
        if (confirmReset) {
            repeat(3) { withFrameNanos { }; if (runCatching { confirmFocus.requestFocus() }.isSuccess) return@LaunchedEffect }
        } else if (visible && page == SettingsPage.REMOTE) {
            remoteFocusNonce++ // S04/T02：reset 取消先滚到 reset 稳定行再返焦
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(160), initialOffsetX = { it }),
        exit = slideOutHorizontally(tween(140), targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(vertical = 16.dp)
                    .width(panelWidth)
                    .background(container, shape)
                    .onPreviewKeyEvent { ev ->
                        // D11：左键逐级返回（根层关闭）；捕获/确认覆盖层自行处理
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) {
                            if (capturing != null || confirmReset) {
                                false
                            } else {
                                if (page == SettingsPage.ROOT) onClose() else page = SettingsPage.ROOT
                                true
                            }
                        } else false
                    }
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                when (page) {
                    SettingsPage.ROOT -> RootSettingsPage(
                        currentChannelTitle = currentChannelTitle,
                        previousChannelTitle = previousChannelTitle,
                        catchupActive = catchupActive,
                        restoreLast = restoreLast,
                        programClick = programClick,
                        playlistPolicy = playlistPolicy,
                        epgPolicy = epgPolicy,
                        multiSourceEnabled = multiSourceEnabled,
                        focusId = rootFocusId,
                        onRootFocused = { rootFocusId = it },
                        listState = rootListState,
                        rootRequester = rootFocus,
                        epgRefreshMessage = epgRefreshMessage,
                        playlistRefreshMessage = playlistRefreshMessage,
                        onSourceConfig = onSourceConfig,
                        onPreviousChannel = onPreviousChannel,
                        onReturnToLive = onReturnToLive,
                        onToggleRestore = {
                            restoreLast = !restoreLast
                            AppSettings.setRestoreLastChannel(context, restoreLast)
                        },
                        onRemote = { page = SettingsPage.REMOTE },
                        onProgramClick = { page = SettingsPage.PROGRAM_CLICK },
                        onPlaylistPolicy = { page = SettingsPage.PLAYLIST_POLICY },
                        onEpgPolicy = { page = SettingsPage.EPG_POLICY },
                        onRefreshPlaylist = onRefreshPlaylist,
                        onRefreshEpg = onRefreshEpg,
                        onSourceSelect = { page = SettingsPage.SOURCE_SELECT },
                        onPlaybackInfo = { page = SettingsPage.PLAYBACK_INFO },
                        onCheckUpdate = onCheckUpdate,
                    )
                    SettingsPage.PROGRAM_CLICK -> OptionPage(
                        title = "节目选择",
                        options = ProgramClickMode.entries.map { it.label },
                        selectedIndex = ProgramClickMode.entries.indexOf(programClick),
                        firstFocus = subFirstFocus,
                        onSelect = { idx ->
                            programClick = ProgramClickMode.entries[idx]
                            AppSettings.setProgramClickMode(context, programClick)
                            page = SettingsPage.ROOT
                        },
                    )
                    SettingsPage.PLAYLIST_POLICY -> OptionPage(
                        title = "播放列表刷新",
                        options = PlaylistRefreshPolicy.entries.map { it.label },
                        selectedIndex = PlaylistRefreshPolicy.entries.indexOf(playlistPolicy),
                        firstFocus = subFirstFocus,
                        onSelect = { idx ->
                            playlistPolicy = PlaylistRefreshPolicy.entries[idx]
                            AppSettings.setPlaylistRefreshPolicy(context, playlistPolicy)
                            page = SettingsPage.ROOT
                        },
                    )
                    SettingsPage.EPG_POLICY -> OptionPage(
                        title = "EPG 刷新",
                        options = EpgRefreshPolicy.entries.map { it.label },
                        selectedIndex = EpgRefreshPolicy.entries.indexOf(epgPolicy),
                        firstFocus = subFirstFocus,
                        onSelect = { idx ->
                            epgPolicy = EpgRefreshPolicy.entries[idx]
                            AppSettings.setEpgRefreshPolicy(context, epgPolicy)
                            page = SettingsPage.ROOT
                        },
                    )
                    SettingsPage.REMOTE -> RemotePage(
                        firstFocus = subFirstFocus,
                        version = remoteVersion,
                        focusIndex = remoteFocusIndex,
                        onFocusedIndex = { remoteFocusIndex = it },
                        rowRequester = remoteRequester,
                        resetRequester = resetRequester,
                        focusNonce = remoteFocusNonce,
                        enabled = capturing == null && !confirmReset,
                        onCapture = { action -> capturing = action; captureError = null },
                        onReset = { confirmReset = true },
                    )
                    SettingsPage.SOURCE_SELECT -> SourceSelectPage(
                        variants = sourceVariants,
                        firstFocus = subFirstFocus,
                        onSelect = { idx -> onSelectSource(idx); page = SettingsPage.ROOT },
                        onSelectAuto = { onSelectAutoSource(); page = SettingsPage.ROOT },
                    )
                    SettingsPage.PLAYBACK_INFO -> PlaybackInfoPage(lines = videoInfoLines, firstFocus = subFirstFocus, onBack = { page = SettingsPage.ROOT })
                }

                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = when (page) {
                        SettingsPage.ROOT -> "OK：进入/切换  Back：关闭"
                        else -> "OK：选择  Back：返回"
                    },
                    style = TvType.Aux,
                    color = TvColors.TextDisabled,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // 按键捕获覆盖层
            capturing?.let { action ->
                CaptureOverlay(
                    action = action,
                    error = captureError,
                    focusRequester = captureFocus,
                    onCaptured = { keyCode ->
                        when {
                            !AppSettings.isAssignableKey(keyCode) -> {
                                captureError = "此按键为系统/基础键，不能分配"
                            }
                            AppSettings.conflictingAction(context, action, keyCode) != null -> {
                                val other = AppSettings.conflictingAction(context, action, keyCode)!!
                                captureError = "此键已用于${other.label}，请选择其他键"
                            }
                            else -> {
                                AppSettings.setRemoteKey(context, action, keyCode)
                                remoteVersion++
                                capturing = null
                                captureError = null
                                Toast.makeText(context, "已设置 ${action.label} = ${AppSettings.keyDisplayName(keyCode)}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onCancel = { capturing = null; captureError = null },
                )
            }

            if (confirmReset) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier
                            .width(320.dp)
                            .background(TvColors.Panel.copy(alpha = 0.98f), RoundedCornerShape(12.dp))
                            .border(1.dp, TvColors.FocusBorder, RoundedCornerShape(12.dp))
                            .padding(20.dp),
                    ) {
                        Text("恢复默认按键映射？", style = TvType.SectionTitle, color = TvColors.TextPrimary)
                        Spacer(modifier = Modifier.padding(top = 8.dp))
                        Text("将清除所有自定义映射", style = TvType.Aux, color = TvColors.TextSecondary)
                        Spacer(modifier = Modifier.padding(top = 16.dp))
                        SettingsRow(text = "确认恢复默认", onClick = {
                            AppSettings.resetRemote(context)
                            remoteVersion++
                            confirmReset = false
                            page = SettingsPage.ROOT
                            Toast.makeText(context, "已恢复默认按键映射", Toast.LENGTH_SHORT).show()
                        })
                        Spacer(modifier = Modifier.padding(top = 8.dp))
                        SettingsRow(text = "取消", firstFocus = confirmFocus, onClick = { confirmReset = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.RootSettingsPage(
    currentChannelTitle: String?,
    previousChannelTitle: String?,
    catchupActive: Boolean,
    restoreLast: Boolean,
    programClick: ProgramClickMode,
    playlistPolicy: PlaylistRefreshPolicy,
    epgPolicy: EpgRefreshPolicy,
    multiSourceEnabled: Boolean,
    focusId: String,
    onRootFocused: (String) -> Unit,
    listState: LazyListState,
    rootRequester: FocusRequester,
    epgRefreshMessage: String?,
    playlistRefreshMessage: String?,
    onSourceConfig: () -> Unit,
    onPreviousChannel: () -> Unit,
    onReturnToLive: () -> Unit,
    onToggleRestore: () -> Unit,
    onRemote: () -> Unit,
    onProgramClick: () -> Unit,
    onPlaylistPolicy: () -> Unit,
    onEpgPolicy: () -> Unit,
    onRefreshPlaylist: () -> Unit,
    onRefreshEpg: () -> Unit,
    onSourceSelect: () -> Unit,
    onPlaybackInfo: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    data class RootItem(
        val id: String,
        val text: String,
        val value: String?,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    )

    val items = buildList {
        add(RootItem("source", "源配置", currentChannelTitle?.let { "当前：$it" }, onClick = onSourceConfig))
        add(RootItem("prev", "上一频道", previousChannelTitle ?: "暂无历史", enabled = previousChannelTitle != null, onClick = onPreviousChannel))
        if (catchupActive) add(RootItem("return_live", "返回直播", "清除回看状态", onClick = onReturnToLive))
        add(RootItem("remote", "遥控器", "按键映射 / 恢复默认", onClick = onRemote))
        add(RootItem("restore", "启动播放", if (restoreLast) "恢复上次频道：开" else "恢复上次频道：关", onClick = onToggleRestore))
        add(RootItem("playlist_policy", "播放列表刷新", playlistPolicy.label, onClick = onPlaylistPolicy))
        add(RootItem("epg_policy", "EPG 刷新", epgPolicy.label, onClick = onEpgPolicy))
        add(RootItem("refresh_playlist", "立即刷新列表", playlistRefreshMessage ?: "手动触发", onClick = onRefreshPlaylist))
        add(RootItem("refresh_epg", "立即刷新EPG", epgRefreshMessage ?: "手动触发", onClick = onRefreshEpg))
        add(RootItem("program_click", "节目选择", programClick.label, onClick = onProgramClick))
        if (multiSourceEnabled) add(RootItem("source_select", "源选择", "多线路 / 延迟", onClick = onSourceSelect))
        add(RootItem("playback_info", "播放信息", "分辨率 / 格式 / 线路", onClick = onPlaybackInfo))
        add(RootItem("check_update", "检查更新", "检查新版本", onClick = onCheckUpdate))
    }

    val targetIndex = items.indexOfFirst { it.id == focusId }.let { if (it >= 0) it else 0 }
    // R05：布局后先滚到锚点，再请求可见目标
    LaunchedEffect(targetIndex, items.size) {
        runCatching { listState.scrollToItem(targetIndex) }
        repeat(3) {
            withFrameNanos { }
            if (runCatching { rootRequester.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    Text("设置", style = TvType.PageTitle, color = TvColors.TextPrimary)
    Spacer(modifier = Modifier.padding(top = 12.dp))
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(items.size) { index ->
            val it = items[index]
            SettingsRow(
                text = it.text,
                value = it.value,
                enabled = it.enabled,
                firstFocus = if (index == targetIndex) rootRequester else null,
                onFocused = { onRootFocused(it.id) },
                onClick = it.onClick,
            )
        }
    }
}

@Composable
private fun ColumnScope.OptionPage(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    firstFocus: FocusRequester,
    onSelect: (Int) -> Unit,
) {
    Text(title, style = TvType.PageTitle, color = TvColors.TextPrimary)
    Spacer(modifier = Modifier.padding(top = 12.dp))
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(options.size) { index ->
            val selected = index == selectedIndex
            SettingsRow(
                text = options[index],
                value = if (selected) "当前" else null,
                firstFocus = if (index == selectedIndex.coerceAtLeast(0)) firstFocus else null,
                leading = if (selected) "●" else "○",
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun ColumnScope.RemotePage(
    firstFocus: FocusRequester,
    version: Int,
    focusIndex: Int,
    onFocusedIndex: (Int) -> Unit,
    rowRequester: FocusRequester,
    resetRequester: FocusRequester,
    focusNonce: Int,
    enabled: Boolean,
    onCapture: (RemoteAction) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    remember(version) { version }
    val listState = rememberLazyListState()
    val resetRowIndex = RemoteAction.entries.size
    LaunchedEffect(focusIndex, resetRowIndex, focusNonce) {
        val targetIndex = focusIndex.coerceIn(0, resetRowIndex)
        runCatching { listState.scrollToItem(targetIndex) }
        val target = if (targetIndex == resetRowIndex) resetRequester else rowRequester
        repeat(3) { withFrameNanos { }; if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    Text("遥控器", style = TvType.PageTitle, color = TvColors.TextPrimary)
    Spacer(modifier = Modifier.padding(top = 4.dp))
    Text("仅 Live 全屏生效；Menu 为不可改的安全设置入口", style = TvType.Aux, color = TvColors.TextSecondary)
    Spacer(modifier = Modifier.padding(top = 12.dp))
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
        items(RemoteAction.entries.size) { index ->
            val action = RemoteAction.entries[index]
            val key = AppSettings.getRemoteKey(context, action)
            val custom = AppSettings.isRemoteCustom(context, action)
            SettingsRow(
                text = action.label,
                value = AppSettings.keyDisplayName(key) + if (custom) "（自定义）" else "（默认）",
                enabled = enabled,
                firstFocus = if (index == focusIndex && index < resetRowIndex) rowRequester else null,
                onFocused = { onFocusedIndex(index) },
                onClick = { onCapture(action) },
            )
        }
        item {
            SettingsRow(
                text = "恢复默认映射",
                value = null,
                enabled = enabled,
                firstFocus = resetRequester,
                onFocused = { onFocusedIndex(resetRowIndex) },
                onClick = onReset,
            )
        }
    }
}

@Composable
private fun ColumnScope.SourceSelectPage(
    variants: List<SourceVariantUi>,
    firstFocus: FocusRequester,
    onSelect: (Int) -> Unit,
    onSelectAuto: () -> Unit,
) {
    Text("源选择", style = TvType.PageTitle, color = TvColors.TextPrimary)
    Spacer(modifier = Modifier.padding(top = 12.dp))
    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
        item {
            SettingsRow(text = "自动（选择可用线路）", value = "自动", firstFocus = firstFocus, onClick = onSelectAuto)
        }
        items(variants.size) { index ->
            val v = variants[index]
            val latency = when {
                v.probing && v.latencyMs == null -> "测速中"
                v.latencyMs != null -> "${v.latencyMs}ms"
                v.probed -> "不可用"   // R11：探测完成仍失败=不可用，未测=未测
                else -> "未测"
            }
            SettingsRow(
                text = if (v.selected) "${v.label}（当前）" else v.label,
                value = latency,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun PlaybackInfoPage(lines: List<Pair<String, String>>, firstFocus: FocusRequester, onBack: () -> Unit) {
    Text("播放信息", style = TvType.PageTitle, color = TvColors.TextPrimary)
    Spacer(modifier = Modifier.padding(top = 12.dp))
    if (lines.isEmpty()) {
        Text("暂无播放信息", style = TvType.Body, color = TvColors.TextSecondary)
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            lines.forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(k, style = TvType.Body, color = TvColors.TextSecondary, modifier = Modifier.width(120.dp))
                    Text(v, style = TvType.Body, color = TvColors.TextPrimary)
                }
            }
        }
    }
    Spacer(modifier = Modifier.padding(top = 12.dp))
    SettingsRow(text = "返回", value = null, firstFocus = firstFocus, onClick = onBack)
}

@Composable
private fun CaptureOverlay(
    action: RemoteAction,
    error: String?,
    focusRequester: FocusRequester,
    onCaptured: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler { onCancel() }
    val captureState = remember { CaptureKeyState() }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .width(320.dp)
                .background(TvColors.Panel.copy(alpha = 0.98f), RoundedCornerShape(12.dp))
                .border(1.dp, TvColors.FocusBorder, RoundedCornerShape(12.dp))
                .padding(20.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { ev ->
                    // T02：完整消费 Down/Up，方向不参与背景空间焦点；仅一次完整 Down→Up 提交
                    val isDown = ev.type == KeyEventType.KeyDown
                    val isUp = ev.type == KeyEventType.KeyUp
                    if (!isDown && !isUp) return@onPreviewKeyEvent true
                    val native = ev.nativeKeyEvent
                    when (val result = captureState.onKey(isDown, native.keyCode, native.repeatCount)) {
                        is CaptureKeyResult.Submit -> { onCaptured(result.keyCode); true }
                        is CaptureKeyResult.Cancel -> { onCancel(); true }
                        is CaptureKeyResult.Consume -> true
                    }
                }
                .focusable(),
        ) {
            Text("设置「${action.label}」", style = TvType.SectionTitle, color = TvColors.TextPrimary)
            Spacer(modifier = Modifier.padding(top = 8.dp))
            Text("按下要使用的按键，Back 取消", style = TvType.Aux, color = TvColors.TextSecondary)
            if (!error.isNullOrBlank()) {
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Text(error, style = TvType.Aux, color = TvColors.Error)
            }
        }
    }
}

@Composable
private fun SettingsRow(
    text: String,
    value: String? = null,
    enabled: Boolean = true,
    firstFocus: FocusRequester? = null,
    leading: String? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .let { if (firstFocus != null) it.focusRequester(firstFocus) else it }
            .background(if (focused) TvColors.FocusFill else Color.Transparent, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { ev ->
                // D11：右键进入可进入项（等同 OK）
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight && enabled) { onClick(); true } else false
            }
            .focusable(enabled = enabled)
            .clickable(enabled = enabled) { onClick() }
            .heightIn(min = TvDims.SettingsRowMinHeight)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Text(leading, style = TvType.Item, color = if (enabled) TvColors.Playing else TvColors.TextDisabled)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = TvType.Item,
            color = if (enabled) TvColors.TextPrimary else TvColors.TextDisabled,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = TvType.Aux,
                color = if (enabled) TvColors.TextSecondary else TvColors.TextDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
