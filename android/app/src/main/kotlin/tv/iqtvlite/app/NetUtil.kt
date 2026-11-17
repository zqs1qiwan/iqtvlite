package tv.iqtvlite.app

import okhttp3.ConnectionPool
import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 网络工具 —— 针对 Android TV 盒子的 IPv6 黑洞问题 + 连接复用优化。
 *
 * 实测（TR100-G9 / Android 9）：
 *   curl -4 hlsztemgsplive.miguvideo.com  → HTTP 200 (106 ms)
 *   curl -6 hlsztemgsplive.miguvideo.com  → HTTP 000 (10 s 超时)
 * 系统 DNS 同时返回 AAAA 和 A 记录，但 IPv6 出口不通。OkHttp 优先 IPv6 → 连接挂起。
 * 统一用 IPv4-only DNS，彻底绕过。
 */
object NetUtil {
    /** 只返回 IPv4 地址的 DNS；若某主机只有 IPv6 则回退到全部地址。 */
    @JvmField
    val ipv4OnlyDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = InetAddress.getAllByName(hostname).toList()
            return all.filterIsInstance<Inet4Address>().ifEmpty { all }
        }
    }

    /**
     * 全局共享连接池：主播放 / PiP 预览 / 多画面 / 播放列表下载 共用。
     * 效果：PiP 浏览时预热到各流媒体的 TCP 连接，换台 setMediaItem 时可直接复用，
     * 省去 DNS + TCP + TLS 握手（冷换台的主要开销之一）。
     * 8 条空闲连接 / 90s 保活，足以覆盖常见流媒体主机。
     */
    @JvmField
    val sharedConnectionPool = ConnectionPool(8, 90, TimeUnit.SECONDS)
}
