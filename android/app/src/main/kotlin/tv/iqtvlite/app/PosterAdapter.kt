package tv.iqtvlite.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope

/**
 * tvOS 风格海报卡浏览器（GridLayoutManager）。
 *
 * 视差近似：聚焦时整个卡片放大 12%，Logo 前景层额外放大 + 上浮，
 * 背景光晕层同步点亮 —— 三层不同位移营造 Apple TV 的纵深感。
 */
class PosterAdapter(
    private val scope: CoroutineScope,
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
        val glow: View = view.findViewById(R.id.poster_glow)
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
        // 真分层视差：前景清晰 Logo + 背景模糊 Logo（由同一张图生成）
        LayeredPoster.load(scope, holder.card.context, holder.logo,
            holder.bgLayer as ImageView, ch.logo)

        // 卡片整体 stateListAnimator（tvOS 焦点放大 + 抬升）
        holder.card.stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(
            holder.card.context, R.animator.focus_scale_poster
        )
        holder.card.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 真视差：前景 Logo 放大上浮，背景模糊层反向缩小下移（景深）
                holder.logoLayer.animate().scaleX(1.20f).scaleY(1.20f).translationY(-14f).setDuration(180).start()
                holder.bgLayer.animate().scaleX(1.1f).scaleY(1.1f).translationY(8f).alpha(0.35f).setDuration(220).start()
                holder.textLayer.animate().translationY(-6f).setDuration(180).start()
                onFocus(ch)
            } else {
                holder.logoLayer.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(180).start()
                holder.bgLayer.animate().scaleX(1f).scaleY(1f).translationY(0f).alpha(0.55f).setDuration(180).start()
                holder.textLayer.animate().translationY(0f).setDuration(180).start()
            }
        }
        holder.card.setOnClickListener { onSelect(ch) }
        holder.card.setOnLongClickListener { onSelect(ch); true }
    }

    override fun getItemCount() = items.size
    fun getAt(pos: Int): Channel? = items.getOrNull(pos)
}
