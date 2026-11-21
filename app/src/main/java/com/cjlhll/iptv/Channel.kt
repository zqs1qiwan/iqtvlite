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
    /** 每频道请求 User-Agent，可来自 M3U http-user-agent 属性 */
    val userAgent: String? = null,
    /** M3U response-time 属性，单位毫秒；未知则为 null */
    val responseTimeMs: Int? = null,
)
