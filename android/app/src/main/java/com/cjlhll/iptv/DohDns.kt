package com.cjlhll.iptv

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class DnsMode(val id: String, val dohUrl: String?) {
    SYSTEM("system", null),
    DOH_ALI("ali", "https://dns.alidns.com/dns-query"),
    DOH_DNSPOD("dnspod", "https://doh.pub/dns-query"),
    DOH_CLOUDFLARE("cloudflare", "https://cloudflare-dns.com/dns-query");

    fun shortLabel(lang: AppLang): String = when (this) {
        SYSTEM -> if (lang == AppLang.EN) "System DNS" else "系统默认"
        DOH_ALI -> if (lang == AppLang.EN) "AliDNS (DoH)" else "阿里 DoH"
        DOH_DNSPOD -> if (lang == AppLang.EN) "DNSPod DoH" else "腾讯 DoH"
        DOH_CLOUDFLARE -> if (lang == AppLang.EN) "Cloudflare DoH" else "Cloudflare DoH"
    }

    fun description(lang: AppLang): String = when (this) {
        SYSTEM -> if (lang == AppLang.EN) "Use system network DNS (lowest latency on LAN)" else "使用当前网络系统 DNS，适合纯内网与家庭宽带默认解析"
        DOH_ALI -> if (lang == AppLang.EN) "Encrypted DNS-over-HTTPS via 223.5.5.5 with automatic system fallback" else "通过阿里云 DoH (223.5.5.5, RFC 8484) 加密解析公网域名，失败自动回退系统 DNS"
        DOH_DNSPOD -> if (lang == AppLang.EN) "Encrypted DNS-over-HTTPS via Tencent DNSPod (doh.pub) with automatic system fallback" else "通过腾讯 DNSPod DoH (doh.pub, RFC 8484) 加密解析公网域名，失败自动回退系统 DNS"
        DOH_CLOUDFLARE -> if (lang == AppLang.EN) "Encrypted DNS-over-HTTPS via 1.1.1.1 with automatic system fallback" else "通过 Cloudflare DoH (1.1.1.1, RFC 8484) 加密解析公网域名，失败自动回退系统 DNS"
    }

    companion object {
        fun parse(raw: String?): DnsMode =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: SYSTEM
    }
}

class DnsAnswerRecord(
    val addressBytes: ByteArray,
    val ttlSec: Int,
)

/**
 * Pure Kotlin RFC 1035 / RFC 8484 wire-format codec (0 external dependencies).
 */
object DohWireCodec {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    fun encodeQuery(hostname: String, qtype: Int, txId: Int = 0): ByteArray {
        val clean = hostname.trim().trimEnd('.')
        require(clean.isNotEmpty() && clean.length <= 253) { "Invalid hostname length" }
        val out = ByteArrayOutputStream(64)
        // Header (12 bytes): ID, flags=0x0100 (RD=1), QDCOUNT=1, AN/NS/AR=0
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)

        for (label in clean.split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.isNotEmpty() && bytes.size <= 63) { "Invalid DNS label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        // QTYPE & QCLASS=IN (1)
        out.write((qtype ushr 8) and 0xFF)
        out.write(qtype and 0xFF)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    fun decodeResponse(packet: ByteArray, expectedType: Int): List<DnsAnswerRecord> {
        if (packet.size < 12) return emptyList()
        if ((packet[2].toInt() and 0x80) == 0) return emptyList()
        val rcode = packet[3].toInt() and 0x0F
        if (rcode != 0) return emptyList()

        val qdCount = u16(packet, 4)
        val anCount = u16(packet, 6)
        if (anCount <= 0) return emptyList()

        var pos = 12
        repeat(qdCount) {
            pos = skipName(packet, pos)
            if (pos < 0 || pos + 4 > packet.size) return emptyList()
            pos += 4
        }

        val expectedLen = when (expectedType) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> return emptyList()
        }

        val results = ArrayList<DnsAnswerRecord>(anCount)
        repeat(anCount) {
            pos = skipName(packet, pos)
            if (pos < 0 || pos + 10 > packet.size) return results
            val type = u16(packet, pos)
            val clazz = u16(packet, pos + 2)
            val ttl = u32(packet, pos + 4)
            val rdLength = u16(packet, pos + 8)
            pos += 10
            if (pos + rdLength > packet.size) return results
            if (clazz == 1 && type == expectedType && rdLength == expectedLen) {
                results.add(DnsAnswerRecord(packet.copyOfRange(pos, pos + rdLength), ttl))
            }
            pos += rdLength
        }
        return results
    }

    private fun skipName(buf: ByteArray, start: Int): Int {
        var pos = start
        var steps = 0
        while (pos < buf.size && steps < 128) {
            val len = buf[pos].toInt() and 0xFF
            if (len == 0) return pos + 1
            if ((len and 0xC0) == 0xC0) {
                return if (pos + 2 <= buf.size) pos + 2 else -1
            }
            pos += 1 + len
            steps++
        }
        return -1
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
}

/**
 * Lightweight RFC 8484 DoH resolver with 256-entry TTL cache, LAN/IP bypass,
 * and automatic fallback to system DNS. Uses [okhttp3.Dns.SYSTEM] internally so bootstrapping never loops.
 */
object DohResolver {
    private const val MAX_CACHE_ENTRIES = 256
    private const val MIN_TTL_MS = 30_000L
    private const val MAX_TTL_MS = 300_000L
    private val DNS_MEDIA_TYPE = "application/dns-message".toMediaType()

    private data class CacheEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long,
    )

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    private val hitCounter = AtomicLong(0L)
    private val fallbackCounter = AtomicLong(0L)

    val dohHits: Long get() = hitCounter.get()
    val dohFallbacks: Long get() = fallbackCounter.get()

    private val dohHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .dns(okhttp3.Dns.SYSTEM)
            .build()
    }

    fun isBypassHost(hostname: String): Boolean {
        val h = hostname.trim().lowercase()
        if (h.isEmpty() || h == "localhost" || !h.contains('.')) return true
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") ||
            h.endsWith(".internal") || h.endsWith(".localhost")
        ) return true
        if (h.contains(':')) return true
        if (h.all { it.isDigit() || it == '.' }) return true
        return false
    }

    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    fun resolve(
        hostname: String,
        mode: DnsMode,
        pref: IpStackPreference,
        nowMs: Long = System.currentTimeMillis(),
        systemLookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): List<InetAddress> {
        val endpoint = mode.dohUrl
        if (endpoint == null || isBypassHost(hostname)) {
            return NetUtil.orderAddresses(systemLookup(hostname), pref)
        }

        val cacheKey = "${mode.id}|${hostname.lowercase()}"
        synchronized(cacheLock) {
            val cached = cache[cacheKey]
            if (cached != null && nowMs < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
                hitCounter.incrementAndGet()
                return NetUtil.orderAddresses(cached.addresses, pref)
            }
        }

        val resolved = runCatching {
            val types = if (pref == IpStackPreference.IPV6_FIRST) {
                intArrayOf(DohWireCodec.TYPE_AAAA, DohWireCodec.TYPE_A)
            } else {
                intArrayOf(DohWireCodec.TYPE_A, DohWireCodec.TYPE_AAAA)
            }
            val addrs = ArrayList<InetAddress>(4)
            var minTtlSec = 120
            for (qtype in types) {
                val recs = queryDoh(endpoint, hostname, qtype)
                for (r in recs) {
                    addrs.add(InetAddress.getByAddress(hostname, r.addressBytes))
                    if (r.ttlSec > 0) minTtlSec = minOf(minTtlSec, r.ttlSec)
                }
            }
            if (addrs.isNotEmpty()) {
                val ttlMs = (minTtlSec.toLong() * 1000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
                synchronized(cacheLock) {
                    cache[cacheKey] = CacheEntry(addrs.toList(), nowMs + ttlMs)
                }
                hitCounter.incrementAndGet()
            }
            addrs
        }.getOrNull().orEmpty()

        if (resolved.isNotEmpty()) {
            return NetUtil.orderAddresses(resolved, pref)
        }

        fallbackCounter.incrementAndGet()
        return NetUtil.orderAddresses(systemLookup(hostname), pref)
    }

    private fun queryDoh(endpoint: String, hostname: String, qtype: Int): List<DnsAnswerRecord> {
        val wire = DohWireCodec.encodeQuery(hostname, qtype)
        val request = Request.Builder()
            .url(endpoint)
            .post(wire.toRequestBody(DNS_MEDIA_TYPE))
            .header("Accept", "application/dns-message")
            .build()
        dohHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.bytes() ?: return emptyList()
            return DohWireCodec.decodeResponse(body, qtype)
        }
    }
}
