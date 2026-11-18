package com.cjlhll.iptv

import org.json.JSONObject
import tv.iqtvlite.app.RustBridge

/** Production parser: Rust owns M3U syntax and stable source/channel IDs. */
object M3uParser {
    fun parse(content: String, source: String = ""): List<Channel> {
        val sourceId = RustBridge.stableId(0L, source)
        val result = JSONObject(RustBridge.parseM3u(content, sourceId))
        require(result.optBoolean("ok")) { result.optString("error", "播放列表解析失败") }
        val rows = result.getJSONArray("channels")
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                fun text(key: String): String? = if (row.isNull(key)) null else row.optString(key).takeIf { it.isNotBlank() }
                val rawUa = text("userAgent")
                val ua = if (rawUa.equals("VLC", true)) "VLC/3.0.20 LibVLC/3.0.20" else rawUa
                val urls = mutableListOf<String>()
                text("url")?.let { urls.add(it) }
                row.optJSONArray("altUrls")?.let { alts -> for (j in 0 until alts.length()) urls.add(alts.getString(j)) }
                for (url in urls) {
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("rtsp://", true) && !url.startsWith("rtp://", true) && !url.startsWith("udp://", true)) continue
                    val address = HttpStreamAddress.resolve(url, ua, text("referrer"))
                    add(Channel(
                        title = text("name") ?: text("tvgName") ?: address.url,
                        url = address.url,
                        group = text("group"),
                        logoUrl = text("logo"),
                        tvgId = text("tvgId"),
                        tvgName = text("tvgName"),
                        catchupMode = text("catchupMode") ?: "append",
                        catchupSource = text("catchupSource") ?: "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}",
                        responseTimeMs = parseResponseTimeMs(text("responseTime")),
                        id = row.getLong("id"), userAgent = address.userAgent, referrer = address.referrer,
                    ))
                }
            }
        }
    }
    internal fun parseResponseTimeMs(raw: String?): Int? = raw?.trim()?.lowercase()?.removeSuffix("ms")?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
}
