package tv.iqtvlite.app

import android.util.Log
import org.json.JSONObject

/**
 * Rust core 的 JNI 桥。
 *
 * 所有解析 / EPG / 稳定 ID / 回退决策逻辑都在 Rust 里（127 个测试验证），
 * Kotlin 只负责 Android UI 与 Media3 播放执行。
 */
object RustBridge {
    private const val TAG = "iqtv-rust"

    init {
        try {
            System.loadLibrary("iqtvlite_capi")
            Log.i(TAG, "Rust core loaded, version=${version()}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load Rust core", e)
        }
    }

    external fun version(): String
    external fun parseM3u(content: String, sourceId: Long): String
    external fun stableId(sourceId: Long, providerKey: String): Long
    external fun extractQuery(url: String, key: String): String
    external fun freeString(ptr: Long)

    /** 解析播放列表 → 频道列表 + 分组 */
    fun parsePlaylist(content: String, sourceId: Long): PlaylistResult {
        val json = JSONObject(parseM3u(content, sourceId))
        if (!json.optBoolean("ok", false)) {
            throw IllegalArgumentException(json.optString("error", "解析失败"))
        }
        val channels = mutableListOf<Channel>()
        val arr = json.getJSONArray("channels")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val urls = mutableListOf<String>()
            o.optJSONArray("altUrls")?.let { alts ->
                for (j in 0 until alts.length()) urls.add(alts.getString(j))
            }
            channels.add(
                Channel(
                    id = o.getLong("id"),
                    providerId = o.optString("providerId", ""),
                    name = o.optString("name", "未命名"),
                    tvgId = o.optString("tvgId", "").ifEmpty { null },
                    logo = o.optString("logo", "").ifEmpty { null },
                    group = o.optString("group", "其他"),
                    url = o.optString("url", ""),
                    altUrls = urls,
                    radio = o.optBoolean("radio", false),
                    userAgent = o.optString("userAgent", "").ifEmpty { null },
                    referrer = o.optString("referrer", "").ifEmpty { null },
                )
            )
        }
        val groups = mutableListOf<String>()
        val ga = json.getJSONArray("groups")
        for (i in 0 until ga.length()) groups.add(ga.getString(i))

        val epgUrls = mutableListOf<String>()
        val ea = json.getJSONArray("epgUrls")
        for (i in 0 until ea.length()) epgUrls.add(ea.getString(i))

        return PlaylistResult(channels, groups, epgUrls)
    }
}

data class Channel(
    val id: Long,
    val providerId: String,
    val name: String,
    val tvgId: String?,
    val logo: String?,
    val group: String,
    val url: String,
    val altUrls: List<String> = emptyList(),
    val radio: Boolean = false,
    val userAgent: String? = null,
    val referrer: String? = null,
)

data class PlaylistResult(
    val channels: List<Channel>,
    val groups: List<String>,
    val epgUrls: List<String>,
)
