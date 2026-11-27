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
 * 原生 Android [RecyclerView] 多列选台抽屉（60fps 弱机性能底座）。
 *
 * 设计要点：
 * 1. **自然有界居中滚动（无首尾大段空白）**：
 *    通过 [boundedCenterOffset] 计算自然滚动偏移：首屏前几台紧贴顶部、末屏最后几台紧贴底部、
 *    中段频道自动保持在视口正中；彻底消除顶部空白，兼具舒适视野与居中浏览体验；
 * 2. **逐级展开与干净边界**：
 *    初始展示频道+节目单；向左展开分组时自动收起节目单；多源时在分组列再向左才逐级展开播放源；
 *    面板右缘干净利落，无渐变拖尾；
 * 3. **对称高亮焦点（无冗余蓝色竖标）**：
 *    所有行左右完全对称，仅以清晰的磨砂高亮底色表达焦点；品牌蓝仅用于正在播放的频道与直播节目；
 * 4. **聚焦长名跑马灯**：
 *    过长的节目名/频道名在被聚焦时按 [PerfPolicy.MARQUEE_ITERATIONS] 有界水平滚动，失焦即停；
 * 5. **严格本地时区 EPG + 零重组**：
 *    统一按 [ZoneId.systemDefault] 格式化时刻与日期；由 `PlayerActivity.dispatchKeyEvent` 同步直达 [handleKeyEvent]。
 */
class NativeChannelDrawerView(context: Context) : LinearLayout(context) {

    data class Snapshot(
        val sources: List<PlaylistSource> = emptyList(),
        val activeSourceId: String? = null,
        val groups: List<String> = emptyList(),
        val selectedGroup: String = SidebarState.ALL_GROUP,
        val channels: List<Channel> = emptyList(),
        val channelNumbers: Map<String, Int> = emptyMap(),
        val selectedChannelUrl: String? = null,
        val selectedChannelKey: String? = null,
        val nowProgramByChannelUrl: Map<String, NowProgramUi> = emptyMap(),
        val epgData: EpgData? = null,
        val epgVersion: Int = 0,
        val epgProgramProvider: (suspend (Channel) -> List<EpgProgram>)? = null,
        val epgSourceConfigured: Boolean = true,
        val epgLoadFailed: Boolean = false,
        val initialFocusUrl: String? = null,
        val uiScale: UiScalePreset = UiScalePreset.DEFAULT,
        val uiTransparencyPct: Int = 18,
        val lang: AppLang = AppLang.ZH,
        val favoriteUrls: Set<String> = emptySet(),
    )

    interface Listener {
        fun onSelectSource(source: PlaylistSource)
        fun onSelectGroup(group: String, resetFocus: Boolean)
        fun onSelectChannel(channel: Channel)
        fun onToggleFavorite(channel: Channel) {}
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

    private var focusedSourceIndex = 0
    private var focusedGroupIndex = 0
    private var focusedChannelIndex = 0
    private var focusedProgramIndex = 0
    private var focusedDateIndex = 0

    // EPG 缓存按 epgVersion 分代，永不缓存空载期假空结果
    private var cachedEpgVersion = -1
    private val epgCache = object : LinkedHashMap<String, List<EpgProgram>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EpgProgram>>?): Boolean = size > 128
    }
    private var loadedEpgChannelUrl: String? = null
    private var loadedEpgAllPrograms: List<EpgProgram> = emptyList()
    private var availableDates: List<LocalDate> = emptyList()
    private var selectedDate: LocalDate? = null
    private var visibleDatePrograms: List<EpgProgram> = emptyList()
    private var epgLoading = false

    private var epgLoadJob: Job? = null
    private var epgPrefetchJob: Job? = null
    private var prefetchJob: Job? = null
    private var pendingGroupSelectJob: Job? = null
    private var pendingGroupName: String? = null
    private var perfOpenAtMs = 0L

    private val density = resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * density).roundToInt()
    private fun dpF(v: Float): Float = v * density

    private var appliedUiScale: UiScalePreset = UiScalePreset.DEFAULT
    private val textScale: Float get() = appliedUiScale.textScale
    private val logoScale: Float get() = appliedUiScale.logoScale

    private val channelRowHeightPx: Int get() = dp((66f * logoScale).roundToInt().coerceIn(56, 78))
    private val groupRowHeightPx: Int get() = dp((46f * textScale).roundToInt().coerceIn(40, 54))
    private val programRowHeightPx: Int get() = dp((58f * textScale).roundToInt().coerceIn(50, 68))
    private val dateRowHeightPx: Int get() = dp((44f * textScale).roundToInt().coerceIn(38, 52))
    private val channelLogoWidthPx: Int get() = dp((58f * logoScale).roundToInt())
    private val channelLogoHeightPx: Int get() = dp((36f * logoScale).roundToInt())

    // 视图组件
    private val sourcesPanel: LinearLayout
    private val sourcesTitleView: TextView
    private val sourcesCountView: TextView
    private val sourcesRecycler: RecyclerView
    private val sourcesLm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val sourcesAdapter = SourcesAdapter()

    private val groupsPanel: LinearLayout
    private val groupsTitleView: TextView
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
    private val datesTitleView: TextView
    private val datesRecycler: RecyclerView
    private val datesLm = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
    private val datesAdapter = DatesAdapter()

    private fun newRowBgDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(6f)
        setColor(Color.TRANSPARENT)
    }

    init {
        orientation = HORIZONTAL
        visibility = GONE
        isClickable = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "native_channel_drawer"

        // ── 0. 播放源列（仅在分组列继续按 LEFT 时逐级展开，多源 ≥2 可用） ─────
        sourcesPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_DEEP)
            visibility = GONE
            layoutParams = LayoutParams(dp(148), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val sourcesHeader = buildColumnHeader("播放源", "").also {
            sourcesTitleView = it.first.getChildAt(0) as TextView
            sourcesCountView = it.second
        }
        sourcesPanel.addView(sourcesHeader.first)
        sourcesRecycler = buildRecycler(sourcesLm, sourcesAdapter).apply {
            contentDescription = "drawer_sources_list"
            setPadding(0, dp(6), 0, dp(6))
        }
        sourcesPanel.addView(sourcesRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(sourcesPanel)

        // ── 1. 分组列（按 LEFT 展开，展开时自动收起右侧节目单） ───────────────
        groupsPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_SOLID)
            visibility = GONE
            layoutParams = LayoutParams(dp(156), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val groupsHeader = buildColumnHeader("分组", "").also {
            groupsTitleView = it.first.getChildAt(0) as TextView
            groupsCountView = it.second
        }
        groupsPanel.addView(groupsHeader.first)
        groupsRecycler = buildRecycler(groupsLm, groupsAdapter).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        groupsPanel.addView(groupsRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(groupsPanel)

        // ── 2. 频道列（自然有界居中滚动，顶部/底部零空白，支持小/中/大缩放） ────
        channelsPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_SOLID)
            layoutParams = LayoutParams(dp(278), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val channelsHeader = buildColumnHeader("全部", "0/0")
        channelsTitleView = channelsHeader.first.getChildAt(0) as TextView
        channelsCountView = channelsHeader.second
        channelsPanel.addView(channelsHeader.first)
        channelsRecycler = buildRecycler(channelsLm, channelsAdapter).apply {
            contentDescription = "drawer_channels_list"
            setPadding(0, dp(6), 0, dp(6))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    post { if (isOpen) scrollChannelBounded(focusedChannelIndex) }
                }
            }
        }
        channelsPanel.addView(channelsRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(channelsPanel)

        // ── 3. 节目单列（紧凑 216dp，干净边界，无右缘渐变拖尾） ───────────────
        programsPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_TRANSLUCENT)
            layoutParams = LayoutParams(dp(216), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val programsHeader = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        }
        programsChannelTitleView = TextView(context).apply {
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        programsSubtitleView = TextView(context).apply {
            setTextColor(COLOR_TEXT_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
            setPadding(0, dp(6), 0, dp(6))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop) {
                    post { if (isOpen) scrollProgramBounded(focusedProgramIndex) }
                }
            }
        }
        programsEmptyView = TextView(context).apply {
            text = "暂无节目单"
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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

        // ── 4. 日期列（在节目列按 RIGHT 展开，干净边界） ──────────────────────
        datesPanel = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(COLOR_SURFACE_TRANSLUCENT)
            visibility = GONE
            layoutParams = LayoutParams(dp(104), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val datesHeader = buildColumnHeader("日期", "").also {
            datesTitleView = it.first.getChildAt(0) as TextView
        }
        datesPanel.addView(datesHeader.first)
        datesRecycler = buildRecycler(datesLm, datesAdapter).apply {
            setPadding(0, dp(6), 0, dp(6))
        }
        datesPanel.addView(datesRecycler, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(datesPanel)
    }

    private fun buildColumnHeader(title: String, rightText: String): Pair<LinearLayout, TextView> {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(6))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        }
        val titleTv = TextView(context).apply {
            text = title
            setTextColor(COLOR_TEXT_PRIMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val rightTv = TextView(context).apply {
            text = rightText
            setTextColor(COLOR_TEXT_MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
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
        epgPrefetchJob?.cancel()
        prefetchJob?.cancel()
        pendingGroupSelectJob?.cancel()
        pendingGroupName = null
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun applyUiScale(scale: UiScalePreset) {
        appliedUiScale = scale
        val sourcesW = when (scale) {
            UiScalePreset.SMALL -> dp(136)
            UiScalePreset.MEDIUM -> dp(148)
            UiScalePreset.LARGE -> dp(168)
        }
        val groupsW = when (scale) {
            UiScalePreset.SMALL -> dp(148)
            UiScalePreset.MEDIUM -> dp(158)
            UiScalePreset.LARGE -> dp(178)
        }
        val channelsW = when (scale) {
            UiScalePreset.SMALL -> dp(254)
            UiScalePreset.MEDIUM -> dp(278)
            UiScalePreset.LARGE -> dp(312)
        }
        val programsW = when (scale) {
            UiScalePreset.SMALL -> dp(204)
            UiScalePreset.MEDIUM -> dp(220)
            UiScalePreset.LARGE -> dp(248)
        }
        val datesW = when (scale) {
            UiScalePreset.SMALL -> dp(98)
            UiScalePreset.MEDIUM -> dp(106)
            UiScalePreset.LARGE -> dp(120)
        }
        (sourcesPanel.layoutParams as? LayoutParams)?.width = sourcesW
        (groupsPanel.layoutParams as? LayoutParams)?.width = groupsW
        (channelsPanel.layoutParams as? LayoutParams)?.width = channelsW
        (programsPanel.layoutParams as? LayoutParams)?.width = programsW
        (datesPanel.layoutParams as? LayoutParams)?.width = datesW
        sourcesTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale.textScale)
        sourcesCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale.textScale)
        groupsTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale.textScale)
        groupsCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale.textScale)
        datesTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale.textScale)
        channelsTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale.textScale)
        channelsCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale.textScale)
        programsChannelTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * scale.textScale)
        programsSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale.textScale)

        // 清空复用池并重绑适配器，确保所有行高、台标与字号立即切换至新档位
        sourcesRecycler.recycledViewPool.clear()
        groupsRecycler.recycledViewPool.clear()
        channelsRecycler.recycledViewPool.clear()
        programsRecycler.recycledViewPool.clear()
        datesRecycler.recycledViewPool.clear()
        sourcesRecycler.adapter = sourcesAdapter
        groupsRecycler.adapter = groupsAdapter
        channelsRecycler.adapter = channelsAdapter
        programsRecycler.adapter = programsAdapter
        datesRecycler.adapter = datesAdapter
        requestLayout()
        if (isOpen) {
            scrollChannelBounded(focusedChannelIndex)
            scrollProgramBounded(focusedProgramIndex)
        }
    }

    // ── 数据与开合控制 ───────────────────────────────────────────────────────

    private fun t(zh: String, en: String): String =
        if (snapshot.lang == AppLang.EN) en else zh

    private fun displayGroupLabel(raw: String): String {
        if (snapshot.lang != AppLang.EN) return raw
        return when (raw) {
            SidebarState.ALL_GROUP -> "All"
            SidebarState.FAVORITES_GROUP -> "Favorites"
            "央视频道" -> "CCTV"
            "卫视频道" -> "Satellite TV"
            "卡通频道" -> "Kids"
            "其他频道" -> "Other"
            "未分组" -> "Ungrouped"
            else -> raw
        }
    }

    private fun applyLocalizedStaticHeaders() {
        sourcesTitleView.text = t("播放源", "Sources")
        groupsTitleView.text = t("分组", "Groups")
        datesTitleView.text = t("日期", "Date")
        channelsTitleView.text = displayGroupLabel(snapshot.selectedGroup)
        sourcesCountView.text = "${snapshot.sources.size}"
        updateGroupsRightHint()
    }

    private fun updateGroupsRightHint() {
        groupsCountView.text = "${snapshot.groups.size}"
        groupsCountView.setTextColor(COLOR_TEXT_MUTED)
    }

    private fun applySurfaceTransparency(pct: Int) {
        val factor = Prefs.uiSurfaceAlpha(pct)
        fun scaled(argb: Int): Int {
            val baseAlpha = (argb ushr 24) and 0xFF
            val alpha = (baseAlpha * factor).toInt().coerceIn(0x40, 0xFF)
            return (alpha shl 24) or (argb and 0x00FFFFFF)
        }
        val deep = scaled(COLOR_SURFACE_DEEP)
        val solid = scaled(COLOR_SURFACE_SOLID)
        val translucent = scaled(COLOR_SURFACE_TRANSLUCENT)
        sourcesPanel.setBackgroundColor(deep)
        groupsPanel.setBackgroundColor(solid)
        channelsPanel.setBackgroundColor(solid)
        programsPanel.setBackgroundColor(translucent)
        datesPanel.setBackgroundColor(translucent)
    }

    fun updateSnapshot(newSnapshot: Snapshot) {
        val prev = snapshot
        snapshot = newSnapshot

        if (newSnapshot.uiScale != appliedUiScale) {
            applyUiScale(newSnapshot.uiScale)
        }
        if (prev.uiTransparencyPct != newSnapshot.uiTransparencyPct) {
            applySurfaceTransparency(newSnapshot.uiTransparencyPct)
        }

        if (newSnapshot.epgVersion != cachedEpgVersion) {
            cachedEpgVersion = newSnapshot.epgVersion
            epgPrefetchJob?.cancel()
            epgCache.clear()
            loadedEpgChannelUrl = null
        }

        val langChanged = prev.lang != newSnapshot.lang
        val sourcesChanged = prev.sources != newSnapshot.sources || prev.activeSourceId != newSnapshot.activeSourceId
        val groupsChanged = prev.groups != newSnapshot.groups || prev.selectedGroup != newSnapshot.selectedGroup
        val channelsChanged = prev.channels !== newSnapshot.channels && prev.channels != newSnapshot.channels
        val playingChanged = prev.selectedChannelKey != newSnapshot.selectedChannelKey ||
            prev.selectedChannelUrl != newSnapshot.selectedChannelUrl
        val nowProgramsChanged = prev.nowProgramByChannelUrl !== newSnapshot.nowProgramByChannelUrl

        applyLocalizedStaticHeaders()

        if (sourcesChanged) {
            focusedSourceIndex = newSnapshot.sources.indexOfFirst { it.id == newSnapshot.activeSourceId }.coerceAtLeast(0)
            if (isOpen) {
                if (activeColumn == DrawerColumn.Sources && newSnapshot.sources.size < 2) {
                    sourcesPanel.visibility = GONE
                    setActiveColumnInternal(DrawerColumn.Groups)
                } else if (activeColumn != DrawerColumn.Sources) {
                    sourcesPanel.visibility = GONE
                }
                sourcesAdapter.notifyDataSetChanged()
            }
        }

        if (groupsChanged) {
            if (!(activeColumn == DrawerColumn.Groups && prev.groups == newSnapshot.groups)) {
                focusedGroupIndex = newSnapshot.groups.indexOf(newSnapshot.selectedGroup).coerceAtLeast(0)
                if (isOpen) groupsAdapter.notifyDataSetChanged()
            }
        }

        if (langChanged && isOpen) {
            groupsAdapter.notifyDataSetChanged()
            programsAdapter.notifyDataSetChanged()
            datesAdapter.notifyDataSetChanged()
            selectedDate?.let { d ->
                programsSubtitleView.text = formatLocalDateLabel(d, ZoneId.systemDefault())
            }
        }

        if (channelsChanged) {
            val anchorUrl = currentFocusedChannel()?.url
                ?.takeIf { u -> newSnapshot.channels.any { it.url == u } }
                ?: newSnapshot.initialFocusUrl?.takeIf { u -> newSnapshot.channels.any { it.url == u } }
                ?: newSnapshot.selectedChannelUrl?.takeIf { u -> newSnapshot.channels.any { it.url == u } }
            focusedChannelIndex = if (anchorUrl != null) {
                newSnapshot.channels.indexOfFirst { it.url == anchorUrl }.coerceAtLeast(0)
            } else 0
            if (isOpen) {
                channelsAdapter.notifyDataSetChanged()
                scrollChannelBounded(focusedChannelIndex)
                updateChannelCounter()
                if (!showGroups) scheduleEpgLoad(immediate = true)
            }
        } else if (isOpen && (playingChanged || nowProgramsChanged)) {
            channelsAdapter.notifyItemRangeChanged(0, newSnapshot.channels.size, PAYLOAD_NOW_OR_PLAYING)
        }

        if (isOpen && !showGroups && prev.epgVersion != newSnapshot.epgVersion) {
            scheduleEpgLoad(immediate = true)
        }
    }

    fun setOpen(open: Boolean) {
        if (isOpen == open) return
        isOpen = open
        if (!open) {
            epgLoadJob?.cancel()
            epgPrefetchJob?.cancel()
            prefetchJob?.cancel()
            pendingGroupSelectJob?.cancel()
            pendingGroupSelectJob = null
            pendingGroupName = null
            LogoLoader.logPerfCounters(drawerOpen = false)
            val tClose = SystemClock.elapsedRealtime()
            visibility = GONE
            showGroups = false
            showDates = false
            sourcesPanel.visibility = GONE
            groupsPanel.visibility = GONE
            programsPanel.visibility = VISIBLE
            datesPanel.visibility = GONE
            setActiveColumnInternal(DrawerColumn.Channels)
            PerfTrace.d { "drawer_close ms=${SystemClock.elapsedRealtime() - tClose}" }
            return
        }

        perfOpenAtMs = SystemClock.elapsedRealtime()
        showGroups = false
        showDates = false
        sourcesPanel.visibility = GONE
        groupsPanel.visibility = GONE
        programsPanel.visibility = VISIBLE
        datesPanel.visibility = GONE
        setActiveColumnInternal(DrawerColumn.Channels)

        focusedSourceIndex = snapshot.sources.indexOfFirst { it.id == snapshot.activeSourceId }.coerceAtLeast(0)
        focusedGroupIndex = snapshot.groups.indexOf(snapshot.selectedGroup).coerceAtLeast(0)
        applyLocalizedStaticHeaders()

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
        sourcesAdapter.notifyDataSetChanged()
        groupsAdapter.notifyDataSetChanged()
        channelsAdapter.notifyDataSetChanged()
        updateChannelCounter()
        // 首次冷开在首次 layout 前即写入目标位置与居中偏移，确保第 1 帧直接落位，零闪跳到第 1 台
        scrollChannelBounded(focusedChannelIndex)

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
            DrawerColumn.Sources -> handleSourcesKey(keyCode)
            DrawerColumn.Groups -> handleGroupsKey(keyCode)
            DrawerColumn.Channels -> handleChannelsKey(keyCode)
            DrawerColumn.Programs -> handleProgramsKey(keyCode)
            DrawerColumn.Dates -> handleDatesKey(keyCode)
        }
    }

    private fun handleSourcesKey(keyCode: Int): Boolean {
        val sources = snapshot.sources
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (sources.isEmpty()) return true
                val prev = focusedSourceIndex
                focusedSourceIndex = if (prev <= 0) sources.lastIndex else prev - 1
                notifyFocusMove(sourcesAdapter, prev, focusedSourceIndex)
                scrollSourceBounded(focusedSourceIndex)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (sources.isEmpty()) return true
                val prev = focusedSourceIndex
                focusedSourceIndex = if (prev >= sources.lastIndex) 0 else prev + 1
                notifyFocusMove(sourcesAdapter, prev, focusedSourceIndex)
                scrollSourceBounded(focusedSourceIndex)
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val chosen = sources.getOrNull(focusedSourceIndex) ?: return true
                if (chosen.id != snapshot.activeSourceId) {
                    listener?.onSelectSource(chosen)
                }
                sourcesPanel.visibility = GONE
                setActiveColumnInternal(DrawerColumn.Groups)
                refreshColumnHighlights()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                sourcesPanel.visibility = GONE
                setActiveColumnInternal(DrawerColumn.Groups)
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

    private fun moveGroupFocus(nextIndex: Int) {
        val groups = snapshot.groups
        if (nextIndex !in groups.indices) return
        val prev = focusedGroupIndex
        focusedGroupIndex = nextIndex
        notifyFocusMove(groupsAdapter, prev, focusedGroupIndex)
        scrollGroupBounded(focusedGroupIndex)
        val targetGroup = groups[focusedGroupIndex]
        pendingGroupName = targetGroup
        pendingGroupSelectJob?.cancel()
        pendingGroupSelectJob = scope.launch {
            delay(130L)
            val commitName = pendingGroupName
            pendingGroupName = null
            if (isOpen && commitName != null) {
                listener?.onSelectGroup(commitName, true)
            }
        }
    }

    private fun flushPendingGroupSelection() {
        val pending = pendingGroupName
        pendingGroupSelectJob?.cancel()
        pendingGroupSelectJob = null
        pendingGroupName = null
        if (pending != null) {
            listener?.onSelectGroup(pending, true)
        }
    }

    private fun handleGroupsKey(keyCode: Int): Boolean {
        val groups = snapshot.groups
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (groups.isEmpty()) return true
                moveGroupFocus(if (focusedGroupIndex <= 0) groups.lastIndex else focusedGroupIndex - 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (groups.isEmpty()) return true
                moveGroupFocus(if (focusedGroupIndex >= groups.lastIndex) 0 else focusedGroupIndex + 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                flushPendingGroupSelection()
                showGroups = false
                sourcesPanel.visibility = GONE
                groupsPanel.visibility = GONE
                programsPanel.visibility = VISIBLE
                setActiveColumnInternal(DrawerColumn.Channels)
                refreshColumnHighlights()
                scheduleEpgLoad(immediate = true)
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                flushPendingGroupSelection()
                if (snapshot.sources.size >= 2) {
                    sourcesPanel.visibility = VISIBLE
                    focusedSourceIndex = snapshot.sources.indexOfFirst { it.id == snapshot.activeSourceId }.coerceAtLeast(0)
                    setActiveColumnInternal(DrawerColumn.Sources)
                    sourcesAdapter.notifyDataSetChanged()
                    scrollSourceBounded(focusedSourceIndex)
                    refreshColumnHighlights()
                } else {
                    listener?.onClose()
                }
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
                    // 逐级展开：先只展开分组列、收起节目单；再向左才展开播放源列
                    showGroups = true
                    showDates = false
                    sourcesPanel.visibility = GONE
                    groupsPanel.visibility = VISIBLE
                    programsPanel.visibility = GONE
                    datesPanel.visibility = GONE
                    focusedGroupIndex = snapshot.groups.indexOf(snapshot.selectedGroup).coerceAtLeast(0)
                    updateGroupsRightHint()
                    setActiveColumnInternal(DrawerColumn.Groups)
                    groupsAdapter.notifyDataSetChanged()
                    scrollGroupBounded(focusedGroupIndex)
                    refreshColumnHighlights()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (visibleDatePrograms.isNotEmpty()) {
                    setActiveColumnInternal(DrawerColumn.Programs)
                    refreshColumnHighlights()
                    scrollProgramBounded(focusedProgramIndex)
                } else if (epgLoading) {
                    scheduleEpgLoad(immediate = true)
                } else {
                    Toast.makeText(context, t("当前频道暂无节目单", "No EPG for this channel"), Toast.LENGTH_SHORT).show()
                }
                true
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BOOKMARK -> {
                currentFocusedChannel()?.let { ch ->
                    listener?.onToggleFavorite(ch)
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
                    scrollProgramBounded(focusedProgramIndex)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (list.isEmpty()) return true
                if (focusedProgramIndex < list.lastIndex) {
                    val prev = focusedProgramIndex
                    focusedProgramIndex = prev + 1
                    notifyFocusMove(programsAdapter, prev, focusedProgramIndex)
                    scrollProgramBounded(focusedProgramIndex)
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
                            Toast.makeText(context, t("当前频道不支持回看", "Catchup not supported"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    prog.startMillis <= now -> {
                        listener?.onSelectChannel(ch)
                    }
                    else -> {
                        Toast.makeText(context, t("该节目尚未播出", "Program not aired yet"), Toast.LENGTH_SHORT).show()
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

    // ── 自然有界居中滚动与 EPG 加载 ──────────────────────────────────────────

    private fun currentFocusedChannel(): Channel? = snapshot.channels.getOrNull(focusedChannelIndex)

    private fun setActiveColumnInternal(col: DrawerColumn) {
        if (activeColumn != col) {
            activeColumn = col
            listener?.onActiveColumnChanged(col)
        }
    }

    private fun refreshColumnHighlights() {
        if (focusedSourceIndex in snapshot.sources.indices) {
            sourcesAdapter.notifyItemChanged(focusedSourceIndex, PAYLOAD_FOCUS)
        }
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
        scrollChannelBounded(clamped)
        updateChannelCounter()
        channels.getOrNull(clamped)?.let { ch ->
            listener?.onFocusedChannelChanged(ch.url)
        }
        if (!showGroups) scheduleEpgLoad(immediate = false)
        scheduleUrgentLogoPrefetch()
    }

    private fun updateChannelCounter() {
        val total = snapshot.channels.size
        channelsCountView.text = if (total == 0) "0/0" else "${focusedChannelIndex + 1}/$total"
    }

    private fun estimatedListViewportH(): Int =
        (resources.displayMetrics.heightPixels - dp(52) - dp(12)).coerceAtLeast(channelRowHeightPx)

    private fun scrollSourceBounded(index: Int) {
        val count = snapshot.sources.size
        if (count == 0) return
        val measured = sourcesRecycler.height - sourcesRecycler.paddingTop - sourcesRecycler.paddingBottom
        val viewportH = if (measured > 0) measured else estimatedListViewportH()
        val offset = boundedCenterOffset(index, count, groupRowHeightPx, viewportH)
        sourcesLm.scrollToPositionWithOffset(index.coerceIn(0, count - 1), offset)
    }

    private fun scrollGroupBounded(index: Int) {
        val count = snapshot.groups.size
        if (count == 0) return
        val measured = groupsRecycler.height - groupsRecycler.paddingTop - groupsRecycler.paddingBottom
        val viewportH = if (measured > 0) measured else estimatedListViewportH()
        val offset = boundedCenterOffset(index, count, groupRowHeightPx, viewportH)
        groupsLm.scrollToPositionWithOffset(index.coerceIn(0, count - 1), offset)
    }

    private fun scrollChannelBounded(index: Int) {
        val count = snapshot.channels.size
        if (count == 0) return
        val measured = channelsRecycler.height - channelsRecycler.paddingTop - channelsRecycler.paddingBottom
        val viewportH = if (measured > 0) measured else estimatedListViewportH()
        if (count * channelRowHeightPx <= viewportH) {
            channelsLm.scrollToPositionWithOffset(0, 0)
            return
        }
        val offset = boundedCenterOffset(index, count, channelRowHeightPx, viewportH)
        channelsLm.scrollToPositionWithOffset(index.coerceIn(0, count - 1), offset)
    }

    private fun scrollProgramBounded(index: Int) {
        val count = visibleDatePrograms.size
        if (count == 0) return
        val measured = programsRecycler.height - programsRecycler.paddingTop - programsRecycler.paddingBottom
        val viewportH = if (measured > 0) measured else estimatedListViewportH()
        if (count * programRowHeightPx <= viewportH) {
            programsLm.scrollToPositionWithOffset(0, 0)
            return
        }
        val offset = boundedCenterOffset(index, count, programRowHeightPx, viewportH)
        programsLm.scrollToPositionWithOffset(index.coerceIn(0, count - 1), offset)
    }

    private fun scheduleEpgLoad(immediate: Boolean) {
        epgLoadJob?.cancel()
        val ch = currentFocusedChannel()
        if (ch == null) {
            renderEpgEmpty(t("暂无节目单", "No EPG"))
            return
        }
        programsChannelTitleView.text = ch.title

        val cached = epgCache[ch.url]
        if (cached != null) {
            epgLoading = false
            applyLoadedPrograms(ch, cached)
            scheduleAdjacentEpgPrefetch(focusedChannelIndex)
            return
        }

        epgLoading = true
        if (loadedEpgChannelUrl != ch.url) {
            loadedEpgChannelUrl = null
            loadedEpgAllPrograms = emptyList()
            visibleDatePrograms = emptyList()
            availableDates = emptyList()
            selectedDate = null
            programsAdapter.notifyDataSetChanged()
            programsRecycler.visibility = GONE
            programsEmptyView.text = if (snapshot.epgSourceConfigured) {
                t("加载节目单…", "Loading EPG…")
            } else {
                t("未配置 EPG 源", "No EPG configured")
            }
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
            if (programs.isNotEmpty() || snapshot.epgVersion > 0) {
                epgCache[ch.url] = programs
            }
            applyLoadedPrograms(ch, programs)
            scheduleAdjacentEpgPrefetch(focusedChannelIndex)
        }
    }

    private fun scheduleAdjacentEpgPrefetch(centerIndex: Int) {
        epgPrefetchJob?.cancel()
        val snap = snapshot
        if (!isOpen || showGroups || snap.channels.isEmpty()) return
        val provider = snap.epgProgramProvider
        val data = snap.epgData
        if (provider == null && data == null) return
        val version = snap.epgVersion
        val targets = ArrayList<Channel>(8)
        for (delta in 1..4) {
            snap.channels.getOrNull(centerIndex + delta)?.let { if (!epgCache.containsKey(it.url)) targets.add(it) }
            snap.channels.getOrNull(centerIndex - delta)?.let { if (!epgCache.containsKey(it.url)) targets.add(it) }
        }
        if (targets.isEmpty()) return
        epgPrefetchJob = scope.launch {
            for (target in targets) {
                if (!isOpen || snapshot.epgVersion != version) return@launch
                if (epgCache.containsKey(target.url)) continue
                val loaded = withContext(Dispatchers.IO) {
                    when {
                        provider != null -> provider(target)
                        data != null -> data.resolveChannelId(target)?.let { data.programsByChannelId[it] }.orEmpty()
                        else -> emptyList()
                    }
                }
                if (!isOpen || snapshot.epgVersion != version) return@launch
                if (loaded.isNotEmpty() || version > 0) {
                    epgCache[target.url] = loaded
                }
            }
        }
    }

    private fun applyLoadedPrograms(ch: Channel, programs: List<EpgProgram>) {
        loadedEpgChannelUrl = ch.url
        loadedEpgAllPrograms = programs
        programsChannelTitleView.text = ch.title

        if (programs.isEmpty()) {
            val emptyHint = when {
                !snapshot.epgSourceConfigured -> t("未配置 EPG 源", "No EPG configured")
                snapshot.epgLoadFailed -> t("节目单加载失败", "Failed to load EPG")
                snapshot.epgVersion == 0 -> t("正在载入节目单…", "Loading EPG…")
                else -> t("暂无节目单", "No EPG")
            }
            renderEpgEmpty(emptyHint)
            return
        }

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

        programsSubtitleView.text = formatLocalDateLabel(date, localZone)

        if (dayPrograms.isEmpty()) {
            programsEmptyView.text = t("当日暂无节目", "No programs today")
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

        programsAdapter.notifyDataSetChanged()
        scrollProgramBounded(focusedProgramIndex)
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
            today -> t("今天", "Today")
            today.minusDays(1) -> t("昨天", "Yesterday")
            today.plusDays(1) -> t("明天", "Tomorrow")
            else -> date.format(DATE_FMT)
        }
    }

    private fun fallbackLogoChars(title: String): String {
        val clean = title.trim()
        if (clean.isEmpty()) return "TV"
        return if (clean.length <= 2) clean else clean.substring(0, 2)
    }

    private fun applyFocusedMarquee(tv: TextView, active: Boolean) {
        if (active && PerfPolicy.MARQUEE_ITERATIONS > 0) {
            if (tv.ellipsize != TextUtils.TruncateAt.MARQUEE) {
                tv.ellipsize = TextUtils.TruncateAt.MARQUEE
                tv.marqueeRepeatLimit = PerfPolicy.MARQUEE_ITERATIONS
            }
            if (!tv.isSelected) tv.isSelected = true
        } else {
            if (tv.isSelected) tv.isSelected = false
            if (tv.ellipsize != TextUtils.TruncateAt.END) {
                tv.ellipsize = TextUtils.TruncateAt.END
            }
        }
    }

    // ── Adapter 0: Sources（多源切换，对称高亮焦点 + 品牌蓝当前源标记） ──────

    private inner class SourcesAdapter : RecyclerView.Adapter<SourceVH>() {
        override fun getItemCount(): Int = snapshot.sources.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(11), 0, dp(11), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, groupRowHeightPx).apply {
                    marginStart = dp(7)
                    marginEnd = dp(7)
                }
            }
            val dot = TextView(context).apply {
                text = "●"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f * textScale)
                layoutParams = LayoutParams(dp(14), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f * textScale)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(dot)
            row.addView(label)
            return SourceVH(row, rowBg, dot, label)
        }

        override fun onBindViewHolder(holder: SourceVH, position: Int) {
            val src = snapshot.sources[position]
            holder.label.text = src.name
            bindVisual(holder, position, src)
        }

        override fun onBindViewHolder(holder: SourceVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position)
            } else {
                bindVisual(holder, position, snapshot.sources[position])
            }
        }

        private fun bindVisual(holder: SourceVH, position: Int, src: PlaylistSource) {
            val isFocused = activeColumn == DrawerColumn.Sources && position == focusedSourceIndex
            val isActive = src.id == snapshot.activeSourceId
            holder.rowBg.setColor(
                when {
                    isFocused -> COLOR_FOCUS_BG
                    isActive -> COLOR_INACTIVE_SELECTED_BG
                    else -> Color.TRANSPARENT
                }
            )
            holder.dot.visibility = if (isActive) VISIBLE else INVISIBLE
            holder.dot.setTextColor(COLOR_ACCENT_BLUE)
            holder.label.setTextColor(
                when {
                    isActive -> COLOR_ACCENT_BLUE
                    isFocused -> COLOR_TEXT_PRIMARY
                    else -> COLOR_TEXT_SECONDARY
                }
            )
            holder.label.typeface = if (isFocused || isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            applyFocusedMarquee(holder.label, isFocused)
        }

        override fun onViewRecycled(holder: SourceVH) {
            holder.label.isSelected = false
            super.onViewRecycled(holder)
        }
    }

    private class SourceVH(
        root: View,
        val rowBg: GradientDrawable,
        val dot: TextView,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    // ── Adapter 1: Groups（对称高亮焦点） ────────────────────────────────────

    private inner class GroupsAdapter : RecyclerView.Adapter<GroupVH>() {
        override fun getItemCount(): Int = snapshot.groups.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, groupRowHeightPx).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * textScale)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            return GroupVH(row, rowBg, label)
        }

        override fun onBindViewHolder(holder: GroupVH, position: Int) {
            val name = snapshot.groups[position]
            holder.label.text = displayGroupLabel(name)
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
            val isSelected = if (activeColumn == DrawerColumn.Groups) position == focusedGroupIndex else name == snapshot.selectedGroup
            holder.rowBg.setColor(
                when {
                    isFocused -> COLOR_FOCUS_BG
                    isSelected -> COLOR_INACTIVE_SELECTED_BG
                    else -> Color.TRANSPARENT
                }
            )
            holder.label.setTextColor(
                when {
                    isSelected -> COLOR_ACCENT_BLUE
                    isFocused -> COLOR_TEXT_PRIMARY
                    else -> COLOR_TEXT_SECONDARY
                }
            )
            holder.label.typeface = if (isFocused || isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            applyFocusedMarquee(holder.label, isFocused)
        }

        override fun onViewRecycled(holder: GroupVH) {
            holder.label.isSelected = false
            super.onViewRecycled(holder)
        }
    }

    private class GroupVH(
        root: View,
        val rowBg: GradientDrawable,
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    // ── Adapter 2: Channels（对称高亮焦点 + 聚焦跑马灯） ─────────────────────

    private inner class ChannelsAdapter : RecyclerView.Adapter<ChannelVH>() {
        override fun getItemCount(): Int = snapshot.channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(6), dp(5), dp(10), dp(5))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, channelRowHeightPx).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
            }

            val numTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * textScale)
                gravity = Gravity.CENTER
                isSingleLine = true
                layoutParams = LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            }

            val logoPlateBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(5f)
                setColor(COLOR_LOGO_PLATE)
            }
            val logoBox = FrameLayout(context).apply {
                background = null
                layoutParams = LayoutParams(channelLogoWidthPx, channelLogoHeightPx).apply { marginEnd = dp(10) }
            }
            val logoImg = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val logoFallback = TextView(context).apply {
                background = logoPlateBg
                setTextColor(COLOR_TEXT_SECONDARY)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * textScale)
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f * textScale)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
            val programTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * textScale)
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
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2.5f)).apply {
                    topMargin = dp(4)
                }
            }
            val progressFillBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(1.5f)
                setColor(COLOR_PROGRESS_FILL_IDLE)
            }
            val progressFill = View(context).apply {
                background = progressFillBg
                pivotX = 0f
            }
            progressTrack.addView(
                progressFill,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )

            textCol.addView(titleTv)
            textCol.addView(programTv)
            textCol.addView(progressTrack)

            row.addView(numTv)
            row.addView(logoBox)
            row.addView(textCol)
            return ChannelVH(row, rowBg, numTv, logoImg, logoFallback, titleTv, programTv, progressTrack, progressFill, progressFillBg)
        }

        private fun dp(v: Float): Int = (v * density).roundToInt()

        override fun onBindViewHolder(holder: ChannelVH, position: Int) {
            val ch = snapshot.channels[position]
            val idKey = ChannelGrouper.channelIdentityKey(ch)
            val num = snapshot.channelNumbers[idKey] ?: snapshot.channelNumbers[ch.url] ?: (position + 1)
            holder.numTv.text = num.toString()
            val fav = ch.url in snapshot.favoriteUrls
            holder.titleTv.text = if (fav) "★ ${ch.title}" else ch.title

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
            val selKey = snapshot.selectedChannelKey
            val isPlaying = if (selKey != null) {
                ChannelGrouper.channelIdentityKey(ch) == selKey
            } else {
                ch.url == snapshot.selectedChannelUrl
            }

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

            val accentText = if (isPlaying) COLOR_ACCENT_BLUE else COLOR_TEXT_PRIMARY
            holder.titleTv.setTextColor(accentText)
            holder.titleTv.typeface = if (isPlaying || isActiveFocus) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            holder.numTv.setTextColor(if (isPlaying) COLOR_ACCENT_BLUE else if (isActiveFocus) COLOR_TEXT_PRIMARY else COLOR_TEXT_MUTED)
            holder.programTv.setTextColor(if (isActiveFocus) COLOR_TEXT_SECONDARY else COLOR_TEXT_MUTED)
            // 仅当前播放频道的进度条亮品牌蓝，其余频道保持低调灰白
            holder.progressFillBg.setColor(if (isPlaying) COLOR_ACCENT_BLUE else COLOR_PROGRESS_FILL_IDLE)
            applyFocusedMarquee(holder.titleTv, isActiveFocus)
            applyFocusedMarquee(holder.programTv, isActiveFocus)
        }

        override fun onViewRecycled(holder: ChannelVH) {
            holder.logoJob?.cancel()
            holder.logoJob = null
            holder.boundLogoUrl = ""
            holder.titleTv.isSelected = false
            holder.programTv.isSelected = false
            super.onViewRecycled(holder)
        }
    }

    private class ChannelVH(
        root: View,
        val rowBg: GradientDrawable,
        val numTv: TextView,
        val logoImg: ImageView,
        val logoFallback: TextView,
        val titleTv: TextView,
        val programTv: TextView,
        val progressTrack: FrameLayout,
        val progressFill: View,
        val progressFillBg: GradientDrawable,
    ) : RecyclerView.ViewHolder(root) {
        var boundLogoUrl: String = ""
        var logoJob: Job? = null
    }

    // ── Adapter 3: Programs（严格本地时区 + 对称高亮 + 聚焦长名滚动） ─────────

    private inner class ProgramsAdapter : RecyclerView.Adapter<ProgramVH>() {
        override fun getItemCount(): Int = visibleDatePrograms.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgramVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, programRowHeightPx).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
            }
            val metaRow = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val timeTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * textScale)
                isSingleLine = true
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badgeTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * textScale)
                typeface = Typeface.DEFAULT_BOLD
                isSingleLine = true
            }
            metaRow.addView(timeTv)
            metaRow.addView(badgeTv)

            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * textScale)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(metaRow)
            row.addView(titleTv)
            return ProgramVH(row, rowBg, timeTv, badgeTv, titleTv)
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
                    holder.badgeTv.text = t("正在播出", "Live")
                    holder.badgeTv.setTextColor(COLOR_ACCENT_SOFT)
                    holder.badgeTv.visibility = VISIBLE
                }
                prog.endMillis <= now && hasCatchup -> {
                    holder.badgeTv.text = t("回看", "Catchup")
                    holder.badgeTv.setTextColor(COLOR_CATCHUP_MINT)
                    holder.badgeTv.visibility = VISIBLE
                }
                prog.startMillis > now -> {
                    holder.badgeTv.text = t("预告", "Soon")
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
            applyFocusedMarquee(holder.titleTv, isFocused)
        }

        override fun onViewRecycled(holder: ProgramVH) {
            holder.titleTv.isSelected = false
            super.onViewRecycled(holder)
        }
    }

    private class ProgramVH(
        root: View,
        val rowBg: GradientDrawable,
        val timeTv: TextView,
        val badgeTv: TextView,
        val titleTv: TextView,
    ) : RecyclerView.ViewHolder(root)

    // ── Adapter 4: Dates（对称高亮焦点） ─────────────────────────────────────

    private inner class DatesAdapter : RecyclerView.Adapter<DateVH>() {
        override fun getItemCount(): Int = availableDates.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateVH {
            val rowBg = newRowBgDrawable()
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rowBg
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dateRowHeightPx).apply {
                    marginStart = dp(6)
                    marginEnd = dp(6)
                }
            }
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * textScale)
                isSingleLine = true
                layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)
            return DateVH(row, rowBg, label)
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
        val label: TextView,
    ) : RecyclerView.ViewHolder(root)

    companion object {
        private const val PAYLOAD_FOCUS = "focus"
        private const val PAYLOAD_NOW_OR_PLAYING = "now_or_playing"
        private const val EPG_DEBOUNCE_MS = 150L

        private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

        private const val COLOR_SURFACE_DEEP = 0xF40A0E14.toInt()        // #F40A0E14 deepest obsidian for sources
        private const val COLOR_SURFACE_SOLID = 0xEE0D1118.toInt()       // #EE0D1118 deep obsidian
        private const val COLOR_SURFACE_TRANSLUCENT = 0xC80D1118.toInt() // #C80D1118 smoked glass
        private const val COLOR_FOCUS_BG = 0x36FFFFFF                    // #36FFFFFF crisp symmetric highlight
        private const val COLOR_INACTIVE_SELECTED_BG = 0x18FFFFFF
        private const val COLOR_PLAYING_BG = 0x222F6BFF                  // subtle playing tint
        private const val COLOR_LOGO_PLATE = 0x1AFFFFFF
        private const val COLOR_PROGRESS_TRACK = 0x24FFFFFF
        private const val COLOR_PROGRESS_FILL_IDLE = 0x7AC5CEDE          // 低调灰白进度填充（非当前播放台）
        private const val COLOR_ACCENT_BLUE = 0xFF5C98FF.toInt()         // #5C98FF playing accent
        private const val COLOR_ACCENT_SOFT = 0xFF94C2FF.toInt()         // #94C2FF live badge
        private const val COLOR_CATCHUP_MINT = 0xFF8AE6B8.toInt()        // #8AE6B8 catchup badge
        private const val COLOR_TEXT_PRIMARY = 0xFFF5F8FC.toInt()        // #F5F8FC
        private const val COLOR_TEXT_SECONDARY = 0xFFC5CEDE.toInt()      // #C5CEDE
        private const val COLOR_TEXT_MUTED = 0xFF9AA6BC.toInt()          // #9AA6BC

        /**
         * 自然有界居中滚动偏移计算：
         * - 列表总高 <= 视口高：紧贴顶部（无空白）；
         * - 顶部前几台：紧贴顶部（第 0 项 offset = 0，消除上方一片空白）；
         * - 中段频道：精确居中于 `(viewportH - rowH) / 2`；
         * - 末尾几台：紧贴底部（末项底部与视口底齐平，消除下方一片空白）。
         */
        internal fun boundedCenterOffset(
            index: Int,
            count: Int,
            rowHeightPx: Int,
            viewportHeightPx: Int,
        ): Int {
            if (count <= 0 || rowHeightPx <= 0 || viewportHeightPx <= 0) return 0
            val clamped = index.coerceIn(0, count - 1)
            val totalH = count * rowHeightPx
            if (totalH <= viewportHeightPx) return clamped * rowHeightPx
            val idealY = ((viewportHeightPx - rowHeightPx) / 2).coerceAtLeast(0)
            val maxScrollY = (totalH - viewportHeightPx).coerceAtLeast(0)
            val targetScrollY = (clamped * rowHeightPx - idealY).coerceIn(0, maxScrollY)
            return clamped * rowHeightPx - targetScrollY
        }
    }
}
