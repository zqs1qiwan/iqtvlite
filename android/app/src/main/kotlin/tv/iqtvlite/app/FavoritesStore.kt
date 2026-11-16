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
}
