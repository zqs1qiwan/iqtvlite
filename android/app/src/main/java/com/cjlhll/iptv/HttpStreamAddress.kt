package com.cjlhll.iptv

/**
 * 播放地址与 HTTP 请求头统一解析器（兼容 Kodi / VLC / TVBox / URL | 参数写法）：
 *
 * 1. 支持 URL 中 `|` 后的任意头部参数（如 `|User-Agent=VLC&Referer=https://...&Origin=...&Cookie=...`）；
 * 2. 支持 M3U `#EXTINF` 属性（`http-user-agent` / `user-agent` / `http-referer` / `http-referrer` /
 *    `http-origin` / `http-cookie`）以及 `#EXTVLCOPT:` / `#KODIPROP:` 指令；
 * 3. **优先级规则（用户明确要求）**：
 *    当播放 URL 中 `|` 后参数与 `#EXTINF` 属性同时存在时，**优先使用播放 URL 中 `|` 后的匹配值**！
 * 4. 完整头部最终合并顺序：`URL | 参数 > #EXTINF / #EXTVLCOPT > 源级/全局自定义 UA 与 Header > 默认 UA`。
 */
object HttpStreamAddress {

    const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    data class Value(
        val url: String,
        val userAgent: String?,
        val referrer: String?,
        val headers: Map<String, String> = emptyMap(),
    )

    private fun stripOuterQuotes(raw: String): String {
        val s = raw.trim()
        if (s.length >= 2 && ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith('\'') && s.endsWith('\'')))) {
            return s.substring(1, s.length - 1).trim()
        }
        return s
    }

    fun normalizeUserAgent(raw: String?): String? {
        val trimmed = stripOuterQuotes(raw.orEmpty())
        if (trimmed.isEmpty()) return null
        return if (trimmed.equals("VLC", ignoreCase = true)) DEFAULT_USER_AGENT else trimmed
    }

    /**
     * 解析流地址及伴随请求头。
     * @param url 原始流地址（可能包含 `|User-Agent=...&Referer=...&Origin=...`）
     * @param extinfUserAgent 来自 `#EXTINF` / `#EXTVLCOPT` 的 UA
     * @param extinfReferrer 来自 `#EXTINF` / `#EXTVLCOPT` 的 Referer
     * @param extinfHeaders 来自 `#EXTINF` / `#EXTVLCOPT` / `#KODIPROP` 的其它额外头
     */
    fun resolve(
        url: String,
        extinfUserAgent: String?,
        extinfReferrer: String?,
        extinfHeaders: Map<String, String> = emptyMap(),
    ): Value {
        val parts = url.split('|', limit = 2)
        val cleanUrl = parts[0].trim()

        // 1. 解析 URL `|` 后的全部 Key=Value（兼容 `&` 与 `;` 分隔，自动 URL 解码）
        val pipeHeaders = LinkedHashMap<String, String>()
        val rawSuffix = parts.getOrNull(1)?.trim().orEmpty()
        if (rawSuffix.isNotEmpty()) {
            for (segment in rawSuffix.split('&', ';')) {
                val eq = segment.indexOf('=')
                if (eq <= 0) continue
                val keyRaw = segment.substring(0, eq).trim()
                val valRaw = segment.substring(eq + 1).trim()
                if (keyRaw.isEmpty() || valRaw.isEmpty()) continue
                val decoded = stripOuterQuotes(
                    runCatching { java.net.URLDecoder.decode(valRaw, "UTF-8") }.getOrDefault(valRaw)
                )
                if (decoded.isEmpty()) continue
                val canonical = PlaylistSources.canonicalHeaderName(keyRaw)
                pipeHeaders[canonical] = if (canonical == "User-Agent") {
                    normalizeUserAgent(decoded) ?: decoded
                } else {
                    decoded
                }
            }
        }

        // 2. 合并：先铺 EXTINF 头部，再用 URL `|` 后头部覆盖（URL 优先！）
        val merged = LinkedHashMap<String, String>()
        for ((k, v) in extinfHeaders) {
            val ck = PlaylistSources.canonicalHeaderName(k)
            val cv = v.trim()
            if (ck.isNotEmpty() && cv.isNotEmpty()) {
                merged[ck] = if (ck == "User-Agent") normalizeUserAgent(cv) ?: cv else cv
            }
        }
        normalizeUserAgent(extinfUserAgent)?.let { merged["User-Agent"] = it }
        extinfReferrer?.trim()?.takeIf { it.isNotEmpty() }?.let { merged["Referer"] = it }

        // URL `|` 参数覆盖 EXTINF 同名项
        for ((k, v) in pipeHeaders) {
            merged[k] = v
        }

        val finalUa = merged["User-Agent"]
        val finalRef = merged["Referer"]
        val extras = merged.filterKeys { it != "User-Agent" && it != "Referer" }
        return Value(url = cleanUrl, userAgent = finalUa, referrer = finalRef, headers = extras)
    }

    /**
     * 轻量纯 Kotlin 辅助解析器：从 M3U 原文提取每个流 URL 对应的 `#EXTINF` / `#EXTVLCOPT` / `#KODIPROP`
     * 扩展头部与 `#EXTM3U x-tvg-url`，补齐 Rust 解析器未透传的 `http-referer` / `http-origin` / `http-cookie` 等。
     */
    data class M3uExtras(
        val headerEpgUrl: String?,
        val perRawUrlHeaders: Map<String, Map<String, String>>,
    )

    private val extinfAttrRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"""")
    private val headerEpgRegex = Regex("""(?i)(?:x-tvg-url|url-tvg)\s*=\s*"([^"]+)"""")

    fun extractM3uExtras(content: String): M3uExtras {
        var headerEpg: String? = null
        val perUrl = HashMap<String, Map<String, String>>()
        val pending = LinkedHashMap<String, String>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    if (headerEpg == null) {
                        headerEpg = headerEpgRegex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pending.clear()
                    val headerPart = line.substringBeforeLast(',')
                    for (m in extinfAttrRegex.findAll(headerPart)) {
                        val k = m.groupValues[1].trim().lowercase()
                        val v = m.groupValues[2].trim()
                        if (v.isEmpty()) continue
                        when (k) {
                            "http-user-agent", "user-agent" -> pending["User-Agent"] = normalizeUserAgent(v) ?: v
                            "http-referer", "http-referrer", "referer", "referrer" -> pending["Referer"] = v
                            "http-origin", "origin" -> pending["Origin"] = v
                            "http-cookie", "cookie" -> pending["Cookie"] = v
                        }
                    }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val body = line.substringAfter(':').trim()
                    val eq = body.indexOf('=')
                    if (eq > 0) {
                        val k = body.substring(0, eq).trim().lowercase()
                        val v = body.substring(eq + 1).trim()
                        if (v.isNotEmpty()) {
                            when (k) {
                                "http-user-agent" -> pending["User-Agent"] = normalizeUserAgent(v) ?: v
                                "http-referrer", "http-referer" -> pending["Referer"] = v
                                "http-origin" -> pending["Origin"] = v
                                "http-cookie" -> pending["Cookie"] = v
                            }
                        }
                    }
                }
                line.startsWith("#KODIPROP:", ignoreCase = true) -> {
                    val body = line.substringAfter(':').trim()
                    if (body.startsWith("inputstream.adaptive.stream_headers=", ignoreCase = true)) {
                        val hdrStr = body.substringAfter('=')
                        for (seg in hdrStr.split('&')) {
                            val eq = seg.indexOf('=')
                            if (eq > 0) {
                                val ck = PlaylistSources.canonicalHeaderName(seg.substring(0, eq))
                                val cv = runCatching { java.net.URLDecoder.decode(seg.substring(eq + 1), "UTF-8") }
                                    .getOrDefault(seg.substring(eq + 1)).trim()
                                if (ck.isNotEmpty() && cv.isNotEmpty()) {
                                    pending[ck] = if (ck == "User-Agent") normalizeUserAgent(cv) ?: cv else cv
                                }
                            }
                        }
                    }
                }
                !line.startsWith("#") -> {
                    if (pending.isNotEmpty()) {
                        val cleanKey = line.substringBefore('|').trim()
                        perUrl[line] = HashMap(pending)
                        if (cleanKey != line) perUrl.putIfAbsent(cleanKey, HashMap(pending))
                    }
                }
            }
        }
        return M3uExtras(headerEpgUrl = headerEpg, perRawUrlHeaders = perUrl)
    }
}
