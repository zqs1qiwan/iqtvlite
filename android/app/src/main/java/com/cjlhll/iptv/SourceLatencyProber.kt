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
    @Volatile private var defaultGlobalUa: String? = null
    @Volatile private var defaultGlobalHeaders: Map<String, String> = emptyMap()

    fun updateRequestDefaults(
        globalUserAgent: String?,
        globalCustomHeaders: Map<String, String>,
    ) {
        val nextUa = globalUserAgent?.trim()?.takeIf { it.isNotEmpty() }
        if (nextUa != defaultGlobalUa || globalCustomHeaders != defaultGlobalHeaders) {
            cache.clear()
        }
        defaultGlobalUa = nextUa
        defaultGlobalHeaders = HashMap(globalCustomHeaders)
    }

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
        val channel = channels[url] ?: Channel(title = "", url = url)
        val merged = MediaPlaybackRequest(channel).httpHeaders(
            globalUserAgent = defaultGlobalUa,
            globalCustomHeaders = defaultGlobalHeaders,
        )
        return Request.Builder().url(url).apply {
            merged.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) header(k, v)
            }
        }
    }
    private val probeSemaphore = Semaphore(6)

    private val client: OkHttpClient by lazy {
        NetUtil.okHttpClient.newBuilder()
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
        val lower = url.trim().lowercase()
        val ok = when {
            lower.startsWith("rtp://") || lower.startsWith("udp://") ->
                runCatching { probeMulticastUdp(url) }.getOrDefault(false)
            lower.startsWith("rtsp://") -> runCatching { probeTcpConnect(url, 554) }.getOrDefault(false)
            else -> runCatching { tryHead(url) }.getOrDefault(false) ||
                runCatching { tryGetSample(url) }.getOrDefault(false)
        }
        if (!ok) {
            Log.d(TAG, "probe failed line=${url.hashCode()}")
            return null
        }
        return ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
    }

    private fun probeTcpConnect(url: String, defaultPort: Int): Boolean {
        val uri = java.net.URI(url)
        val host = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else defaultPort
        val addrs = NetUtil.orderAddresses(java.net.InetAddress.getAllByName(host).toList())
        for (addr in addrs) {
            val connected = runCatching {
                java.net.Socket().use { sock ->
                    sock.connect(java.net.InetSocketAddress(addr, port), 1_500)
                }
                true
            }.getOrDefault(false)
            if (connected) return true
        }
        return false
    }

    private fun probeMulticastUdp(url: String): Boolean {
        val ep = RtpPacketParser.parseEndpoint(url) ?: return false
        val groupAddr = NetUtil.orderAddresses(java.net.InetAddress.getAllByName(ep.host).toList()).firstOrNull()
            ?: return false
        val buf = ByteArray(2048)
        val pkt = java.net.DatagramPacket(buf, buf.size)
        return if (ep.isMulticast || groupAddr.isMulticastAddress) {
            val ms = java.net.MulticastSocket(null)
            var joined = emptyList<java.net.NetworkInterface>()
            try {
                ms.reuseAddress = true
                ms.soTimeout = 1_500
                MulticastNetworkSupport.bindMulticastSocket(ms, groupAddr, ep.port)
                joined = MulticastNetworkSupport.joinMulticastOnActiveInterfaces(ms, groupAddr, ep.port)
                ms.receive(pkt)
                pkt.length > 0
            } finally {
                MulticastNetworkSupport.leaveMulticastInterfaces(ms, groupAddr, ep.port, joined)
                runCatching { ms.close() }
            }
        } else {
            val ds = java.net.DatagramSocket(null)
            try {
                ds.reuseAddress = true
                ds.soTimeout = 1_500
                ds.bind(java.net.InetSocketAddress(ep.port))
                ds.receive(pkt)
                pkt.length > 0
            } finally {
                runCatching { ds.close() }
            }
        }
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
