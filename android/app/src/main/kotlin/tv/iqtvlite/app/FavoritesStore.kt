package tv.iqtvlite.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * 收藏 + 最近观看（TiviMate 核心功能）。
 *
 * 设计：用 SharedPreferences 存稳定 ID 列表（来自 Rust 的 xxh3 source+provider_key），
 * 所以播放列表重新同步后仍然有效（这正是稳定 ID 的意义）。
 *
 * 不引入 SQLite 依赖：数据量很小（几百个 ID），SharedPreferences 的 XML 足够，
 * 且避免了观看历史需要给 channels 表插入行的复杂性。
 */
object FavoritesStore {
    private const val PREF = "iqtvlite_favorites"
    private const val KEY_FAVS = "fav_ids"
    private const val KEY_RECENTS = "recent_ids"
    private const val KEY_PLAY_COUNTS = "play_counts"
    private const val MAX_RECENTS = 30

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun readIds(ctx: Context, key: String): List<Long> {
        val raw = prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getLong(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun writeIds(ctx: Context, key: String, ids: List<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    // ── 收藏 ────────────────────────────────────────────────────────
    fun favorites(ctx: Context): List<Long> = readIds(ctx, KEY_FAVS)

    fun isFavorite(ctx: Context, id: Long): Boolean = favorites(ctx).contains(id)

    fun toggleFavorite(ctx: Context, id: Long): Boolean {
        val current = favorites(ctx).toMutableList()
        val added = if (current.contains(id)) {
            current.remove(id)
            false
        } else {
            current.add(0, id) // 新收藏放最前
            true
        }
        writeIds(ctx, KEY_FAVS, current)
        return added
    }

    // ── 最近观看 ────────────────────────────────────────────────────
    fun recents(ctx: Context): List<Long> = readIds(ctx, KEY_RECENTS)

    /** 播放频道时调用，把它移到最近观看的最前面。 */
    fun markWatched(ctx: Context, id: Long) {
        val current = recents(ctx).toMutableList()
        current.remove(id)
        current.add(0, id)
        writeIds(ctx, KEY_RECENTS, current.take(MAX_RECENTS))
    }

    fun clearRecents(ctx: Context) = writeIds(ctx, KEY_RECENTS, emptyList())

    // ── R6 播放统计（纯本地，零遥测）────────────────────────────────
    /** 频道 ID → 播放次数（JSONObject 存 SharedPreferences，稳定 ID 跨源持久）。 */
    fun playCounts(ctx: Context): Map<Long, Int> {
        val raw = prefs(ctx).getString(KEY_PLAY_COUNTS, null) ?: return emptyMap()
        return try {
            val o = org.json.JSONObject(raw)
            o.keys().asSequence().associate { k -> k.toLong() to o.getInt(k) }
        } catch (_: Exception) { emptyMap() }
    }

    /** 播放频道时累加次数。 */
    fun recordPlay(ctx: Context, id: Long) {
        val counts = playCounts(ctx).toMutableMap()
        counts[id] = (counts[id] ?: 0) + 1
        val o = org.json.JSONObject()
        counts.forEach { (k, v) -> o.put(k.toString(), v) }
        prefs(ctx).edit().putString(KEY_PLAY_COUNTS, o.toString()).apply()
    }

    /** 常看频道 ID（按播放次数降序，前 N 个）。 */
    fun mostWatched(ctx: Context, limit: Int = 10): List<Long> =
        playCounts(ctx).toList().sortedByDescending { it.second }.take(limit).map { it.first }

    fun clearStats(ctx: Context) = prefs(ctx).edit().remove(KEY_PLAY_COUNTS).apply()
}
