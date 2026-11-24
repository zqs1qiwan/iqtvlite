package com.cjlhll.iptv

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class DrawerColumn {
    Sources,
    Groups,
    Channels,
    Programs,
    Dates
}

private data class ProgramWindow(
    val programs: List<EpgProgram>,
    val focusIndex: Int
)

private data class EpgChannelUiData(
    val zone: ZoneId,
    val programs: List<EpgProgram>,
    val dates: List<LocalDate>
)

private val epgTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val epgDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

private fun zoneForPrograms(@Suppress("UNUSED_PARAMETER") programs: List<EpgProgram>): ZoneId =
    ZoneId.systemDefault()

private fun zoneForProgram(@Suppress("UNUSED_PARAMETER") program: EpgProgram, fallback: ZoneId): ZoneId =
    ZoneId.systemDefault() ?: fallback

/**
 * 选台抽屉：
 * 1. **真·焦点恒定居中**：通过 `BoxWithConstraints` 测量真实视口高度并设置上下半屏
 *    `contentPadding = (viewportH - rowH) / 2`，使包括第 0 项与末项在内的所有频道都能精确落到
 *    可视区正中，固定中心高亮框与真实焦点行 0px 偏差（小分组自动退化为自然顶对齐）；
 * 2. **彻底删除 `subList(0, revealedRows)` 截断补丁**：`LazyColumn` 原生按视口复用，从根源消除
 *    第 0 项按 UP 首尾循环死锁与快速连按撞墙问题；
 * 3. **确定性键盘闭环**：支持 Activity 兜底分发，即使动画/空 EPG 瞬时失焦也不死锁；
 * 4. **视觉高级统一**：曜石深底 + 静态 `graphicsLayer` 透明度 + 节目区半透明分区（无分割线）+
 *    清晰灰白磨砂焦点底 + 品牌蓝正在播放标识。
 */
@OptIn(FlowPreview::class)
@Composable
fun PlayerDrawer(
    visible: Boolean,
    groups: List<String>,
    selectedGroup: String,
    channels: List<Channel>,
    selectedChannelUrl: String?,
    channelNumbers: Map<String, Int> = emptyMap(),
    nowProgramByChannelUrl: Map<String, NowProgramUi> = emptyMap(),
    epgData: EpgData? = null,
    epgProgramProvider: (suspend (Channel) -> List<EpgProgram>)? = null,
    nowMillis: Long = 0L,
    epgSourceConfigured: Boolean = true,
    epgLoadFailed: Boolean = false,
    onSelectGroup: (String) -> Unit,
    onSelectGroupReset: (String) -> Unit = onSelectGroup,
    onSelectChannel: (Channel) -> Unit,
    onPlayProgram: (CatchupPlayRequest) -> Unit,
    onClose: () -> Unit,
    onUserInteraction: () -> Unit = {},
    onActiveColumnChanged: (DrawerColumn) -> Unit = {},
    requestedActiveColumn: DrawerColumn? = null,
    initialFocusUrl: String? = null,
    onFocusedChannelChanged: (String) -> Unit = {},
    onFallbackKeyHandlerReady: (((Int) -> Boolean)?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefetchDensity = LocalDensity.current

    var showGroups by remember { mutableStateOf(false) }
    var showDates by remember { mutableStateOf(false) }
    var activeColumn by remember { mutableStateOf(DrawerColumn.Channels) }

    val selectedGroupRequester = remember { FocusRequester() }
    val selectedChannelRequester = remember { FocusRequester() }
    val selectedProgramRequester = remember { FocusRequester() }
    val selectedDateRequester = remember { FocusRequester() }

    var pendingFocusToGroups by remember { mutableStateOf(false) }
    var pendingFocusToChannels by remember { mutableStateOf(false) }
    var pendingFocusToPrograms by remember { mutableStateOf(false) }
    var pendingFocusToDates by remember { mutableStateOf(false) }

    LaunchedEffect(activeColumn) {
        onActiveColumnChanged(activeColumn)
    }

    LaunchedEffect(requestedActiveColumn) {
        val target = requestedActiveColumn ?: return@LaunchedEffect
        if (activeColumn != target) {
            activeColumn = target
            when (target) {
                DrawerColumn.Sources, DrawerColumn.Groups -> pendingFocusToGroups = true
                DrawerColumn.Channels -> pendingFocusToChannels = true
                DrawerColumn.Programs -> pendingFocusToPrograms = true
                DrawerColumn.Dates -> pendingFocusToDates = true
            }
        }
    }

    var focusedChannelUrl by remember { mutableStateOf<String?>(null) }
    var stableFocusedChannelUrl by remember { mutableStateOf<String?>(null) }
    var groupChangedFlag by remember { mutableStateOf(false) }
    var isFocusRestoring by remember { mutableStateOf(false) }
    var autoProgramScrollKey by remember { mutableStateOf<String?>(null) }

    val visibleValue by rememberUpdatedState(visible)

    val epgChannelUiCache = remember(epgData) {
        object : LinkedHashMap<String, EpgChannelUiData>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpgChannelUiData>?): Boolean {
                return size > 32
            }
        }
    }

    // 浏览焦点防抖 220ms 后更新右侧 EPG（响应比旧 350ms 更轻快，同时连按不卡顿）。
    LaunchedEffect(Unit) {
        snapshotFlow { focusedChannelUrl }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(220)
            .collectLatest { url ->
                if (!visibleValue) return@collectLatest
                if (stableFocusedChannelUrl != url) stableFocusedChannelUrl = url
            }
    }

    // 性能埋点（保留 IQTV-PERF 日志供真机 framestats/打开耗时对照）
    val perfOpenAt = remember { longArrayOf(0L) }
    val perfCloseAt = remember { longArrayOf(0L) }
    LaunchedEffect(visible) {
        if (visible) {
            perfOpenAt[0] = android.os.SystemClock.elapsedRealtime()
            perfCloseAt[0] = 0L
            autoProgramScrollKey = null
        } else {
            LogoLoader.logPerfCounters(drawerOpen = false)
            perfCloseAt[0] = android.os.SystemClock.elapsedRealtime()
            withFrameNanos { }
            PerfTrace.d { "drawer_close ms=${android.os.SystemClock.elapsedRealtime() - perfCloseAt[0]}" }
        }
    }

    LaunchedEffect(visible, channels.size) {
        if (!visible) return@LaunchedEffect
        withFrameNanos { }
        val started = perfOpenAt[0]
        if (started > 0L) {
            val ms = android.os.SystemClock.elapsedRealtime() - started
            PerfTrace.d {
                "drawer_open ms=$ms total=${channels.size} groups=${groups.size} group=$selectedGroup"
            }
        }
    }

    val groupFocusTargetIndex = remember(groups, selectedGroup) {
        groups.indexOf(selectedGroup).coerceAtLeast(0)
    }
    val groupFocusTargetName = remember(groups, groupFocusTargetIndex) {
        groups.getOrNull(groupFocusTargetIndex)
    }

    val channelIndexByUrl = remember(channels) {
        HashMap<String, Int>(minOf(channels.size * 2, 4096)).apply {
            for (i in channels.indices) {
                putIfAbsent(channels[i].url, i)
            }
        }
    }

    // 稳定且唯一的 LazyColumn key（URL 唯一时直接用 URL，遇到重复 URL 自动加后缀，不依赖位次移动）
    val stableRowKeys = remember(channels) {
        val seen = HashMap<String, Int>(channels.size * 2)
        channels.map { ch ->
            val count = (seen[ch.url] ?: 0) + 1
            seen[ch.url] = count
            if (count == 1) ch.url else "${ch.url}#$count"
        }
    }

    val focusTargetIndex = remember(channelIndexByUrl, selectedChannelUrl, focusedChannelUrl, groupChangedFlag) {
        val fUrl = focusedChannelUrl
        if (!fUrl.isNullOrBlank()) {
            channelIndexByUrl[fUrl]?.let { return@remember it }
        }
        if (groupChangedFlag && channelIndexByUrl.isNotEmpty()) {
            return@remember 0
        }
        val sUrl = selectedChannelUrl
        if (!sUrl.isNullOrBlank()) {
            channelIndexByUrl[sUrl]?.let { return@remember it }
        }
        if (channelIndexByUrl.isNotEmpty()) 0 else -1
    }

    val focusTargetUrl = remember(channels, focusTargetIndex) {
        channels.getOrNull(focusTargetIndex)?.url
    }

    // 打开瞬间冻结定位锚点：当前播放台第一优先（若在当前分组中），否则用浏览记忆，否则首台。
    val openAnchor = remember(visible) {
        if (visible) (initialFocusUrl ?: selectedChannelUrl) else null
    }
    val openSessionFocusUrl = remember(visible, selectedGroup, channels.isEmpty()) {
        if (!visible) {
            null
        } else {
            PerfPolicy.drawerOpenPositionAnchor(
                playingUrl = selectedChannelUrl,
                browseAnchor = openAnchor,
                knownUrls = channelIndexByUrl.keys,
            ) ?: channels.firstOrNull()?.url
        }
    }
    val openSessionFocusIndex = remember(openSessionFocusUrl, channelIndexByUrl) {
        openSessionFocusUrl?.let { channelIndexByUrl[it] } ?: 0
    }

    // 每次关→开或切组重建 LazyListState，首帧即用 initialFirstVisibleItemIndex = focusIndex 居中落位。
    val channelListState = key(visible, selectedGroup) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = openSessionFocusIndex.coerceAtLeast(0),
            initialFirstVisibleItemScrollOffset = 0,
        )
    }
    val groupListState = rememberLazyListState()
    val programListState = rememberLazyListState()
    val dateListState = rememberLazyListState()

    // 唯一焦点居中滚动点：因为上下半屏 contentPadding == (viewportH - rowH)/2，
    // scrollToItem(focusTargetIndex, 0) 会让 0..N-1 的每一行都精确居中（0px 误差）。
    LaunchedEffect(visible, focusTargetIndex, channels.size) {
        if (!visible || focusTargetIndex < 0 || focusTargetIndex >= channels.size) return@LaunchedEffect
        channelListState.scrollToItem(index = focusTargetIndex, scrollOffset = 0)
        PerfTrace.d { "drawer_focus_locate focus=$focusTargetIndex total=${channels.size}" }
    }

    // 抽屉开合同步重置状态（彻底消除旧 delay(400) 导致的快速关开残态）
    LaunchedEffect(visible) {
        if (!visible) {
            isFocusRestoring = false
            showGroups = false
            showDates = false
            activeColumn = DrawerColumn.Channels
            pendingFocusToGroups = false
            pendingFocusToChannels = false
            pendingFocusToPrograms = false
            pendingFocusToDates = false
            return@LaunchedEffect
        }

        showGroups = false
        showDates = false
        activeColumn = DrawerColumn.Channels
        groupChangedFlag = false

        val targetUrl = openSessionFocusUrl
        if (targetUrl != null) {
            focusedChannelUrl = targetUrl
            stableFocusedChannelUrl = targetUrl
        }

        if (channels.isEmpty()) return@LaunchedEffect

        isFocusRestoring = true
        repeat(15) {
            if (!visibleValue || activeColumn != DrawerColumn.Channels) {
                isFocusRestoring = false
                return@LaunchedEffect
            }
            withFrameNanos { }
            if (runCatching { selectedChannelRequester.requestFocus() }.isSuccess) {
                isFocusRestoring = false
                return@LaunchedEffect
            }
        }
        isFocusRestoring = false
    }

    LaunchedEffect(selectedGroup) {
        if (!visible) return@LaunchedEffect
        groupChangedFlag = true
        val remembered = initialFocusUrl?.takeIf { channelIndexByUrl.containsKey(it) }
        val playingInGroup = selectedChannelUrl?.takeIf { channelIndexByUrl.containsKey(it) }
        val nextUrl = remembered ?: playingInGroup ?: channels.firstOrNull()?.url
        focusedChannelUrl = nextUrl
        if (nextUrl != null) stableFocusedChannelUrl = nextUrl
    }

    // 台标按可视窗口延后预取（不与开抽屉首帧抢 CPU）
    LaunchedEffect(visible, selectedGroup, channels, selectedChannelUrl) {
        if (!visible) return@LaunchedEffect
        val pxW = LogoLoader.CANONICAL_WIDTH_PX
        val pxH = LogoLoader.CANONICAL_HEIGHT_PX
        snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collectLatest { visibleIdx ->
                delay(PerfPolicy.URGENT_PREFETCH_DELAY_MS)
                val selectedIdx = channels.indexOfFirst { it.url == selectedChannelUrl }
                    .let { if (it >= 0) it else channelListState.firstVisibleItemIndex }
                val urgent = PerfPolicy.urgentPrefetchIndices(
                    selectedIndex = selectedIdx,
                    visibleIndices = visibleIdx,
                    channelCount = channels.size,
                )
                val urgentUrls = urgent
                    .mapNotNull { channels.getOrNull(it)?.logoUrl?.trim()?.takeIf { u -> u.isNotBlank() } }
                    .distinct()
                if (urgentUrls.isNotEmpty()) {
                    LogoLoader.prefetch(context, urgentUrls, pxW, pxH)
                }

                val filler = LinkedHashSet<Int>()
                for (i in (selectedIdx - 24)..(selectedIdx + 24)) if (i in channels.indices) filler.add(i)
                val fillerUrls = filler.filterNot { it in urgent }
                    .mapNotNull { channels[it].logoUrl?.trim()?.takeIf { u -> u.isNotBlank() } }
                    .distinct()
                    .filterNot { it in urgentUrls }
                if (fillerUrls.isNotEmpty()) {
                    delay(900)
                    LogoLoader.prefetchBackground(context, fillerUrls, pxW, pxH)
                }
            }
    }

    // 分组列焦点迁移
    LaunchedEffect(pendingFocusToGroups, visible, groupFocusTargetName, groupFocusTargetIndex) {
        if (!pendingFocusToGroups || !visible) return@LaunchedEffect
        if (!showGroups || groupFocusTargetName == null) {
            pendingFocusToGroups = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        runCatching { groupListState.scrollToItem(groupFocusTargetIndex) }
        repeat(4) {
            withFrameNanos { }
            if (runCatching { selectedGroupRequester.requestFocus() }.isSuccess) {
                pendingFocusToGroups = false
                return@LaunchedEffect
            }
        }
        pendingFocusToGroups = false
    }

    // 频道列焦点迁移
    LaunchedEffect(pendingFocusToChannels, visible, focusTargetUrl, focusTargetIndex) {
        if (!pendingFocusToChannels || !visible) return@LaunchedEffect
        if (focusTargetUrl == null) {
            pendingFocusToChannels = false
            return@LaunchedEffect
        }
        repeat(4) {
            withFrameNanos { }
            if (runCatching { selectedChannelRequester.requestFocus() }.isSuccess) {
                pendingFocusToChannels = false
                return@LaunchedEffect
            }
        }
        pendingFocusToChannels = false
    }

    val epgDataChannel = remember(channels, stableFocusedChannelUrl, selectedChannelUrl) {
        val url = stableFocusedChannelUrl ?: selectedChannelUrl
        channels.firstOrNull { it.url == url } ?: channels.firstOrNull()
    }

    val epgChannelData by produceState<EpgChannelUiData?>(
        initialValue = epgDataChannel?.url?.let { epgChannelUiCache[it] },
        key1 = epgData,
        key2 = epgDataChannel,
        key3 = epgProgramProvider
    ) {
        val ch = epgDataChannel
        if (ch == null) {
            value = null
            return@produceState
        }
        val cacheKey = ch.url
        epgChannelUiCache[cacheKey]?.let {
            value = it
            return@produceState
        }
        val data = epgData
        if (data == null && epgProgramProvider == null) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val programs = if (epgProgramProvider != null) {
                epgProgramProvider(ch)
            } else {
                val channelId = data?.resolveChannelId(ch)
                if (channelId != null) data.programsByChannelId[channelId].orEmpty() else emptyList()
            }
            if (programs.isEmpty()) {
                return@withContext EpgChannelUiData(
                    zone = ZoneId.systemDefault(),
                    programs = emptyList(),
                    dates = emptyList()
                )
            }
            val zone = zoneForPrograms(programs)
            val dates = programs
                .asSequence()
                .map { millisToLocalDate(it.startMillis, zone) }
                .distinct()
                .sorted()
                .toList()
            EpgChannelUiData(zone = zone, programs = programs, dates = dates)
        }
        value?.let { epgChannelUiCache[cacheKey] = it }
    }

    val fullPrograms = epgChannelData?.programs.orEmpty()
    val zone = epgChannelData?.zone ?: ZoneId.systemDefault()
    val todayDate = remember(nowMillis, zone) {
        Instant.ofEpochMilli(if (nowMillis > 0L) nowMillis else System.currentTimeMillis())
            .atZone(zone)
            .toLocalDate()
    }
    val epgDates = epgChannelData?.dates.orEmpty()
    var selectedEpgDate by remember { mutableStateOf<LocalDate?>(null) }

    LaunchedEffect(visible) {
        if (!visible) selectedEpgDate = null
    }
    LaunchedEffect(focusedChannelUrl) {
        selectedEpgDate = null
    }
    LaunchedEffect(epgDates, todayDate, focusedChannelUrl, visible) {
        if (!visible) return@LaunchedEffect
        val selected = selectedEpgDate
        if (selected != null && epgDates.contains(selected)) return@LaunchedEffect
        selectedEpgDate = if (epgDates.contains(todayDate)) todayDate else epgDates.firstOrNull()
    }

    val dateFocusTargetIndex = remember(epgDates, selectedEpgDate) {
        val d = selectedEpgDate ?: return@remember 0
        epgDates.indexOf(d).coerceAtLeast(0)
    }

    val programsBySelectedDate = remember(fullPrograms, selectedEpgDate, zone) {
        val d = selectedEpgDate ?: return@remember emptyList()
        val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        sliceProgramsOverlapping(fullPrograms, dayStart, dayEnd)
    }

    val programWindow = remember(programsBySelectedDate, nowMillis, selectedEpgDate, todayDate) {
        if (programsBySelectedDate.isEmpty()) {
            ProgramWindow(emptyList(), 0)
        } else {
            val effectiveNow = if (nowMillis > 0L) nowMillis else System.currentTimeMillis()
            val focusIdx = if (selectedEpgDate == todayDate) {
                indexOfProgramAt(programsBySelectedDate, effectiveNow)
            } else 0
            ProgramWindow(programsBySelectedDate, focusIdx)
        }
    }

    val programScrollKey = remember(epgDataChannel?.url, selectedEpgDate) {
        val url = epgDataChannel?.url ?: return@remember null
        "$url|${selectedEpgDate ?: ""}"
    }

    LaunchedEffect(visible, programScrollKey, programWindow.focusIndex, programWindow.programs.size) {
        if (!visible) return@LaunchedEffect
        val k = programScrollKey ?: return@LaunchedEffect
        if (programWindow.programs.isEmpty() || autoProgramScrollKey == k) return@LaunchedEffect
        delay(60)
        runCatching { programListState.scrollToItem((programWindow.focusIndex - 3).coerceAtLeast(0)) }
        autoProgramScrollKey = k
    }

    LaunchedEffect(pendingFocusToPrograms, visible, programWindow.focusIndex, programWindow.programs.size) {
        if (!pendingFocusToPrograms || !visible) return@LaunchedEffect
        if (programWindow.programs.isEmpty()) {
            withTimeoutOrNull(1200) {
                snapshotFlow { programWindow.programs.isNotEmpty() }.filter { it }.first()
            }
            if (programWindow.programs.isEmpty()) {
                pendingFocusToPrograms = false
                return@LaunchedEffect
            }
        }
        withFrameNanos { }
        runCatching { programListState.scrollToItem((programWindow.focusIndex - 3).coerceAtLeast(0)) }
        repeat(4) {
            withFrameNanos { }
            if (runCatching { selectedProgramRequester.requestFocus() }.isSuccess) {
                pendingFocusToPrograms = false
                return@LaunchedEffect
            }
        }
        pendingFocusToPrograms = false
    }

    LaunchedEffect(pendingFocusToDates, visible, epgDates.size, dateFocusTargetIndex) {
        if (!pendingFocusToDates || !visible) return@LaunchedEffect
        if (!showDates || epgDates.isEmpty()) {
            pendingFocusToDates = false
            return@LaunchedEffect
        }
        withFrameNanos { }
        runCatching { dateListState.scrollToItem(dateFocusTargetIndex) }
        repeat(4) {
            withFrameNanos { }
            if (runCatching { selectedDateRequester.requestFocus() }.isSuccess) {
                pendingFocusToDates = false
                return@LaunchedEffect
            }
        }
        pendingFocusToDates = false
    }

    val channelCount = channels.size
    val focusedIndex = remember(channelIndexByUrl, focusedChannelUrl) {
        focusedChannelUrl?.takeIf { it.isNotBlank() }?.let { channelIndexByUrl[it] }
    }

    // 统一的抽屉按键路由（Compose onPreviewKeyEvent 与 Activity 兜底共用，彻底消灭失焦死锁）
    fun stepChannelFocus(down: Boolean): Boolean {
        if (channelCount <= 0) return true
        val base = focusedIndex ?: PerfPolicy.drawerSafeFirstVisibleIndex(
            channelListState.firstVisibleItemIndex, channelCount
        )
        val next = if (down) (base + 1) % channelCount
        else if (base - 1 < 0) channelCount - 1 else base - 1
        if (next != base) {
            val targetUrl = channels[next].url
            focusedChannelUrl = targetUrl
            onFocusedChannelChanged(targetUrl)
            pendingFocusToChannels = true
            PerfTrace.d { "row_focus idx=$next via=key down=$down" }
        }
        return true
    }

    fun handleDrawerKeyCode(keyCode: Int): Boolean {
        if (!visible) return false
        onUserInteraction()
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> {
                if (activeColumn != DrawerColumn.Channels) pendingFocusToChannels = true
                onClose()
                true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (activeColumn == DrawerColumn.Channels) stepChannelFocus(down = false) else false
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (activeColumn == DrawerColumn.Channels) stepChannelFocus(down = true) else false
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> when (activeColumn) {
                DrawerColumn.Channels -> {
                    if (!showGroups) showGroups = true
                    pendingFocusToGroups = true
                    true
                }
                DrawerColumn.Programs -> {
                    showDates = false
                    activeColumn = DrawerColumn.Channels
                    pendingFocusToChannels = true
                    true
                }
                DrawerColumn.Dates -> {
                    activeColumn = DrawerColumn.Programs
                    pendingFocusToPrograms = true
                    true
                }
                DrawerColumn.Sources, DrawerColumn.Groups -> true // 左边界消费，不穿透
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> when (activeColumn) {
                DrawerColumn.Sources, DrawerColumn.Groups -> {
                    showGroups = false
                    activeColumn = DrawerColumn.Channels
                    pendingFocusToChannels = true
                    val nextUrl = selectedChannelUrl?.takeIf { channelIndexByUrl.containsKey(it) }
                        ?: channels.firstOrNull()?.url
                    if (nextUrl != null) {
                        focusedChannelUrl = nextUrl
                        onFocusedChannelChanged(nextUrl)
                    }
                    true
                }
                DrawerColumn.Channels -> {
                    if (programWindow.programs.isNotEmpty()) {
                        showDates = true
                        pendingFocusToPrograms = true
                    }
                    true
                }
                DrawerColumn.Programs -> {
                    if (epgDates.isNotEmpty()) {
                        if (!showDates) showDates = true
                        pendingFocusToDates = true
                    }
                    true
                }
                DrawerColumn.Dates -> true // 右边界消费，不穿透
            }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // 仅在兜底路径（Compose 节点未消费 OK 时）生效：在频道列直接切换当前逻辑焦点频道
                if (activeColumn == DrawerColumn.Channels) {
                    channels.getOrNull(focusTargetIndex)?.let { ch ->
                        focusedChannelUrl = ch.url
                        onFocusedChannelChanged(ch.url)
                        onSelectChannel(ch)
                    }
                    true
                } else false
            }
            else -> false
        }
    }

    val latestFallbackHandler = rememberUpdatedState<(Int) -> Boolean>(::handleDrawerKeyCode)
    val stableFallbackHandler = remember { { keyCode: Int -> latestFallbackHandler.value(keyCode) } }
    val latestReady = rememberUpdatedState(onFallbackKeyHandlerReady)
    DisposableEffect(Unit) {
        latestReady.value(stableFallbackHandler)
        onDispose { latestReady.value(null) }
    }

    val datesVisible = showDates && epgDates.isNotEmpty()
    val groupsWidthDp = if (showGroups) 156.dp else 0.dp
    val channelsWidthDp = 310.dp
    val programsWidthDp = PerfPolicy.DRAWER_PROGRAM_COLUMN_DP.dp + 32.dp + (if (datesVisible) 114.dp else 0.dp)
    val drawerWidth = groupsWidthDp + channelsWidthDp + programsWidthDp

    if (!visible) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                onUserInteraction()
                when (ev.key) {
                    Key.Back -> handleDrawerKeyCode(android.view.KeyEvent.KEYCODE_BACK)
                    Key.DirectionUp -> {
                        if (activeColumn == DrawerColumn.Channels) stepChannelFocus(down = false) else false
                    }
                    Key.DirectionDown -> {
                        if (activeColumn == DrawerColumn.Channels) stepChannelFocus(down = true) else false
                    }
                    Key.DirectionLeft -> handleDrawerKeyCode(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                    Key.DirectionRight -> handleDrawerKeyCode(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                    else -> false
                }
            }
    ) {
        // 1. 静态缓存的左侧选台区背景（曜石深底，单次 graphicsLayer alpha 合成，不随滚动重录）
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(groupsWidthDp + channelsWidthDp)
                .graphicsLayer {
                    alpha = PerfPolicy.drawerContainerAlpha(PerfPolicy.DRAWER_OPAQUE_CONTAINER)
                }
                .background(TvDesignTokens.SurfaceObsidian)
        )

        // 2. 静态缓存的右侧节目单区背景（更低透明度，自然分区、无 1dp 硬分割线）
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .offset(x = groupsWidthDp + channelsWidthDp)
                .width(programsWidthDp)
                .graphicsLayer { alpha = PerfPolicy.DRAWER_PROGRAM_AREA_ALPHA }
                .background(TvDesignTokens.SurfaceObsidian)
        )

        // 3. 内容层（独立 graphicsLayer，列表滚动不连带重绘背景层）
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(drawerWidth)
                .graphicsLayer()
                .focusProperties { canFocus = visible }
        ) {
            // ── 分组列（按需左展） ──────────────────────────────────────────
            AnimatedVisibility(
                visible = showGroups,
                enter = fadeIn(tween(110)),
                exit = fadeOut(tween(90)),
            ) {
                DrawerColumnPanel(
                    title = "分组",
                    active = activeColumn == DrawerColumn.Groups,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(156.dp)
                        .padding(horizontal = 14.dp, vertical = 18.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = groupListState,
                    ) {
                        itemsIndexed(groups, key = { _, g -> g }) { index, g ->
                            DrawerGroupItem(
                                name = g,
                                selected = g == selectedGroup,
                                focusRequester = if (index == groupFocusTargetIndex) selectedGroupRequester else null,
                                onFocused = {
                                    activeColumn = DrawerColumn.Groups
                                    showDates = false
                                    if (g != selectedGroup) onSelectGroupReset(g)
                                },
                                onClick = {
                                    onSelectGroupReset(g)
                                    // 分组列 OK 选中该组并顺滑进入频道列
                                    showGroups = false
                                    activeColumn = DrawerColumn.Channels
                                    pendingFocusToChannels = true
                                }
                            )
                        }
                    }
                }
            }

            // ── 频道列（核心：真·焦点恒定居中，列表在其下平滑滚动） ──────────
            DrawerColumnPanel(
                title = if (selectedGroup == SidebarState.ALL_GROUP) "全部频道" else selectedGroup,
                subtitle = "${channels.size}",
                active = activeColumn == DrawerColumn.Channels,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(channelsWidthDp)
                    .padding(horizontal = 14.dp, vertical = 18.dp),
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val rowHeight = PerfPolicy.DRAWER_ROW_HEIGHT_DP.dp
                    val viewportHeight = maxHeight
                    val visibleRowCount = (viewportHeight / rowHeight).toInt().coerceAtLeast(1)
                    val isScrollable = channels.size > visibleRowCount
                    val centerTopDp = ((viewportHeight - rowHeight) / 2f).coerceAtLeast(0.dp)
                    val listPadding = if (isScrollable) {
                        PaddingValues(top = centerTopDp, bottom = centerTopDp)
                    } else {
                        PaddingValues(0.dp)
                    }

                    // 固定居中高亮框（小分组自动跟随真实行 y；离开频道列时微弱降亮度提示列焦点已移走）
                    if (PerfPolicy.DRAWER_CENTER_HIGHLIGHT && channels.isNotEmpty()) {
                        val safeIdx = focusTargetIndex.coerceIn(0, channels.lastIndex)
                        val highlightTop = if (isScrollable) centerTopDp else rowHeight * safeIdx
                        val inChannelCol = activeColumn == DrawerColumn.Channels
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight)
                                .offset(y = highlightTop)
                                .graphicsLayer()
                                .background(
                                    if (inChannelCol) TvDesignTokens.FocusSurface
                                    else TvDesignTokens.FocusSurface.copy(alpha = 0.08f)
                                )
                                .testTag("drawer_center_focus_highlight")
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(),
                        state = channelListState,
                        contentPadding = listPadding,
                    ) {
                        itemsIndexed(
                            items = channels,
                            key = { idx, _ -> stableRowKeys.getOrElse(idx) { idx.toString() } },
                            contentType = { _, _ -> "channel" },
                        ) { index, ch ->
                            val isLogicalFocused = ch.url == focusTargetUrl
                            DrawerChannelItem(
                                channel = ch,
                                index = channelNumbers[ch.url] ?: (index + 1),
                                selected = (ch.url == selectedChannelUrl),
                                logicalFocused = isLogicalFocused,
                                suppressFocusBg = PerfPolicy.DRAWER_CENTER_HIGHLIGHT,
                                nowProgram = nowProgramByChannelUrl[ch.url],
                                focusRequester = if (isLogicalFocused) selectedChannelRequester else null,
                                onFocused = {
                                    activeColumn = DrawerColumn.Channels
                                    if (!isFocusRestoring) {
                                        focusedChannelUrl = ch.url
                                        onFocusedChannelChanged(ch.url)
                                        groupChangedFlag = false
                                    }
                                    showDates = false
                                    showGroups = false
                                },
                                onClick = {
                                    focusedChannelUrl = ch.url
                                    onFocusedChannelChanged(ch.url)
                                    onSelectChannel(ch)
                                }
                            )
                        }
                    }
                }
            }

            // ── 节目单 + 日期列（半透明轻质感，无分割线） ───────────────────
            DrawerColumnPanel(
                title = "节目单",
                active = activeColumn == DrawerColumn.Programs || activeColumn == DrawerColumn.Dates,
                modifier = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth()
                    .padding(horizontal = 16.dp, vertical = 18.dp),
            ) {
                val programs = programWindow.programs
                val dates = epgDates
                val selectedDate = selectedEpgDate
                val isEpgLoading = visible && (epgData != null || epgProgramProvider != null) &&
                    epgDataChannel != null && epgChannelData == null

                Row(modifier = Modifier.fillMaxSize()) {
                    if (programs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .width(PerfPolicy.DRAWER_PROGRAM_COLUMN_DP.dp)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = DrawerUiPolicy.programEmptyText(
                                    configured = epgSourceConfigured,
                                    loadFailed = epgLoadFailed,
                                    loading = isEpgLoading,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvDesignTokens.TextMuted,
                                modifier = Modifier.testTag("drawer_epg_empty")
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .width(PerfPolicy.DRAWER_PROGRAM_COLUMN_DP.dp)
                                .fillMaxHeight(),
                            state = programListState
                        ) {
                            itemsIndexed(
                                items = programs,
                                key = { index, p -> "${p.channelId}:${p.startMillis}:${p.endMillis}:$index" }
                            ) { index, p ->
                                val timeText = formatEpgTimeRange(
                                    startMillis = p.startMillis,
                                    endMillis = p.endMillis,
                                    zone = zoneForProgram(p, zone)
                                )
                                val effectiveNow = if (nowMillis > 0L) nowMillis else System.currentTimeMillis()
                                val state = when {
                                    p.endMillis <= effectiveNow -> ProgramTimeState.Past
                                    p.startMillis <= effectiveNow && effectiveNow < p.endMillis -> ProgramTimeState.Now
                                    else -> ProgramTimeState.Future
                                }
                                DrawerProgramItem(
                                    time = timeText,
                                    title = p.title,
                                    timeState = state,
                                    canCatchup = state == ProgramTimeState.Past && epgDataChannel?.catchupMode != null,
                                    focusRequester = if (index == programWindow.focusIndex) selectedProgramRequester else null,
                                    drawerVisible = visible,
                                    onFocused = {
                                        activeColumn = DrawerColumn.Programs
                                        showDates = true
                                    },
                                    onClick = {
                                        when (state) {
                                            ProgramTimeState.Past -> {
                                                val ch = epgDataChannel
                                                if (ch != null && !ch.catchupMode.isNullOrBlank()) {
                                                    val url = CatchupHelper.buildCatchupUrl(
                                                        liveUrl = ch.url,
                                                        modeRaw = ch.catchupMode,
                                                        sourceTemplate = ch.catchupSource,
                                                        start = Instant.ofEpochMilli(p.startMillis),
                                                        stop = Instant.ofEpochMilli(p.endMillis)
                                                    )
                                                    if (url != null) {
                                                        onPlayProgram(
                                                            CatchupPlayRequest(
                                                                catchupUrl = url,
                                                                liveUrl = ch.url,
                                                                programTitle = p.title,
                                                                startMillis = p.startMillis,
                                                                endMillis = p.endMillis,
                                                                owner = ch
                                                            )
                                                        )
                                                    } else {
                                                        Toast.makeText(context, "回看地址生成失败", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    Toast.makeText(context, "该频道不支持回看", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                            ProgramTimeState.Now -> {
                                                // 点击当前正在播出的节目直接切换直播该频道
                                                epgDataChannel?.let(onSelectChannel)
                                            }
                                            ProgramTimeState.Future -> {
                                                Toast.makeText(context, "该节目尚未播出", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = showDates && dates.isNotEmpty(),
                        enter = fadeIn(tween(110)),
                        exit = fadeOut(tween(90)),
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            Spacer(modifier = Modifier.width(10.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .width(104.dp)
                                    .fillMaxHeight(),
                                state = dateListState
                            ) {
                                itemsIndexed(
                                    items = dates,
                                    key = { _, d -> d.toString() }
                                ) { index, d ->
                                    DrawerDateItem(
                                        text = if (d == todayDate) "今天" else epgDateFormatter.format(d),
                                        selected = (d == selectedDate),
                                        focusRequester = if (index == dateFocusTargetIndex) selectedDateRequester else null,
                                        onFocused = {
                                            activeColumn = DrawerColumn.Dates
                                            showDates = true
                                        },
                                        onClick = { selectedEpgDate = d }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerColumnPanel(
    title: String,
    subtitle: String? = null,
    active: Boolean = true,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp, start = 4.dp, end = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (active) TvDesignTokens.TextPrimary else TvDesignTokens.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = TvDesignTokens.TextMuted,
                    maxLines = 1,
                )
            }
        }
        content()
    }
}

@Composable
private fun DrawerGroupItem(
    name: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                selected -> BrandFocus.BlueColor
                focused -> TvDesignTokens.TextPrimary
                else -> TvDesignTokens.TextSecondary
            }
        )
    }
}

@Composable
private fun DrawerChannelItem(
    channel: Channel,
    index: Int,
    selected: Boolean,
    logicalFocused: Boolean = false,
    suppressFocusBg: Boolean = false,
    nowProgram: NowProgramUi?,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val showFocused = focused || logicalFocused
    val label = remember(index, channel.title) {
        DrawerUiPolicy.channelLabel(index, channel.title)
    }
    val nameColor = when {
        DrawerUiPolicy.playingNameColor(selected) == DrawerUiPolicy.NameColor.PLAYING -> BrandFocus.BlueColor
        showFocused -> TvDesignTokens.TextPrimary
        else -> TvDesignTokens.TextPrimary.copy(alpha = 0.86f)
    }
    val bg = when {
        suppressFocusBg -> Color.Transparent
        showFocused -> BrandFocus.FocusBgColor
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(PerfPolicy.DRAWER_ROW_HEIGHT_DP.dp)
            .clipToBounds()
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(
            logoUrl = channel.logoUrl,
            fallbackTitle = channel.title,
            modifier = Modifier.size(width = 60.dp, height = 38.dp),
            width = 60.dp,
            height = 38.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        val showSubtitle = PerfPolicy.rowSubtitleVisible(focused)
        val programTitle = if (showSubtitle) TextSanitize.programTitle(nowProgram?.title) else null
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = if (showFocused || selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = nameColor
            )
            Text(
                text = if (!programTitle.isNullOrBlank()) programTitle else "无信息",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = when {
                    programTitle.isNullOrBlank() -> TvDesignTokens.TextMuted.copy(alpha = 0.7f)
                    showFocused -> TvDesignTokens.TextPrimary.copy(alpha = 0.85f)
                    else -> TvDesignTokens.TextSecondary.copy(alpha = 0.78f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(15.dp)
                    .testTag("drawer_row_subtitle")
            )
            val progress = nowProgram?.progress
            if (progress != null) {
                ThinProgressBar(
                    progress = progress,
                    selected = selected,
                    modifier = Modifier.padding(top = 3.dp).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ThinProgressBar(
    progress: Float,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val track = TvDesignTokens.TextPrimary.copy(alpha = 0.12f)
    val fill = if (selected) BrandFocus.BlueColor else TvDesignTokens.TextSecondary.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .height(3.dp)
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(clamped)
                .background(fill)
        )
    }
}

private enum class ProgramTimeState { Past, Now, Future }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusMarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    focused: Boolean,
    drawerVisible: Boolean,
    iterations: Int? = null,
    modifier: Modifier = Modifier
) {
    val effectiveIterations = iterations
        ?: PerfPolicy.marqueeIterations(focused = focused, drawerVisible = drawerVisible)
    Text(
        text = text,
        maxLines = 1,
        overflow = if (focused && effectiveIterations > 0) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = style,
        color = color,
        modifier = modifier.then(
            if (effectiveIterations > 0) Modifier.basicMarquee(iterations = effectiveIterations) else Modifier
        )
    )
}

@Composable
private fun DrawerDateItem(
    text: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = if (focused || selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                selected -> BrandFocus.BlueColor
                focused -> TvDesignTokens.TextPrimary
                else -> TvDesignTokens.TextSecondary
            }
        )
    }
}

@Composable
private fun DrawerProgramItem(
    time: String,
    title: String,
    timeState: ProgramTimeState,
    canCatchup: Boolean,
    focusRequester: FocusRequester?,
    drawerVisible: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) BrandFocus.FocusBgColor else Color.Transparent

    val textColor = when (timeState) {
        ProgramTimeState.Past -> if (canCatchup) TvDesignTokens.TextSecondary else TvDesignTokens.TextMuted
        ProgramTimeState.Now -> BrandFocus.BlueColor
        ProgramTimeState.Future -> TvDesignTokens.TextPrimary.copy(alpha = 0.86f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                runCatching { focusRequester?.requestFocus() }
            }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FocusMarqueeText(
            text = time,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            focused = focused,
            drawerVisible = drawerVisible,
            iterations = PerfPolicy.epgMarqueeIterations(focused, drawerVisible),
            modifier = Modifier.width(PerfPolicy.DRAWER_PROGRAM_TIME_COLUMN_DP.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        FocusMarqueeText(
            text = title,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (timeState == ProgramTimeState.Now || focused) FontWeight.Medium else FontWeight.Normal
            ),
            color = if (focused && timeState != ProgramTimeState.Now) TvDesignTokens.TextPrimary else textColor,
            focused = focused,
            drawerVisible = drawerVisible,
            iterations = PerfPolicy.epgMarqueeIterations(focused, drawerVisible),
            modifier = Modifier.weight(1f, fill = true)
        )
    }
}

private fun indexOfProgramAt(programs: List<EpgProgram>, nowMillis: Long): Int {
    if (programs.isEmpty()) return 0
    var lo = 0
    var hi = programs.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val p = programs[mid]
        if (nowMillis < p.startMillis) {
            hi = mid - 1
        } else if (nowMillis >= p.endMillis) {
            lo = mid + 1
        } else {
            return mid
        }
    }
    return lo.coerceIn(0, programs.lastIndex)
}

private fun lowerBoundStartMillis(programs: List<EpgProgram>, value: Long): Int {
    var lo = 0
    var hi = programs.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (programs[mid].startMillis < value) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun sliceProgramsOverlapping(programs: List<EpgProgram>, rangeStart: Long, rangeEnd: Long): List<EpgProgram> {
    if (programs.isEmpty()) return emptyList()
    val endIndex = lowerBoundStartMillis(programs, rangeEnd)
    var startIndex = (lowerBoundStartMillis(programs, rangeStart) - 1).coerceAtLeast(0)
    if (startIndex > endIndex) startIndex = endIndex
    if (startIndex == endIndex) return emptyList()
    return programs.subList(startIndex, endIndex).filter { it.endMillis > rangeStart }
}

private fun formatEpgTimeRange(startMillis: Long, endMillis: Long, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalTime()
    return "${epgTimeFormatter.format(start)}-${epgTimeFormatter.format(end)}"
}

private fun millisToLocalDate(millis: Long, zone: ZoneId): LocalDate {
    return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
