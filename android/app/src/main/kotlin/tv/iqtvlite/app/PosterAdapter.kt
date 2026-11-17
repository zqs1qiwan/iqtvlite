package tv.iqtvlite.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/**
 * tvOS 风格海报卡浏览器（GridLayoutManager）。
 *
 * 视差近似：聚焦时整个卡片放大 12%，Logo 前景层额外放大 + 上浮，
 * 背景光晕层同步点亮 —— 三层不同位移营造 Apple TV 的纵深感。
 */
class PosterAdapter(
    private val onSelect: (Channel) -> Unit,
    private val onFocus: (Channel) -> Unit,
) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private var items: List<Channel> = emptyList()
    private var favIds: Set<Long> = emptySet()
    private var nowByTvg: Map<String, Pair<String, Int>> = emptyMap()
    private var selectedId = -1L
    private var itemW = -1
    private var itemH = -1
    fun setItemSize(w: Int, h: Int) { itemW = w; itemH = h }

    fun submit(list: List<Channel>, favorites: List<Long>) {
        items = list
        favIds = favorites.toSet()
        notifyDataSetChanged()
    }
    fun setNowPlaying(map: Map<String, Pair<String, Int>>) { nowByTvg = map; notifyDataSetChanged() }
    fun setSelected(id: Long) { val old = selectedId; selectedId = id; notifyItemChangedByIndex(old); notifyItemChangedByIndex(id) }
    private fun notifyItemChangedByIndex(id: Long) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) notifyItemChanged(i)
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.poster_card)
        val bgLayer: View = view.findViewById(R.id.poster_bg_layer)
        val logoLayer: View = view.findViewById(R.id.poster_logo_layer)
        val textLayer: View = view.findViewById(R.id.poster_text_layer)
        val logo: ImageView = view.findViewById(R.id.poster_logo)
        val number: TextView = view.findViewById(R.id.poster_number)
        val name: TextView = view.findViewById(R.id.poster_name)
        val programme: TextView = view.findViewById(R.id.poster_programme)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        if (itemW > 0 && itemH > 0) {
            v.layoutParams = RecyclerView.LayoutParams(itemW, itemH)
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.number.text = (position + 1).toString().padStart(3, '0') + if (favIds.contains(ch.id)) " ★" else ""
        holder.name.text = ch.name
        holder.programme.text = ch.tvgId?.let { nowByTvg[it]?.first } ?: ch.group
        holder.logo.setImageResource(R.drawable.ic_tv)
        if (ch.logo != null) holder.logo.load(ch.logo) { crossfade(true) }

        // 卡片整体 stateListAnimator（tvOS 焦点放大 + 抬升）
        holder.card.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
            holder.card.context, R.animator.focus_scale_poster
        )
        holder.card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 视差：Logo 前景层放大更多 + 上浮；文字层轻微上移
                holder.logoLayer.animate().scaleX(1.18f).scaleY(1.18f).translationY(-10f).setDuration(160).start()
                holder.textLayer.animate().translationY(-4f).setDuration(160).start()
                onFocus(ch)
            } else {
                holder.logoLayer.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(160).start()
                holder.textLayer.animate().translationY(0f).setDuration(160).start()
            }
        }
        holder.card.setOnClickListener { onSelect(ch) }
        holder.card.setOnLongClickListener { onSelect(ch); true }
    }

    override fun getItemCount() = items.size
    fun getAt(pos: Int): Channel? = items.getOrNull(pos)
}
