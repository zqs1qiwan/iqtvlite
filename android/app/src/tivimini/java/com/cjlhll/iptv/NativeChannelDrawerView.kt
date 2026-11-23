package com.cjlhll.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * 原生 Android [RecyclerView] 4 列 TiviMate 选台抽屉（60fps 弱机性能底座）。
 *
 * 从根源解决 Compose LazyColumn 在 Android 9 / ARMv7 机顶盒上 61ms–200ms UI 线程掉帧的问题：
 * 1. **零 Compose diff、零 relayout**：RecyclerView 原生 ViewHolder 复用 + `itemAnimator = null` +
 *    局部 `PAYLOAD_FOCUS` 仅更新 2 个 ViewHolder 背景，单次上下按键耗时 < 1ms；
 * 2. **真·0px 恒定居中**：通过 `clipToPadding = false` + 动态上下半屏 `padding = (viewportH - rowH)/2`
 *    + `scrollToPositionWithOffset(focusIndex, 0)`，让第 0 项至末项每一行都精确落于可视区几何正中；
 *    少于一屏的小分组自动退化为顶对齐，不留空白断层；
 * 3. **严格本地时区 EPG**：所有节目时间（`HH:mm`）与日期（`今天` / `MM-dd`）统一按设备本地时区
 *    [ZoneId.systemDefault] 格式化，彻底修复 XMLTV `+0000` 显示 UTC 时间的偏差；
 * 4. **统一曜石极简视觉**：左区 `#EB0F131A` + 右 EPG 区 `#9E0F131A`（无生硬分割线）+ 右缘柔光过渡，
 *    磨砂灰白焦点底 `#24FFFFFF` + 3dp 品牌蓝左指示条 `#2F6BFF`；
 * 5. **确定性遥控闭环**：由 `PlayerActivity.dispatchKeyEvent` 同步直达 [handleKeyEvent]，零焦锁、零透传。
 */
class NativeChannelDrawerView(context: Context) : LinearLayout(context) {

    data class Snapshot(
        val groups: List<String> = emptyList(),
        val selectedGroup: String = SidebarState.ALL_GROUP,
        val channels: List<Channel> = emptyList(),
        val channelNumbers: Map<String, Int> = emptyMap(),
        val selectedChannelUrl: String? = null,
        val nowProgramByChannelUrl: Map<String, NowProgramUi> = emptyMap(),
        val epgData: EpgData? = null,
        val epgVersion: Int = 0,
        val epgProgramProvider: (suspend (Channel) -> List<EpgProgram>)? = null,
        val epgSourceConfigured: Boolean = true,
        val epgLoadFailed: Boolean = false,
        val initialFocusUrl: String? = null,
    )

    interface Listener {
        fun onSelectGroup(group: String, resetFocus: Boolean)
        fun onSelectChannel(channel: Channel)
        fun onPlayProgram(request: CatchupPlayRequest)
        fun onClose()
        fun onActiveColumnChanged(column: DrawerColumn)
        fun onFocusedChannelChanged(url: String)
    }

    var listener: Listener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var snapshot = Snapshot()
    private var isOpen = false

    private var activeColumn: DrawerColumn = DrawerColumn.Channels
    private var showGroups = false
    private var showDates = false

    private var focusedGroupIndex = 0
    private var focusedChannelIndex = 0
    private var focusedProgramIndex = 0
    private var focusedDateIndex = 0

    // EPG 缓存按 epgVersion 分代，永不缓存空载期假空结果
    private var cachedEpgVersion = -1
    private val epgCache = object : LinkedHashMap<String, List<EpgProgram>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EpgProgram>>?): Boolean = size > 32
    }
    private var loadedEpgChannelUrl: String? = null
    private var loadedEpgAllPrograms: List<EpgProgram> = emptyList()
    private var availableDates: List<LocalDate> = emptyList()
    private var selectedDate: LocalDate? = null
    private var visibleDatePrograms: List<EpgProgram> = emptyList()
    private var epgLoading = false

    private var epgLoadJob: Job? = null
    private var prefetchJob: Job? = null
    private var perfOpenAtMs = 0L

    // 尺寸与色彩常量（与 TvDesignTokens 100% 统一）
    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()
    private fun dpF(v: Float): Float = v * density

    private val channelRowHeightPx = dp(68)
    private val groupRowHeightPx = dp(48)
    private val programRowHeightPx = dp(60)
    private val dateRowHeightPx = dp(46)

    // 视图组件
    private val groupsPanel: LinearLayout
    private val groupsCountView: TextView
    private val groupsRecycler: RecyclerView
    private val groupsLm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val groupsAdapter = GroupsAdapter()

    private val channelsPanel: LinearLayout
    private val channelsTitleView: TextView
    private val channelsCountView: TextView
    private val channelsRecycler: RecyclerView
    private val channelsLm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val channelsAdapter = ChannelsAdapter()

    private val programsPanel: LinearLayout
    private val programsChannelTitleView: TextView
    private val programsSubtitleView: TextView
    private val programsEmptyView: TextView
    private val programsRecycler: RecyclerView
    private val programsLm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val programsAdapter = ProgramsAdapter()

    private val datesPanel: LinearLayout
    private val datesRecycler: RecyclerView
    private val datesLm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val datesAdapter = DatesAdapter()

    private val edgeScrim: View

    private fun newRowBgDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(8f)
        setColor(Color.TRANSPARENT)
    }

    private fun newAccentPillDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(2f)
        setColor(COLOR_ACCENT_BLUE)
    }

    init {
        orientation = HORIZONTAL
        visibility = GONE
        isClickable = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "native_channel_drawer"

        // ── 1. 分组列（按 LEFT 展开） ──────────────────────────────────────────
        groupsPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_SOLID)
            visibility = GONE
            layoutParams = LayoutParams(dp(184), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val groupsHeader = buildColumnHeader("频道分组", "").also {
            groupsCountView = it.second
        }
        groupsPanel.addView(groupsHeader.first)
        groupsRecycler = buildRecycler(groupsLm, groupsAdapter).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        groupsPanel.addView(groupsRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(groupsPanel)

        // ── 2. 频道列（常显，真·0px 焦点恒定居中） ────────────────────────────
        channelsPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_SOLID)
            layoutParams = LayoutParams(dp(316), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val channelsHeader = buildColumnHeader("全部频道", "0/0")
        channelsTitleView = channelsHeader.first.getChildAt(0) as TextView
        channelsCountView = channelsHeader.second
        channelsPanel.addView(channelsHeader.first)
        channelsRecycler = buildRecycler(channelsLm, channelsAdapter).apply {
            contentDescription = "drawer_channels_list"
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    applyChannelCenterPadding(bottom - top)
                    scrollChannelToCenter(focusedChannelIndex)
                }
            }
        }
        channelsPanel.addView(channelsRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(channelsPanel)

        // ── 3. 节目单列（半透明曜石玻璃，无生硬分割线） ───────────────────────
        programsPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_TRANSLUCENT)
            layoutParams = LayoutParams(dp(276), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val programsHeader = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        }
        programsChannelTitleView = TextView(context).apply {
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        programsSubtitleView = TextView(context).apply {
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        programsHeader.addView(programsChannelTitleView)
        programsHeader.addView(programsSubtitleView)
        programsPanel.addView(programsHeader)

        val programsContentBox = FrameLayout(context).apply {
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        programsRecycler = buildRecycler(programsLm, programsAdapter).apply {
            contentDescription = "drawer_programs_list"
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    applyProgramCenterPadding(bottom - top)
                    scrollProgramToCenter(focusedProgramIndex)
                }
            }
        }
        programsEmptyView = TextView(context).apply {
            text = "暂无节目单"
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            visibility = GONE
        }
        programsContentBox.addView(
            programsRecycler,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        programsContentBox.addView(
            programsEmptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        programsPanel.addView(programsContentBox)
        addView(programsPanel)

        // ── 4. 日期列（在节目列按 RIGHT 展开） ────────────────────────────────
        datesPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_TRANSLUCENT)
            visibility = GONE
            layoutParams = LayoutParams(dp(128), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        datesPanel.addView(buildColumnHeader("日期", "").first)
        datesRecycler = buildRecycler(datesLm, datesAdapter).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        datesPanel.addView(datesRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(datesPanel)

        // ── 5. 右缘柔光过渡（融入播放画面） ────────────────────────────────────
        edgeScrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(COLOR_SURFACE_TRANSLUCENT, Color.TRANSPARENT),
            )
            layoutParams = LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(edgeScrim)
    }

    private fun buildColumnHeader(title: String, rightText: String): Pair<LinearLayout, TextView> {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(8))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
        }
        val titleTv = TextView(context).apply {
            text = title
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightTv = TextView(context).apply {
            text = rightText
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            isSingleLine = true
        }
        header.addView(titleTv)
        header.addView(rightTv)
        return header to rightTv
    }

    private fun buildRecycler(lm: LinearLayoutManager, rvAdapter: RecyclerView.Adapter<*>): RecyclerView {
        return RecyclerView(context).apply {
            layoutManager = lm
            adapter = rvAdapter
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(12)
            isFocusable = false
            isFocusableInTouchMode = false
        }
    }

    override fun onDetachedFromWindow() {
        epgLoadJob?.cancel()
        prefetchJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    // ── 数据与开合控制 ───────────────────────────────────────────────────────

    fun updateSnapshot(newSnapshot: Snapshot) {
        val prev = snapshot
        snapshot = newSnapshot

        if (newSnapshot.epgVersion != cachedEpgVersion) {
            cachedEpgVersion = newSnapshot.epgVersion
            epgCache.clear()
            loadedEpgChannelUrl = null
        }

        val groupsChanged = prev.groups != newSnapshot.groups || prev.selectedGroup != newSnapshot.selectedGroup
        val channelsChanged = prev.channels !== newSnapshot.channels && prev.channels != newSnapshot.channels
        val playingChanged = prev.selectedChannelUrl != newSnapshot.selectedChannelUrl
        val nowProgramsChanged = prev.nowProgramByChannelUrl !== newSnapshot.nowProgramByChannelUrl

        if (groupsChanged) {
            focusedGroupIndex = newSnapshot.groups.indexOf(newSnapshot.selectedGroup).coerceAtLeast(0)
            groupsCountView.text = "${newSnapshot.groups.size}"
            if (isOpen) groupsAdapter.notifyDataSetChanged()
        }

        channelsTitleView.text = newSnapshot.selectedGroup

        if (channelsChanged) {
            val anchorUrl = currentFocusedChannel()?.url
                ?.takeIf { u -> newSnapshot.channels.any { it.url == u } }
                ?: newSnapshot.initialFocusUrl?.takeIf { u -> newSnapshot.channels.any { it.url == u } }
                ?: newSnapshot.selectedChannelUrl?.takeIf { u -> newSnapshot.channels.any { it.url == u } }
            focusedChannelIndex = if (anchorUrl != null) {
                newSnapshot.channels.indexOfFirst { it.url == anchorUrl }.coerceAtLeast(0)
            } else 0
            if (isOpen) {
                applyChannelCenterPadding(channelsRecycler.height)
                channelsAdapter.notifyDataSetChanged()
                scrollChannelToCenter(focusedChannelIndex)
                updateChannelCounter()
                scheduleEpgLoad(immediate = true)
            }
        } else if (isOpen && (playingChanged || nowProgramsChanged)) {
            channelsAdapter.notifyItemRangeChanged(0, newSnapshot.channels.size, PAYLOAD_NOW_OR_PLAYING)
        }

        if (isOpen && prev.epgVersion != newSnapshot.epgVersion) {
            scheduleEpgLoad(immediate = true)
        }
    }

    fun setOpen(open: Boolean) {
        if (isOpen == open) return
        isOpen = open
        if (!open) {
            epgLoadJob?.cancel()
            prefetchJob?.cancel()
            LogoLoader.logPerfCounters(drawerOpen = false)
            val tClose = SystemClock.elapsedRealtime()
            visibility = GONE
            showGroups = false
            showDates = false
            groupsPanel.visibility = GONE
            datesPanel.visibility = GONE
            setActiveColumnInternal(DrawerColumn.Channels)
            PerfTrace.d { "drawer_close ms=${SystemClock.elapsedRealtime() - tClose}" }
            return
        }

        perfOpenAtMs = SystemClock.elapsedRealtime()
        showGroups = false
        showDates = false
        groupsPanel.visibility = GONE
        datesPanel.visibility = GONE
        setActiveColumnInternal(DrawerColumn.Channels)

        focusedGroupIndex = snapshot.groups.indexOf(snapshot.selectedGroup).coerceAtLeast(0)
        groupsCountView.text = "${snapshot.groups.size}"
        channelsTitleView.text = snapshot.selectedGroup

        val channels = snapshot.channels
        val knownUrls = HashSet<String>(channels.size * 2).apply { for (c in channels) add(c.url) }
        val targetUrl = PerfPolicy.drawerOpenPositionAnchor(
            playingUrl = snapshot.selectedChannelUrl,
            browseAnchor = snapshot.initialFocusUrl ?: snapshot.selectedChannelUrl,
            knownUrls = knownUrls,
        ) ?: channels.firstOrNull()?.url

        focusedChannelIndex = if (targetUrl != null) {
            channels.indexOfFirst { it.url == targetUrl }.coerceAtLeast(0)
        } else 0

        visibility = VISIBLE
        groupsAdapter.notifyDataSetChanged()
        channelsAdapter.notifyDataSetChanged()
        updateChannelCounter()

        // 在当前帧或布局完成后即刻精确居中落位（0px 误差）
        if (channelsRecycler.height > 0) {
            applyChannelCenterPadding(channelsRecycler.height)
            scrollChannelToCenter(focusedChannelIndex)
        } else {
            channelsRecycler.post {
                if (isOpen) {
                    applyChannelCenterPadding(channelsRecycler.height)
                    scrollChannelToCenter(focusedChannelIndex)
                }
            }
        }

        currentFocusedChannel()?.let { listener?.onFocusedChannelChanged(it.url) }
        scheduleEpgLoad(immediate = true)
        scheduleUrgentLogoPrefetch()

        post {
            if (isOpen && perfOpenAtMs > 0L) {
                val ms = SystemClock.elapsedRealtime() - perfOpenAtMs
                PerfTrace.d {
                    "drawer_open ms=$ms total=${snapshot.channels.size} groups=${snapshot.groups.size} group=${snapshot.selectedGroup}"
                }
                PerfTrace.d { "drawer_focus_locate focus=$focusedChannelIndex total=${snapshot.channels.size}" }
            }
        }
    }

    // ── 确定性电视遥控分发（< 0.5ms，零焦点死锁） ────────────────────────────

    fun handleKeyEvent(keyCode: Int): Boolean {
        if (!isOpen) return false
        return when (activeColumn) {
            DrawerColumn.Groups -> handleGroupsKey(keyCode)
            DrawerColumn.Channels -> handleChannelsKey(keyCode)
            DrawerColumn.Programs -> handleProgramsKey(keyCode)
            DrawerColumn.Dates -> handleDatesKey(keyCode)
        }
    }

    private fun handleGroupsKey(keyCode: Int): Boolean {
        val groups = snapshot.groups
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (groups.isEmpty()) return true
                val prev = focusedGroupIndex
                focusedGroupIndex = if (prev <= 0) groups.lastIndex else prev - 1
                notifyFocusMove(groupsAdapter, prev, focusedGroupIndex)
                groupsLm.scrollToPositionWithOffset(focusedGroupIndex, (groupsRecycler.height / 3).coerceAtLeast(0))
                listener?.onSelectGroup(groups[focusedGroupIndex], true)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (groups.isEmpty()) return true
                val prev = focusedGroupIndex
                focusedGroupIndex = if (prev >= groups.lastIndex) 0 else prev + 1
                notifyFocusMove(groupsAdapter, prev, focusedGroupIndex)
                groupsLm.scrollToPositionWithOffset(focusedGroupIndex, (groupsRecycler.height / 3).coerceAtLeast(0))
                listener?.onSelectGroup(groups[focusedGroupIndex], true)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                showGroups = false
                groupsPanel.visibility = GONE
                setActiveColumnInternal(DrawerColumn.Channels)
                refreshColumnHighlights()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                listener?.onClose()
                true
            }
            else -> false
        }
    }

    private fun handleChannelsKey(keyCode: Int): Boolean {
        val channels = snapshot.channels
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (channels.isEmpty()) return true
                moveChannelFocus(if (focusedChannelIndex <= 0) channels.lastIndex else focusedChannelIndex - 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (channels.isEmpty()) return true
                moveChannelFocus(if (focusedChannelIndex >= channels.lastIndex) 0 else focusedChannelIndex + 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                currentFocusedChannel()?.let { ch ->
                    listener?.onSelectChannel(ch)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (snapshot.groups.isNotEmpty()) {
                    showGroups = true
                    groupsPanel.visibility = VISIBLE
                    focusedGroupIndex = snapshot.groups.indexOf(snapshot.selectedGroup).coerceAtLeast(0)
                    setActiveColumnInternal(DrawerColumn.Groups)
                    groupsAdapter.notifyDataSetChanged()
                    groupsLm.scrollToPositionWithOffset(focusedGroupIndex, (groupsRecycler.height / 3).coerceAtLeast(0))
                    refreshColumnHighlights()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (visibleDatePrograms.isNotEmpty()) {
                    setActiveColumnInternal(DrawerColumn.Programs)
                    refreshColumnHighlights()
                    scrollProgramToCenter(focusedProgramIndex)
                } else if (epgLoading) {
                    scheduleEpgLoad(immediate = true)
                } else {
                    Toast.makeText(context, "当前频道暂无节目单", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> false
        }
    }

    private fun handleProgramsKey(keyCode: Int): Boolean {
        val list = visibleDatePrograms
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (list.isEmpty()) return true
                if (focusedProgramIndex > 0) {
                    val prev = focusedProgramIndex
                    focusedProgramIndex = prev - 1
                    notifyFocusMove(programsAdapter, prev, focusedProgramIndex)
                    scrollProgramToCenter(focusedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (list.isEmpty()) return true
                if (focusedProgramIndex < list.lastIndex) {
                    val prev = focusedProgramIndex
                    focusedProgramIndex = prev + 1
                    notifyFocusMove(programsAdapter, prev, focusedProgramIndex)
                    scrollProgramToCenter(focusedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                showDates = false
                datesPanel.visibility = GONE
                setActiveColumnInternal(DrawerColumn.Channels)
                refreshColumnHighlights()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (availableDates.size > 1) {
                    showDates = true
                    datesPanel.visibility = VISIBLE
                    focusedDateIndex = selectedDate?.let { availableDates.indexOf(it).coerceAtLeast(0) } ?: 0
                    setActiveColumnInternal(DrawerColumn.Dates)
                    datesAdapter.notifyDataSetChanged()
                    datesLm.scrollToPositionWithOffset(focusedDateIndex, (datesRecycler.height / 3).coerceAtLeast(0))
                    refreshColumnHighlights()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val ch = currentFocusedChannel() ?: return true
                val prog = list.getOrNull(focusedProgramIndex) ?: return true
                val now = System.currentTimeMillis()
                when {
                    prog.endMillis <= now -> {
                        val catchupUrl = CatchupHelper.buildCatchupUrl(
                            liveUrl = ch.url,
                            modeRaw = ch.catchupMode,
                            sourceTemplate = ch.catchupSource,
                            start = Instant.ofEpochMilli(prog.startMillis),
                            stop = Instant.ofEpochMilli(prog.endMillis),
                        )
                        if (catchupUrl != null) {
                            listener?.onPlayProgram(
                                CatchupPlayRequest(
                                    catchupUrl = catchupUrl,
                                    liveUrl = ch.url,
                                    programTitle = prog.title,
                                    startMillis = prog.startMillis,
                                    endMillis = prog.endMillis,
                                    owner = ch,
                                )
                            )
                        } else {
                            Toast.makeText(context, "当前频道不支持回看", Toast.LENGTH_SHORT).show()
                        }
                    }
                    prog.startMillis <= now -> {
                        listener?.onSelectChannel(ch)
                    }
                    else -> {
                        Toast.makeText(context, "该节目尚未播出", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            else -> false
        }
    }

    private fun handleDatesKey(keyCode: Int): Boolean {
        val dates = availableDates
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (dates.isEmpty()) return true
                if (focusedDateIndex > 0) {
                    val prev = focusedDateIndex
                    focusedDateIndex = prev - 1
                    notifyFocusMove(datesAdapter, prev, focusedDateIndex)
                    selectDate(dates[focusedDateIndex])
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (dates.isEmpty()) return true
                if (focusedDateIndex < dates.lastIndex) {
                    val prev = focusedDateIndex
                    focusedDateIndex = prev + 1
                    notifyFocusMove(datesAdapter, prev, focusedDateIndex)
                    selectDate(dates[focusedDateIndex])
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                showDates = false
                datesPanel.visibility = GONE
                setActiveColumnInternal(DrawerColumn.Programs)
                refreshColumnHighlights()
                true
            }
            else -> false
        }
    }

    // ── 内部定位与 EPG 加载 ──────────────────────────────────────────────────

    private fun currentFocusedChannel(): Channel? = snapshot.channels.getOrNull(focusedChannelIndex)

    private fun setActiveColumnInternal(col: DrawerColumn) {
        if (activeColumn != col) {
            activeColumn = col
            listener?.onActiveColumnChanged(col)
        }
    }

    private fun refreshColumnHighlights() {
        if (focusedGroupIndex in snapshot.groups.indices) {
            groupsAdapter.notifyItemChanged(focusedGroupIndex, PAYLOAD_FOCUS)
        }
        if (focusedChannelIndex in snapshot.channels.indices) {
            channelsAdapter.notifyItemChanged(focusedChannelIndex, PAYLOAD_FOCUS)
        }
        if (focusedProgramIndex in visibleDatePrograms.indices) {
            programsAdapter.notifyItemChanged(focusedProgramIndex, PAYLOAD_FOCUS)
        }
        if (focusedDateIndex in availableDates.indices) {
            datesAdapter.notifyItemChanged(focusedDateIndex, PAYLOAD_FOCUS)
        }
    }

    private fun notifyFocusMove(adapter: RecyclerView.Adapter<*>, prev: Int, next: Int) {
        if (prev >= 0 && prev < adapter.itemCount) adapter.notifyItemChanged(prev, PAYLOAD_FOCUS)
        if (next >= 0 && next < adapter.itemCount) adapter.notifyItemChanged(next, PAYLOAD_FOCUS)
    }

    private fun moveChannelFocus(newIndex: Int) {
        val channels = snapshot.channels
        if (channels.isEmpty()) return
        val clamped = newIndex.coerceIn(0, channels.lastIndex)
        val prev = focusedChannelIndex
        focusedChannelIndex = clamped
        notifyFocusMove(channelsAdapter, prev, clamped)
        scrollChannelToCenter(clamped)
        updateChannelCounter()
        channels.getOrNull(clamped)?.let { ch ->
            listener?.onFocusedChannelChanged(ch.url)
        }
        scheduleEpgLoad(immediate = false)
        scheduleUrgentLogoPrefetch()
    }

    private fun updateChannelCounter() {
        val total = snapshot.channels.size
        channelsCountView.text = if (total == 0) "0/0" else "${focusedChannelIndex + 1}/$total"
    }

    private fun applyChannelCenterPadding(viewportH: Int) {
        if (viewportH <= 0) return
        val count = snapshot.channels.size
        val useCenter = count * channelRowHeightPx > viewportH
        val pad = if (useCenter) ((viewportH - channelRowHeightPx) / 2).coerceAtLeast(0) else dp(8)
        if (channelsRecycler.paddingTop != pad || channelsRecycler.paddingBottom != pad) {
            channelsRecycler.setPadding(0, pad, 0, pad)
        }
    }

    private fun scrollChannelToCenter(index: Int) {
        val count = snapshot.channels.size
        if (count == 0) return
        val h = channelsRecycler.height
        val useCenter = h > 0 && count * channelRowHeightPx > h
        if (useCenter) {
            // 因为 paddingTop == (h - rowH) / 2，offset = 0 即让该行精确落于几何中心（0px 偏差）
            channelsLm.scrollToPositionWithOffset(index.coerceIn(0, count - 1), 0)
        } else {
            channelsLm.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun applyProgramCenterPadding(viewportH: Int) {
        if (viewportH <= 0) return
        val count = visibleDatePrograms.size
        val useCenter = count * programRowHeightPx > viewportH
        val pad = if (useCenter) ((viewportH - programRowHeightPx) / 2).coerceAtLeast(0) else dp(6)
        if (programsRecycler.paddingTop != pad || programsRecycler.paddingBottom != pad) {
            programsRecycler.setPadding(0, pad, 0, pad)
        }
    }

    private fun scrollProgramToCenter(index: Int) {
        val count = visibleDatePrograms.size
        if (count == 0) return
        val h = programsRecycler.height
        val useCenter = h > 0 && count * programRowHeightPx > h
        if (useCenter) {
            programsLm.scrollToPositionWithOffset(index.coerceIn(0, count - 1), 0)
        } else {
            programsLm.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun scheduleEpgLoad(immediate: Boolean) {
        epgLoadJob?.cancel()
        val ch = currentFocusedChannel()
        if (ch == null) {
            renderEpgEmpty("暂无节目单")
            return
        }
        programsChannelTitleView.text = ch.title

        val cached = epgCache[ch.url]
        if (cached != null) {
            epgLoading = false
            applyLoadedPrograms(ch, cached)
            return
        }

        epgLoading = true
        if (loadedEpgChannelUrl != ch.url) {
            // 切换频道时立即清空上一频道的旧节目列表，绝不残留错台 EPG
            loadedEpgChannelUrl = null
            loadedEpgAllPrograms = emptyList()
            visibleDatePrograms = emptyList()
            availableDates = emptyList()
            selectedDate = null
            programsAdapter.notifyDataSetChanged()
            programsRecycler.visibility = GONE
            programsEmptyView.text = if (snapshot.epgSourceConfigured) "加载节目单…" else "未配置 EPG 源"
            programsEmptyView.visibility = VISIBLE
            programsSubtitleView.text = ""
        }

        epgLoadJob = scope.launch {
            if (!immediate) delay(EPG_DEBOUNCE_MS)
            if (!isOpen) return@launch
            val provider = snapshot.epgProgramProvider
            val data = snapshot.epgData
            val programs = withContext(Dispatchers.IO) {
                when {
                    provider != null -> provider(ch)
                    data != null -> data.resolveChannelId(ch)?.let { data.programsByChannelId[it] }.orEmpty()
                    else -> emptyList()
                }
            }
            if (!isOpen || currentFocusedChannel()?.url != ch.url) return@launch
            epgLoading = false
            // 仅在 EPG 版本已就绪或查到数据时写缓存，避免冷启 EPG 未载完时误缓存空列表
            if (programs.isNotEmpty() || snapshot.epgVersion > 0) {
                epgCache[ch.url] = programs
            }
            applyLoadedPrograms(ch, programs)
        }
    }

    private fun applyLoadedPrograms(ch: Channel, programs: List<EpgProgram>) {
        loadedEpgChannelUrl = ch.url
        loadedEpgAllPrograms = programs
        programsChannelTitleView.text = ch.title

        if (programs.isEmpty()) {
            val emptyHint = when {
                !snapshot.epgSourceConfigured -> "未配置 EPG 源"
                snapshot.epgLoadFailed -> "节目单加载失败"
                snapshot.epgVersion == 0 -> "正在载入节目单…"
                else -> "暂无节目单"
            }
            renderEpgEmpty(emptyHint)
            return
        }

        // 严格采用设备本地时区分组日期（修复 UTC+0 偏移导致日期与时刻全部错位 8 小时的问题）
        val localZone = ZoneId.systemDefault()
        val today = LocalDate.now(localZone)
        val dates = programs
            .map { Instant.ofEpochMilli(it.startMillis).atZone(localZone).toLocalDate() }
            .distinct()
            .sorted()

        availableDates = dates
        val defaultDate = dates.firstOrNull { it == today }
            ?: dates.lastOrNull { it.isBefore(today) }
            ?: dates.first()

        selectDate(defaultDate)
    }

    private fun selectDate(date: LocalDate) {
        selectedDate = date
        focusedDateIndex = availableDates.indexOf(date).coerceAtLeast(0)
        val localZone = ZoneId.systemDefault()
        val dayPrograms = loadedEpgAllPrograms.filter {
            Instant.ofEpochMilli(it.startMillis).atZone(localZone).toLocalDate() == date
        }
        visibleDatePrograms = dayPrograms

        val dateLabel = formatLocalDateLabel(date, localZone)
        val dateHint = if (availableDates.size > 1) "$dateLabel · 按 → 选日期" else dateLabel
        programsSubtitleView.text = dateHint

        if (dayPrograms.isEmpty()) {
            programsEmptyView.text = "当日暂无节目"
            programsEmptyView.visibility = VISIBLE
            programsRecycler.visibility = GONE
            return
        }

        programsEmptyView.visibility = GONE
        programsRecycler.visibility = VISIBLE

        val now = System.currentTimeMillis()
        focusedProgramIndex = dayPrograms.indexOfFirst { it.startMillis <= now && now < it.endMillis }
            .takeIf { it >= 0 }
            ?: dayPrograms.indexOfFirst { it.startMillis > now }.takeIf { it >= 0 }
            ?: dayPrograms.lastIndex.coerceAtLeast(0)

        applyProgramCenterPadding(programsRecycler.height)
        programsAdapter.notifyDataSetChanged()
        scrollProgramToCenter(focusedProgramIndex)
    }

    private fun renderEpgEmpty(message: String) {
        loadedEpgAllPrograms = emptyList()
        availableDates = emptyList()
        selectedDate = null
        visibleDatePrograms = emptyList()
        focusedProgramIndex = 0
        programsSubtitleView.text = ""
        programsEmptyView.text = message
        programsEmptyView.visibility = VISIBLE
        programsRecycler.visibility = GONE
        if (activeColumn == DrawerColumn.Programs || activeColumn == DrawerColumn.Dates) {
            showDates = false
            datesPanel.visibility = GONE
            setActiveColumnInternal(DrawerColumn.Channels)
            refreshColumnHighlights()
        }
    }

    private fun scheduleUrgentLogoPrefetch() {
        prefetchJob?.cancel()
        val channels = snapshot.channels
        if (channels.isEmpty()) return
        val center = focusedChannelIndex
        prefetchJob = scope.launch {
            delay(PerfPolicy.URGENT_PREFETCH_DELAY_MS)
            if (!isOpen) return@launch
            val urls = ArrayList<String>(18)
            for (delta in -8..8) {
                val idx = center + delta
                if (idx in channels.indices) {
                    channels[idx].logoUrl?.trim()?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
                }
            }
            if (urls.isNotEmpty()) {
                LogoLoader.prefetch(context, urls.distinct(), LogoLoader.CANONICAL_WIDTH_PX, LogoLoader.CANONICAL_HEIGHT_PX)
            }
        }
    }

    private fun formatLocalDateLabel(date: LocalDate, zone: ZoneId): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            today.plusDays(1) -> "明天"
            else -> date.format(DATE_FMT)
        }
    }

    private fun fallbackLogoChars(title: String): String {
        val clean = title.trim()
        if (clean.isEmpty()) return "TV"
        return if (clean.length <= 2) clean else clean.substring(0, 2)
    }

    // ── Adapter 1: Groups ────────────────────────────────────────────────────

    private inner class GroupsAdapter : RecyclerView.Adapter<GroupVH>() {
        override fun getItemCount(): Int = snapshot.groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(8), 0, dp(12), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, groupRowHeightPx).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
            val bar = View(context).apply {
                background = newAccentPillDrawable()
                layoutParams = LayoutParams(dp(3), dp(22)).apply { marginEnd = dp(10) }
            }
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(bar)
            row.addView(label)
            return GroupVH(row, rowBg, bar, label)
        }

        override fun onBindViewHolder(holder: GroupVH, position: Int) {
            val name = snapshot.groups[position]
            holder.label.text = name
            bindVisual(holder, position, name)
        }

        override fun onBindViewHolder(holder: GroupVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                bindVisual(holder, position, snapshot.groups[position])
            }
        }

        private fun bindVisual(holder: GroupVH, position: Int, name: String) {
            val isFocused = activeColumn == DrawerColumn.Groups && position == focusedGroupIndex
            val isSelected = name == snapshot.selectedGroup
            holder.rowBg.setColor(
                when {
                    isFocused -> COLOR_FOCUS_BG
                    isSelected -> COLOR_INACTIVE_SELECTED_BG
                    else -> Color.TRANSPARENT
                }
            )
            holder.bar.visibility = if (isFocused) VISIBLE else INVISIBLE
            holder.label.setTextColor(
                when {
                    isSelected -> COLOR_ACCENT_BLUE
                    isFocused -> COLOR_TEXT_PRIMARY
                    else -> COLOR_TEXT_SECONDARY
                }
            )
            holder.label.typeface = if (isFocused || isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private class GroupVH(
        root: View,
        val rowBg: GradientDrawable,
        val bar: View,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    // ── Adapter 2: Channels（零 relayout 60fps 核心） ─────────────────────────

    private inner class ChannelsAdapter : RecyclerView.Adapter<ChannelVH>() {
        override fun getItemCount(): Int = snapshot.channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(6), dp(6), dp(12), dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, channelRowHeightPx).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }

            val bar = View(context).apply {
                background = newAccentPillDrawable()
                layoutParams = LayoutParams(dp(3), dp(36)).apply { marginEnd = dp(8) }
            }

            val numTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                isSingleLine = true
                layoutParams = LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
            }

            val logoBox = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(6f)
                    setColor(COLOR_LOGO_PLATE)
                }
                layoutParams = LayoutParams(dp(44), dp(28)).apply { marginEnd = dp(10) }
            }
            val logoImg = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            val logoFallback = TextView(context).apply {
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isSingleLine = true
            }
            logoBox.addView(logoFallback, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            logoBox.addView(logoImg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            val textCol = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val programTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            val progressTrack = FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(1.5f)
                    setColor(COLOR_PROGRESS_TRACK)
                }
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
                    topMargin = dp(4)
                }
            }
            val progressFill = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpF(1.5f)
                    setColor(COLOR_ACCENT_BLUE)
                }
                pivotX = 0f
            }
            progressTrack.addView(
                progressFill,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )

            textCol.addView(titleTv)
            textCol.addView(programTv)
            textCol.addView(progressTrack)

            row.addView(bar)
            row.addView(numTv)
            row.addView(logoBox)
            row.addView(textCol)
            return ChannelVH(row, rowBg, bar, numTv, logoImg, logoFallback, titleTv, programTv, progressTrack, progressFill)
        }

        override fun onBindViewHolder(holder: ChannelVH, position: Int) {
            val ch = snapshot.channels[position]
            val num = snapshot.channelNumbers[ch.url] ?: (position + 1)
            holder.numTv.text = num.toString()
            holder.titleTv.text = ch.title

            bindNowProgram(holder, ch)
            bindLogo(holder, ch)
            bindFocusAndPlaying(holder, position, ch)
        }

        override fun onBindViewHolder(holder: ChannelVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
                return
            }
            val ch = snapshot.channels[position]
            if (payloads.contains(PAYLOAD_NOW_OR_PLAYING)) {
                bindNowProgram(holder, ch)
            }
            bindFocusAndPlaying(holder, position, ch)
        }

        private fun bindNowProgram(holder: ChannelVH, ch: Channel) {
            val nowUi = snapshot.nowProgramByChannelUrl[ch.url]
            val progTitle = nowUi?.title
            if (progTitle.isNullOrBlank()) {
                holder.programTv.visibility = GONE
                holder.progressTrack.visibility = GONE
            } else {
                holder.programTv.visibility = VISIBLE
                holder.programTv.text = progTitle
                val p = nowUi.progress
                if (p != null) {
                    holder.progressTrack.visibility = VISIBLE
                    holder.progressFill.pivotX = 0f
                    holder.progressFill.scaleX = p.coerceIn(0.02f, 1f)
                } else {
                    holder.progressTrack.visibility = GONE
                }
            }
        }

        private fun bindLogo(holder: ChannelVH, ch: Channel) {
            val url = ch.logoUrl?.trim().orEmpty()
            holder.boundLogoUrl = url
            holder.logoJob?.cancel()
            holder.logoJob = null

            if (url.isEmpty() || LogoLoader.logoLoadEnabledFlow.value == false) {
                holder.logoImg.setImageDrawable(null)
                holder.logoImg.visibility = GONE
                holder.logoFallback.text = fallbackLogoChars(ch.title)
                holder.logoFallback.visibility = VISIBLE
                return
            }

            val cached = LogoLoader.getInMemoryBitmap(url)
            if (cached != null) {
                holder.logoImg.setImageBitmap(cached)
                holder.logoImg.visibility = VISIBLE
                holder.logoFallback.visibility = GONE
                return
            }

            holder.logoImg.setImageDrawable(null)
            holder.logoImg.visibility = GONE
            holder.logoFallback.text = fallbackLogoChars(ch.title)
            holder.logoFallback.visibility = VISIBLE

            holder.logoJob = scope.launch {
                val bmp = LogoLoader.loadBitmap(context, url)
                if (bmp != null && holder.boundLogoUrl == url) {
                    holder.logoImg.setImageBitmap(bmp)
                    holder.logoImg.visibility = VISIBLE
                    holder.logoFallback.visibility = GONE
                }
            }
        }

        private fun bindFocusAndPlaying(holder: ChannelVH, position: Int, ch: Channel) {
            val isFocused = position == focusedChannelIndex
            val isActiveFocus = isFocused && activeColumn == DrawerColumn.Channels
            val isPlaying = ch.url == snapshot.selectedChannelUrl

            holder.rowBg.setColor(
                when {
                    isActiveFocus -> COLOR_FOCUS_BG
                    isFocused -> COLOR_INACTIVE_SELECTED_BG
                    isPlaying -> COLOR_PLAYING_BG
                    else -> Color.TRANSPARENT
                }
            )
            holder.itemView.contentDescription = if (isActiveFocus) {
                "drawer_focused_channel:${ch.title}"
            } else {
                "drawer_channel:${ch.title}"
            }
            holder.bar.visibility = if (isActiveFocus) VISIBLE else INVISIBLE

            val accentText = if (isPlaying) COLOR_ACCENT_BLUE else COLOR_TEXT_PRIMARY
            holder.titleTv.setTextColor(accentText)
            holder.titleTv.typeface = if (isPlaying || isActiveFocus) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.numTv.setTextColor(if (isPlaying) COLOR_ACCENT_BLUE else if (isActiveFocus) COLOR_TEXT_PRIMARY else COLOR_TEXT_MUTED)
            holder.programTv.setTextColor(if (isActiveFocus) COLOR_TEXT_SECONDARY else COLOR_TEXT_MUTED)
        }

        override fun onViewRecycled(holder: ChannelVH) {
            holder.logoJob?.cancel()
            holder.logoJob = null
            holder.boundLogoUrl = ""
            super.onViewRecycled(holder)
        }
    }

    private class ChannelVH(
        root: View,
        val rowBg: GradientDrawable,
        val bar: View,
        val numTv: TextView,
        val logoImg: ImageView,
        val logoFallback: TextView,
        val titleTv: TextView,
        val programTv: TextView,
        val progressTrack: FrameLayout,
        val progressFill: View,
    ) : RecyclerView.ViewHolder(root) {
        var boundLogoUrl: String = ""
        var logoJob: Job? = null
    }

    // ── Adapter 3: Programs（严格本地时区显示） ──────────────────────────────

    private inner class ProgramsAdapter : RecyclerView.Adapter<ProgramVH>() {
        override fun getItemCount(): Int = visibleDatePrograms.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgramVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(6), dp(6), dp(12), dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, programRowHeightPx).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
            val bar = View(context).apply {
                background = newAccentPillDrawable()
                layoutParams = LayoutParams(dp(3), dp(30)).apply { marginEnd = dp(8) }
            }
            val col = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val metaRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val timeTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                isSingleLine = true
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badgeTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.DEFAULT_BOLD
                isSingleLine = true
            }
            metaRow.addView(timeTv)
            metaRow.addView(badgeTv)

            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            col.addView(metaRow)
            col.addView(titleTv)
            row.addView(bar)
            row.addView(col)
            return ProgramVH(row, rowBg, bar, timeTv, badgeTv, titleTv)
        }

        override fun onBindViewHolder(holder: ProgramVH, position: Int) {
            val prog = visibleDatePrograms[position]
            val localZone = ZoneId.systemDefault()
            val startStr = Instant.ofEpochMilli(prog.startMillis).atZone(localZone).format(TIME_FMT)
            val endStr = Instant.ofEpochMilli(prog.endMillis).atZone(localZone).format(TIME_FMT)
            holder.timeTv.text = "$startStr - $endStr"
            holder.titleTv.text = prog.title

            val now = System.currentTimeMillis()
            val ch = currentFocusedChannel()
            val hasCatchup = ch != null && !ch.catchupMode.isNullOrBlank() &&
                ch.catchupMode.lowercase() != "none" && !ch.catchupSource.isNullOrBlank()

            when {
                prog.startMillis <= now && now < prog.endMillis -> {
                    holder.badgeTv.text = "正在播出"
                    holder.badgeTv.setTextColor(COLOR_ACCENT_SOFT)
                    holder.badgeTv.visibility = VISIBLE
                }
                prog.endMillis <= now && hasCatchup -> {
                    holder.badgeTv.text = "回看"
                    holder.badgeTv.setTextColor(COLOR_CATCHUP_MINT)
                    holder.badgeTv.visibility = VISIBLE
                }
                prog.startMillis > now -> {
                    holder.badgeTv.text = "预告"
                    holder.badgeTv.setTextColor(COLOR_TEXT_MUTED)
                    holder.badgeTv.visibility = VISIBLE
                }
                else -> {
                    holder.badgeTv.visibility = GONE
                }
            }
            bindVisual(holder, position, prog, now)
        }

        override fun onBindViewHolder(holder: ProgramVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                bindVisual(holder, position, visibleDatePrograms[position], System.currentTimeMillis())
            }
        }

        private fun bindVisual(holder: ProgramVH, position: Int, prog: EpgProgram, now: Long) {
            val isFocused = activeColumn == DrawerColumn.Programs && position == focusedProgramIndex
            val isLive = prog.startMillis <= now && now < prog.endMillis
            holder.rowBg.setColor(
                when {
                    isFocused -> COLOR_FOCUS_BG
                    isLive -> COLOR_PLAYING_BG
                    else -> Color.TRANSPARENT
                }
            )
            holder.bar.visibility = if (isFocused) VISIBLE else INVISIBLE
            holder.timeTv.setTextColor(if (isLive) COLOR_ACCENT_SOFT else if (isFocused) COLOR_TEXT_PRIMARY else COLOR_TEXT_MUTED)
            holder.titleTv.setTextColor(
                when {
                    isLive -> COLOR_ACCENT_BLUE
                    isFocused -> COLOR_TEXT_PRIMARY
                    prog.endMillis <= now -> COLOR_TEXT_SECONDARY
                    else -> COLOR_TEXT_MUTED
                }
            )
            holder.titleTv.typeface = if (isFocused || isLive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private class ProgramVH(
        root: View,
        val rowBg: GradientDrawable,
        val bar: View,
        val timeTv: TextView,
        val badgeTv: TextView,
        val titleTv: TextView,
    ) : RecyclerView.ViewHolder(root)

    // ── Adapter 4: Dates ─────────────────────────────────────────────────────

    private inner class DatesAdapter : RecyclerView.Adapter<DateVH>() {
        override fun getItemCount(): Int = availableDates.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(8), 0, dp(10), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dateRowHeightPx).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
            }
            val bar = View(context).apply {
                background = newAccentPillDrawable()
                layoutParams = LayoutParams(dp(3), dp(22)).apply { marginEnd = dp(8) }
            }
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                isSingleLine = true
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(bar)
            row.addView(label)
            return DateVH(row, rowBg, bar, label)
        }

        override fun onBindViewHolder(holder: DateVH, position: Int) {
            val d = availableDates[position]
            holder.label.text = formatLocalDateLabel(d, ZoneId.systemDefault())
            bindVisual(holder, position, d)
        }

        override fun onBindViewHolder(holder: DateVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                bindVisual(holder, position, availableDates[position])
            }
        }

        private fun bindVisual(holder: DateVH, position: Int, d: LocalDate) {
            val isFocused = activeColumn == DrawerColumn.Dates && position == focusedDateIndex
            val isSelected = d == selectedDate
            holder.rowBg.setColor(
                when {
                    isFocused -> COLOR_FOCUS_BG
                    isSelected -> COLOR_INACTIVE_SELECTED_BG
                    else -> Color.TRANSPARENT
                }
            )
            holder.bar.visibility = if (isFocused) VISIBLE else INVISIBLE
            holder.label.setTextColor(
                when {
                    isSelected -> COLOR_ACCENT_BLUE
                    isFocused -> COLOR_TEXT_PRIMARY
                    else -> COLOR_TEXT_SECONDARY
                }
            )
            holder.label.typeface = if (isFocused || isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private class DateVH(
        root: View,
        val rowBg: GradientDrawable,
        val bar: View,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    companion object {
        private const val PAYLOAD_FOCUS = "focus"
        private const val PAYLOAD_NOW_OR_PLAYING = "now_or_playing"
        private const val EPG_DEBOUNCE_MS = 150L

        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

        private val COLOR_SURFACE_SOLID = Color.argb(238, 13, 17, 24)       // #EE0D1118 deep obsidian
        private val COLOR_SURFACE_TRANSLUCENT = Color.argb(200, 13, 17, 24) // #C80D1118 smoked glass (legible over bright daylight scenes)
        private val COLOR_FOCUS_BG = Color.argb(52, 255, 255, 255)          // #34FFFFFF crisp TV glass focus
        private val COLOR_INACTIVE_SELECTED_BG = Color.argb(24, 255, 255, 255)
        private val COLOR_PLAYING_BG = Color.argb(34, 47, 107, 255)         // refined brand blue tint
        private val COLOR_LOGO_PLATE = Color.argb(26, 255, 255, 255)
        private val COLOR_PROGRESS_TRACK = Color.argb(46, 255, 255, 255)
        private val COLOR_ACCENT_BLUE = Color.rgb(92, 152, 255)             // #5C98FF vivid luminous blue on obsidian
        private val COLOR_ACCENT_SOFT = Color.rgb(148, 194, 255)            // #94C2FF soft sky accent
        private val COLOR_CATCHUP_MINT = Color.rgb(138, 230, 184)           // #8AE6B8
        private val COLOR_TEXT_PRIMARY = Color.rgb(245, 248, 252)           // #F5F8FC
        private val COLOR_TEXT_SECONDARY = Color.rgb(197, 206, 222)         // #C5CEDE
        private val COLOR_TEXT_MUTED = Color.rgb(154, 166, 188)             // #9AA6BC
    }
}
