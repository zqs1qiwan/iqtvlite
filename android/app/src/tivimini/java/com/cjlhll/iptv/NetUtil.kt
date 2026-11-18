package com.cjlhll.iptv

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress

object NetUtil {
    /** Prefer IPv4 on devices whose IPv6 route cannot reach the public IPTV/EPG hosts. */
    val ipv4FirstDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = InetAddress.getAllByName(hostname).toList()
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            return ipv4.ifEmpty { addresses }
        }
    }

    val ipv4OkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().dns(ipv4FirstDns)
            .addInterceptor { chain ->
                val request = chain.request()
                val withUa = if (request.header("User-Agent") == null) request.newBuilder()
                    .header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").build() else request
                chain.proceed(withUa)
            }.build()
    }
}
