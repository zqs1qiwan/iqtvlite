package tv.iqtvlite.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 竖向节目列表（tivimini Programs 列风格）：显示当前频道的节目时间表。
 * 点击节目 → 播放对应频道/回看。
 */
class ProgramListAdapter(
    private val onProgramme: (EpgProgramme) -> Unit,
) : RecyclerView.Adapter<ProgramListAdapter.VH>() {

    private var programs: List<EpgProgramme> = emptyList()
    private var nowSecs: Long = System.currentTimeMillis() / 1000
    private var selectedPos = -1

    fun submit(list: List<EpgProgramme>, now: Long) {
        programs = list
        nowSecs = now
        notifyDataSetChanged()
    }

    /** 选中高亮（遥控器焦点定位用） */
    fun setSelected(pos: Int) {
        val old = selectedPos
        selectedPos = pos
        if (old in programs.indices) notifyItemChanged(old)
        if (pos in programs.indices) notifyItemChanged(pos)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val time: TextView = v.findViewById(R.id.prog_time)
        val title: TextView = v.findViewById(R.id.prog_title)
        val progress: ProgressBar = v.findViewById(R.id.prog_progress)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_program, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val p = programs[pos]
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        h.time.text = "${fmt.format(java.util.Date(p.start * 1000))}"
        h.title.text = p.title
        h.progress.progress = p.progress
        h.progress.visibility = if (p.progress in 1..99) View.VISIBLE else View.GONE
        h.itemView.isSelected = pos == selectedPos
        h.itemView.isFocusable = true
        h.itemView.setOnClickListener { setSelected(pos); onProgramme(p) }
    }

    override fun getItemCount() = programs.size
}
