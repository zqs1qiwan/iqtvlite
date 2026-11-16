package tv.iqtvlite.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onSelect: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var selectedPos = -1

    fun submit(list: List<Channel>) {
        items = list
        filtered = list
        notifyDataSetChanged()
    }

    /** 收藏/最近观看的 ID 列表（由 MainActivity 更新） */
    private var favIds: Set<Long> = emptySet()
    private var recentIds: Set<Long> = emptySet()
    private var currentQuery = ""
    private var currentGroupFilter: String? = null

    fun setLists(favorites: List<Long>, recents: List<Long>) {
        favIds = favorites.toSet()
        recentIds = recents.toSet()
    }

    /**
     * 分组过滤。支持两个虚拟分组：
     *   - `__favorites__` 收藏
     *   - `__recents__` 最近观看（按最近顺序）
     */
    fun filter(query: String, group: String?) {
        currentQuery = query
        currentGroupFilter = group
        filtered = when (group) {
            "__favorites__" -> items.filter { favIds.contains(it.id) }
            "__recents__" -> recentIds.mapNotNull { id -> items.find { it.id == id } }
            null -> items
            else -> items.filter { ch -> ch.group == group }
        }.filter { ch ->
            query.isBlank() ||
                ch.name.contains(query, ignoreCase = true) ||
                (ch.tvgId?.contains(query, ignoreCase = true) == true)
        }
        notifyDataSetChanged()
    }

    /** 列表变化后重新应用当前过滤（收藏切换后调用）。 */
    fun refresh() = filter(currentQuery, currentGroupFilter)

    fun setSelected(pos: Int) {
        val old = selectedPos
        selectedPos = pos
        if (old in filtered.indices) notifyItemChanged(old)
        if (pos in filtered.indices) notifyItemChanged(pos)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.channel_name)
        val group: TextView = view.findViewById(R.id.channel_group)
        val logo: ImageView = view.findViewById(R.id.channel_logo)
        val number: TextView = view.findViewById(R.id.channel_number)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = filtered[position]
        holder.name.text = ch.name
        holder.group.text = ch.group
        holder.number.text = (position + 1).toString().padStart(3, '0')
        if (ch.logo != null) {
            holder.logo.load(ch.logo) { crossfade(true) }
        } else {
            holder.logo.setImageResource(R.drawable.ic_tv)
        }
        holder.itemView.isSelected = position == selectedPos
        holder.itemView.setOnClickListener {
            setSelected(position)
            onSelect(ch)
        }
        // 长按 → 收藏/取消收藏（TiviMate 风格）
        holder.itemView.setOnLongClickListener {
            onToggleFavorite(ch)
            true
        }
        // D-pad 焦点支持（TV 遥控器）
        holder.itemView.isFocusable = true
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                setSelected(position)
                v.post { v.requestFocus() }
            }
        }
    }

    override fun getItemCount() = filtered.size

    fun getAt(pos: Int): Channel? = filtered.getOrNull(pos)
}
