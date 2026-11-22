package com.cjlhll.iptv

/**
 * FULLFIX 复审 B2（U5）：抽屉 UI 的纯逻辑 —— 播放台 ▶ 标记、节目单空态三分类。
 *
 * Android/Compose-free，可直接 JVM 单测。PlayerDrawer 只消费这里的字符串结果，
 * 不在重组里拼接，保持"无蓝框/Flat、无硬边框"的既有视觉风格。
 */
object DrawerUiPolicy {

    /** 播放中前缀（纯文本，无新节点/无动画/无边框）。 */
    const val PLAYING_MARK = "▶"

    /**
     * 频道行标签：当前播放台加 `▶ ` 前缀，浏览焦点行不加 —— 两个维度正交，
     * 焦点由 surfaceVariant 底色表达，播放台由 ▶ 文本表达，可同时辨认。
     */
    fun channelLabel(selected: Boolean, index: Int, title: String): String =
        (if (selected) "$PLAYING_MARK " else "") + "$index. $title"

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
