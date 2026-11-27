package com.cjlhll.iptv

data class Channel(
    val title: String,
    val url: String,
    val group: String? = null,
    val logoUrl: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val catchupMode: String? = null,
    val catchupSource: String? = null,
    /** M3U response-time 属性，单位毫秒；未知则为 null */
    val responseTimeMs: Int? = null,
    val id: Long = 0,
    val userAgent: String? = null,
    val referrer: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
    /** 来自同一个 M3U `#EXTINF` 条目的原始序号；相同序号的多条 URL 为同一频道的多源线路，不同 `#EXTINF` 条目各自独立成台。 */
    val entryIndex: Int = -1,
    val radio: Boolean = false,
    val groups: List<String> = emptyList(),
)
