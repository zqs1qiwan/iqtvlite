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
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var filtered: List<Channel> = emptyList()
    private var selectedPos = -1

    fun submit(list: List<Channel>) {
        items = list
        filtered = list
        notifyDataSetChanged()
    }

    fun filter(query: String, group: String?) {
        filtered = items.filter { ch ->
            val matchText = query.isBlank() ||
                ch.name.contains(query, ignoreCase = true) ||
                (ch.tvgId?.contains(query, ignoreCase = true) == true)
            val matchGroup = group == null || ch.group == group
            matchText && matchGroup
        }
        notifyDataSetChanged()
    }

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
