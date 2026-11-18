package com.cjlhll.iptv

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 源持久化读接口（便于测试注入）。 */
interface SourcePrefsStore {
    fun getLiveSource(): String
    fun getPlaylistFileName(): String?
    fun commitSource(live: String, epg: String, fileName: String)
    fun setPlaylistFileName(fileName: String)
    /** 持久源版本号（R03 边界）：跨进程可递增，用于识别旧后台结果。 */
    fun getVersion(): Long
    fun setVersion(version: Long)
}

/** 缓存写接口（便于测试注入 IO 失败/锁闩）。 */
interface PlaylistCacheStore {
    fun fileNameFor(sourceUrl: String): String
    fun writeAtomic(fileName: String, content: String): Boolean
    fun read(fileName: String): String?
}

/**
 * 源配置与缓存的单一事务仓库（R03）。
 *
 * - 所有写者（手动配置 / 后台刷新）共用同一把锁与持久版本号 `version`。
 * - 后台刷新携带发起时的 `expectedVersion`，若期间发生任何一次提交（A→B→A 亦会使版本改变），
 *   则丢弃旧结果，避免旧源结果覆盖新源指针/缓存。
 * - 缓存写入成功后才更新 Prefs 指针，失败保留旧缓存。
 */
class SourceRepository(
    private val prefs: SourcePrefsStore,
    private val cache: PlaylistCacheStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()

    fun currentVersion(): Long = prefs.getVersion()

    /** 手动配置提交：验证成功后原子提交源、EPG、缓存指针（单 Editor）并递增持久版本。 */
    suspend fun beginChange(): Long = mutex.withLock {
        val next = prefs.getVersion() + 1
        prefs.setVersion(next)
        next
    }

    suspend fun commitSource(live: String, epg: String, content: String, expectedVersion: Long? = null): Boolean = mutex.withLock {
        if (expectedVersion != null && prefs.getVersion() != expectedVersion) return@withLock false
        val fileName = cache.fileNameFor(live)
        if (!cache.writeAtomic(fileName, content)) return@withLock false
        prefs.setVersion(prefs.getVersion() + 1)
        prefs.commitSource(live, epg, fileName)
        true
    }

    /** 后台刷新提交：仅当版本未变、源指针仍为同源时替换缓存并更新指针。 */
    suspend fun commitBackground(expectedVersion: Long, live: String, content: String): Boolean = mutex.withLock {
        if (expectedVersion != prefs.getVersion()) return@withLock false
        if (prefs.getLiveSource() != live) return@withLock false
        val fileName = cache.fileNameFor(live)
        if (!cache.writeAtomic(fileName, content)) return@withLock false
        prefs.setVersion(prefs.getVersion() + 1)
        prefs.setPlaylistFileName(fileName)
        true
    }
}

/** Android 生产适配器（Prefs + AtomicFile 缓存）。 */
object SourceStore {
    private fun repository(context: Context): SourceRepository {
        val app = context.applicationContext
        val prefs = object : SourcePrefsStore {
            override fun getLiveSource(): String = Prefs.getLiveSource(app)
            override fun getPlaylistFileName(): String? = Prefs.getPlaylistFileName(app)
            override fun commitSource(live: String, epg: String, fileName: String) {
                Prefs.setSourceConfig(app, live, epg, fileName) // 单 Editor
            }
            override fun setPlaylistFileName(fileName: String) = Prefs.setPlaylistFileName(app, fileName)
            override fun getVersion(): Long = Prefs.getSourceVersion(app)
            override fun setVersion(version: Long) = Prefs.setSourceVersion(app, version)
        }
        val cache = object : PlaylistCacheStore {
            override fun fileNameFor(sourceUrl: String): String = PlaylistCache.fileNameForSource(sourceUrl)
            override fun writeAtomic(fileName: String, content: String): Boolean =
                PlaylistCache.writeAtomic(app, fileName, content)
            override fun read(fileName: String): String? = PlaylistCache.read(app, fileName)
        }
        return SourceRepository(prefs, cache)
    }

    private val instances = HashMap<String, SourceRepository>()

    private fun repoFor(context: Context): SourceRepository = synchronized(instances) {
        val key = context.applicationContext.packageName
        instances.getOrPut(key) { repository(context) }
    }

    fun currentVersion(context: Context): Long = repoFor(context).currentVersion()

    suspend fun beginChange(context: Context): Long = repoFor(context).beginChange()

    suspend fun commitSource(context: Context, live: String, epg: String, content: String, expectedVersion: Long? = null): Boolean =
        repoFor(context).commitSource(live, epg, content, expectedVersion)

    suspend fun commitBackground(context: Context, expectedVersion: Long, live: String, content: String): Boolean =
        repoFor(context).commitBackground(expectedVersion, live, content)
}
