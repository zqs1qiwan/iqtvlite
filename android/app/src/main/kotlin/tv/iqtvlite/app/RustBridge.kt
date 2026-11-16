package tv.iqtvlite.app

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

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
    external fun parseEpgGrid(
        xmltv: String,
        tvgIdsJson: String,
        now: Long,
        windowStart: Long,
        windowEnd: Long,
        slotMinutes: Int
    ): String
    external fun stableId(sourceId: Long, providerKey: String): Long
    external fun extractQuery(url: String, key: String): String
    external fun freeString(ptr: Long)

    /**
     * 读取响应体为字符串，并处理「服务器直接返回 gzip 文件但没有 Content-Encoding 头」的情况。
     * 老白直播的 EPG 端点 https://epg.laobaitv.net 就是这样（实测首字节 1f 8b）。
     */
    private fun decodeBody(body: okhttp3.ResponseBody): String {
        val bytes = body.bytes()
        if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0x1F && (bytes[1].toInt() and 0xFF) == 0x8B) {
            Log.i(TAG, "detected gzip body, decompressing ${bytes.size} bytes")
            val gz = GZIPInputStream(ByteArrayInputStream(bytes))
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (gz.read(buf).also { n = it } > 0) out.write(buf, 0, n)
            gz.close()
            return out.toString("UTF-8")
        }
        return String(bytes, Charsets.UTF_8)
    }

    fun readBody(body: okhttp3.ResponseBody): String = decodeBody(body)

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
