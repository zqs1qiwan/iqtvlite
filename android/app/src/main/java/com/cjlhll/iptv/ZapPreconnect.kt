package com.cjlhll.iptv

import android.content.Context
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * t218 (arm B): neighbour pre-connect — **opt-in, default OFF**.
 *
 * Warms the TCP path (DNS + connect) for the channels next to the current one so the *next* zap does
 * not pay the connection setup inside its `fmt` window. It deliberately does **not** download media:
 * one socket, connect-only, closed immediately — the smallest possible footprint on a 512 MB box.
 *
 * Guarantees:
 *  - does nothing unless `Prefs.zapPreconnectEnabled` is on;
 *  - one attempt at a time, hard [PreconnectPolicy.TIMEOUT_MS] cap;
 *  - all failures are swallowed (a warm must never affect a real zap);
 *  - [cancel] is called on every zap so a warm can never compete with the actual connection.
 */
object ZapPreconnect {
    private const val TAG = "IQTV-PRECONNECT"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    @Volatile private var lastPassAt = 0L

    /** t218: what actually happened, for the acceptance log (`IQTV-PRECONNECT`). */
    data class Result(val attempted: Int, val connected: Int, val elapsedMs: Long)

    /**
     * Warm the neighbours of [currentIndex] in [urls] (one URL per channel group, in list order).
     * Returns immediately; the work is on IO and bounded by the policy.
     */
    fun warmNeighbours(context: Context, urls: List<String>, currentIndex: Int, drawerOpen: Boolean, idle: Boolean) {
        if (!Prefs.getZapPreconnectEnabled(context)) return
        val now = System.currentTimeMillis()
        if (!PreconnectPolicy.mayWarm(
                enabled = true,
                drawerOpen = drawerOpen,
                idle = idle,
                inFlight = running.get(),
                sinceLastMs = now - lastPassAt,
            )
        ) return
        val targets = PreconnectPolicy.neighbours(urls.size, currentIndex)
            .mapNotNull { urls.getOrNull(it) }
            .filter { PreconnectPolicy.canWarmUrl(it) }
        if (targets.isEmpty()) return
        if (!running.compareAndSet(false, true)) return
        lastPassAt = now
        job = scope.launch {
            val t0 = System.currentTimeMillis()
            var connected = 0
            try {
                withTimeoutOrNull(PreconnectPolicy.TIMEOUT_MS) {
                    targets.forEach { url ->
                        if (connectOnce(url)) connected++
                    }
                }
            } catch (e: Exception) {
                // silent by design
            } finally {
                running.set(false)
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "warm attempted=${targets.size} connected=$connected elapsed=${System.currentTimeMillis() - t0}ms")
                }
            }
        }
    }

    /** One connect-only warm. Never throws, never reads the body. */
    private fun connectOnce(url: String): Boolean = try {
        val uri = URI(url)
        val host = uri.host ?: return false
        val port = if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PreconnectPolicy.TIMEOUT_MS.toInt())
        }
        true
    } catch (e: IOException) {
        false
    } catch (e: Exception) {
        false
    }

    /** Called on every zap: a warm must never compete with the real connection. */
    fun cancel() {
        job?.cancel()
        job = null
        running.set(false)
    }

    /** Test/diagnostic view. */
    fun isRunning(): Boolean = running.get()
}
