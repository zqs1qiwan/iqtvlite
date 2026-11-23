package com.cjlhll.iptv

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 源作用域、单飞并发控制的 EPG 仓库：
 * - 底层改用 SQLite 索引库 [EpgSqlStore]，冷启 <20ms、内存 <1MB（彻底淘汰 22s Java `.ser` 反序列化）；
 * - 兼容已有调用签名 [load] / [loadStructured]（网络更新成功即原子替换 SQLite 并通知 UI 刷新版本号）；
 * - 失败绝不覆盖已有良好索引数据。
 */
object EpgRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight = HashMap<String, Deferred<EpgLoadResult>>()

    /** 轻量空壳：标志该源 SQLite 索引已就绪（具体节目按需从 EpgSqlStore 毫秒查询）。 */
    private val SQL_READY_SENTINEL = EpgData(emptyMap(), emptyMap())

    fun isSqlBacked(data: EpgData?): Boolean = data === SQL_READY_SENTINEL

    suspend fun load(context: Context, epgSourceUrl: String, forceRefresh: Boolean = false): EpgData? =
        loadStructured(context, epgSourceUrl, forceRefresh).dataOrNull

    suspend fun loadStructured(context: Context, source: String, forceRefresh: Boolean = false): EpgLoadResult {
        if (source.isBlank()) return EpgLoadResult.Failure(source, "empty source")
        val appContext = context.applicationContext
        if (!forceRefresh && EpgSqlStore.ensureIndexLoaded(appContext, source)) {
            return EpgLoadResult.CacheFallback(source, SQL_READY_SENTINEL)
        }
        val work = mutex.withLock {
            inFlight[source] ?: scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try { loadCore(appContext, source, forceRefresh) }
                finally { mutex.withLock { inFlight.remove(source) } }
            }.also { inFlight[source] = it; it.start() }
        }
        return work.await()
    }

    private suspend fun loadCore(context: Context, source: String, force: Boolean): EpgLoadResult = withContext(Dispatchers.IO) {
        val hasSql = EpgSqlStore.ensureIndexLoaded(context, source)
        if (!force && hasSql) {
            return@withContext EpgLoadResult.CacheFallback(source, SQL_READY_SENTINEL)
        }

        // 若本地还有旧版原始 XMLTV 缓存（尚未迁移进 SQLite），先尝试无网迁移一次
        val name = EpgCache.fileNameForSource(source)
        val raw = File(context.filesDir, name)
        val legacySer = File(context.filesDir, "$name.rust-v1.ser")
        if (!force && !hasSql && raw.exists()) {
            val migrated = runCatching {
                val parsed = XmlTvParser.parseFile(raw)
                EpgSqlStore.replaceAll(context, source, parsed)
                legacySer.delete()
                true
            }.getOrDefault(false)
            if (migrated) {
                return@withContext EpgLoadResult.CacheFallback(source, SQL_READY_SENTINEL)
            }
        }

        val candidate = File.createTempFile("epg-", ".xmltv", context.cacheDir)
        try {
            val client = NetUtil.ipv4OkHttpClient.newBuilder()
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            client.newCall(Request.Builder().url(source).build()).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = requireNotNull(response.body)
                require(body.contentLength() <= 32L * 1024 * 1024) { "EPG response too large" }
                body.byteStream().use { input ->
                    candidate.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 32L * 1024 * 1024) { "EPG response too large" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            val parsed = XmlTvParser.parseFile(candidate)
            EpgSqlStore.replaceAll(context, source, parsed)
            // 清理占空间的旧版 Java .ser 文件
            if (legacySer.exists()) runCatching { legacySer.delete() }
            EpgLoadResult.Success(source, SQL_READY_SENTINEL)
        } catch (e: Exception) {
            Log.w("EPG", "refresh failed type=${e.javaClass.simpleName}")
            if (hasSql || EpgSqlStore.ensureIndexLoaded(context, source)) {
                EpgLoadResult.CacheFallback(source, SQL_READY_SENTINEL)
            } else {
                EpgLoadResult.Failure(source, "download/parse failed")
            }
        } finally {
            candidate.delete()
        }
    }
}
