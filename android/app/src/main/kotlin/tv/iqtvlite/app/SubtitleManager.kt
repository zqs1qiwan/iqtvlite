package tv.iqtvlite.app

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * 字幕管理（TiviMate 风格）。
 *
 * 支持三类字幕：
 * 1. CEA-608 内嵌字幕（Red Bull 等直播源，TYPE=CLOSED-CAPTIONS）
 * 2. HLS 外部 WebVTT 字幕轨
 * 3. MPEG-TS 内嵌字幕
 *
 * 「字幕」按钮循环切换：关闭 → 可用轨道 1 → 轨道 2 → … → 关闭。
 */
object SubtitleManager {
    private const val TAG = "iqtv-subtitle"

    /** 当前可用字幕轨道（从 player.currentTracks 提取） */
    @Volatile
    var available: List<TrackInfo> = emptyList()
        private set

    /** 当前选中的索引（-1 = 关闭） */
    @Volatile
    var selectedIndex = -1
        private set

    data class TrackInfo(val groupIndex: Int, val trackIndex: Int, val label: String, val language: String?)

    /** 从 ExoPlayer 的 Tracks 里刷新字幕列表。 */
    fun refresh(player: Player) {
        val groups = player.currentTracks.groups
        val list = mutableListOf<TrackInfo>()
        groups.forEachIndexed { gi, group ->
            if (group.type != C.TRACK_TYPE_TEXT) return@forEachIndexed
            for (ti in 0 until group.length) {
                val format = group.getTrackFormat(ti)
                // 过滤掉强制/装饰轨道
                if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) continue
                val label = buildString {
                    append(format.label ?: format.language ?: "字幕")
                    if (!format.language.isNullOrEmpty()) append(" (${format.language})")
                    if (format.sampleMimeType == androidx.media3.common.MimeTypes.APPLICATION_CEA608) append(" [CC]")
                }
                list.add(TrackInfo(gi, ti, label, format.language))
            }
        }
        available = list
        if (selectedIndex >= list.size) selectedIndex = -1
        Log.i(TAG, "available subtitles: ${list.map { it.label }}")
    }

    /**
     * 循环切换字幕。返回新选中轨道的标签（"关闭" 表示关闭）。
     */
    fun cycle(player: Player, context: android.content.Context): String {
        refresh(player)
        if (available.isEmpty()) return "无字幕轨"

        selectedIndex = if (selectedIndex + 1 >= available.size) -1 else selectedIndex + 1

        val paramsBuilder = androidx.media3.common.TrackSelectionParameters.Builder(context)
        if (selectedIndex < 0) {
            // 关闭字幕：禁用所有 text track
            paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            Log.i(TAG, "subtitle OFF")
            player.trackSelectionParameters = paramsBuilder.build()
            return "字幕已关闭"
        }

        paramsBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        val t = available[selectedIndex]
        val override = androidx.media3.common.TrackSelectionOverride(
            player.currentTracks.groups[t.groupIndex].mediaTrackGroup,
            listOf(t.trackIndex),
        )
        paramsBuilder.addOverride(override)
        player.trackSelectionParameters = paramsBuilder.build()
        Log.i(TAG, "subtitle -> ${t.label}")
        return t.label
    }

    fun reset() {
        selectedIndex = -1
        available = emptyList()
    }
}
