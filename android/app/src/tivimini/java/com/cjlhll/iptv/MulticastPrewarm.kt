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
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            var bytes = 0L
            var idrSeen = false
            var socket: MulticastSocket? = null
            try {
                withTimeoutOrNull(MulticastPrewarmPolicy.TIMEOUT_MS) {
                    socket = MulticastSocket(endpoint.port)
                    socket?.joinGroup(InetAddress.getByName(endpoint.host))
                    val buffer = ByteArray(48 * 1024)
                    while (!MulticastPrewarmPolicy.reachedBudget(bytes)) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)
                        val len = packet.length
                        bytes += len
                        // A TS packet carrying an H.264 IDR has NAL type 5 not far after the 0x47 sync
                        // byte; a crate-level scan is enough to know "the group is live and has video".
                        if (!idrSeen && len > 5) idrSeen = looksLikeIdr(buffer, len)
                    }
                }
            } catch (e: Exception) {
                // silent by design
            } finally {
                try {
                    socket?.leaveGroup(InetAddress.getByName(endpoint.host))
                } catch (e: Exception) {
                    // ignore
                }
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // ignore
                }
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

    /** Cheap IDR sniff: an H.264 NAL of type 5 within the first bytes of a TS payload. */
    private fun looksLikeIdr(buf: ByteArray, len: Int): Boolean {
        var i = 0
        while (i + 4 < len) {
            if (buf[i] == 0x00.toByte() && buf[i + 1] == 0x00.toByte() && buf[i + 2] == 0x01.toByte()) {
                val nal = buf[i + 3].toInt() and 0x1F
                if (nal == 5) return true
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** Called on every real zap and when the experiment is disabled. */
    fun cancel() {
        job?.cancel()
        job = null
        running.set(false)
    }

    fun isRunning(): Boolean = running.get()
}
