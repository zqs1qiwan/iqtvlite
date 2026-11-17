package tv.iqtvlite.app

/**
 * 播放列表来源解析（纯逻辑，无 Android/Activity 依赖）。
 *
 * 从供应商的 HTML 落地页里找真实 M3U 地址；解析播放列表里的 UA 覆盖标记。
 * 独立于 Activity，便于单测与复用。
 */
object PlaylistUrlResolver {
    /**
     * 从 laobaitv.net 之类的 HTML 配置页里提取真实 M3U 地址。
     * 优先明确的 .m3u(8) 链接（排除 googleapis 字体），其次 data-url / href 标注的生成接口。
     * 找不到返回 null，调用方回退到默认线路。
     */
    fun extractPlaylistUrl(html: String): String? {
        val direct = Regex(
            """https?://[^"''<>\s\\]+\.m3u8?(?:\?[^"''<>\s\\]*)?""",
            RegexOption.IGNORE_CASE,
        )
        direct.find(html)?.value?.let { if (!it.contains("googleapis")) return it }
        val api = Regex(
            """(?:href|src|data-url|action)=["'](https?://[^"']*(?:get\.php|playlist|\.m3u)[^"']*)["']""",
            RegexOption.IGNORE_CASE,
        )
        return api.find(html)?.groups?.get(1)?.value
    }

    /** 把播放列表里的 UA 标记（VLC / TiviMate / Mozilla / 完整自定义串）解析为实际 User-Agent。 */
    fun resolveUserAgent(raw: String?): String = when {
        raw == null -> "TiviMate/5.1.6 (Android 12)"
        raw.equals("VLC", ignoreCase = true) || raw.startsWith("VLC/") -> "VLC/3.0.20 LibVLC/3.0.20"
        raw.equals("TiviMate", ignoreCase = true) || raw.startsWith("TiviMate/") -> "TiviMate/5.1.6 (Android 12)"
        raw.startsWith("Mozilla/") -> raw
        else -> raw
    }
}
