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
        val m3uExtras = HttpStreamAddress.extractM3uExtras(content)
        var rawStreamIdx = 0
        val seenIds = HashSet<Long>(rows.length())
        return buildList {
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                fun text(key: String): String? = if (row.isNull(key)) null else row.optString(key).takeIf { it.isNotBlank() }
                val rawUa = text("userAgent")
                val ua = HttpStreamAddress.normalizeUserAgent(rawUa)
                val urls = mutableListOf<String>()
                text("url")?.let { urls.add(it) }
                row.optJSONArray("altUrls")?.let { alts -> for (j in 0 until alts.length()) urls.add(alts.getString(j)) }
                val baseId = row.getLong("id")
                val uniqueChannelId = if (baseId != 0L && seenIds.add(baseId)) baseId else {
                    val mixed = (baseId xor ((i + 1).toLong() * 0x9E3779B97F4A7C15UL.toLong())).let {
                        if (it == 0L) (i + 1).toLong() else it
                    }
                    seenIds.add(mixed)
                    mixed
                }
                for (url in urls) {
                    val idx = rawStreamIdx++
                    if (!url.startsWith("http://", true) && !url.startsWith("https://", true) && !url.startsWith("rtsp://", true) && !url.startsWith("rtp://", true) && !url.startsWith("udp://", true)) continue
                    val extHints = m3uExtras.perEntryHeaders.getOrNull(idx)
                        ?: m3uExtras.perRawUrlHeaders[url]
                        ?: m3uExtras.perRawUrlHeaders[url.substringBefore('|').trim()]
                        ?: emptyMap()
                    val resolvedGroup = text("group") ?: m3uExtras.perEntryExtGrp.getOrNull(idx)
                    val parsedGroups = buildList {
                        row.optJSONArray("groups")?.let { arr ->
                            for (gIdx in 0 until arr.length()) {
                                val g = arr.optString(gIdx).trim()
                                if (g.isNotEmpty() && g !in this) add(g)
                            }
                        }
                        if (isEmpty() && !resolvedGroup.isNullOrBlank()) {
                            for (part in resolvedGroup.split(';')) {
                                val g = part.trim()
                                if (g.isNotEmpty() && g !in this) add(g)
                            }
                        }
                    }
                    val address = HttpStreamAddress.resolve(
                        url = url,
                        extinfUserAgent = ua ?: extHints["User-Agent"],
                        extinfReferrer = text("referrer") ?: extHints["Referer"],
                        extinfHeaders = extHints,
                    )
                    add(Channel(
                        title = text("name") ?: text("tvgName") ?: address.url,
                        url = address.url,
                        group = parsedGroups.firstOrNull() ?: resolvedGroup,
                        logoUrl = text("logo"),
                        tvgId = text("tvgId"),
                        tvgName = text("tvgName"),
                        catchupMode = text("catchupMode"),
                        catchupSource = text("catchupSource"),
                        responseTimeMs = parseResponseTimeMs(text("responseTime")),
                        id = uniqueChannelId,
                        userAgent = address.userAgent,
                        referrer = address.referrer,
                        customHeaders = address.headers,
                        entryIndex = i,
                        radio = row.optBoolean("radio", false),
                        groups = parsedGroups,
                    ))
                }
            }
        }
    }
    internal fun parseResponseTimeMs(raw: String?): Int? = raw?.trim()?.lowercase()?.removeSuffix("ms")?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
}
