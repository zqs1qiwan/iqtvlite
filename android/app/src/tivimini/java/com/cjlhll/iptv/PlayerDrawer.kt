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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity

import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

enum class DrawerColumn {
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

private fun zoneForPrograms(programs: List<EpgProgram>): ZoneId {
    val seconds = programs.firstOrNull { it.sourceOffsetSeconds != null }?.sourceOffsetSeconds
    return if (seconds != null) java.time.ZoneOffset.ofTotalSeconds(seconds) else ZoneId.systemDefault()
}

private fun zoneForProgram(program: EpgProgram, fallback: ZoneId): ZoneId {
    val seconds = program.sourceOffsetSeconds
    return if (seconds != null) java.time.ZoneOffset.ofTotalSeconds(seconds) else fallback
}

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
    nowMillis: Long = 0L,
    onSelectGroup: (String) -> Unit,
    onSelectChannel: (Channel) -> Unit,
    onPlayProgram: (CatchupPlayRequest) -> Unit,
    onClose: () -> Unit,
    onActiveColumnChanged: (DrawerColumn) -> Unit = {},
    requestedActiveColumn: DrawerColumn? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // t20（E4）：不透明容器，避免每帧对整层 1080p 面板做 alpha 合成（framestats 里 Draw 118–162ms）。
    val container = if (PerfPolicy.DRAWER_OPAQUE_CONTAINER) MaterialTheme.colorScheme.surface
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val scrim = Color.Transparent
    val shape = RoundedCornerShape(0.dp)
    val prefetchDensity = LocalDensity.current

    var showGroups by remember { mutableStateOf(false) }
    var showDates by remember { mutableStateOf(false) }

    val selectedGroupRequester = remember { FocusRequester() }
    val selectedChannelRequester = remember { FocusRequester() }
    val selectedProgramRequester = remember { FocusRequester() }
    val selectedDateRequester = remember { FocusRequester() }
    var activeColumn by remember { mutableStateOf(DrawerColumn.Channels) }
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
                DrawerColumn.Groups -> pendingFocusToGroups = true
                DrawerColumn.Channels -> pendingFocusToChannels = true
                DrawerColumn.Programs -> pendingFocusToPrograms = true
                DrawerColumn.Dates -> pendingFocusToDates = true
            }
        }
    }

    var focusedChannelUrl by remember { mutableStateOf<String?>(null) }
    var stableFocusedChannelUrl by remember { mutableStateOf<String?>(null) }

    val visibleValue by rememberUpdatedState(visible)

    var autoProgramScrollKey by remember { mutableStateOf<String?>(null) }
    var isFocusRestoring by remember { mutableStateOf(false) }
    var groupChangedFlag by remember { mutableStateOf(false) }
    var hasInitializedDrawerFocus by remember { mutableStateOf(false) }

    val epgChannelUiCache = remember(epgData) {
        object : LinkedHashMap<String, EpgChannelUiData>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpgChannelUiData>?): Boolean {
                return size > 32
            }
        }
    }

    // val groupListState = rememberLazyListState()
    // val channelListState = rememberLazyListState()
    // val programListState = rememberLazyListState()
    // val dateListState = rememberLazyListState()

    LaunchedEffect(Unit) {
        snapshotFlow { focusedChannelUrl }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce(350)
            .collectLatest { url ->
                if (!visibleValue) return@collectLatest
                if (stableFocusedChannelUrl != url) stableFocusedChannelUrl = url
            }
    }

    // t154: one log line per browsing session — "prefetch=req:N dec:M hit:K".
    LaunchedEffect(visible) {
        if (!visible) {
            LogoLoader.logPerfCounters(drawerOpen = false)
            return@LaunchedEffect
        }
        autoProgramScrollKey = null
    }

    // t7：抽屉开合的性能埋点 —— 记录 "key 到达 → 抽屉首帧" 的耗时，并给 atrace 打 section。
    // 只在 log.tag.IQTV-PERF DEBUG 时输出一行（academy 用它做"改前/改后同口径"对比，
    // 不依赖 uiautomator 的 dump 尺寸判据）。
    val perfOpenAt = remember { longArrayOf(0L) }
    // t27（D1/D3）：统计“打开时真正组合了多少行”（旧日志 rows=channels.size 是总数，误导验收）。
    val composedUrls = remember { HashSet<String>() }
    // t20（E1）：关闭时不组合列表。
    // t29（D4）：打开时**分帧增量组合** —— 首帧 10 行，之后每帧 +3 行（withFrameNanos 驱动），
    // 避免"120ms 后一次性大块组合"造成的 300+ms 巨帧（t28 实测 5 个 332–368ms）。
    var contentReady by remember { mutableStateOf(PerfPolicy.DRAWER_DEFER_CONTENT_MS <= 0L) }
    var revealedRows by remember { mutableIntStateOf(0) }
    val showContent = visible && contentReady
    val visibleRows = if (!PerfPolicy.DRAWER_INCREMENTAL_ROWS) channels.size
    else revealedRows.coerceIn(0, channels.size)
    LaunchedEffect(visible) {
        if (!visible) {
            contentReady = false
            revealedRows = 0
            composedUrls.clear()
        } else if (PerfPolicy.DRAWER_DEFER_CONTENT_MS > 0L) {
            contentReady = false
            revealedRows = 0
            delay(PerfPolicy.DRAWER_DEFER_CONTENT_MS)
            contentReady = true
        } else {
            contentReady = true
            revealedRows = 0
        }
    }
    // t29：逐帧把行数补到 total（每帧一次，单帧最多 3 行 → 单帧工作量远低于 16ms）。
    // 埋点：drawer_compose frames=<补了几帧> rows=<总行数> lastFrameRows=<末帧新增行>。
    LaunchedEffect(visible, channels.size) {
        if (!visible || !PerfPolicy.DRAWER_INCREMENTAL_ROWS) return@LaunchedEffect
        var frames = 0
        while (revealedRows < channels.size) {
            val before = revealedRows
            androidx.compose.runtime.withFrameNanos { }
            revealedRows = PerfPolicy.nextRevealedRows(revealedRows, channels.size)
            frames++
            if (frames == 1 || revealedRows >= channels.size) {
                PerfTrace.d {
                    "drawer_compose frames=$frames rows=${channels.size} " +
                        "lastFrameRows=${revealedRows - before} first=${PerfPolicy.DRAWER_FIRST_FRAME_ROWS}"
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) {
            perfOpenAt[0] = android.os.SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(visible, channels.size, selectedChannelUrl) {
        if (!visible) return@LaunchedEffect
        // 等这一帧真正画完（withFrameNanos 返回时本帧已提交）。
        androidx.compose.runtime.withFrameNanos { }
        val started = perfOpenAt[0]
        if (started > 0L) {
            val ms = android.os.SystemClock.elapsedRealtime() - started
            PerfTrace.d {
                "drawer_open ms=$ms total=${channels.size} composed=${composedUrls.size} " +
                    "revealed=$revealedRows first=${PerfPolicy.DRAWER_FIRST_FRAME_ROWS} groups=${groups.size} group=$selectedGroup"
            }
        }
    }

    val groupFocusTargetIndex = remember(groups, selectedGroup) {
        val i = groups.indexOf(selectedGroup)
        if (i >= 0) i else 0
    }

    val groupFocusTargetName = remember(groups, groupFocusTargetIndex) {
        groups.getOrNull(groupFocusTargetIndex)
    }

    val channelIndexByUrl = remember(channels) {
        HashMap<String, Int>(minOf(channels.size * 2, 4096)).apply {
            for (i in channels.indices) {
                put(channels[i].url, i)
            }
        }
    }

    val focusTargetIndex = remember(channelIndexByUrl, selectedChannelUrl, focusedChannelUrl, groupChangedFlag) {
        val fUrl = focusedChannelUrl
        if (!fUrl.isNullOrBlank()) {
            val idx = channelIndexByUrl[fUrl]
            if (idx != null) return@remember idx
        }

        // 如果发生了分组切换，且当前没有聚焦频道，则优先聚焦第一个
        if (groupChangedFlag && channelIndexByUrl.isNotEmpty()) {
            return@remember 0
        }

        val sUrl = selectedChannelUrl
        if (!sUrl.isNullOrBlank()) {
            val idx = channelIndexByUrl[sUrl]
            if (idx != null) return@remember idx
        }
        if (channelIndexByUrl.isNotEmpty()) {
            return@remember 0
        }
        -1
    }

    val focusTargetUrl = remember(channels, focusTargetIndex) {
        channels.getOrNull(focusTargetIndex)?.url
    }

    val groupListState = rememberLazyListState()

    val initialScrollIndex = remember(channelIndexByUrl, selectedChannelUrl) {
        val url = selectedChannelUrl
        if (!url.isNullOrBlank()) channelIndexByUrl[url] ?: 0 else 0
    }

    val channelListState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollIndex
    )

    // t29（D4）：分帧补行完成后校正一次滚动 —— 首帧只有 10 行时 scrollToItem(选中行) 会被钳住，
    // 补满后需要把选中行带回可视区（否则长列表深处选中的频道看不到焦点）。
    LaunchedEffect(visible, revealedRows, channels.size) {
        if (!visible || revealedRows < channels.size || activeColumn != DrawerColumn.Channels) return@LaunchedEffect
        val target = focusedChannelUrl ?: selectedChannelUrl
        val idx = target?.let { channelIndexByUrl[it] } ?: return@LaunchedEffect
        if (idx !in channelListState.layoutInfo.visibleItemsInfo.map { it.index }) {
            runCatching { channelListState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        }
    }

    val programListState = rememberLazyListState()
    val dateListState = rememberLazyListState()

    // t137/t145 PREFETCH: selected±8 first, then the full visible window (no truncation), and an
    // idle filler that also covers a selection past the first 60. CPU stays bounded because the
    // filler is deferred 1 s, batched 2-at-a-time with a gap, and cancelled on any scroll.
    //
    // t7（PERF-02）：紧急预取延后到开合动画结束（PerfPolicy.URGENT_PREFETCH_DELAY_MS）并截断到
    // PerfPolicy.MAX_URGENT_PREFETCH 个。旧实现 200ms 后就丢出约 17 个解码任务，正好和开抽屉的
    // 那几帧抢 CPU（弱机 4 核）。集合挑选逻辑抽到 PerfPolicy.urgentPrefetchIndices 单测。
    LaunchedEffect(visible, selectedGroup, channels, selectedChannelUrl) {
        if (!visible) return@LaunchedEffect
        val pxW = with(prefetchDensity) { 64.dp.roundToPx() }
        val pxH = with(prefetchDensity) { 40.dp.roundToPx() }
        snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.map { it.index } }
            .collectLatest { visibleIdx ->
                // 先让开合帧落下来，再开始解码（单变量：只改这一处延迟/上限）。
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
                    PerfTrace.d { "drawer_prefetch urgent=${urgentUrls.size} visible=${visibleIdx.size}" }
                    LogoLoader.prefetch(context, urgentUrls, pxW, pxH)
                }

                // idle filler: the selection's own band FIRST (PREFETCH-02b: a selection past
                // index 60 must never be pushed out), then the head of the group.
                val filler = LinkedHashSet<Int>()
                for (i in (selectedIdx - 60)..(selectedIdx + 8)) if (i in channels.indices) filler.add(i)
                for (i in 0 until minOf(60, channels.size)) filler.add(i)
                val fillerUrls = filler.filterNot { it in urgent }
                    .mapNotNull { channels[it].logoUrl?.trim()?.takeIf { u -> u.isNotBlank() } }
                    .distinct()
                    .filterNot { it in urgentUrls }
                if (fillerUrls.isNotEmpty()) {
                    delay(1000)
                    LogoLoader.prefetchBackground(context, fillerUrls, pxW, pxH)
                }
            }
    }

    LaunchedEffect(visible) {
        if (!visible) {
            delay(400)
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

        if (!hasInitializedDrawerFocus) {
            hasInitializedDrawerFocus = true
            val targetUrl = selectedChannelUrl ?: channels.firstOrNull()?.url
            if (targetUrl != null) {
                focusedChannelUrl = targetUrl
                stableFocusedChannelUrl = targetUrl
                val targetIndex = channelIndexByUrl[targetUrl]
                if (targetIndex != null && channels.isNotEmpty()) {
                    val targetScroll = (targetIndex - 2).coerceAtLeast(0)
                    val visibleIndices = channelListState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
                    if (targetIndex !in visibleIndices) {
                        runCatching { channelListState.scrollToItem(targetScroll) }
                    }
                }
            }
        }

        if (channels.isEmpty() || activeColumn != DrawerColumn.Channels) return@LaunchedEffect

        isFocusRestoring = true
        // t29：分帧组合下，选中行可能要几百毫秒后才被组合出来 → 重试窗口按帧计（最多 ~90 帧≈1.5s），
        // 只要选中行还没出现就继续等；一旦拿到焦点立刻返回（不改变“打开即聚焦选中行”的行为）。
        var attempts = 0
        while (attempts < 90) {
            if (activeColumn != DrawerColumn.Channels) {
                isFocusRestoring = false
                return@LaunchedEffect
            }
            if (runCatching { selectedChannelRequester.requestFocus() }.isSuccess) {
                isFocusRestoring = false
                return@LaunchedEffect
            }
            // 分帧补行期间优先用帧同步；补完后退回 30ms 轮询。
            if (revealedRows < channels.size) {
                androidx.compose.runtime.withFrameNanos { }
            } else {
                delay(30)
            }
            attempts++
        }
        isFocusRestoring = false
    }

    LaunchedEffect(selectedGroup) {
        if (!visible) return@LaunchedEffect
        if (!showGroups) return@LaunchedEffect
        groupChangedFlag = true
        focusedChannelUrl = null
        if (channels.isEmpty()) return@LaunchedEffect
        runCatching {
            channelListState.scrollToItem(0)
        }
    }

    LaunchedEffect(selectedChannelUrl, visible) {
        if (!visible) return@LaunchedEffect
        val targetUrl = selectedChannelUrl ?: return@LaunchedEffect
        if (focusedChannelUrl.isNullOrBlank()) {
            focusedChannelUrl = targetUrl
            stableFocusedChannelUrl = targetUrl
        }
    }

    LaunchedEffect(pendingFocusToGroups, visible, groupFocusTargetName, groupFocusTargetIndex) {
        if (!pendingFocusToGroups) return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        if (!showGroups) {
            pendingFocusToGroups = false
            return@LaunchedEffect
        }
        if (groupFocusTargetName == null) {
            pendingFocusToGroups = false
            return@LaunchedEffect
        }

        withFrameNanos { }

        runCatching {
            groupListState.scrollToItem(groupFocusTargetIndex)
        }

        repeat(3) {
            withFrameNanos { }
            if (runCatching { selectedGroupRequester.requestFocus() }.isSuccess) {
                pendingFocusToGroups = false
                return@LaunchedEffect
            }
        }

        pendingFocusToGroups = false
    }

    LaunchedEffect(pendingFocusToChannels, visible, focusTargetUrl, focusTargetIndex) {
        if (!pendingFocusToChannels) return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        if (focusTargetUrl == null) {
            pendingFocusToChannels = false
            return@LaunchedEffect
        }

        withFrameNanos { }

        runCatching {
            if (channelListState.layoutInfo.visibleItemsInfo.none { it.index == focusTargetIndex }) {
                channelListState.scrollToItem(focusTargetIndex)
            }
        }

        repeat(3) {
            withFrameNanos { }
            if (runCatching { selectedChannelRequester.requestFocus() }.isSuccess) {
                pendingFocusToChannels = false
                return@LaunchedEffect
            }
        }

        pendingFocusToChannels = false
    }

    val focusedChannel = remember(channels, focusedChannelUrl, selectedChannelUrl) {
        val url = focusedChannelUrl ?: selectedChannelUrl
        channels.firstOrNull { it.url == url } ?: channels.firstOrNull()
    }

    val epgDataChannel = remember(channels, stableFocusedChannelUrl, selectedChannelUrl) {
        val url = stableFocusedChannelUrl ?: selectedChannelUrl
        channels.firstOrNull { it.url == url } ?: channels.firstOrNull()
    }

    val epgChannelData by produceState<EpgChannelUiData?>(
        initialValue = epgDataChannel?.url?.let { epgChannelUiCache[it] },
        key1 = epgData,
        key2 = epgDataChannel
    ) {
        val data = epgData
        val ch = epgDataChannel
        if (data == null || ch == null) {
            value = null
            return@produceState
        }
        val cacheKey = ch.url
        val cached = epgChannelUiCache[cacheKey]
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val channelId = data.resolveChannelId(ch) ?: return@withContext EpgChannelUiData(
                zone = ZoneId.systemDefault(),
                programs = emptyList(),
                dates = emptyList()
            )
            val programs = data.programsByChannelId[channelId].orEmpty()
            if (programs.isEmpty()) return@withContext EpgChannelUiData(
                zone = ZoneId.systemDefault(),
                programs = emptyList(),
                dates = emptyList()
            )
            val zone = zoneForPrograms(programs)
            val dates = programs
                .asSequence()
                .map { millisToLocalDate(it.startMillis, zone) }
                .distinct()
                .sorted()
                .toList()
            EpgChannelUiData(zone = zone, programs = programs, dates = dates)
        }

        val computed = value
        if (computed != null) {
            epgChannelUiCache[cacheKey] = computed
        }
    }

    val fullPrograms = epgChannelData?.programs.orEmpty()
    val zone = epgChannelData?.zone ?: ZoneId.systemDefault()
    val todayDate = remember(nowMillis, zone) {
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
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
        selectedEpgDate = when {
            epgDates.contains(todayDate) -> todayDate
            else -> epgDates.firstOrNull()
        }
    }

    val dateFocusTargetIndex = remember(epgDates, selectedEpgDate) {
        val d = selectedEpgDate ?: return@remember 0
        val i = epgDates.indexOf(d)
        if (i >= 0) i else 0
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
            val focusIndex = if (selectedEpgDate == todayDate) {
                indexOfProgramAt(programsBySelectedDate, nowMillis)
            } else {
                0
            }
            ProgramWindow(programsBySelectedDate, focusIndex)
        }
    }

    val programScrollKey = remember(epgDataChannel?.url, selectedEpgDate) {
        val url = epgDataChannel?.url ?: return@remember null
        val date = selectedEpgDate?.toString() ?: ""
        "$url|$date"
    }

    LaunchedEffect(visible, programScrollKey, programWindow.focusIndex, programWindow.programs.size) {
        if (!visible) return@LaunchedEffect
        val key = programScrollKey ?: return@LaunchedEffect
        if (programWindow.programs.isEmpty()) return@LaunchedEffect
        if (autoProgramScrollKey == key) return@LaunchedEffect
        delay(80)
        runCatching { programListState.scrollToItem((programWindow.focusIndex - 3).coerceAtLeast(0)) }
        autoProgramScrollKey = key
    }

    LaunchedEffect(pendingFocusToPrograms, visible, programWindow.focusIndex, programWindow.programs.size) {
        if (!pendingFocusToPrograms) return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        if (programWindow.programs.isEmpty()) {
            withTimeoutOrNull(1500) {
                snapshotFlow { programWindow.programs.isNotEmpty() }
                    .filter { it }
                    .first()
            }
            if (programWindow.programs.isEmpty()) {
                pendingFocusToPrograms = false
                return@LaunchedEffect
            }
        }

        withFrameNanos { }

        runCatching { programListState.scrollToItem((programWindow.focusIndex - 3).coerceAtLeast(0)) }
        withFrameNanos { }

        if (runCatching { selectedProgramRequester.requestFocus() }.isSuccess) {
            pendingFocusToPrograms = false
            return@LaunchedEffect
        }

        runCatching { programListState.scrollToItem((programWindow.focusIndex - 3).coerceAtLeast(0)) }

        repeat(3) {
            withFrameNanos { }
            if (runCatching { selectedProgramRequester.requestFocus() }.isSuccess) {
                pendingFocusToPrograms = false
                return@LaunchedEffect
            }
        }

        pendingFocusToPrograms = false
    }

    LaunchedEffect(visible, focusedChannelUrl, selectedEpgDate, programWindow.focusIndex, programWindow.programs.size) {
        if (!visible) return@LaunchedEffect
        if (programWindow.programs.isEmpty()) return@LaunchedEffect
        if (activeColumn != DrawerColumn.Programs && activeColumn != DrawerColumn.Dates) return@LaunchedEffect
        programListState.scrollToItem((programWindow.focusIndex - 3).coerceAtLeast(0))
    }

    LaunchedEffect(pendingFocusToDates, visible, epgDates.size, dateFocusTargetIndex) {
        if (!pendingFocusToDates) return@LaunchedEffect
        if (!visible) return@LaunchedEffect
        if (!showDates) {
            pendingFocusToDates = false
            return@LaunchedEffect
        }
        if (epgDates.isEmpty()) {
            pendingFocusToDates = false
            return@LaunchedEffect
        }

        withFrameNanos { }

        runCatching {
            dateListState.scrollToItem(dateFocusTargetIndex)
        }

        repeat(3) {
            withFrameNanos { }
            if (runCatching { selectedDateRequester.requestFocus() }.isSuccess) {
                pendingFocusToDates = false
                return@LaunchedEffect
            }
        }

        pendingFocusToDates = false
    }

    val datesVisible = showDates && epgDates.isNotEmpty()
    val targetDrawerWidth = remember(showGroups, datesVisible) {
        val groupsSection = if (showGroups) (150.dp + 1.dp) else 0.dp
        val channelsSection = 300.dp
        val divider = 1.dp
        val programsSection = 300.dp + 32.dp + (if (datesVisible) (110.dp + 10.dp) else 0.dp)
        groupsSection + channelsSection + divider + programsSection
    }
    // No per-frame drawer animation: animating the whole panel width/offset recomposed + re-laid-out
    // the entire sidebar every frame, which both janked and made the video stutter while opening or
    // browsing the sidebar. Instant layout keeps the sidebar cheap; the content still fades in/out.
    val drawerWidth = targetDrawerWidth
    val drawerOffsetX = if (visible) 0.dp else -drawerWidth

    val channelCount = channels.size
    val focusedIndex = remember(channelIndexByUrl, focusedChannelUrl) {
        val url = focusedChannelUrl
        if (url.isNullOrBlank()) null else channelIndexByUrl[url]
    }

    Box(modifier = modifier.fillMaxSize()) {
        val overlayModifier = if (visible) {
            Modifier
                .fillMaxSize()
                // t20（E4）：scrim 是 Transparent，画它等于每帧白送一个全屏 draw op；去掉。
                .onPreviewKeyEvent {
                    if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                    if (it.key == Key.Back) {
                        if (activeColumn != DrawerColumn.Channels) {
                            pendingFocusToChannels = true
                        }
                        onClose()
                        true
                    } else if (it.key == Key.DirectionUp && activeColumn == DrawerColumn.Channels && channelCount > 0) {
                        val index = focusedIndex
                        if (index == 0) {
                            focusedChannelUrl = channels.last().url
                            pendingFocusToChannels = true
                            true
                        } else {
                            false
                        }
                    } else if (it.key == Key.DirectionDown && activeColumn == DrawerColumn.Channels && channelCount > 0) {
                        val index = focusedIndex
                        if (index == channelCount - 1) {
                            focusedChannelUrl = channels.first().url
                            pendingFocusToChannels = true
                            true
                        } else {
                            false
                        }
                    } else if (it.key == Key.DirectionLeft && activeColumn == DrawerColumn.Channels) {
                        if (!showGroups) showGroups = true
                        pendingFocusToGroups = true
                        true
                    } else if (it.key == Key.DirectionRight && activeColumn == DrawerColumn.Groups) {
                        pendingFocusToChannels = true
                        true
                    } else if (it.key == Key.DirectionRight && activeColumn == DrawerColumn.Channels) {
                        pendingFocusToPrograms = true
                        true
                    } else if (it.key == Key.DirectionRight && activeColumn == DrawerColumn.Programs) {
                        if (!showDates) showDates = true
                        pendingFocusToDates = true
                        true
                    } else if (it.key == Key.DirectionLeft && activeColumn == DrawerColumn.Programs) {
                        pendingFocusToChannels = true
                        true
                    } else if (it.key == Key.DirectionLeft && activeColumn == DrawerColumn.Dates) {
                        pendingFocusToPrograms = true
                        true
                    } else {
                        false
                    }
                }
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = overlayModifier) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .offset(x = drawerOffsetX)
                    .background(container)
                    .focusProperties { canFocus = visible }
                    .padding(0.dp)
            ) {
                // t20（E1/E2）：抽屉**关闭时完全不组合列表内容**（此前 147 个布局节点会被父级
                // 每帧重组/重绘拖进来）；打开时按 PerfPolicy.DRAWER_DEFER_CONTENT_MS 决定是否
                // 先画面板再组合列表。默认 0 = 立即组合（观感不变）。
                if (showContent) {
                Row(modifier = Modifier.fillMaxHeight()) {
                    AnimatedVisibility(
                        visible = showGroups,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(100)),
                    ) {
                        Row(modifier = Modifier.fillMaxHeight()) {
                            DrawerColumnPanel(
                                title = "分组",
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(150.dp)
                                    .padding(16.dp),
                            ) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    state = groupListState
                                ) {
                                    itemsIndexed(groups, key = { index, g -> "$index:$g" }) { index, g ->
                                        val isTarget = index == groupFocusTargetIndex
                                        DrawerGroupItem(
                                            name = g,
                                            selected = g == selectedGroup,
                                            focusRequester = if (isTarget) selectedGroupRequester else null,
                                            onFocused = {
                                                activeColumn = DrawerColumn.Groups
                                                showDates = false
                                            },
                                            onClick = { onSelectGroup(g) }
                                        )
                                    }
                                }
                            }

                            Spacer(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                            )
                        }
                    }

                    DrawerColumnPanel(
                        title = "频道",
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(300.dp)
                            .padding(16.dp),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = channelListState
                        ) {
                            // t29（D4）：只先组合前 visibleRows 行（其余逐帧补），避免单帧大块组合。
                            itemsIndexed(
                                channels.subList(0, visibleRows.coerceIn(0, channels.size)),
                                key = { index, ch -> "${index}:${ch.url}" },
                                contentType = { _, _ -> "channel" },
                            ) { index, ch ->
                                composedUrls.add(ch.url)
                                DrawerChannelItem(
                                    channel = ch,
                                    index = channelNumbers[ch.url] ?: (index + 1),
                                    selected = (ch.url == selectedChannelUrl),
                                    nowProgram = nowProgramByChannelUrl[ch.url],
                                    focusRequester = if (ch.url == focusTargetUrl) selectedChannelRequester else null,
                                    drawerVisible = visible,
                                    onFocused = {
                                        activeColumn = DrawerColumn.Channels
                                        if (!isFocusRestoring) {
                                            focusedChannelUrl = ch.url
                                            groupChangedFlag = false
                                        }
                                        showDates = false
                                        showGroups = false
                                    },
                                    onClick = {
                                        focusedChannelUrl = ch.url
                                        // pendingFocusToChannels = true
                                        onSelectChannel(ch)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
                    )

                    DrawerColumnPanel(
                        title = "节目单",
                        modifier = Modifier
                            .fillMaxHeight()
                            .wrapContentWidth()
                            .padding(16.dp),
                    ) {
                        val programs = programWindow.programs
                        val dates = epgDates
                        val selectedDate = selectedEpgDate
                        val isEpgLoading = visible && epgData != null && epgDataChannel != null && epgChannelData == null

                        Row(modifier = Modifier.fillMaxSize()) {
                            if (programs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .width(300.dp)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (isEpgLoading) "加载中" else "暂无节目单",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .width(300.dp)
                                        .fillMaxHeight(),
                                    state = programListState
                                ) {
                                    itemsIndexed(
                                        items = programs,
                                        key = { index, p -> "$index:${p.channelId}:${p.startMillis}:${p.endMillis}:${p.title}" }
                                    ) { index, p ->
                                        val timeText = formatEpgTimeRange(
                                            startMillis = p.startMillis,
                                            endMillis = p.endMillis,
                                            zone = zoneForProgram(p, zone)
                                        )
                                        val state = when {
                                            p.endMillis <= nowMillis -> ProgramTimeState.Past
                                            p.startMillis <= nowMillis && nowMillis < p.endMillis -> ProgramTimeState.Now
                                            else -> ProgramTimeState.Future
                                        }
                                        DrawerProgramItem(
                                            time = timeText,
                                            title = p.title,
                                            timeState = state,
                                            focusRequester = if (index == programWindow.focusIndex) selectedProgramRequester else null,
                                            drawerVisible = visible,
                                            onFocused = {
                                                activeColumn = DrawerColumn.Programs
                                                showDates = true
                                            },
                                            onClick = {
                                                Log.d("PlayerDrawer", "Program clicked: ${p.title}, state=$state")
                                                if (state == ProgramTimeState.Past) {
                                                    val ch = epgDataChannel
                                                    Log.d("PlayerDrawer", "Checking channel for catchup: ${ch?.title}, mode=${ch?.catchupMode}, source=${ch?.catchupSource}")

                                                    if (ch != null && ch.catchupMode != null) {
                                                        val url = CatchupHelper.buildCatchupUrl(
                                                            liveUrl = ch.url,
                                                            modeRaw = ch.catchupMode,
                                                            sourceTemplate = ch.catchupSource,
                                                            start = Instant.ofEpochMilli(p.startMillis),
                                                            stop = Instant.ofEpochMilli(p.endMillis)
                                                        )
                                                        Log.d("PlayerDrawer", "Generated catchup URL")
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
                                                        Log.d("PlayerDrawer", "Catchup not supported for this channel or mode is null")
                                                        Toast.makeText(context, "该频道不支持回看", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    Log.d("PlayerDrawer", "Program is not in the past, ignoring click")
                                                    Toast.makeText(context, "只能回看历史节目", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = showDates && dates.isNotEmpty(),
                                enter = fadeIn(tween(120)),
                                exit = fadeOut(tween(100)),
                            ) {
                                Row(modifier = Modifier.fillMaxHeight()) {
                                    Spacer(modifier = Modifier.width(10.dp))

                                    LazyColumn(
                                        modifier = Modifier
                                            .width(110.dp)
                                            .fillMaxHeight(),
                                        state = dateListState
                                    ) {
                                        itemsIndexed(
                                            items = dates,
                                            key = { index, d -> "$index:${d}" }
                                        ) { index, d ->
                                            DrawerDateItem(
                                                text = epgDateFormatter.format(d),
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
        }
    }
}

@Composable
private fun DrawerColumnPanel(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
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
    val bg = when {
        focused -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                focusRequester?.requestFocus()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DrawerChannelItem(
    channel: Channel,
    index: Int,
    selected: Boolean,
    nowProgram: NowProgramUi?,
    focusRequester: FocusRequester?,
    drawerVisible: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // t27（D3）：行标题预格式化（避免每帧字符串拼接）。
    val label = remember(index, channel.title) { "$index. ${channel.title}" }
    val bg = when {
        focused -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }

    val contentColor = MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                focusRequester?.requestFocus()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelLogo(
            logoUrl = channel.logoUrl,
            fallbackTitle = channel.title,
            modifier = Modifier.size(width = 64.dp, height = 40.dp),
            width = 64.dp,
            height = 40.dp
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor
            )
            if (!nowProgram?.title.isNullOrBlank()) {
                FocusMarqueeText(
                    text = nowProgram?.title.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    focused = focused,
                    drawerVisible = drawerVisible
                )
                val progress = nowProgram?.progress
                if (progress != null) {
                    ThinProgressBar(
                        progress = progress,
                        focused = focused,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                    )
                }
            } else {
                Text(
                    text = "无信息",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}

@Composable
private fun ThinProgressBar(
    progress: Float,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    val clamped = progress.coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.16f else 0.12f)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = if (focused) 0.95f else 0.80f)
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

private enum class ProgramTimeState {
    Past,
    Now,
    Future
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusMarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    focused: Boolean,
    drawerVisible: Boolean,
    modifier: Modifier = Modifier
) {
    // t7：跑马灯只在"抽屉可见 + 该行聚焦"时跑有限圈数。旧实现 iterations=Int.MAX_VALUE 会在抽屉
    // 打开期间永久请求下一帧，弱机上每帧重合成 1080p 半透明层 → gfxinfo 33/33 全 janky。
    val iterations = PerfPolicy.marqueeIterations(focused = focused, drawerVisible = drawerVisible)
    Text(
        text = text,
        maxLines = 1,
        overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
        style = style,
        color = color,
        modifier = modifier.then(
            if (iterations > 0) Modifier.basicMarquee(iterations = iterations) else Modifier
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
    val bg = when {
        focused -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                focusRequester?.requestFocus()
            }
            .padding(horizontal = 10.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun DrawerProgramItem(
    time: String,
    title: String,
    timeState: ProgramTimeState,
    focusRequester: FocusRequester?,
    drawerVisible: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
    }

    val textColor = when (timeState) {
        ProgramTimeState.Past -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        ProgramTimeState.Now -> Color(0xFF40C4FF) // Light Blue A200
        ProgramTimeState.Future -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
            .background(bg)
            .onFocusChanged {
                if (it.isFocused) onFocused()
                focused = it.isFocused
            }
            .focusable()
            .clickable {
                onClick()
                focusRequester?.requestFocus()
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = time,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            modifier = Modifier.width(96.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        FocusMarqueeText(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            focused = focused,
            drawerVisible = drawerVisible,
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
        if (programs[mid].startMillis < value) {
            lo = mid + 1
        } else {
            hi = mid
        }
    }
    return lo
}

private fun sliceProgramsOverlapping(programs: List<EpgProgram>, rangeStart: Long, rangeEnd: Long): List<EpgProgram> {
    if (programs.isEmpty()) return emptyList()
    val endIndex = lowerBoundStartMillis(programs, rangeEnd)
    var startIndex = (lowerBoundStartMillis(programs, rangeStart) - 1).coerceAtLeast(0)
    if (startIndex > endIndex) startIndex = endIndex
    if (startIndex == endIndex) return emptyList()
    val slice = programs.subList(startIndex, endIndex)
    return slice.filter { it.endMillis > rangeStart }
}

private fun formatEpgTimeRange(startMillis: Long, endMillis: Long, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalTime()
    val end = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalTime()
    return "${epgTimeFormatter.format(start)}-${epgTimeFormatter.format(end)}"
}

private fun millisToLocalDate(millis: Long, zone: ZoneId): LocalDate {
    return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}