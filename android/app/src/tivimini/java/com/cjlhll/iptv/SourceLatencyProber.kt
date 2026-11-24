package com.cjlhll.iptv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 对播放源做连通性/延迟探测。M3U 里的 response-time 大量为占位 120ms，不可信。
 */
object SourceLatencyProber {
    private const val TAG = "SourceLatencyProber"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val PROBE_TIMEOUT_MS = 2_000L

    private data class CacheEntry(
        val latencyMs: Int?,
        val probedAt: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val channels = ConcurrentHashMap<String, Channel>()
    fun updateChannels(values: List<Channel>) {
        val updated = values.associateBy { it.url }
        for ((url, old) in channels) {
            val next = updated[url]
            if (next?.userAgent != old.userAgent || next?.referrer != old.referrer || next?.customHeaders != old.customHeaders) {
                cache.remove(url)
            }
        }
        channels.clear(); channels.putAll(updated)
    }
    private fun request(url: String): Request.Builder {
        val channel = channels[url]
        return Request.Builder().url(url).apply {
            channel?.customHeaders?.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) header(k, v)
            }
            header("User-Agent", channel?.userAgent ?: HttpStreamAddress.DEFAULT_USER_AGENT)
            channel?.referrer?.let { header("Referer", it) }
        }
    }
    private val probeSemaphore = Semaphore(6)

    private val client: OkHttpClient by lazy {
        NetUtil.ipv4OkHttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    fun cachedLatencyMs(url: String): Int? {
        val entry = cache[url] ?: return null
        if (System.currentTimeMillis() - entry.probedAt > CACHE_TTL_MS) return null
        return entry.latencyMs
    }

    fun hasFreshCache(url: String): Boolean {
        val entry = cache[url] ?: return false
        return System.currentTimeMillis() - entry.probedAt <= CACHE_TTL_MS
    }

    suspend fun probe(url: String, force: Boolean = false): Int? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        if (!force) {
            cachedLatencyMs(url)?.let { return@withContext it }
            if (hasFreshCache(url)) return@withContext null
        }

        probeSemaphore.withPermit {
            if (!force) {
                cachedLatencyMs(url)?.let { return@withPermit it }
                if (hasFreshCache(url)) return@withPermit null
            }
            measure(url).also { ms ->
                cache[url] = CacheEntry(ms, System.currentTimeMillis())
                Log.d(TAG, "probe $ms ms line=${url.hashCode()}")
            }
        }
    }

    suspend fun probeAll(urls: List<String>, force: Boolean = false): Map<String, Int?> = coroutineScope {
        urls.distinct().map { url ->
            async { url to probe(url, force) }
        }.awaitAll().toMap()
    }

    private fun measure(url: String): Int? {
        val startNs = System.nanoTime()
        val ok = runCatching { tryHead(url) }.getOrDefault(false) ||
            runCatching { tryGetSample(url) }.getOrDefault(false)
        if (!ok) {
            Log.d(TAG, "probe failed line=${url.hashCode()}")
            return null
        }
        return ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
    }

    private fun tryHead(url: String): Boolean {
        val request = request(url)
            .head()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 405 || response.code == 501) return false
            return response.isSuccessful || response.code in 200..399
        }
    }

    private fun tryGetSample(url: String): Boolean {
        val request = request(url)
            .get()
            .header("Range", "bytes=0-1023")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code !in listOf(206, 200)) return false
            response.body?.byteStream()?.use { stream ->
                val buf = ByteArray(256)
                stream.read(buf)
            }
            return true
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
