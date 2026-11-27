package com.cjlhll.iptv

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

enum class IpStackPreference(
    val id: String,
    val labelZh: String,
    val labelEn: String,
) {
    IPV6_FIRST("ipv6_first", "IPv6 优先（自动回退 IPv4）", "IPv6 Preferred (Fallback IPv4)"),
    IPV4_FIRST("ipv4_first", "IPv4 优先（自动回退 IPv6）", "IPv4 Preferred (Fallback IPv6)");

    fun localizedLabel(lang: AppLang): String = if (lang == AppLang.EN) labelEn else labelZh

    fun shortLabel(lang: AppLang): String = when (this) {
        IPV6_FIRST -> if (lang == AppLang.EN) "IPv6 First" else "IPv6 优先"
        IPV4_FIRST -> if (lang == AppLang.EN) "IPv4 First" else "IPv4 优先"
    }

    companion object {
        fun parse(raw: String?): IpStackPreference = when (raw?.trim()?.lowercase()) {
            "ipv4_first", "ipv4", "4" -> IPV4_FIRST
            else -> IPV6_FIRST
        }
    }
}

object NetUtil {
    @Volatile
    private var stackPreference: IpStackPreference = IpStackPreference.IPV6_FIRST

    @Volatile
    private var dnsMode: DnsMode = DnsMode.SYSTEM

    fun currentIpStackPreference(): IpStackPreference = stackPreference

    fun setIpStackPreference(preference: IpStackPreference) {
        stackPreference = preference
    }

    fun currentDnsMode(): DnsMode = dnsMode

    fun setDnsMode(mode: DnsMode) {
        dnsMode = mode
    }

    /**
     * Reorder dual-stack DNS results according to [preference] while always preserving
     * addresses from the secondary family so connect/handshake failures fall back seamlessly.
     */
    internal fun orderAddresses(
        addresses: List<InetAddress>,
        preference: IpStackPreference = stackPreference,
    ): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        val v6 = ArrayList<InetAddress>(addresses.size)
        val v4 = ArrayList<InetAddress>(addresses.size)
        val other = ArrayList<InetAddress>()
        for (addr in addresses) {
            when (addr) {
                is Inet6Address -> v6.add(addr)
                is Inet4Address -> v4.add(addr)
                else -> other.add(addr)
            }
        }
        val ordered = ArrayList<InetAddress>(addresses.size)
        when (preference) {
            IpStackPreference.IPV6_FIRST -> {
                ordered.addAll(v6)
                ordered.addAll(v4)
            }
            IpStackPreference.IPV4_FIRST -> {
                ordered.addAll(v4)
                ordered.addAll(v6)
            }
        }
        ordered.addAll(other)
        return ordered
    }

    /** Dual-stack DNS respecting DoH mode (with system fallback) and IPv6-first / IPv4-first ordering. */
    val dualStackDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return DohResolver.resolve(
                hostname = hostname,
                mode = dnsMode,
                pref = stackPreference,
            )
        }
    }

    /** Legacy helper preserved for tests/callers: prefers IPv4 while keeping IPv6 fallback. */
    val ipv4FirstDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return orderAddresses(InetAddress.getAllByName(hostname).toList(), IpStackPreference.IPV4_FIRST)
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().dns(dualStackDns)
            .addInterceptor { chain ->
                val request = chain.request()
                val withUa = if (request.header("User-Agent") == null) request.newBuilder()
                    .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build() else request
                chain.proceed(withUa)
            }.build()
    }

    /** Alias kept for existing callers across the codebase; routes through [dualStackDns]. */
    val ipv4OkHttpClient: OkHttpClient
        get() = okHttpClient
}
