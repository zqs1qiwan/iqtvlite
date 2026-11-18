package com.cjlhll.iptv

/** Kodi-style suffix options belong in headers, never in the requested stream path. */
object HttpStreamAddress {
    data class Value(val url: String, val userAgent: String?, val referrer: String?)
    fun resolve(url: String, userAgent: String?, referrer: String?): Value {
        val parts = url.split('|', limit=2)
        val options = parts.getOrNull(1)?.split('&').orEmpty().mapNotNull { part ->
            val key = part.substringBefore('=', "").lowercase()
            if (key.isEmpty()) null else key to runCatching { java.net.URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrDefault(part.substringAfter('='))
        }.toMap()
        val rawUa = userAgent ?: options["user-agent"]
        val ua = if (rawUa.equals("VLC", true)) "VLC/3.0.20 LibVLC/3.0.20" else rawUa
        return Value(parts[0], ua, referrer ?: options["referer"] ?: options["referrer"])
    }
}
