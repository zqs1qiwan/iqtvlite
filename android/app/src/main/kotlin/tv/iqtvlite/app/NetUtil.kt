package tv.iqtvlite.app

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * 网络工具 —— 针对 Android TV 盒子的 IPv6 黑洞问题。
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
            val all = try {
                InetAddress.getAllByName(hostname).toList()
            } catch (e: Exception) {
                throw e
            }
            return all.filterIsInstance<Inet4Address>().ifEmpty { all }
        }
    }
}
