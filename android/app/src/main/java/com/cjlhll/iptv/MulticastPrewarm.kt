package com.cjlhll.iptv

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * t220: multicast pre-warm — **opt-in, default OFF** (see [MulticastPrewarmPolicy]).
 *
 * Joins the neighbour channel's multicast group with its own socket and reads a short, bounded slice
 * of the TS stream (up to [MulticastPrewarmPolicy.MAX_BYTES], [MulticastPrewarmPolicy.TIMEOUT_MS]),
 * then leaves the group again. The goal is to have the IGMP membership / route / first packets warm
 * when the user actually zaps there.
 *
 * This build **does not inject** the buffered packets into ExoPlayer (the player still opens its own
 * socket), so the arm is a "join + first-packet probe". That limit is deliberate: injecting packets
 * into a live TS pipeline would need a custom DataSource and risks A/V desync on a 512 MB box.
 *
 * Everything is silent: a failed warm must never affect real playback.
 */
object MulticastPrewarm {
    private const val TAG = "IQTV-PREWARM"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile private var activeSocket: MulticastSocket? = null

    @Volatile private var lastPassAt = 0L

    /** Warm the neighbour of [currentIndex] if it is a multicast URL. Never throws, never blocks. */
    fun warmNeighbour(context: Context, urls: List<String>, currentIndex: Int, drawerOpen: Boolean, idle: Boolean) {
        if (!Prefs.getMulticastPrewarmEnabled(context)) return
        val now = System.currentTimeMillis()
        if (!MulticastPrewarmPolicy.mayWarm(
                enabled = true,
                drawerOpen = drawerOpen,
                idle = idle,
                inFlight = running.get(),
                sinceLastMs = now - lastPassAt,
            )
        ) return
        val neighbour = MulticastPrewarmPolicy.neighbour(urls.size, currentIndex) ?: return
        val endpoint = MulticastPrewarmPolicy.parse(urls.getOrNull(neighbour) ?: return) ?: return
        if (!running.compareAndSet(false, true)) return
        lastPassAt = now
        val appContext = context.applicationContext
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            var bytes = 0L
            var idrSeen = false
            var socket: MulticastSocket? = null
            var joinedIfaces: List<java.net.NetworkInterface> = emptyList()
            val groupAddr = runCatching { InetAddress.getByName(endpoint.host) }.getOrNull()
            val wifiLock = runCatching {
                (appContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                    ?.createMulticastLock("iqtvlite-prewarm")
                    ?.apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            try {
                if (groupAddr == null) return@launch
                withTimeoutOrNull(MulticastPrewarmPolicy.TIMEOUT_MS) {
                    socket = MulticastSocket(null).apply {
                        reuseAddress = true
                        runCatching { receiveBufferSize = 512 * 1024 }
                        soTimeout = 600
                        MulticastNetworkSupport.bindMulticastSocket(this, groupAddr, endpoint.port)
                    }
                    activeSocket = socket
                    joinedIfaces = socket?.let { ms ->
                        MulticastNetworkSupport.joinMulticastOnActiveInterfaces(ms, groupAddr, endpoint.port)
                    }.orEmpty()
                    val buffer = ByteArray(48 * 1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    while (!MulticastPrewarmPolicy.reachedBudget(bytes)) {
                        try {
                            socket?.receive(packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                        val len = packet.length
                        if (len <= 0) continue
                        val packed = RtpPacketParser.extractPayloadPacked(buffer, len)
                        val payloadLen = packed.toInt()
                        val payloadOff = (packed ushr 32).toInt()
                        if (payloadLen > 0) {
                            bytes += payloadLen
                            // Scan stripped MPEG-TS payload (works for both rtp:// and raw udp://).
                            if (!idrSeen && payloadLen > 5) idrSeen = looksLikeIdr(buffer, payloadOff, payloadLen)
                        } else {
                            bytes += len
                        }
                    }
                }
            } catch (e: Exception) {
                // silent by design
            } finally {
                MulticastNetworkSupport.leaveMulticastInterfaces(socket, groupAddr, endpoint.port, joinedIfaces)
                runCatching { socket?.close() }
                if (activeSocket === socket) activeSocket = null
                runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
                running.set(false)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(
                        TAG,
                        "warm host=${endpoint.host} port=${endpoint.port} " +
                            MulticastPrewarmPolicy.summaryLine(bytes, System.currentTimeMillis() - t0, idrSeen)
                    )
                }
            }
        }
    }

    /** Cheap IDR sniff: an H.264 NAL of type 5 (or HEVC IDR NAL 19/20) within a TS payload. */
    private fun looksLikeIdr(buf: ByteArray, offset: Int, len: Int): Boolean {
        var i = offset
        val limit = offset + len
        while (i + 4 < limit) {
            if (buf[i] == 0x00.toByte() && buf[i + 1] == 0x00.toByte() && buf[i + 2] == 0x01.toByte()) {
                val b3 = buf[i + 3].toInt() and 0xFF
                val h264Nal = b3 and 0x1F
                val hevcNal = (b3 ushr 1) and 0x3F
                if (h264Nal == 5 || hevcNal == 19 || hevcNal == 20) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** Called on every real zap and when the experiment is disabled; closes socket to unblock receive(). */
    fun cancel() {
        runCatching { activeSocket?.close() }
        activeSocket = null
        job?.cancel()
        job = null
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()
}
