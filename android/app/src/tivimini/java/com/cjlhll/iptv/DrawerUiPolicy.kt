package com.cjlhll.iptv

/**
 * V4 §5 + 复审 B2：抽屉 UI 纯逻辑 —— 播放台用**变色**标识（不再 ▶ 文本）、节目单空态三分类。
 *
 * Android/Compose-free，可直接 JVM 单测。PlayerDrawer 只消费这里的字符串/枚举结果。
 */
object DrawerUiPolicy {

    /** 台名颜色 token（UI 层映射：PLAYING→品牌蓝 #2F6BFF，NORMAL→常规 onSurface）。 */
    enum class NameColor { PLAYING, NORMAL }

    /**
     * V4 §5：频道行标签始终为纯 `"$index. $title"`，不再加 ▶ 前缀。
     * 播放台改由台名颜色（[playingNameColor]）表达，与浏览焦点底色正交。
     */
    fun channelLabel(index: Int, title: String): String = "$index. $title"

    /** V4 §5：播放台台名染色 token。 */
    fun playingNameColor(selected: Boolean): NameColor =
        if (selected) NameColor.PLAYING else NameColor.NORMAL

    /** 节目单空态三分类（加 loading 作为瞬时态）：加载中 / 未配置 / 加载失败 / 无节目。 */
    fun programEmptyText(
        configured: Boolean,
        loadFailed: Boolean,
        loading: Boolean,
    ): String = when {
        loading -> "加载中"
        !configured -> "未配置 EPG 源"
        loadFailed -> "EPG 加载失败"
        else -> "暂无节目单"
    }
}
