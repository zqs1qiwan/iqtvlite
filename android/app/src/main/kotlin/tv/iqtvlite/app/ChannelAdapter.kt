package tv.iqtvlite.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class ChannelAdapter(
    private val onSelect: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Unit,
    private val onLongPress: (Channel) -> Unit,
    private val onFocus: (Channel) -> Unit = {},
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
    private var mostWatchedIds: List<Long> = emptyList()
    private var currentQuery = ""
    private var currentGroupFilter: String? = null

    fun setLists(favorites: List<Long>, recents: List<Long>, mostWatched: List<Long> = emptyList()) {
        favIds = favorites.toSet()
        recentIds = recents.toSet()
        mostWatchedIds = mostWatched
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
            "__mostwatched__" -> mostWatchedIds.mapNotNull { id -> items.find { it.id == id } }
            null -> items
            else -> items.filter { ch ->
                val effectiveGroup = customGroups[ch.tvgId ?: ch.providerId] ?: ch.group
                effectiveGroup == group
            }
        }.filter { ch ->
            query.isBlank() ||
                ch.name.contains(query, ignoreCase = true) ||
                (ch.tvgId?.contains(query, ignoreCase = true) == true)
        }
        notifyDataSetChanged()
    }

    /** 自定义分组覆盖（tvgId → 组名） */
    private var customGroups: Map<String, String> = emptyMap()
    fun setCustomGroups(groups: Map<String, String>) { customGroups = groups }

    /** 列表变化后重新应用当前过滤（收藏切换后调用）。 */
    fun refresh() = filter(currentQuery, currentGroupFilter)

    fun setSelected(pos: Int) {
        val old = selectedPos
        selectedPos = pos
        if (old in filtered.indices) notifyItemChanged(old)
        if (pos in filtered.indices) notifyItemChanged(pos)
    }

    /** 当前节目信息（tvgId -> 标题 + 进度百分比），由 EPG 加载后注入 */
    private var nowByTvg: Map<String, Pair<String, Int>> = emptyMap()
    /** 当前正在播放的频道 id：进度条只给它显示，避免满屏蓝条视觉噪音 */
    private var playingId: Long = -1L
    fun setNowPlaying(map: Map<String, Pair<String, Int>>) {
        nowByTvg = map
        notifyDataSetChanged()
    }
    fun setPlaying(id: Long) {
        if (playingId == id) return
        val old = playingId
        playingId = id
        // 只刷新受影响的两行
        filtered.indexOfFirst { it.id == old }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        filtered.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.channel_name)
        val group: TextView = view.findViewById(R.id.channel_group)
        val logo: ImageView = view.findViewById(R.id.channel_logo)
        val number: TextView = view.findViewById(R.id.channel_number)
        val progress: ProgressBar = view.findViewById(R.id.channel_progress)
        val playingMark: View = view.findViewById(R.id.now_playing_mark)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = filtered[position]
        holder.name.text = ch.name
        holder.number.text = (position + 1).toString().padStart(3, '0')
        holder.logo.setImageResource(R.drawable.ic_tv)
        if (ch.logo != null) {
            holder.logo.load(ch.logo) { crossfade(true) }
        }
        // 当前节目名给所有行看（EPG 信息有用）；进度条只给正在播放的那一行
        val now = ch.tvgId?.let { nowByTvg[it] }
        if (now != null) {
            holder.group.text = now.first
            holder.progress.progress = now.second
            holder.progress.visibility = if (ch.id == playingId) android.view.View.VISIBLE else android.view.View.GONE
        } else {
            holder.group.text = ch.group
            holder.progress.visibility = android.view.View.GONE
        }
        // 显式设置背景 drawable，绕开系统默认的高亮色（AppCompat 默认黄色）
        holder.itemView.background = androidx.core.content.ContextCompat.getDrawable(
            holder.itemView.context,
            if (position == selectedPos) R.drawable.channel_row_active else R.drawable.channel_row_normal
        )
        holder.itemView.isSelected = position == selectedPos
        holder.playingMark.visibility = if (position == selectedPos) android.view.View.VISIBLE else android.view.View.INVISIBLE
        holder.itemView.setOnClickListener {
            setSelected(position)
            onSelect(ch)
        }
        // 长按 → 频道操作菜单（TiviMate 风格）
        holder.itemView.setOnLongClickListener {
            onLongPress(ch)
            true
        }
        // D-pad 焦点支持（TV 遥控器）
        holder.itemView.isFocusable = true
        // tvOS 风格：每个 item 挂 stateListAnimator，由系统按焦点状态自动驱动（海报卡 15% 缩放 + 抬升）
        holder.itemView.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
            holder.itemView.context, R.animator.focus_scale_poster
        )
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                setSelected(position)
                onFocus(ch) // PiP 预览焦点频道
            }
        }
    }

    override fun getItemCount() = filtered.size

    fun getAt(pos: Int): Channel? = filtered.getOrNull(pos)
}
