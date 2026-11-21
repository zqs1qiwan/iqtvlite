package com.cjlhll.iptv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

object EpgRepository {
    private const val TAG = "EPG"

    private val sourceCache = EpgSourceCache()

    suspend fun load(
        context: Context,
        epgSourceUrl: String,
        forceRefresh: Boolean = false,
        allowNetwork: Boolean = true,
    ): EpgData? {
        val result = loadStructured(context, epgSourceUrl, forceRefresh, allowNetwork)
        if (result.isNetworkSuccess) AppSettings.setEpgLastSuccess(context, System.currentTimeMillis())
        return result.dataOrNull
    }

    /**
     * 结构化加载（R04）：返回网络成功 / 缓存回退 / 失败，并携带源 ID。
     * 只有真实网络解析成功可更新“最近成功时间”，缓存回退不得记为成功。
     * [fetcher] 可注入用于测试断网/两套源数据。
     */
    suspend fun loadStructured(
        context: Context,
        epgSourceUrl: String,
        forceRefresh: Boolean = false,
        allowNetwork: Boolean = true,
        fetcher: (suspend (String) -> ByteArray?)? = null,
    ): EpgLoadResult {
        if (epgSourceUrl.isBlank()) return EpgLoadResult.Failure(epgSourceUrl, "empty url")
        val sourceId = epgSourceUrl
        val cacheFileName = EpgCache.fileNameForSource(epgSourceUrl)
        val parsedCacheFileName = "$cacheFileName.ser"

        // S05：缓存读取（含反序列化）放到 IO；forceRefresh 只表示必须尝试网络，仍保留同源旧缓存作为失败候选
        return withContext(Dispatchers.IO) {
            var cached: EpgData? = sourceCache.get(sourceId)
            if (cached == null) {
                cached = loadParsedCache(context, parsedCacheFileName)
                    ?: EpgCache.getFile(context, cacheFileName)?.takeIf { it.exists() && it.length() > 0 }?.let { parseFile(it) }
                if (cached != null) sourceCache.put(sourceId, cached)
            }

            if (!allowNetwork) {
                return@withContext if (cached != null) EpgLoadResult.CacheFallback(sourceId, cached)
                else EpgLoadResult.Failure(sourceId, "manual mode: no cache")
            }

            // S05：注入 fetcher 时返回 null 就是网络失败，不得回退真实网络
            val fetch: suspend (String) -> ByteArray? = if (fetcher != null) fetcher else { u -> downloadBytes(u) }
            val result = loadStructuredCore(sourceId, cached, fetch)
            if (result is EpgLoadResult.Success) {
                sourceCache.put(sourceId, result.data)
                saveParsedCache(context, parsedCacheFileName, result.data)
            }
            result
        }
    }

    /**
     * 真实网络/解析/分类核心（S05），不含磁盘缓存副作用，可注入 fetcher 做生产入口级测试。
     * fetcher 返回 null 即网络失败：用同源 cached 回退（CacheFallback），无 cached 则 Failure。
     */
    suspend fun loadStructuredCore(
        sourceId: String,
        cached: EpgData?,
        fetcher: suspend (String) -> ByteArray?,
        parser: ((ByteArray) -> EpgData?)? = null,
    ): EpgLoadResult = withContext(Dispatchers.IO) {
        val bytes = try {
            fetcher(sourceId)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: ${e.message}")
            null
        }
        if (bytes == null) {
            return@withContext EpgOutcome.classify(null, cached, sourceId, "network failed")
        }
        val parsed = if (parser != null) {
            runCatching { parser(bytes) }.getOrNull()
        } else {
            runCatching {
                java.io.ByteArrayInputStream(bytes).use { stream -> XmlTvParser.parse(stream) }
            }.getOrNull()
        }
        if (parsed != null) EpgOutcome.classify(parsed, null, sourceId)
        else EpgOutcome.classify(null, cached, sourceId, "parse failed")
    }

    private fun downloadBytes(url: String): ByteArray? {
        val request = Request.Builder().url(url).build()
        return try {
            NetUtil.ipv4OkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.bytes() ?: return null
                if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                    java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(raw)).use { it.readBytes() }
                } else raw
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadBytes failed: ${e.message}")
            null
        }
    }



    private fun loadParsedCache(context: Context, fileName: String): EpgData? {
        return try {
            val file = java.io.File(context.filesDir, fileName)
            if (!file.exists()) return null
            java.io.ObjectInputStream(java.io.FileInputStream(file)).use {
                it.readObject() as? EpgData
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load parsed cache: ${e.message}")
            try { java.io.File(context.filesDir, fileName).delete() } catch (_: Exception) {}
            null
        }
    }

    private fun saveParsedCache(context: Context, fileName: String, data: EpgData) {
        try {
            val file = java.io.File(context.filesDir, fileName)
            java.io.ObjectOutputStream(java.io.FileOutputStream(file)).use {
                it.writeObject(data)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save parsed cache: ${e.message}")
        }
    }

    private fun parseFile(file: java.io.File): EpgData? {
        return try {
            val inputStream = java.io.FileInputStream(file)
            val stream = if (looksLikeGzip(file)) java.util.zip.GZIPInputStream(inputStream) else inputStream
            stream.use { XmlTvParser.parse(it) }
        } catch (e: Exception) {
            Log.w(TAG, "parse xmltv failed: ${e.message}")
            null
        }
    }

    private fun looksLikeGzip(file: java.io.File): Boolean {
        if (file.length() < 2) return false
        try {
            java.io.FileInputStream(file).use { fis ->
                val bytes = ByteArray(2)
                if (fis.read(bytes) != 2) return false
                return bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
            }
        } catch (_: Exception) {
            return false
        }
    }
}

