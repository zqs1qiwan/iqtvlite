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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // FULLFIX B2③：节目单空态三分类（未配置 EPG / 加载失败 / 无节目）。
    epgSourceConfigured: Boolean = true,
    epgLoadFailed: Boolean = false,
    onSelectGroup: (String) -> Unit,
    // V4 §2：分组列聚焦/点击切组（resetFocus=true → 从该组第一台开始）。
    onSelectGroupReset: (String) -> Unit = onSelectGroup,
    onSelectChannel: (Channel) -> Unit,
    onPlayProgram: (CatchupPlayRequest) -> Unit,
    onClose: () -> Unit,
    onActiveColumnChanged: (DrawerColumn) -> Unit = {},
    requestedActiveColumn: DrawerColumn? = null,
    // FULLFIX §2：上次浏览焦点（按组记忆，由 SidebarController 提供）。
    initialFocusUrl: String? = null,
    /** FULLFIX §2：把抽屉内真实浏览焦点回写给 SidebarController（按组记忆）。 */
    onFocusedChannelChanged: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // FULLFIX §3：paint 始终用**不透明** surface（t20 性能不回退）；半透明只加在
    // 已录制好的静态背景 layer 的 graphicsLayer.alpha 上（一处 layer blend，不是每个
    // draw command 参与整层合成）。container 一律不透明，旧 `surface.copy(alpha=...)` 已删除。
    val container = MaterialTheme.colorScheme.surface
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
    // t46：**唯一增长入口** —— 任何触发（打开 / 滚动 / 焦点）只抬高目标值，
    // 由下面的“泵”每帧 +2 行推进；不再一次性赋值（t45 的 301–393ms 巨帧就是一次性 +16/+28 造成的）。
    var revealTarget by remember { mutableIntStateOf(0) }
    // t15（滚动校正）：待校正的目标行（scrollToItem 索引）+ once 标记。
    // 设为 >=0 表示"揭示到该行后需要补一次 scrollToItem"；done 后设为 -1。
    var pendingScrollIndex by remember { mutableIntStateOf(-1) }
    val showContent = visible && contentReady
    LaunchedEffect(visible) {
        if (!visible) {
            contentReady = false
            revealedRows = 0
            pendingScrollIndex = -1 // t15：关闭时清空待校正，避免跨开合带入陈旧目标
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
    LaunchedEffect(visible) {
        if (visible) {
            perfOpenAt[0] = android.os.SystemClock.elapsedRealtime()
        }
    }
    // t33：关闭耗时单独埋点 —— 用来把 framestats 里的巨帧归因到“打开首帧”还是“关闭反向重绘”。
    val perfCloseAt = remember { longArrayOf(0L) }
    LaunchedEffect(visible) {
        if (visible) {
            perfCloseAt[0] = 0L
        } else {
            perfCloseAt[0] = android.os.SystemClock.elapsedRealtime()
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            PerfTrace.d { "drawer_close ms=${android.os.SystemClock.elapsedRealtime() - perfCloseAt[0]}" }
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

    // FULLFIX §2：把「打开时的恢复焦点」冻结在开抽屉这一刻。抽屉内浏览会通过
    // onFocusedChannelChanged 回写 SidebarController → Activity 传入新的 initialFocusUrl；
    // 若让它直接驱动 initialScrollIndex，每次移动焦点都会重触发打开窗口 effect
    // （revealedRows 被重置成 3 → 行数闪烁 + 性能回退）。remember(visible) 的 key 只在
    // 开合翻转时重算，正好拿到当时的恢复位置；切组用下方 live 的 initialFocusUrl 单独处理。
    val openFocusUrl = remember(visible) { if (visible) initialFocusUrl else null }

    // FULLFIX §2：优先用上次浏览焦点（按组记忆），其次播放台 —— 这是「不再回开头」的核心单变量。
    val initialScrollIndex = remember(channelIndexByUrl, selectedChannelUrl, openFocusUrl) {
        PerfPolicy.drawerInitialIndex(openFocusUrl, selectedChannelUrl, channelIndexByUrl)
    }

    val channelListState = rememberLazyListState(
        // V4 §1：初值固定 0，避免冷启时 initialScrollIndex=深行被 LazyColumn 钳到 0 造成
        // 「先闪到开头又跳回」的两次跳变。恢复位置完全交给 t15 的 pendingScrollIndex 门控校正。
        initialFirstVisibleItemIndex = 0
    )

    // t39（关键改动）：**不再把 147 行逐帧全部揭示**。
    // t37 的 50th=40ms 来自“补满 147 行要 72 帧、每帧都重组列表”这 72 个重帧；
    // 现在只维护一个**工作窗口**（首屏 3 行 → 第 2 帧扩到 选中行+lookahead+一屏），
    // 之后**随滚动/焦点按需增长**（见 scroll-follow 效果与 onRowFocusedScroll）。
    // 这样打开只需 2 个"轻"帧，50th 回到 ≤16ms 才有戏；列表行为与"全量揭示"完全一致（LazyColumn 本就只组合可见项）。
    LaunchedEffect(visible, channels.size, initialScrollIndex) {
        if (!visible || !PerfPolicy.DRAWER_INCREMENTAL_ROWS) return@LaunchedEffect
        // t46：打开只**设定目标**（有界开窗），实际推进交给分帧泵（每帧 +2 行）。
        // t48：首屏目标 28 → 18（减少分帧数 13→9），仍按 step=2 分帧推进。
        // S1：按实际行数计算首帧与目标窗口。旧写法
        // `(initialScrollIndex + OPEN_WINDOW).coerceIn(FIRST_FRAME_ROWS, channels.size)` 在
        // channels.size < FIRST_FRAME_ROWS（0/1/2 行的小分组）时 lo>hi 会抛
        // IllegalArgumentException，导致打开抽屉直接崩溃。pure helper 保证 lo<=hi：
        // 0 行 → first=0、openTarget=0；1/2 行 → 目标钳在行数内，不崩也不越界。
        val openTarget = PerfPolicy.drawerSafeOpenTarget(initialScrollIndex, channels.size)
        revealedRows = PerfPolicy.drawerFirstRows(channels.size)
        revealTarget = openTarget
        PerfTrace.d {
            "drawer_compose target=$openTarget first=${PerfPolicy.DRAWER_FIRST_FRAME_ROWS} " +
                "step=${PerfPolicy.DRAWER_ROWS_PER_FRAME} rows=${channels.size}"
        }
    }

    // t46：**分帧泵** —— 每帧只 +2 行，直到追上 revealTarget；新目标到来时继续推进。
    // 这样“打开 28 行”和“滚动再 +16 行”都不会一次性组合，巨帧消失。
    LaunchedEffect(visible, channels.size) {
        if (!visible || !PerfPolicy.DRAWER_INCREMENTAL_ROWS) return@LaunchedEffect
        val started = android.os.SystemClock.elapsedRealtime()
        var frames = 0
        snapshotFlow { revealTarget }.collect { target ->
            while (revealedRows < target) {
                val before = revealedRows
                androidx.compose.runtime.withFrameNanos { }
                revealedRows = (before + PerfPolicy.DRAWER_ROWS_PER_FRAME).coerceAtMost(target)
                frames++
                if (frames == 1 || revealedRows >= target) {
                    PerfTrace.d {
                        "drawer_compose frames=$frames rows=${channels.size} target=$target " +
                            "ms=${android.os.SystemClock.elapsedRealtime() - started} " +
                            "lastFrameRows=${revealedRows - before} first=${PerfPolicy.DRAWER_FIRST_FRAME_ROWS} " +
                            "step=${PerfPolicy.DRAWER_ROWS_PER_FRAME}"
                    }
                }
            }
        }
    }

    // t15（滚动校正核心）：把"滚动定位"与"行数揭示"解耦。
    // 只要有待校正目标（pendingScrollIndex>=0），就等分帧揭示覆盖到那一行
    // （PerfPolicy.drawerScrollCorrected：revealedRows >= target+1）再执行一次 scrollToItem，
    // 然后清空 pending（once 语义，防重复滚动/抖动）。这修复了首帧 revealedRows=3 时
    // scrollToItem(126) 被钳到 0、之后补到 147 行却不校正 → first=1 的根因。
    LaunchedEffect(visible, revealedRows, pendingScrollIndex) {
        if (!visible || pendingScrollIndex < 0) return@LaunchedEffect
        if (!PerfPolicy.drawerScrollCorrected(revealedRows, pendingScrollIndex)) return@LaunchedEffect
        val target = pendingScrollIndex
        pendingScrollIndex = -1 // once：先清标记再滚，避免重入
        runCatching { channelListState.scrollToItem(target) }
        PerfTrace.d { "drawer_scroll_correct target=$target revealed=$revealedRows" }
    }

    // t39：滚动/布局驱动按需增长 —— 只要“最后一个可见项”接近已揭示末尾，就再揭示一个窗口，
    // 保证向下滚动永远有下一屏（用户感知与全量揭示一致，但不再有 72 个重帧）。
    LaunchedEffect(visible, channels.size) {
        if (!visible || !PerfPolicy.DRAWER_INCREMENTAL_ROWS) return@LaunchedEffect
        snapshotFlow { channelListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (lastVisible >= revealTarget - PerfPolicy.DRAWER_ROWS_PER_FRAME) {
                    val next = (lastVisible + PerfPolicy.DRAWER_LOOKAHEAD_ROWS).coerceIn(0, channels.size)
                    if (next > revealTarget) {
                        revealTarget = next
                        PerfTrace.d { "drawer_reveal_grow target=$revealTarget lastVisible=$lastVisible" }
                    }
                }
            }
    }

    // t39：直接由 item 的 onFocusChanged 驱动滚动（不再依赖 focusedChannelUrl 回写是否生效）。
    val drawerScope = rememberCoroutineScope()
    /**
     * t39（滚动跟随）：焦点行被聚焦时调用。
     * 步骤：① 若该行还没被揭示（subList 只有 revealedRows 项），先把 revealedRows 扩到 idx+lookahead；
     * ② 等一帧让列表真正长出这些项（否则 scrollToItem 会被钳在末尾）；
     * ③ 只有当该行不在可视窗口内时才 scrollToItem，避免打断正常浏览。
     */
    val onRowFocusedScroll: (Int) -> Unit = { idx ->
        if (PerfPolicy.DRAWER_INCREMENTAL_ROWS) {
            // t46：只抬高目标（分帧泵每帧 +2 行推进），然后等该行真的长出来再滚（最多 ~30 帧）。
            val need = (idx + PerfPolicy.DRAWER_LOOKAHEAD_ROWS).coerceIn(0, channels.size)
            if (need > revealTarget) revealTarget = need
            drawerScope.launch {
                var waited = 0
                while (idx >= revealedRows && waited < 30) {
                    androidx.compose.runtime.withFrameNanos { }
                    waited++
                }
                androidx.compose.runtime.withFrameNanos { }
                val visible = channelListState.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull()?.index ?: 0
                val last = visible.lastOrNull()?.index ?: 0
                if (idx < first || idx > last) {
                    runCatching { channelListState.scrollToItem((idx - 2).coerceAtLeast(0)) }
                }
            }
        }
    }

    // V4 §1：旧的「打开后确认 scrollToItem」effect 已移除——它在首帧 revealed=3 时也会被钳到 0，
    // 与 t15 的 pendingScrollIndex 门控校正形成双滚动/闪烁。现在所有打开定位统一走 pending 校正。

    // t39：兜底 —— 若 focusedChannelUrl 由别处（如首帧恢复）改到窗口外，仍保证可见。
    LaunchedEffect(visible, focusedChannelUrl) {
        if (!visible || !PerfPolicy.DRAWER_INCREMENTAL_ROWS) return@LaunchedEffect
        val url = focusedChannelUrl ?: return@LaunchedEffect
        val idx = channelIndexByUrl[url] ?: return@LaunchedEffect
        if (idx >= revealedRows) revealedRows = (idx + PerfPolicy.DRAWER_LOOKAHEAD_ROWS).coerceIn(0, channels.size)
        androidx.compose.runtime.withFrameNanos { }
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
            val targetUrl = openFocusUrl?.takeIf { channelIndexByUrl.containsKey(it) }
                ?: selectedChannelUrl ?: channels.firstOrNull()?.url
            if (targetUrl != null) {
                focusedChannelUrl = targetUrl
                stableFocusedChannelUrl = targetUrl
                val targetIndex = channelIndexByUrl[targetUrl]
                if (targetIndex != null && channels.isNotEmpty()) {
                    val targetScroll = PerfPolicy.drawerScrollTargetIndex(targetIndex, channels.size)
                    // t15：不在首帧直接 scrollToItem（此时 revealedRows=3 → 被钳到 0）。
                    // 改为登记待校正目标，由下方的 revealedRows 门控 effect 在分帧揭示覆盖后执行。
                    pendingScrollIndex = targetScroll
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
        // V4 §2：移除旧的 `if (!showGroups) return` —— 分组列收起后切组也要更新焦点/滚动。
        // selectGroupReset 会清该组记忆 → initialFocusUrl 为 null → remembered=null → idx=0（第一台）。
        // FULLFIX §2：切组不再硬编码回 0（走 pendingScrollIndex 门控）；仅无记忆时才从第一台开始。
        groupChangedFlag = true
        val remembered = initialFocusUrl?.takeIf { channelIndexByUrl.containsKey(it) }
        focusedChannelUrl = remembered
        if (channels.isEmpty()) return@LaunchedEffect
        val idx = remembered?.let { channelIndexByUrl[it] } ?: 0
        // t15：切组也走 revealedRows 门控的滚动校正（而不是首帧可能被钳的 scrollToItem）。
        pendingScrollIndex = PerfPolicy.drawerScrollTargetIndex(idx, channels.size)
    }

    LaunchedEffect(selectedChannelUrl, visible) {
        if (!visible) return@LaunchedEffect
        val targetUrl = selectedChannelUrl ?: return@LaunchedEffect
        if (focusedChannelUrl.isNullOrBlank()) {
            focusedChannelUrl = targetUrl
            stableFocusedChannelUrl = targetUrl
        }
        // t15（P8b）：换台使 selectedChannelUrl 变深行（焦点已初始化过）时，同样登记滚动校正。
        // 但只在抽屉刚打开后的首个 selectedChannelUrl 事件才强制校正；抽屉内正常浏览由
        // onRowFocusedScroll 跟随，这里不抢。hasInitializedDrawerFocus 为 true 且本次是
        // 由换台驱动的深行（不在可视区）时才登记，避免打开后反复抖动。
        if (hasInitializedDrawerFocus) {
            val idx = channelIndexByUrl[targetUrl]
            if (idx != null && idx > 0) {
                val visibleIndices = channelListState.layoutInfo.visibleItemsInfo.map { it.index }
                if (idx !in visibleIndices) {
                    pendingScrollIndex = PerfPolicy.drawerScrollTargetIndex(idx, channels.size)
                }
            }
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
                    } else if ((it.key == Key.DirectionUp || it.key == Key.DirectionDown) &&
                        activeColumn == DrawerColumn.Channels && channelCount > 0
                    ) {
                        // t46：**频道列内自己处理 UP/DOWN**（不再交给默认焦点搜索）。
                        // 默认焦点搜索在“下一行还没被揭示”时会跳到右侧节目单，导致 t45 观测到的
                        // “快按后 DOWN×15 可见行仍 1–12”。这里显式改 focusedChannelUrl：
                        // → focusTargetIndex/focusTargetUrl 变化 → 该行挂上 requester → 现有 effect 聚焦；
                        // → onRowFocusedScroll 抬高 revealTarget 并按帧补行，然后滚动跟随。
                        val down = it.key == Key.DirectionDown
                        // S1：`coerceIn(0, channelCount - 1)` 在 channelCount=0 时 lo>hi 会抛
                        // IllegalArgumentException；外层分支虽已用 channelCount>0 守卫，
                        // 这里仍改用安全钳制，空列表返回 0 占位（不会进入下面的取模分支）。
                        val base = focusedIndex
                            ?: PerfPolicy.drawerSafeFirstVisibleIndex(
                                channelListState.firstVisibleItemIndex, channelCount
                            )
                        val next = if (down) (base + 1) % channelCount
                        else if (base - 1 < 0) channelCount - 1 else base - 1
                        if (next == base) {
                            true
                        } else {
                            focusedChannelUrl = channels[next].url
                            onFocusedChannelChanged(channels[next].url)
                            pendingFocusToChannels = true
                            PerfTrace.d { "row_focus idx=$next via=key down=$down" }
                            true
                        }
                    } else if (it.key == Key.DirectionLeft && activeColumn == DrawerColumn.Channels) {
                        if (!showGroups) showGroups = true
                        pendingFocusToGroups = true
                        true
                    } else if (it.key == Key.DirectionRight && activeColumn == DrawerColumn.Groups) {
                        // V4 §2：→ 从分组列进入频道列并收起分组列，聚焦该组第一台。
                        showGroups = false
                        activeColumn = DrawerColumn.Channels
                        pendingFocusToChannels = true
                        val firstUrl = channels.firstOrNull()?.url
                        if (firstUrl != null) {
                            focusedChannelUrl = firstUrl
                            onFocusedChannelChanged(firstUrl)
                            pendingScrollIndex = PerfPolicy.drawerScrollTargetIndex(0, channels.size)
                        }
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
            // t58（攻固定开销）：把**静态背景**拆成独立 layer —— 只录制一次，之后每帧不再重录；
            // 内容区再单独一层，列表滚动/揭示只让内容层重录，不牵连背景层。
            if (visible) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .offset(x = drawerOffsetX)
                        // FULLFIX §3：半透明只作用于这张已缓存的静态背景 layer（GPU 一次 layer blend），
                        // 内容层/列表层不受影响，三层 graphicsLayer 与 t58 缓存结构不变。
                        .graphicsLayer {
                            alpha = PerfPolicy.drawerContainerAlpha(PerfPolicy.DRAWER_OPAQUE_CONTAINER)
                        }
                        .background(container)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .offset(x = drawerOffsetX)
                    .graphicsLayer()
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
                                                // V4 §2：聚焦即展示（幂等，组名真变化才重算）；从该组第一台开始。
                                                if (g != selectedGroup) onSelectGroupReset(g)
                                            },
                                            onClick = { onSelectGroupReset(g) }
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
                            // t58：列表单独一层 —— 滚动/揭示只重录这一层。
                            modifier = Modifier.fillMaxSize().graphicsLayer(),
                            state = channelListState
                        ) {
                            // t36（回退 t33 的 E3）：只把**已揭示的行**交给列表（subList），
                            // 未揭示的行根本不存在 → 每帧只测量已揭示项（t34 的 Draw ×10 由此消除）。
                            // 56dp 固定行高保留；焦点跟随由下面的 LaunchedEffect 保证。
                            itemsIndexed(
                                channels.subList(0, revealedRows.coerceIn(0, channels.size)),
                                key = { index, ch -> "${index}:${ch.url}" },
                                contentType = { _, _ -> "channel" },
                            ) { index, ch ->
                                composedUrls.add(ch.url)
                                DrawerChannelItem(
                                    channel = ch,
                                    index = channelNumbers[ch.url] ?: (index + 1),
                                    selected = (ch.url == selectedChannelUrl),
                                    // V4 §1：逻辑焦点（不依赖真实 Compose 焦点），行一被组合即高亮。
                                    logicalFocused = (ch.url == focusTargetUrl),
                                    nowProgram = nowProgramByChannelUrl[ch.url],
                                    focusRequester = if (ch.url == focusTargetUrl) selectedChannelRequester else null,
                                    onFocused = {
                                        activeColumn = DrawerColumn.Channels
                                        if (!isFocusRestoring) {
                                            focusedChannelUrl = ch.url
                                            onFocusedChannelChanged(ch.url)
                                            groupChangedFlag = false
                                        }
                                        showDates = false
                                        showGroups = false
                                        // t39：焦点一落行就把它滚进视野（不依赖 focusedChannelUrl 回写）。
                                        onRowFocusedScroll(index)
                                    },
                                    onClick = {
                                        focusedChannelUrl = ch.url
                                        onFocusedChannelChanged(ch.url)
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
                                        text = DrawerUiPolicy.programEmptyText(
                                            configured = epgSourceConfigured,
                                            loadFailed = epgLoadFailed,
                                            loading = isEpgLoading,
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.testTag("drawer_epg_empty")
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
        focused -> BrandFocus.BgColor
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
    // V4 §1：逻辑焦点（ch.url==focusTargetUrl），与本地真实焦点 focused 正交。
    logicalFocused: Boolean = false,
    nowProgram: NowProgramUi?,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // V4 §1：逻辑焦点 OR 真实焦点 —— 行一被组合即高亮，不依赖 requestFocus 成功（深行要等 ~60 帧）。
    val showFocused = focused || logicalFocused
    // V4 §5：播放台用台名变色标识（不再 ▶ 文本），与浏览焦点底色正交。
    val label = remember(index, channel.title) {
        DrawerUiPolicy.channelLabel(index, channel.title)
    }
    val nameColor = if (DrawerUiPolicy.playingNameColor(selected) == DrawerUiPolicy.NameColor.PLAYING) {
        BrandFocus.BlueColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // t46：聚焦行日志（确认副标题真的渲染 + 供 t47 归因滚动落点）。
    LaunchedEffect(focused) {
        if (focused) {
            PerfTrace.d {
                "row_focus idx=$index subtitle=${!nowProgram?.title.isNullOrBlank()} " +
                    "title=${nowProgram?.title.orEmpty()}"
            }
        }
    }
    val bg = when {
        showFocused -> BrandFocus.BgColor
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // t33：固定行高（避免逐行测量/文字重排；长标题仍单行 ellipsis）
            .height(PerfPolicy.DRAWER_ROW_HEIGHT_DP.dp)
            // t48：行内容严格裁剪在行内（残影的兜底）
            .clipToBounds()
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
        // FULLFIX §1：所有行都组合副标题（台名 + 节目/无信息 + 进度），不再只给聚焦行。
        // focused 只影响颜色/进度强调，不影响是否组合；总开关 DRAWER_FOCUSED_SUBTITLE 仍在，
        // 一行改 false 可回到「全行无副标题」。
        // t60：先清洗不可见字符（NBSP/全角空格/零宽），避免首字被挤成单独一行（残影根因）。
        val showSubtitle = PerfPolicy.rowSubtitleVisible(focused)
        val programTitle = if (showSubtitle) TextSanitize.programTitle(nowProgram?.title) else null
        // t48（修残影）：整棵副标题子树用 key 隔开 —— 失焦时销毁重建，不复用旧 draw 命令；
        // 配合外层 clipToBounds，杜绝单字残影（城/军/武/音）。
        key("sub_$index") {
            Column(modifier = Modifier.weight(1f, fill = true)) {
                Text(
                    text = label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (showFocused || nameColor == BrandFocus.BlueColor) {
                            androidx.compose.ui.text.font.FontWeight.Bold
                        } else {
                            androidx.compose.ui.text.font.FontWeight.Medium
                        }
                    ),
                    color = nameColor
                )
                // FULLFIX §1：无节目显示「无信息」（恢复初始设计），仍固定 16dp 高并 clip。
                Box(modifier = Modifier.height(16.dp).fillMaxWidth().clipToBounds()) {
                    Text(
                        text = if (!programTitle.isNullOrBlank()) programTitle else "无信息",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = if (programTitle.isNullOrBlank()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        },
                        // t46：给验收一个可断言的语义节点（t45 在语义树里没观测到副标题）。
                        // t60：不用 softWrap=false（会把文字测成极窄宽），单行 ellipsis + 确定宽度；
                        // onTextLayout 里探测“过窄/多行”并打点，供验收断言。
                        onTextLayout = { result ->
                            val w = result.size.width
                            if (w < 24 || result.lineCount > 1) {
                                PerfTrace.d { "sub_narrow idx=$index w=$w lines=${result.lineCount}" }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("drawer_row_subtitle")
                    )
                }
                // FULLFIX §1：进度恢复 3dp ThinProgressBar（复用现存 helper，focused 时强调更高）。
                val progress = nowProgram?.progress
                if (progress != null) {
                    ThinProgressBar(
                        progress = progress,
                        focused = focused,
                        modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                    )
                }
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
        focused -> BrandFocus.BgColor
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
        focused -> BrandFocus.BgColor
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.01f)
    }

    val textColor = when (timeState) {
        ProgramTimeState.Past -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
        ProgramTimeState.Now -> BrandFocus.BlueColor // V4 §9：品牌蓝（原 Light Blue 40C4FF）
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