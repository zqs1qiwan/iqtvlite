package tv.iqtvlite.app

import android.content.Context
import java.io.File

/**
 * 播放列表本地缓存 —— 冷启动秒开的关键。
 *
 * 策略：
 * 1. 启动时先读缓存 → 立即渲染频道列表（< 1s）
 * 2. 后台拉网络播放列表 → 成功后刷新 + 更新缓存
 * 3. 网络失败时用缓存继续工作（离线可用）
 */
object PlaylistCache {
    private fun file(ctx: Context, sourceIndex: Int) =
        File(ctx.filesDir, "playlist_$sourceIndex.m3u")

    fun save(ctx: Context, sourceIndex: Int, content: String) {
        try { file(ctx, sourceIndex).writeText(content) } catch (_: Exception) {}
    }

    fun load(ctx: Context, sourceIndex: Int): String? =
        try { file(ctx, sourceIndex).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    fun clear(ctx: Context, sourceIndex: Int) {
        try { file(ctx, sourceIndex).delete() } catch (_: Exception) {}
    }
}
