package tv.iqtvlite.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * EPG 时间轴网格适配器。
 *
 * 每行 = 固定频道列（120dp） + 水平排列的节目块。
 * 每个节目块宽度 = span × 每列宽度（默认 120dp = 30 分钟）。
 * 支持横向滚动查看未来节目，纵向滚动切换频道。
 */
class EpgGridAdapter(
    private val timeFormat: SimpleDateFormat,
    private val onProgramme: (EpgRow, EpgProgramme) -> Unit,
    private val onCatchup: (EpgRow, EpgProgramme) -> Unit,
) : RecyclerView.Adapter<EpgGridAdapter.VH>() {

    /** 当前时间（Unix 秒）—— 用于判断节目是否已播出（可回看） */
    var nowSecs: Long = System.currentTimeMillis() / 1000

    private var rows: List<EpgRow> = emptyList()
    private var allRows: List<EpgRow> = emptyList()
    private var query: String = ""
    private var slotMinutes: Int = 30

    companion object {
        private const val SLOT_DP = 160 // 每个时间片的宽度（dp）
    }

    fun submit(grid: EpgGridData) {
        allRows = grid.rows
        slotMinutes = grid.slotMinutes
        applyFilter(query)
    }

    /** R1 EPG 节目搜索：按节目名/频道名过滤；返回匹配频道数。 */
    fun filter(queryText: String): Int {
        query = queryText.trim()
        applyFilter(query)
        return rows.size
    }

    private fun applyFilter(q: String) {
        rows = if (q.isEmpty()) {
            allRows
        } else {
            val needle = q.lowercase(java.util.Locale.ROOT)
            allRows.filter { row ->
                row.name.lowercase(java.util.Locale.ROOT).contains(needle) ||
                    row.programmes.any { it.title.lowercase(java.util.Locale.ROOT).contains(needle) }
            }
        }
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.epg_channel_name)
        val icon: ImageView = view.findViewById(R.id.epg_channel_icon)
        val timeline: LinearLayout = view.findViewById(R.id.epg_timeline)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = rows[position]
        holder.name.text = row.name
        if (row.icon != null) {
            holder.icon.load(row.icon) { crossfade(true) }
        } else {
            holder.icon.setImageResource(R.drawable.ic_tv)
        }

        holder.timeline.removeAllViews()
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val slotPx = (SLOT_DP * density).toInt()

        for (p in row.programmes) {
            val block = FrameLayout(ctx).apply {
                val widthPx = (slotPx * p.span).coerceAtLeast(1)
                layoutParams = LinearLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    setMargins(2, 4, 2, 4)
                }
                val isMatch = query.isNotEmpty() && p.title.lowercase(java.util.Locale.ROOT).contains(query.lowercase(java.util.Locale.ROOT))
                setBackgroundColor(when {
                    isMatch -> Color.parseColor("#2A4A6B")          // R1 搜索命中：高亮蓝
                    p.progress in 1..99 -> Color.parseColor("#1B3358") // 当前播出
                    else -> Color.parseColor("#161F2B")
                })
                if (isMatch) {
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#4FC3F7")), null, null)
                } else {
                    foreground = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#335B8C")),
                        null, null
                    )
                }
                isFocusable = true
            }

            val title = TextView(ctx).apply {
                text = p.title
                textSize = 15f
                setTextColor(Color.parseColor("#E8EDF2"))
                maxLines = 2
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(10, 8, 10, 10) }
            }
            block.addView(title)

            // 当前播出进度条
            if (p.progress in 1..99) {
                val bar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = p.progress
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 4,
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM
                        setMargins(10, 0, 10, 6)
                    }
                }
                block.addView(bar)
            }

            // 时间标签
            val time = TextView(ctx).apply {
                text = timeFormat.format(Date(p.start * 1000))
                textSize = 10f
                setTextColor(Color.parseColor("#66717D"))
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    setMargins(0, 0, 10, 18)
                }
            }
            block.addView(time)

            block.setOnClickListener { onProgramme(row, p) }
            // 长按已播出的节目 → catch-up 回看
            block.setOnLongClickListener {
                if (p.stop <= nowSecs) {
                    onCatchup(row, p)
                    true
                } else false
            }
            holder.timeline.addView(block)
        }

        // 只显示启动后 6 小时（每行 12 片 × 160dp = 1920dp），足够一屏横向滚动
        holder.itemView.isFocusable = true
    }

    override fun getItemCount() = rows.size
}

fun epgTimeFormat(): SimpleDateFormat =
    SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
