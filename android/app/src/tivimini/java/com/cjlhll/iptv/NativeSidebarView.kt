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
    private val onFocus: (SidebarRow) -> Unit,
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

    class H(val root: FrameLayout, val accent: View, val arrow: TextView, val primary: TextView, val secondary: TextView) :
        RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
        val ctx = parent.context
        val root = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeightPx)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        val accent = View(ctx).apply { setBackgroundColor(COLOR_BLUE) }
        root.addView(accent, FrameLayout.LayoutParams(dp(ctx, 2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        val arrow = TextView(ctx).apply {
            text = "▶"
            setTextColor(COLOR_BLUE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(arrow, FrameLayout.LayoutParams(dp(ctx, 16), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply { marginStart = dp(ctx, 4) })
        val primary = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(primary, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(ctx, 22); marginEnd = dp(ctx, 96)
        })
        val secondary = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(COLOR_MUTED)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        root.addView(secondary, FrameLayout.LayoutParams(dp(ctx, 90), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            marginEnd = dp(ctx, 6)
        })
        return H(root, accent, arrow, primary, secondary)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: H, position: Int) {
        val row = rows[position]
        val root = holder.root
        holder.primary.text = row.text
        holder.secondary.text = row.secondary.orEmpty()
        holder.accent.visibility = if (row.selected || row.now) View.VISIBLE else View.INVISIBLE
        holder.accent.setBackgroundColor(if (row.now) COLOR_AMBER else COLOR_BLUE)
        holder.arrow.visibility = if (row.selected) View.VISIBLE else View.INVISIBLE
        applyFocus(holder, false)
        root.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            applyFocus(holder, hasFocus)
            if (hasFocus) onFocus(row)
        }
        root.setOnClickListener { onClick(row) }
    }

    private fun applyFocus(holder: H, focused: Boolean) {
        holder.root.background = if (focused) ColorDrawable(FOCUS_BG) else null
        holder.primary.setTextColor(if (focused) COLOR_INK else COLOR_MUTED)
        holder.primary.setTypeface(null, if (focused) Typeface.BOLD else Typeface.NORMAL)
    }

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

    private val groupsAdapter = RowAdapter(dp(40), { onRowFocused(DrawerColumn.Groups, it) }, { onRowClicked(DrawerColumn.Groups, it) })
    private val channelsAdapter = RowAdapter(dp(56), { onRowFocused(DrawerColumn.Channels, it) }, { onRowClicked(DrawerColumn.Channels, it) })
    private val programsAdapter = RowAdapter(dp(44), { onRowFocused(DrawerColumn.Programs, it) }, { onRowClicked(DrawerColumn.Programs, it) })
    private val datesAdapter = RowAdapter(dp(44), { onRowFocused(DrawerColumn.Dates, it) }, { onRowClicked(DrawerColumn.Dates, it) })

    private val groupsColumn = column("分组", groupsRv, 150, groupsAdapter)
    private val channelsColumn = column("频道", channelsRv, 300, channelsAdapter)
    private val programsColumn = column("节目单", programsRv, 300, programsAdapter)
    private val datesColumn = column("日期", datesRv, 110, datesAdapter)

    private var lastState: SidebarState = controller.value
    private var lastSignature: Any? = null
    private var pendingInitialFocus = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xE6161B22.toInt())
        }
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(groupsColumn)
        row.addView(channelsColumn)
        row.addView(programsColumn)
        row.addView(datesColumn)
    }

    private fun column(title: String, rv: RecyclerView, widthDp: Int, adapter: RowAdapter): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), ViewGroup.LayoutParams.MATCH_PARENT)
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
        val groups = ChannelGrouper.sortedDrawerGroups(state.channelGroups)
        groupsAdapter.submit(groups.map { name ->
            SidebarRow(text = name, selected = name == state.selectedGroup, groupName = name)
        })
        groupsColumn.visibility = if (state.showGroups) View.VISIBLE else View.GONE

        val groupRows = buildChannelRows(state)
        channelsAdapter.submit(groupRows)
        renderProgramsOnly()

        if (pendingInitialFocus) {
            pendingInitialFocus = false
            requestInitialFocus()
        }
    }

    /** Recompute only the program/date columns (used by the 350ms focus debounce). */
    fun renderProgramsOnly() {        val state = lastState
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

    private fun onRowFocused(column: DrawerColumn, row: SidebarRow) {
        controller.setActiveColumn(column)
        if (column == DrawerColumn.Channels && row.url != null) {
            controller.focusChannel(row.url)
            // 350ms debounce before recomputing the program column (off-main formatting, local invalidate).
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ renderProgramsOnly() }, 350L)
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

    fun focusGroups() = focusColumn(groupsRv)
    fun focusChannels() = focusColumn(channelsRv)
    fun focusPrograms() = focusColumn(programsRv)
    fun focusDates() = focusColumn(datesRv)

    private fun focusColumn(rv: RecyclerView) {
        rv.post {
            rv.isFocusable = true
            if ((rv.adapter?.itemCount ?: 0) == 0) {
                rv.requestFocus()
                return@post
            }
            val lm = rv.layoutManager as? LinearLayoutManager
            val pos = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
            rv.scrollToPosition(pos)
            rv.post { rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() ?: rv.requestFocus() }
        }
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
