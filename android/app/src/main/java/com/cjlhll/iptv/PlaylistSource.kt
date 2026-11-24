package com.cjlhll.iptv

/**
 * 多播放源纯 Kotlin 模型与编解码器（Android/Compose-free，100% 可 JVM 单测）。
 *
 * 多播放列表支持：
 * - 每个源拥有独立 [id]、[name]、[url]、[epgUrl]、[enabled]（可在设置中隐藏/展示）、[userAgent]；
 * - [serialize] / [parse] 采用自研轻量转义格式（不依赖 org.json Android 桩），确保 JVM 与真机零差异；
 * - 兼容老版本单源偏好：若列表为空自动从旧 `live_source` / `epg_source` 无缝迁移。
 */
data class PlaylistSource(
    val id: String,
    val name: String,
    val url: String,
    val epgUrl: String = "",
    val enabled: Boolean = true,
    val userAgent: String = "",
)

object PlaylistSources {
    private const val RECORD_SEP = "\n"
    private const val FIELD_SEP = "\t"

    fun defaultNameForUrl(url: String, fallbackIndex: Int = 1): String {
        val clean = url.trim()
        if (clean.isEmpty()) return "播放源 $fallbackIndex"
        val host = clean
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
            .trim()
        return when {
            host.equals("iptv.laobaitv.net", true) -> "老白直播源"
            host.equals("lbzs.laobaitv.net", true) -> "老白专享源"
            host.isNotBlank() -> host
            else -> "播放源 $fallbackIndex"
        }
    }

    fun newId(url: String, existingIds: Set<String> = emptySet()): String {
        val base = "src_" + (url.trim().hashCode().toUInt().toString(16))
        if (base !in existingIds) return base
        var idx = 2
        while ("${base}_$idx" in existingIds) idx++
        return "${base}_$idx"
    }

    fun visibleSources(sources: List<PlaylistSource>, activeId: String? = null): List<PlaylistSource> {
        val enabled = sources.filter { it.enabled && it.url.isNotBlank() }
        if (enabled.isNotEmpty()) return enabled
        val active = activeId?.let { id -> sources.firstOrNull { it.id == id && it.url.isNotBlank() } }
        return listOfNotNull(active ?: sources.firstOrNull { it.url.isNotBlank() })
    }

    fun activeSource(sources: List<PlaylistSource>, activeId: String?): PlaylistSource? {
        if (sources.isEmpty()) return null
        return activeId?.let { id -> sources.firstOrNull { it.id == id } }
            ?: sources.firstOrNull { it.enabled }
            ?: sources.first()
    }

    fun upsert(sources: List<PlaylistSource>, item: PlaylistSource): List<PlaylistSource> {
        val idx = sources.indexOfFirst { it.id == item.id }
        return if (idx >= 0) {
            sources.toMutableList().apply { this[idx] = item }
        } else {
            sources + item
        }
    }

    fun remove(sources: List<PlaylistSource>, id: String): List<PlaylistSource> =
        sources.filterNot { it.id == id }

    fun toggleEnabled(sources: List<PlaylistSource>, id: String): List<PlaylistSource> =
        sources.map { if (it.id == id) it.copy(enabled = !it.enabled) else it }

    fun serialize(sources: List<PlaylistSource>): String {
        if (sources.isEmpty()) return ""
        return sources.joinToString(RECORD_SEP) { s ->
            listOf(
                esc(s.id),
                esc(s.name),
                esc(s.url),
                esc(s.epgUrl),
                if (s.enabled) "1" else "0",
                esc(s.userAgent),
            ).joinToString(FIELD_SEP)
        }
    }

    fun parse(raw: String?): List<PlaylistSource> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split(RECORD_SEP).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split(FIELD_SEP)
            if (cols.size < 3) return@mapNotNull null
            val id = unesc(cols[0]).ifBlank { return@mapNotNull null }
            val url = unesc(cols[2]).trim()
            if (url.isBlank() || !seen.add(id)) return@mapNotNull null
            val name = unesc(cols[1]).trim().ifBlank { defaultNameForUrl(url) }
            val epg = cols.getOrNull(3)?.let { unesc(it).trim() }.orEmpty()
            val enabled = cols.getOrNull(4)?.trim() != "0"
            val ua = cols.getOrNull(5)?.let { unesc(it).trim() }.orEmpty()
            PlaylistSource(id = id, name = name, url = url, epgUrl = epg, enabled = enabled, userAgent = ua)
        }
    }

    /** 解析多行 `Header-Name: Value` 全局自定义请求头文本。 */
    fun parseCustomHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            val eq = trimmed.indexOf('=')
            val sep = when {
                colon > 0 && eq > 0 -> minOf(colon, eq)
                colon > 0 -> colon
                else -> eq
            }
            if (sep <= 0) continue
            val key = canonicalHeaderName(trimmed.substring(0, sep).trim())
            val value = trimmed.substring(sep + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                out[key] = value
            }
        }
        return out
    }

    fun formatCustomHeaders(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }

    fun canonicalHeaderName(raw: String): String = when (raw.trim().lowercase()) {
        "user-agent", "http-user-agent", "ua" -> "User-Agent"
        "referer", "referrer", "http-referer", "http-referrer" -> "Referer"
        "origin", "http-origin" -> "Origin"
        "cookie", "http-cookie" -> "Cookie"
        "authorization" -> "Authorization"
        "x-forwarded-for" -> "X-Forwarded-For"
        else -> raw.trim()
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> { sb.append('\t'); i += 2; continue }
                    'n' -> { sb.append('\n'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }
}
