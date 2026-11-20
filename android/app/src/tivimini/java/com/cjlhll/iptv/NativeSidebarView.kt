package com.cjlhll.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val COLOR_INK = 0xFFF2F5FA.toInt()
private const val COLOR_MUTED = 0xFFA9B4C7.toInt()
private const val COLOR_BLUE = 0xFF4EA8FF.toInt()
private const val COLOR_AMBER = 0xFFF5A524.toInt()
private const val FOCUS_BG = 0x14FFFFFF

/** Focus-aware weight without an adapter rebind (SIDEBAR3-02). */
private class FocusWeightTextView(ctx: Context) : AppCompatTextView(ctx) {
    private val normalTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val focusedTypeface = Typeface.create("sans-serif", Typeface.BOLD)

    init {
        typeface = normalTypeface
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val want = if (drawableState.contains(android.R.attr.state_focused)) focusedTypeface else normalTypeface
        if (typeface !== want) typeface = want
    }
}

private data class SidebarRow(
    val text: String,
    val secondary: String? = null,
    val selected: Boolean = false,
    val now: Boolean = false,
    val url: String? = null,
    val groupIndex: Int = -1,
    val date: LocalDate? = null,
    val groupName: String? = null,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val programTitle: String? = null,
)

private class RowAdapter(
    private val rowHeightPx: Int,
    private val onFocus: (SidebarRow, Int) -> Unit,
    private val onClick: (SidebarRow) -> Unit,
) : RecyclerView.Adapter<RowAdapter.H>() {

    private var rows: List<SidebarRow> = emptyList()

    fun submit(newRows: List<SidebarRow>) {
        val old = rows
        if (old == newRows) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean {
                val a = old[o]; val b = newRows[n]
                return a.text == b.text && a.url == b.url && a.groupName == b.groupName && a.date == b.date && a.startMillis == b.startMillis
            }
            override fun areContentsTheSame(o: Int, n: Int): Boolean = old[o] == newRows[n]
        })
        rows = newRows
        diff.dispatchUpdatesTo(this)
    }

    class H(val root: FrameLayout, val accent: View, val primary: TextView, val secondary: TextView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val ctx = parent.context
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx)
            isFocusable = true
            isFocusableInTouchMode = true
            // Focus background via a state-list drawable: no per-focus setBackground/allocation/rebind.
            background = stateDrawable(FOCUS_BG, Color.TRANSPARENT)
        }
        val accent = View(ctx).apply { background = accentSelector() }
        root.addView(accent, FrameLayout.LayoutParams(dp(ctx, 2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        val primary = FocusWeightTextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(textColorSelector())
            isDuplicateParentStateEnabled = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(primary, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(ctx, 12); marginEnd = dp(ctx, 96)
        })
        val secondary = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            // SIDEBAR3-03: secondary stays muted on focus (distinct from the primary).
            setTextColor(COLOR_MUTED)
            isDuplicateParentStateEnabled = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        root.addView(secondary, FrameLayout.LayoutParams(dp(ctx, 90), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            marginEnd = dp(ctx, 6)
        })
        return H(root, accent, primary, secondary)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val row = rows[position]
        // Preformatted text incl. the ▶ marker — no per-frame formatting.
        holder.primary.text = if (row.selected) "▶ ${row.text}" else row.text
        holder.secondary.text = row.secondary.orEmpty()
        // Accent bar is a state-list drawable driven by view state (selected = blue, activated = amber).
        holder.accent.isSelected = row.selected
        holder.accent.isActivated = row.now
        holder.root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onFocus(row, holder.bindingAdapterPosition)
        }
        holder.root.setOnClickListener { onClick(row) }
    }

    private fun stateDrawable(focused: Int, normal: Int): android.graphics.drawable.StateListDrawable =
        android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), ColorDrawable(focused))
            addState(intArrayOf(), ColorDrawable(normal))
        }

    private fun accentSelector(): android.graphics.drawable.StateListDrawable =
        android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), ColorDrawable(COLOR_AMBER))
            addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(COLOR_BLUE))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }

    private fun textColorSelector(): android.content.res.ColorStateList =
        android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(COLOR_INK, COLOR_MUTED)
        )

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}

/**
 * Native (View/RecyclerView) channel-browser sidebar (t62 M2+M3). Lives in the Activity root
 * FrameLayout between the zero-copy SurfaceView and the transparent Compose overlay; renders from
 * [SidebarController] (single source of truth) and drives the same TIV semantics.
 */
class NativeSidebarView(
    context: Context,
    private val controller: SidebarController,
    private val onPlayChannel: (groupIndex: Int, variantIndex: Int) -> Unit,
    private val onPlayCatchup: (CatchupPlayRequest) -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    private val groupsRv = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
    private val channelsRv = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
    private val programsRv = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
    private val datesRv = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }

    private val groupsAdapter = RowAdapter(dp(40), { row, pos -> onRowFocused(DrawerColumn.Groups, row, pos) }, { onRowClicked(DrawerColumn.Groups, it) })
    private val channelsAdapter = RowAdapter(dp(56), { row, pos -> onRowFocused(DrawerColumn.Channels, row, pos) }, { onRowClicked(DrawerColumn.Channels, it) })
    private val programsAdapter = RowAdapter(dp(44), { row, pos -> onRowFocused(DrawerColumn.Programs, row, pos) }, { onRowClicked(DrawerColumn.Programs, it) })
    private val datesAdapter = RowAdapter(dp(44), { row, pos -> onRowFocused(DrawerColumn.Dates, row, pos) }, { onRowClicked(DrawerColumn.Dates, it) })

    private val groupsColumn = column("分组", groupsRv, groupsAdapter)
    private val channelsColumn = column("频道", channelsRv, channelsAdapter)
    private val programsColumn = column("节目单", programsRv, programsAdapter)
    private val datesColumn = column("日期", datesRv, datesAdapter)

    private var lastState: SidebarState = controller.value
    private var lastSignature: Any? = null
    private val lastPositions = HashMap<DrawerColumn, Int>()
    private val programRefresh = Runnable { renderProgramsOnly() }
    // SIDEBAR3-04: refresh the NOW/next text periodically while the drawer is visible.
    private val nowTick = object : Runnable {
        override fun run() {
            renderProgramsOnly()
            channelsAdapter.submit(buildChannelRows(lastState))
            handler.postDelayed(this, 60_000L)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        handler.removeCallbacks(nowTick)
        if (visibility == View.VISIBLE) handler.postDelayed(nowTick, 60_000L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(nowTick)
        handler.removeCallbacks(programRefresh)
    }

    /** SIDEBAR3-07: overlay toggles must not reuse a stale position for that column. */
    fun clearPosition(column: DrawerColumn) {
        lastPositions.remove(column)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // GAP2-01: a 633dp-wide panel anchored to the left holds the base columns AND the overlay
        // columns. The date overlay is right-aligned to the base right edge (513 = 633-120), so it can
        // never float over the video on the far right.
        val panel = FrameLayout(context).apply { setBackgroundColor(Color.TRANSPARENT) }
        addView(panel, LayoutParams(dp(633), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        val base = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6161B22.toInt())
        }
        panel.addView(base, LayoutParams(dp(633), ViewGroup.LayoutParams.MATCH_PARENT))
        base.addView(channelsColumn, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))
        base.addView(programsColumn, LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT))
        groupsColumn.setBackgroundColor(0xE6161B22.toInt())
        datesColumn.setBackgroundColor(0xE6161B22.toInt())
        groupsColumn.layoutParams = LayoutParams(dp(151), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        datesColumn.layoutParams = LayoutParams(dp(120), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply {
            leftMargin = dp(513)
        }
        panel.addView(groupsColumn)
        panel.addView(datesColumn)
    }

    private fun column(title: String, rv: RecyclerView, adapter: RowAdapter): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(COLOR_INK)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(14), dp(12), dp(14), dp(8))
            })
            rv.adapter = adapter
            rv.isFocusable = false
            rv.isFocusableInTouchMode = false
            rv.setHasFixedSize(true)
            rv.itemAnimator = null
            rv.setItemViewCacheSize(8)
            addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }

    /** Render the whole sidebar from the controller state. */
    fun render(state: SidebarState) {
        lastState = state
        // Focus-only emissions must not re-bind the lists (that would steal/reset the native focus).
        val signature = listOf(
            state.channelGroups, state.selectedGroup, state.epgVersion, state.selectedEpgDate,
            state.showGroups, state.showDates,
        )
        if (signature == lastSignature) return
        lastSignature = signature
        // GAP2-02: structural change (group/date/EPG) invalidates stale browsing positions; focus
        // restore then resolves channels by focusedChannelUrl and programs from the top.
        lastPositions.remove(DrawerColumn.Channels)
        lastPositions.remove(DrawerColumn.Programs)
        val groups = ChannelGrouper.sortedDrawerGroups(state.channelGroups)
        groupsAdapter.submit(groups.map { name ->
            SidebarRow(text = name, selected = name == state.selectedGroup, groupName = name)
        })
        groupsColumn.visibility = if (state.showGroups) View.VISIBLE else View.GONE

        channelsAdapter.submit(buildChannelRows(state))
        renderProgramsOnly()
    }

    /** Recompute only the program/date columns (used by the 350ms focus debounce). */
    fun renderProgramsOnly() {
        val state = lastState
        programsAdapter.submit(programsFor(state))
        val dates = datesFor(state)
        datesAdapter.submit(dates.map { date ->
            SidebarRow(text = date.format(DateTimeFormatter.ofPattern("MM-dd")), selected = date == (state.selectedEpgDate ?: today()), date = date)
        })
        datesColumn.visibility = if (state.showDates && dates.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun buildChannelRows(state: SidebarState): List<SidebarRow> =
        state.channelGroups.mapIndexedNotNull { index, group ->
            if (state.selectedGroup != SidebarState.ALL_GROUP && ChannelGrouper.displayGroupName(group) != state.selectedGroup) {
                return@mapIndexedNotNull null
            }
            val channel = group.variants.getOrNull(group.defaultVariantIndex)?.channel ?: return@mapIndexedNotNull null
            SidebarRow(
                text = "${index + 1}. ${channel.title}",
                secondary = nowTitle(state, channel),
                selected = channel.url == state.selectedChannelUrl,
                url = channel.url,
                groupIndex = index,
            )
        }

    fun requestInitialFocus() {
        val state = lastState
        val idx = state.channelGroups.mapIndexedNotNull { index, group ->
            if (state.selectedGroup != SidebarState.ALL_GROUP && ChannelGrouper.displayGroupName(group) != state.selectedGroup) null else index
        }.indexOfFirst { state.channelGroups.getOrNull(it)?.variants?.any { v -> v.channel.url == (state.focusedChannelUrl ?: state.selectedChannelUrl) } == true }
        val target = if (idx >= 0) idx else 0
        channelsRv.post {
            if (channelsRv.adapter?.itemCount ?: 0 == 0) return@post
            channelsRv.scrollToPosition(target)
            channelsRv.post {
                channelsRv.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                    ?: channelsRv.requestFocus()
            }
        }
    }

    private fun onRowFocused(column: DrawerColumn, row: SidebarRow, position: Int) {
        if (position >= 0) lastPositions[column] = position
        controller.setActiveColumn(column)
        if (column == DrawerColumn.Channels && row.url != null) {
            controller.focusChannel(row.url)
            // 350ms debounce before recomputing the program column (off-main formatting, local invalidate).
            handler.removeCallbacks(programRefresh)
            handler.postDelayed(programRefresh, 350L)
        }
    }

    private fun onRowClicked(column: DrawerColumn, row: SidebarRow) {
        when (column) {
            DrawerColumn.Groups -> row.groupName?.let { controller.selectGroup(it) }
            DrawerColumn.Channels -> if (row.groupIndex >= 0) {
                controller.activateChannel(row.groupIndex, 0)
                onPlayChannel(row.groupIndex, 0)
                // NS-06: refresh only the selection marker; do not rebind/recreate the list (focus kept).
                channelsAdapter.submit(buildChannelRows(controller.value))
            }
            DrawerColumn.Dates -> row.date?.let { controller.selectEpgDate(it) }
            DrawerColumn.Programs -> playCatchup(row)
        }
        render(controller.value)
    }

    /** TIV-09: historical program OK -> catchup URL for the OWNING channel, then close + play. */
    private fun playCatchup(row: SidebarRow) {
        val state = lastState
        val url = state.focusedChannelUrl ?: state.selectedChannelUrl
        val channel = state.channelGroups.asSequence().flatMap { it.variants.asSequence() }
            .firstOrNull { it.channel.url == url }?.channel
        val now = System.currentTimeMillis()
        when {
            row.endMillis > now -> toast("仅历史节目可回看")
            channel == null -> toast("无法回看")
            else -> {
                val catchupUrl = CatchupHelper.buildCatchupUrl(
                    channel.url,
                    channel.catchupMode,
                    channel.catchupSource,
                    Instant.ofEpochMilli(row.startMillis),
                    Instant.ofEpochMilli(row.endMillis),
                )
                if (catchupUrl == null) {
                    toast("该频道不支持回看")
                } else {
                    // The owning channel is captured immutably (id/UA/Referer) in the request.
                    onPlayCatchup(
                        CatchupPlayRequest(
                            catchupUrl = catchupUrl,
                            liveUrl = channel.url,
                            programTitle = row.programTitle ?: row.text,
                            startMillis = row.startMillis,
                            endMillis = row.endMillis,
                            owner = channel,
                        )
                    )
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun focusGroups() = focusColumn(groupsRv, DrawerColumn.Groups)
    fun focusChannels() = focusColumn(channelsRv, DrawerColumn.Channels)
    fun focusPrograms() = focusColumn(programsRv, DrawerColumn.Programs)
    fun focusDates() = focusColumn(datesRv, DrawerColumn.Dates)

    /** GAP-02: restore this column's last focused position (channels fall back to the playing channel). */
    private fun focusColumn(rv: RecyclerView, column: DrawerColumn) {
        val target = lastPositions[column] ?: defaultPosition(column)
        // GAP-04: overlay columns become visible asynchronously; give layout one frame before focus.
        rv.postDelayed({
            rv.isFocusable = true
            val count = rv.adapter?.itemCount ?: 0
            if (count == 0) {
                rv.requestFocus()
                return@postDelayed
            }
            val pos = target.coerceIn(0, count - 1)
            rv.scrollToPosition(pos)
            rv.post {
                val holder = rv.findViewHolderForAdapterPosition(pos)
                if (holder != null) {
                    holder.itemView.requestFocus()
                } else {
                    // GAP2-04: retry once after the overlay column has been laid out.
                    rv.postDelayed({
                        rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: rv.requestFocus()
                    }, 30L)
                }
            }
        }, 30L)
    }

    private fun defaultPosition(column: DrawerColumn): Int {
        if (column != DrawerColumn.Channels) return 0
        val state = lastState
        val url = state.focusedChannelUrl ?: state.selectedChannelUrl
        return buildChannelRows(state).indexOfFirst { it.url == url }.takeIf { it >= 0 } ?: 0
    }

    private fun programsFor(state: SidebarState): List<SidebarRow> {
        val epg = controller.epgData() ?: return emptyList()
        val url = state.focusedChannelUrl ?: state.selectedChannelUrl ?: return emptyList()
        val channel = state.channelGroups.asSequence().flatMap { it.variants.asSequence() }
            .firstOrNull { it.channel.url == url }?.channel ?: return emptyList()
        val channelId = epg.resolveChannelId(channel) ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val date = state.selectedEpgDate ?: today()
        val now = System.currentTimeMillis()
        return (epg.programsByChannelId[channelId] ?: return emptyList())
            .filter { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() == date }
            .sortedBy { it.startMillis }
            .map { p ->
                SidebarRow(
                    text = p.title,
                    secondary = "${Instant.ofEpochMilli(p.startMillis).atZone(zone).format(timeFormat)}-${Instant.ofEpochMilli(p.endMillis).atZone(zone).format(timeFormat)}",
                    now = p.startMillis <= now && now < p.endMillis,
                    startMillis = p.startMillis,
                    endMillis = p.endMillis,
                    programTitle = p.title,
                )
            }
    }

    private fun datesFor(state: SidebarState): List<LocalDate> {
        val epg = controller.epgData() ?: return emptyList()
        val url = state.focusedChannelUrl ?: state.selectedChannelUrl ?: return emptyList()
        val channel = state.channelGroups.asSequence().flatMap { it.variants.asSequence() }
            .firstOrNull { it.channel.url == url }?.channel ?: return emptyList()
        val channelId = epg.resolveChannelId(channel) ?: return emptyList()
        val zone = ZoneId.systemDefault()
        return (epg.programsByChannelId[channelId] ?: emptyList())
            .map { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .distinct()
            .sorted()
    }

    private fun nowTitle(state: SidebarState, channel: Channel): String? {
        val epg = controller.epgData() ?: return null
        val channelId = epg.resolveChannelId(channel) ?: return null
        val now = System.currentTimeMillis()
        return epg.programsByChannelId[channelId]?.firstOrNull { it.startMillis <= now && now < it.endMillis }?.title
    }

    private fun today(): LocalDate = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
