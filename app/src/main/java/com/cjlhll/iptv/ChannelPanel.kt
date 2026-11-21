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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 左侧单容器导航的层。 */
enum class NavLayer { Channels, Groups, Programs, Dates }

/** 节目时间状态。 */
private enum class ProgramTimeState { Past, Now, Future }

/** EPG 状态（D08）：区分未配置/加载/就绪/空/失败/旧缓存。 */
enum class EpgStatus { UNCONFIGURED, LOADING, READY, EMPTY, FAILURE, STALE }

private fun programKey(p: EpgProgram): String = "${p.channelId}:${p.startMillis}:${p.endMillis}"

private val panelTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val panelDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

/** 逐层返回的父层映射：每次 Back 只退一层。 */
private fun parentOf(layer: NavLayer): NavLayer? = when (layer) {
    NavLayer.Channels -> null
    NavLayer.Groups -> NavLayer.Channels
    NavLayer.Programs -> NavLayer.Channels
    NavLayer.Dates -> NavLayer.Programs
}

/**
 * 单容器左侧导航面板：Channels / Groups / Programs / Dates 在同一 panelWidth 内替换内容，
 * 不横向追加列（src 勘察 P0：抽屉常态 633dp）。视频始终可见。
 *
 * 焦点规则：每层保存稳定锚点（频道 url、日期、节目 key），进入/返回时恢复到原项目。
 * Back 由内部 BackHandler 逐层消费，Activity 在无面板时处理退出。
 */
@Composable
fun ChannelPanel(
    visible: Boolean,
    panelWidth: Dp,
    groups: List<String>,
    selectedGroup: String,
    channels: List<NumberedChannel>,
    playingUrl: String?,
    epgData: EpgData?,
    epgStatus: EpgStatus,
    epgLastSuccessText: String?,
    nowMillis: Long,
    nowProgramByUrl: Map<String, NowProgramUi>,
    requestedLayer: NavLayer?,
    requestedChannelUrl: String? = null,
    requestedEntryGuide: Boolean = false,
    programClickMode: ProgramClickMode,
    onLayerChanged: (NavLayer) -> Unit,
    onSelectGroup: (String) -> Unit,
    onSelectChannel: (Channel) -> Unit,
    onPlayProgram: (CatchupPlayRequest) -> Unit,
    onNeedEpgConfig: () -> Unit,
    onRetryEpg: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = TvColors.Panel.copy(alpha = TvColors.PANEL_ALPHA)
    val shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)

    var layer by remember { mutableStateOf(NavLayer.Channels) }
    var focusedChannelUrl by remember { mutableStateOf<String?>(null) }
    var selectedDateEpochDay by remember { mutableStateOf<Long?>(null) }
    var pendingCatchup by remember { mutableStateOf<CatchupPlayRequest?>(null) }
    var focusGroupName by remember { mutableStateOf(selectedGroup) }
    var focusedProgramKey by remember { mutableStateOf<String?>(null) }
    var programAnchorKey by remember { mutableStateOf<String?>(null) }
    var programsFromGuide by remember { mutableStateOf(false) }

    // 外部请求切层（打开到指定层）
    LaunchedEffect(requestedLayer) {
        val target = requestedLayer ?: return@LaunchedEffect
        layer = target
        if (target == NavLayer.Programs) {
            programsFromGuide = requestedEntryGuide
            // R06：Guide 直接指定当前直播频道，忽略上次浏览焦点
            if (requestedEntryGuide && !requestedChannelUrl.isNullOrBlank()) {
                focusedChannelUrl = requestedChannelUrl
            }
        }
        if (target == NavLayer.Channels) programsFromGuide = false
    }
    LaunchedEffect(layer) { onLayerChanged(layer) }

    // 面板关闭时重置为根层，下次打开从 Channels 开始
    LaunchedEffect(visible) {
        if (!visible) {
            layer = NavLayer.Channels
            pendingCatchup = null
        } else if (focusedChannelUrl == null) {
            focusedChannelUrl = playingUrl ?: channels.firstOrNull()?.channel?.url
        }
    }

    // 当前焦点频道（Programs 依据它加载 EPG）
    val focusedChannel = remember(channels, focusedChannelUrl, playingUrl) {
        channels.firstOrNull { it.channel.url == (focusedChannelUrl ?: playingUrl) }?.channel
            ?: channels.firstOrNull()?.channel
    }

    // 该频道节目全集
    val epgPrograms: List<EpgProgram> = remember(epgData, focusedChannel?.url) {
        val data = epgData
        val ch = focusedChannel
        if (data == null || ch == null) emptyList()
        else {
            val id = data.resolveChannelId(ch)
            if (id == null) emptyList() else data.programsByChannelId[id].orEmpty()
        }
    }
    val zone: ZoneId = remember(epgPrograms) {
        val seconds = epgPrograms.firstOrNull { it.sourceOffsetSeconds != null }?.sourceOffsetSeconds
        if (seconds != null) java.time.ZoneOffset.ofTotalSeconds(seconds) else ZoneId.systemDefault()
    }
    val epgDates: List<LocalDate> = remember(epgPrograms, zone) {
        epgPrograms.asSequence().map { millisToLocalDate(it.startMillis, zone) }.distinct().sorted().toList()
    }
    val today: LocalDate = remember(nowMillis, zone) {
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    }
    LaunchedEffect(focusedChannel?.url, epgDates) {
        selectedDateEpochDay = when {
            epgDates.contains(today) -> today.toEpochDay()
            else -> epgDates.firstOrNull()?.toEpochDay()
        }
    }
    val selectedDate = epgDates.firstOrNull { it.toEpochDay() == selectedDateEpochDay }
    val programsForDate = remember(epgPrograms, selectedDate, zone) {
        val d = selectedDate ?: return@remember emptyList()
        val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        slicePrograms(epgPrograms, start, end)
    }
    val focusProgramIndex = remember(programsForDate, selectedDate, today, nowMillis) {
        if (programsForDate.isEmpty()) 0
        else if (selectedDate == today) indexOfProgramAt(programsForDate, nowMillis)
        else 0
    }
    // 从日期层返回时优先恢复到进入前的节目锚点（D07）
    val effectiveProgramFocusIndex = remember(programsForDate, programAnchorKey, focusProgramIndex) {
        val key = programAnchorKey
        if (key != null) {
            val i = programsForDate.indexOfFirst { programKey(it) == key }
            if (i >= 0) i else focusProgramIndex
        } else focusProgramIndex
    }

    // 焦点请求器
    val channelRequester = remember { FocusRequester() }
    val groupRequester = remember { FocusRequester() }
    val programRequester = remember { FocusRequester() }
    val dateRequester = remember { FocusRequester() }
    val emptyActionRequester = remember { FocusRequester() }

    val channelListState = rememberLazyListState()
    val groupListState = rememberLazyListState()
    val programListState = rememberLazyListState()
    val dateListState = rememberLazyListState()

    val targetChannelUrl = focusedChannelUrl ?: playingUrl
    val targetChannelIndex = remember(channels, targetChannelUrl) {
        channels.indexOfFirst { it.channel.url == targetChannelUrl }.takeIf { it >= 0 } ?: 0
    }
    val targetGroupIndex = remember(groups, focusGroupName, selectedGroup) {
        val fromFocus = groups.indexOf(focusGroupName)
        if (fromFocus >= 0) fromFocus else groups.indexOf(selectedGroup).coerceAtLeast(0)
    }
    val targetDateIndex = remember(epgDates, selectedDate) {
        epgDates.indexOf(selectedDate).takeIf { it >= 0 } ?: 0
    }

    // 进入某层后滚动到锚点并请求焦点；数据/状态替换后也重新请求（R05）
    LaunchedEffect(visible, layer, epgStatus, programsForDate.size) {
        if (!visible) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            val success = when (layer) {
                NavLayer.Channels -> {
                    if (channels.isEmpty()) {
                        runCatching { emptyActionRequester.requestFocus() }.isSuccess
                    } else {
                        runCatching { channelListState.scrollToItem((targetChannelIndex - 2).coerceAtLeast(0)) }
                        runCatching { channelRequester.requestFocus() }.isSuccess
                    }
                }
                NavLayer.Groups -> {
                    runCatching { groupListState.scrollToItem(targetGroupIndex) }
                    runCatching { groupRequester.requestFocus() }.isSuccess
                }
                NavLayer.Programs -> {
                    if (programsForDate.isEmpty()) {
                        runCatching { emptyActionRequester.requestFocus() }.isSuccess
                    } else {
                        runCatching { programListState.scrollToItem((effectiveProgramFocusIndex - 3).coerceAtLeast(0)) }
                        runCatching { programRequester.requestFocus() }.isSuccess
                    }
                }
                NavLayer.Dates -> {
                    runCatching { dateListState.scrollToItem(targetDateIndex) }
                    runCatching { dateRequester.requestFocus() }.isSuccess
                }
            }
            if (success) return@LaunchedEffect
        }
    }

    // 回看确认取消后：先滚动到进入前记录的节目，再请求焦点（S04）
    LaunchedEffect(pendingCatchup) {
        if (pendingCatchup == null && visible && layer == NavLayer.Programs) {
            val idx = programsForDate.indexOfFirst { programKey(it) == programAnchorKey }
            if (idx >= 0) runCatching { programListState.scrollToItem(idx) }
            repeat(3) {
                withFrameNanos { }
                if (runCatching { programRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }

    // Back 逐层消费；Guide 直接进入 Programs 时 Back 回视频（D07）
    BackHandler(enabled = visible) {
        when {
            pendingCatchup != null -> pendingCatchup = null
            layer == NavLayer.Programs && programsFromGuide -> onClose()
            layer != NavLayer.Channels -> {
                val parent = parentOf(layer)
                if (parent == null) onClose() else {
                    layer = parent
                    if (parent == NavLayer.Channels) programsFromGuide = false
                }
            }
            else -> onClose()
        }
    }

    val containerModifier = if (visible) {
        Modifier
            .fillMaxHeight()
            .padding(end = 0.dp)
            .then(if (pendingCatchup != null) Modifier.focusProperties { canFocus = false } else Modifier) // S04：确认模态时隔离底层
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (pendingCatchup != null) return@onPreviewKeyEvent false
                when (layer) {
                    NavLayer.Channels -> when (ev.key) {
                        Key.DirectionLeft -> { layer = NavLayer.Groups; true }
                        Key.DirectionRight -> { layer = NavLayer.Programs; programsFromGuide = false; true }
                        else -> false
                    }
                    NavLayer.Groups -> when (ev.key) {
                        Key.DirectionRight -> {
                            // 使用当前实际焦点分组，而非打开时的初始分组（D07）
                            onSelectGroup(focusGroupName)
                            layer = NavLayer.Channels
                            true
                        }
                        Key.DirectionLeft -> true
                        else -> false
                    }
                    NavLayer.Programs -> when (ev.key) {
                        Key.DirectionLeft -> { layer = NavLayer.Channels; programsFromGuide = false; true }
                        Key.DirectionRight -> {
                            if (epgDates.isEmpty()) {
                                Toast.makeText(context, "暂无可选日期", Toast.LENGTH_SHORT).show()
                                true
                            } else {
                                programAnchorKey = focusedProgramKey // 记录进入日期前的节目锚点
                                layer = NavLayer.Dates
                                true
                            }
                        }
                        else -> false
                    }
                    NavLayer.Dates -> when (ev.key) {
                        Key.DirectionLeft -> { layer = NavLayer.Programs; true }
                        Key.DirectionRight -> true
                        else -> false
                    }
                }
            }
    } else Modifier

    Box(modifier = modifier.fillMaxSize()) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(160), initialOffsetX = { -it }) + fadeIn(tween(120)),
        exit = slideOutHorizontally(tween(140), targetOffsetX = { -it }) + fadeOut(tween(100)),
        modifier = Modifier.fillMaxHeight(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(panelWidth)
                .background(container, shape)
                .then(containerModifier)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Column(modifier = Modifier.fillMaxHeight()) {
                when (layer) {
                    NavLayer.Channels -> ChannelLayer(
                        channels = channels,
                        playingUrl = playingUrl,
                        focusedUrl = focusedChannelUrl,
                        groupName = selectedGroup,
                        nowProgramByUrl = nowProgramByUrl,
                        requester = channelRequester,
                        focusIndex = targetChannelIndex,
                        emptyRequester = emptyActionRequester,
                        listState = channelListState,
                        onFocused = { url -> focusedChannelUrl = url },
                        onClick = { channel ->
                            focusedChannelUrl = channel.url
                            onSelectChannel(channel)
                            onClose()
                        },
                        onRetry = onOpenSettings,
                    )
                    NavLayer.Groups -> GroupLayer(
                        groups = groups,
                        selectedGroup = selectedGroup,
                        focusIndex = targetGroupIndex,
                        requester = groupRequester,
                        listState = groupListState,
                        onFocused = { name -> focusGroupName = name },
                        onClick = { name ->
                            onSelectGroup(name)
                            layer = NavLayer.Channels
                        },
                    )
                    NavLayer.Programs -> ProgramLayer(
                        title = focusedChannel?.let { EpgNormalize.displayName(it.title) } ?: "节目单",
                        dateText = selectedDate?.let { panelDateFormatter.format(it) } ?: "",
                        programs = programsForDate,
                        zone = zone,
                        nowMillis = nowMillis,
                        focusIndex = effectiveProgramFocusIndex,
                        requester = programRequester,
                        emptyRequester = emptyActionRequester,
                        listState = programListState,
                        epgStatus = epgStatus,
                        epgLastSuccessText = epgLastSuccessText,
                        hasAnyPrograms = epgPrograms.isNotEmpty(),
                        canCatchup = focusedChannel?.let { ch ->
                            CatchupHelper.buildCatchupUrl(
                                liveUrl = ch.url,
                                modeRaw = ch.catchupMode,
                                sourceTemplate = ch.catchupSource,
                                start = Instant.ofEpochMilli((nowMillis - 3_600_000L).coerceAtLeast(0L)),
                                stop = Instant.ofEpochMilli(nowMillis),
                            ) != null
                        } == true,
                        onFocusedProgram = { key -> focusedProgramKey = key },
                        onBackToChannels = { layer = NavLayer.Channels; programsFromGuide = false },
                        onNeedEpgConfig = onNeedEpgConfig,
                        onRetryEpg = onRetryEpg,
                        onProgramClick = { program ->
                            handleProgramClick(
                                program = program,
                                channel = focusedChannel,
                                nowMillis = nowMillis,
                                programClickMode = programClickMode,
                                onPlayProgram = { req -> onPlayProgram(req) },
                                onSwitchLive = { ch -> onSelectChannel(ch); onClose() },
                                onPendingConfirm = { req ->
                                    // S04：进入模态前记录历史节目的稳定 key，供取消后恢复
                                    programAnchorKey = programKey(program)
                                    focusedProgramKey = programKey(program)
                                    pendingCatchup = req
                                },
                                context = context,
                            )
                        },
                    )
                    NavLayer.Dates -> DateLayer(
                        dates = epgDates,
                        today = today,
                        selectedDate = selectedDate,
                        focusIndex = targetDateIndex,
                        requester = dateRequester,
                        listState = dateListState,
                        onClick = { date ->
                            selectedDateEpochDay = date.toEpochDay()
                            layer = NavLayer.Programs
                        },
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = hintFor(layer),
                    style = TvType.Aux,
                    color = TvColors.TextDisabled,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    // 回看确认对话框（A06：“确认后回看”可取消，初始焦点在开始回看）
    pendingCatchup?.let { req ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ConfirmCatchupOverlay(
                request = req,
                onConfirm = {
                    pendingCatchup = null
                    onPlayProgram(req)
                },
                onCancel = { pendingCatchup = null },
            )
        }
    }
    }
}

private fun hintFor(layer: NavLayer): String = when (layer) {
    NavLayer.Channels -> "左：分组  右：节目单  OK：切换频道  Back：返回"
    NavLayer.Groups -> "OK：选择分组  Back：返回频道"
    NavLayer.Programs -> "左：频道  右：日期  OK：回看/切换  Back：返回"
    NavLayer.Dates -> "OK：选择日期  Back：返回节目"
}

@Composable
private fun ChannelLayer(
    channels: List<NumberedChannel>,
    playingUrl: String?,
    focusedUrl: String?,
    groupName: String,
    nowProgramByUrl: Map<String, NowProgramUi>,
    requester: FocusRequester,
    focusIndex: Int,
    emptyRequester: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onFocused: (String) -> Unit,
    onClick: (Channel) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Text("频道", style = TvType.SectionTitle, color = TvColors.TextPrimary)
        Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(
            text = "$groupName · ${channels.size} 个频道",
            style = TvType.Aux,
            color = TvColors.TextSecondary,
        )
        Spacer(modifier = Modifier.padding(top = 8.dp))
        if (channels.isEmpty()) {
            EmptyStateAction(
                text = "该分组无频道，重新加载",
                requester = emptyRequester,
                onClick = onRetry,
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxHeight()) {
                itemsIndexed(channels, key = { _, n -> n.channel.url }) { index, numbered ->
                    ChannelRow(
                        numbered = numbered,
                        playing = numbered.channel.url == playingUrl,
                        focusRequester = if (index == focusIndex) requester else null,
                        nowProgram = nowProgramByUrl[numbered.channel.url],
                        onFocused = { onFocused(numbered.channel.url) },
                        onClick = { onClick(numbered.channel) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    numbered: NumberedChannel,
    playing: Boolean,
    focusRequester: FocusRequester?,
    nowProgram: NowProgramUi?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .background(if (focused) TvColors.FocusFill else Color.Transparent, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .focusable()
            .clickable { onClick() }
            .heightIn(min = TvDims.ChannelRowMinHeight)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(4.dp).fillMaxHeight()) {
            if (playing) {
                Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(TvColors.Playing, RoundedCornerShape(2.dp)))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        ChannelLogo(
            logoUrl = numbered.channel.logoUrl,
            fallbackTitle = numbered.channel.title,
            modifier = Modifier.size(width = 48.dp, height = 32.dp),
            width = 48.dp,
            height = 32.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${numbered.number}. ${numbered.channel.title}",
                    style = TvType.Item,
                    color = TvColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (playing) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("播放中", style = TvType.Aux, color = TvColors.Playing)
                }
            }
            Text(
                text = nowProgram?.title?.takeIf { it.isNotBlank() }?.let { "正在播放 $it" } ?: "暂无节目信息",
                style = TvType.Aux,
                color = if (nowProgram?.title.isNullOrBlank()) TvColors.TextDisabled else TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = nowProgram?.nextTitle?.takeIf { it.isNotBlank() }?.let { "接下来 $it" } ?: "接下来 暂无后续节目信息",
                style = TvType.Aux,
                color = if (nowProgram?.nextTitle.isNullOrBlank()) TvColors.TextDisabled else TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupLayer(
    groups: List<String>,
    selectedGroup: String,
    focusIndex: Int,
    requester: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onFocused: (String) -> Unit,
    onClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Text("频道分组", style = TvType.SectionTitle, color = TvColors.TextPrimary)
        Spacer(modifier = Modifier.padding(top = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight()) {
            itemsIndexed(groups, key = { _, g -> g }) { index, name ->
                val selected = name == selectedGroup
                GroupRow(
                    name = name,
                    selected = selected,
                    focusRequester = if (index == focusIndex) requester else null,
                    onFocused = { onFocused(name) },
                    onClick = { onClick(name) },
                )
            }
        }
    }
}

@Composable
private fun GroupRow(
    name: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .background(if (focused) TvColors.FocusFill else Color.Transparent, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .clickable { onClick() }
            .heightIn(min = TvDims.SettingsRowMinHeight)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "✓ $name" else name,
            style = TvType.Item,
            color = if (selected) TvColors.Playing else TvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProgramLayer(
    title: String,
    dateText: String,
    programs: List<EpgProgram>,
    zone: ZoneId,
    nowMillis: Long,
    focusIndex: Int,
    requester: FocusRequester,
    emptyRequester: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    epgStatus: EpgStatus,
    epgLastSuccessText: String?,
    hasAnyPrograms: Boolean,
    canCatchup: Boolean,
    onFocusedProgram: (String) -> Unit,
    onBackToChannels: () -> Unit,
    onNeedEpgConfig: () -> Unit,
    onRetryEpg: () -> Unit,
    onProgramClick: (EpgProgram) -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Text(title, style = TvType.SectionTitle, color = TvColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.padding(top = 4.dp))
        Text(dateText, style = TvType.Aux, color = TvColors.TextSecondary)
        if (epgStatus == EpgStatus.STALE) {
            Text(
                text = "更新失败，显示上次节目单" + (epgLastSuccessText?.let { "（$it）" } ?: ""),
                style = TvType.Aux,
                color = TvColors.Error,
            )
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        when {
            epgStatus == EpgStatus.UNCONFIGURED -> EmptyStateAction(
                text = "尚未配置EPG，按确定配置",
                requester = emptyRequester,
                onClick = onNeedEpgConfig,
            )
            epgStatus == EpgStatus.LOADING -> CenteredNote("正在加载节目单…")
            epgStatus == EpgStatus.FAILURE -> EmptyStateAction(
                text = "加载失败，按确定重试节目单",
                requester = emptyRequester,
                onClick = onRetryEpg,
            )
            programs.isEmpty() && hasAnyPrograms -> EmptyStateAction(
                text = "本日暂无节目，返回频道",
                requester = emptyRequester,
                onClick = onBackToChannels,
            )
            programs.isEmpty() -> EmptyStateAction(
                text = "此频道暂无节目单，返回频道",
                requester = emptyRequester,
                onClick = onBackToChannels,
            )
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxHeight()) {
                itemsIndexed(programs, key = { _, p -> programKey(p) }) { index, p ->
                    val state = when {
                        p.endMillis <= nowMillis -> ProgramTimeState.Past
                        p.startMillis <= nowMillis && nowMillis < p.endMillis -> ProgramTimeState.Now
                        else -> ProgramTimeState.Future
                    }
                    ProgramRow(
                        time = formatTimeRange(p.startMillis, p.endMillis, zone),
                        title = p.title,
                        state = state,
                        canCatchup = canCatchup,
                        focusRequester = if (index == focusIndex) requester else null,
                        onFocused = { onFocusedProgram(programKey(p)) },
                        onClick = { onProgramClick(p) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgramRow(
    time: String,
    title: String,
    state: ProgramTimeState,
    canCatchup: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    val textColor = when (state) {
        ProgramTimeState.Past -> TvColors.TextSecondary
        ProgramTimeState.Now -> TvColors.Playing
        ProgramTimeState.Future -> TvColors.TextPrimary
    }
    val stateLabel = when (state) {
        ProgramTimeState.Past -> if (canCatchup) "可回看" else "已结束"
        ProgramTimeState.Now -> "直播中"
        ProgramTimeState.Future -> "未开始"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .background(if (focused) TvColors.FocusFill else Color.Transparent, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .clickable { onClick() }
            .heightIn(min = TvDims.ProgramRowMinHeight)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(time, style = TvType.Aux, color = textColor)
            Text(title, style = TvType.Body, color = textColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(stateLabel, style = TvType.Aux, color = textColor)
    }
}

@Composable
private fun DateLayer(
    dates: List<LocalDate>,
    today: LocalDate,
    selectedDate: LocalDate?,
    focusIndex: Int,
    requester: FocusRequester,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onClick: (LocalDate) -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Text("日期", style = TvType.SectionTitle, color = TvColors.TextPrimary)
        Spacer(modifier = Modifier.padding(top = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight()) {
            itemsIndexed(dates, key = { _, d -> d.toEpochDay() }) { index, d ->
                val selected = d == selectedDate
                val label = when {
                    d == today -> "今天 ${panelDateFormatter.format(d)}"
                    else -> panelDateFormatter.format(d)
                }
                GroupRow(
                    name = if (selected) "$label（当前）" else label,
                    selected = selected,
                    focusRequester = if (index == focusIndex) requester else null,
                    onFocused = {},
                    onClick = { onClick(d) },
                )
            }
        }
    }
}

@Composable
private fun EmptyStateAction(text: String, requester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .background(if (focused) TvColors.FocusFill else TvColors.Panel, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .heightIn(min = TvDims.SettingsRowMinHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = TvType.Item, color = TvColors.TextPrimary)
    }
}

@Composable
private fun CenteredNote(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = TvType.Body, color = TvColors.TextSecondary)
    }
}

@Composable
private fun ConfirmCatchupOverlay(
    request: CatchupPlayRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val confirmRequester = remember { FocusRequester() }
    LaunchedEffect(request) { runCatching { confirmRequester.requestFocus() } }
    BackHandler { onCancel() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .background(TvColors.Panel.copy(alpha = 0.98f), RoundedCornerShape(12.dp))
                .border(1.dp, TvColors.FocusBorder, RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Text("开始回看？", style = TvType.SectionTitle, color = TvColors.TextPrimary)
            Spacer(modifier = Modifier.padding(top = 6.dp))
            Text(request.programTitle, style = TvType.Body, color = TvColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.padding(top = 16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmButton("开始回看", confirmRequester, onConfirm)
                ConfirmButton("取消", null, onCancel)
            }
        }
    }
}

@Composable
private fun ConfirmButton(text: String, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvDims.CardCorner)
    Box(
        modifier = Modifier
            .let { if (requester != null) it.focusRequester(requester) else it }
            .background(if (focused) TvColors.FocusFill else TvColors.Panel, shape)
            .then(if (focused) Modifier.border(2.dp, TvColors.FocusBorder, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .heightIn(min = TvDims.SettingsRowMinHeight)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text, style = TvType.Item, color = TvColors.TextPrimary)
    }
}

private fun handleProgramClick(
    program: EpgProgram,
    channel: Channel?,
    nowMillis: Long,
    programClickMode: ProgramClickMode,
    onPlayProgram: (CatchupPlayRequest) -> Unit,
    onSwitchLive: (Channel) -> Unit,
    onPendingConfirm: (CatchupPlayRequest) -> Unit,
    context: android.content.Context,
) {
    when {
        program.endMillis <= nowMillis -> {
            if (channel == null || channel.catchupMode.isNullOrBlank()) {
                Toast.makeText(context, "该频道不支持回看", Toast.LENGTH_SHORT).show()
                return
            }
            val url = CatchupHelper.buildCatchupUrl(
                liveUrl = channel.url,
                modeRaw = channel.catchupMode,
                sourceTemplate = channel.catchupSource,
                start = Instant.ofEpochMilli(program.startMillis),
                stop = Instant.ofEpochMilli(program.endMillis),
            )
            if (url == null) {
                Toast.makeText(context, "回看地址生成失败", Toast.LENGTH_SHORT).show()
                return
            }
            val req = CatchupPlayRequest(
                catchupUrl = url,
                liveUrl = channel.url,
                programTitle = program.title,
                startMillis = program.startMillis,
                endMillis = program.endMillis,
            )
            if (programClickMode == ProgramClickMode.CONFIRM) onPendingConfirm(req) else onPlayProgram(req)
        }
        program.startMillis <= nowMillis && nowMillis < program.endMillis -> {
            if (channel != null) onSwitchLive(channel)
        }
        else -> Toast.makeText(context, "节目尚未开始", Toast.LENGTH_SHORT).show()
    }
}

private fun millisToLocalDate(millis: Long, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

private fun formatTimeRange(startMillis: Long, endMillis: Long, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalTime()
    return "${panelTimeFormatter.format(start)}-${panelTimeFormatter.format(end)}"
}

private fun indexOfProgramAt(programs: List<EpgProgram>, nowMillis: Long): Int {
    if (programs.isEmpty()) return 0
    var lo = 0
    var hi = programs.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val p = programs[mid]
        if (nowMillis < p.startMillis) hi = mid - 1
        else if (nowMillis >= p.endMillis) lo = mid + 1
        else return mid
    }
    return lo.coerceIn(0, programs.lastIndex)
}

private fun lowerBoundStart(programs: List<EpgProgram>, value: Long): Int {
    var lo = 0
    var hi = programs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (programs[mid].startMillis < value) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun slicePrograms(programs: List<EpgProgram>, rangeStart: Long, rangeEnd: Long): List<EpgProgram> {
    if (programs.isEmpty()) return emptyList()
    val endIndex = lowerBoundStart(programs, rangeEnd)
    var startIndex = (lowerBoundStart(programs, rangeStart) - 1).coerceAtLeast(0)
    if (startIndex > endIndex) startIndex = endIndex
    if (startIndex == endIndex) return emptyList()
    return programs.subList(startIndex, endIndex).filter { it.endMillis > rangeStart }
}
