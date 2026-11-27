package com.cjlhll.iptv

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Pure Kotlin endpoint + RFC 2250 / RFC 3550 RTP-over-UDP MPEG-TS payload parser (Android-free,
 * directly covered by JVM unit tests).
 */
object RtpPacketParser {
    private const val TS_SYNC = 0x47
    private const val TS_PACKET_SIZE = 188
    private const val RTP_HEADER_BYTES = 12

    data class Endpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val isMulticast: Boolean,
    )

    data class PayloadSlice(
        val offset: Int,
        val length: Int,
    )

    /** Mutable reusable holder filled by [inspectPacket] to avoid per-packet heap allocations. */
    class ParsedPacket {
        var isRtp: Boolean = false
        var sequenceNumber: Int = 0
        var ssrc: Int = 0
        var offset: Int = 0
        var length: Int = 0
    }

    fun parseEndpoint(rawUri: String): Endpoint? {
        val withoutPipe = rawUri.substringBefore('|').trim()
        val schemeIdx = withoutPipe.indexOf("://")
        if (schemeIdx <= 0) return null
        val scheme = withoutPipe.substring(0, schemeIdx).lowercase()
        if (scheme != "rtp" && scheme != "udp") return null

        val normalized = run {
            val rest = withoutPipe.substring(schemeIdx + 3)
            val cleaned = if (rest.startsWith("@")) rest.substring(1) else rest
            "$scheme://$cleaned"
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val rawHost = uri.host?.trim()?.trimStart('[')?.trimEnd(']')?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port
        if (port !in 1..65535) return null
        val isMulticast = isMulticastHost(rawHost)
        return Endpoint(scheme = scheme, host = rawHost, port = port, isMulticast = isMulticast)
    }

    internal fun isMulticastHost(host: String): Boolean {
        val ipv4Octets = host.split('.')
        if (ipv4Octets.size == 4) {
            val first = ipv4Octets[0].toIntOrNull()
            if (first != null && ipv4Octets.all { it.toIntOrNull() in 0..255 }) {
                return first in 224..239
            }
        }
        // IPv6 multicast addresses start with ff00::/8
        val lower = host.lowercase()
        return lower.startsWith("ff")
    }

    /**
     * Zero-allocation inspection of one UDP datagram: supports both raw MPEG-TS over UDP
     * and RFC 2250 / RFC 3550 RTP v2 packets (extracting 16-bit sequence number & 32-bit SSRC).
     * Returns `false` if the datagram is RTCP or malformed and should be skipped.
     */
    fun inspectPacket(packet: ByteArray, length: Int, out: ParsedPacket): Boolean {
        if (length <= 0 || length > packet.size) return false

        // Raw MPEG-TS carried directly over UDP (even when mislabeled rtp://)
        if ((packet[0].toInt() and 0xFF) == TS_SYNC &&
            (length % TS_PACKET_SIZE == 0 || length < TS_PACKET_SIZE || (length > TS_PACKET_SIZE && (packet[TS_PACKET_SIZE].toInt() and 0xFF) == TS_SYNC))
        ) {
            out.isRtp = false
            out.sequenceNumber = 0
            out.ssrc = 0
            out.offset = 0
            out.length = length
            return true
        }

        if (length < RTP_HEADER_BYTES) return false
        val b0 = packet[0].toInt() and 0xFF
        val version = b0 ushr 6
        if (version != 2) return false

        // Ignore RTCP control packets (PT 200..204) if received on the RTP port.
        val b1 = packet[1].toInt() and 0xFF
        if (b1 in 200..204) return false

        val hasPadding = (b0 and 0x20) != 0
        val hasExtension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        var headerEnd = RTP_HEADER_BYTES + csrcCount * 4
        if (headerEnd > length) return false

        if (hasExtension) {
            if (headerEnd + 4 > length) return false
            val extWords = ((packet[headerEnd + 2].toInt() and 0xFF) shl 8) or (packet[headerEnd + 3].toInt() and 0xFF)
            headerEnd += 4 + extWords * 4
            if (headerEnd > length) return false
        }

        var dataEnd = length
        if (hasPadding) {
            if (dataEnd <= headerEnd) return false
            val padLen = packet[dataEnd - 1].toInt() and 0xFF
            if (padLen <= 0 || padLen > dataEnd - headerEnd) return false
            dataEnd -= padLen
        }

        val payloadLen = dataEnd - headerEnd
        if (payloadLen <= 0) return false

        out.isRtp = true
        out.sequenceNumber = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        out.ssrc =
            ((packet[8].toInt() and 0xFF) shl 24) or
            ((packet[9].toInt() and 0xFF) shl 16) or
            ((packet[10].toInt() and 0xFF) shl 8) or
            (packet[11].toInt() and 0xFF)
        out.offset = headerEnd
        out.length = payloadLen
        return true
    }

    /**
     * Zero-allocation hot-path helper returning packed `(offset shl 32) or length`.
     * Returns `0L` if the datagram has no valid MPEG-TS / RTP payload and should be skipped.
     */
    fun extractPayloadPacked(packet: ByteArray, length: Int): Long {
        if (length <= 0 || length > packet.size) return 0L

        if ((packet[0].toInt() and 0xFF) == TS_SYNC &&
            (length % TS_PACKET_SIZE == 0 || length < TS_PACKET_SIZE || (length > TS_PACKET_SIZE && (packet[TS_PACKET_SIZE].toInt() and 0xFF) == TS_SYNC))
        ) {
            return length.toLong()
        }

        if (length < RTP_HEADER_BYTES) return 0L
        val b0 = packet[0].toInt() and 0xFF
        val version = b0 ushr 6
        if (version != 2) return 0L

        val b1 = packet[1].toInt() and 0xFF
        if (b1 in 200..204) return 0L

        val hasPadding = (b0 and 0x20) != 0
        val hasExtension = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        var headerEnd = RTP_HEADER_BYTES + csrcCount * 4
        if (headerEnd > length) return 0L

        if (hasExtension) {
            if (headerEnd + 4 > length) return 0L
            val extWords = ((packet[headerEnd + 2].toInt() and 0xFF) shl 8) or (packet[headerEnd + 3].toInt() and 0xFF)
            headerEnd += 4 + extWords * 4
            if (headerEnd > length) return 0L
        }

        var dataEnd = length
        if (hasPadding) {
            if (dataEnd <= headerEnd) return 0L
            val padLen = packet[dataEnd - 1].toInt() and 0xFF
            if (padLen <= 0 || padLen > dataEnd - headerEnd) return 0L
            dataEnd -= padLen
        }

        val payloadLen = dataEnd - headerEnd
        if (payloadLen <= 0) return 0L
        return (headerEnd.toLong() shl 32) or (payloadLen.toLong() and 0xFFFF_FFFFL)
    }

    fun extractPayload(packet: ByteArray, length: Int): PayloadSlice? {
        val packed = extractPayloadPacked(packet, length)
        val len = packed.toInt()
        if (len <= 0) return null
        val off = (packed ushr 32).toInt()
        return PayloadSlice(offset = off, length = len)
    }
}

/**
 * Pure Kotlin zero-allocation-after-init RTP sequence deduplicator, bounded micro-reorder buffer,
 * and RFC 3550 packet-loss estimator.
 */
class RtpStreamAssembler(
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    private val maxWaitPackets: Int = DEFAULT_MAX_WAIT_PACKETS,
    private val slotCapacity: Int = DEFAULT_SLOT_CAPACITY,
) {
    enum class OfferResult {
        DELIVER_CURRENT,
        DROP,
        BUFFERED,
        FLUSH_THEN_CURRENT,
        DRAIN_READY,
    }

    private val slotData = Array(windowSize) { ByteArray(slotCapacity) }
    private val slotUsed = BooleanArray(windowSize)
    private val slotSeq = IntArray(windowSize)
    private val slotLen = IntArray(windowSize)

    private val readyQueue = IntArray(windowSize)
    private var readyHead = 0
    private var readyTail = 0
    private var readyReadOffset = 0

    private var hasStream = false
    private var activeSsrc = 0
    private var expectedSeq = 0
    private var waitPackets = 0

    // RFC 3550 unique-vs-expected packet counters
    private var uniqueRtpPackets = 0L
    private var expectedRtpSpan = 0L
    private var highestSeq16 = 0

    fun reset() {
        slotUsed.fill(false)
        readyHead = 0
        readyTail = 0
        readyReadOffset = 0
        hasStream = false
        activeSsrc = 0
        expectedSeq = 0
        waitPackets = 0
        uniqueRtpPackets = 0L
        expectedRtpSpan = 0L
        highestSeq16 = 0
    }

    fun receivedPackets(): Long = uniqueRtpPackets

    fun expectedPackets(): Long = if (uniqueRtpPackets == 0L) 0L else expectedRtpSpan + 1L

    fun lostPackets(): Long = (expectedPackets() - uniqueRtpPackets).coerceAtLeast(0L)

    fun lossPercent(): Int {
        val exp = expectedPackets()
        if (exp < MIN_LOSS_SAMPLE_PACKETS) return 0
        val lost = lostPackets()
        return ((lost * 100L) / exp).toInt().coerceIn(0, 100)
    }

    fun hasReady(): Boolean = readyHead < readyTail

    /** Copies up to [maxLen] bytes from the current ready buffered packet without touching caller state. */
    fun readReadyInto(dest: ByteArray, destOffset: Int, maxLen: Int): Int {
        val slot = readyQueue[readyHead]
        val available = slotLen[slot] - readyReadOffset
        val toCopy = minOf(available, maxLen)
        System.arraycopy(slotData[slot], readyReadOffset, dest, destOffset, toCopy)
        if (toCopy < available) {
            readyReadOffset += toCopy
        } else {
            readyReadOffset = 0
            slotUsed[slot] = false
            readyHead++
            if (readyHead >= readyTail) {
                readyHead = 0
                readyTail = 0
            }
        }
        return toCopy
    }

    /** Drains all ready buffered slices sequentially into [consumer] with zero allocation. */
    inline fun drainReady(consumer: (ByteArray, Int, Int) -> Unit) {
        while (hasReady()) {
            val buf = readyData()
            val off = currentReadyOffset()
            val len = readyLength()
            consumer(buf, off, len)
            consumeReady()
        }
    }

    fun currentReadyOffset(): Int = readyReadOffset

    fun readyData(): ByteArray = slotData[readyQueue[readyHead]]

    fun readyLength(): Int = slotLen[readyQueue[readyHead]] - readyReadOffset

    fun consumeReady() {
        val slot = readyQueue[readyHead++]
        readyReadOffset = 0
        slotUsed[slot] = false
        if (readyHead >= readyTail) {
            readyHead = 0
            readyTail = 0
        }
    }

    fun offer(buffer: ByteArray, parsed: RtpPacketParser.ParsedPacket): OfferResult {
        if (!parsed.isRtp) {
            val hadBuffered = flushAllPending()
            hasStream = false
            return if (hadBuffered) OfferResult.FLUSH_THEN_CURRENT else OfferResult.DELIVER_CURRENT
        }

        val seq = parsed.sequenceNumber and 0xFFFF
        val ssrc = parsed.ssrc

        if (!hasStream || ssrc != activeSsrc) {
            val hadBuffered = if (hasStream) flushAllPending() else false
            hasStream = true
            activeSsrc = ssrc
            expectedSeq = (seq + 1) and 0xFFFF
            waitPackets = 0
            highestSeq16 = seq
            uniqueRtpPackets = 1L
            expectedRtpSpan = 0L
            return if (hadBuffered) OfferResult.FLUSH_THEN_CURRENT else OfferResult.DELIVER_CURRENT
        }

        val delta = signedDelta16(seq, expectedSeq)
        return when {
            delta == 0 -> {
                recordUniqueSequence(seq)
                expectedSeq = (seq + 1) and 0xFFFF
                waitPackets = 0
                OfferResult.DELIVER_CURRENT
            }
            delta in -DUPLICATE_LOOKBACK..-1 -> OfferResult.DROP
            delta in 1 until windowSize -> {
                if (isSeqStored(seq)) {
                    OfferResult.DROP
                } else {
                    recordUniqueSequence(seq)
                    if (parsed.length <= slotCapacity && storePacket(buffer, parsed.offset, parsed.length, seq)) {
                        waitPackets++
                        if (waitPackets >= maxWaitPackets) {
                            advancePastMissingAndPromote()
                            OfferResult.DRAIN_READY
                        } else {
                            OfferResult.BUFFERED
                        }
                    } else {
                        val hadBuffered = flushAllPending()
                        expectedSeq = (seq + 1) and 0xFFFF
                        waitPackets = 0
                        if (hadBuffered) OfferResult.FLUSH_THEN_CURRENT else OfferResult.DELIVER_CURRENT
                    }
                }
            }
            else -> {
                val hiDelta = signedDelta16(seq, highestSeq16)
                if (hiDelta in 1..MAX_CONTIGUOUS_GAP) {
                    recordUniqueSequence(seq)
                } else {
                    // Major sequence splice or stream restart: re-baseline loss counter cleanly
                    highestSeq16 = seq
                    uniqueRtpPackets = 1L
                    expectedRtpSpan = 0L
                }
                val hadBuffered = flushAllPending()
                expectedSeq = (seq + 1) and 0xFFFF
                waitPackets = 0
                if (hadBuffered) OfferResult.FLUSH_THEN_CURRENT else OfferResult.DELIVER_CURRENT
            }
        }
    }

    private fun recordUniqueSequence(seq: Int) {
        uniqueRtpPackets++
        val hiDelta = signedDelta16(seq, highestSeq16)
        if (hiDelta > 0) {
            expectedRtpSpan += hiDelta.toLong()
            highestSeq16 = seq
        }
    }

    /** Called after [OfferResult.DELIVER_CURRENT] finishes reading, to queue any now-contiguous buffered packets. */
    fun onCurrentDelivered() {
        queueContiguousFromExpected()
    }

    /** Flushes buffered out-of-order packets when waiting on a lost sequence number times out. */
    fun flushOnTimeout(): Boolean {
        if (hasReady()) return true
        return advancePastMissingAndPromote()
    }

    private fun isSeqStored(seq: Int): Boolean {
        for (i in 0 until windowSize) {
            if (slotUsed[i] && slotSeq[i] == seq) return true
        }
        return false
    }

    private fun storePacket(buffer: ByteArray, offset: Int, length: Int, seq: Int): Boolean {
        for (i in 0 until windowSize) {
            if (!slotUsed[i]) {
                System.arraycopy(buffer, offset, slotData[i], 0, length)
                slotUsed[i] = true
                slotSeq[i] = seq
                slotLen[i] = length
                return true
            }
        }
        return false
    }

    private fun findSlotBySeq(seq: Int): Int {
        for (i in 0 until windowSize) {
            if (slotUsed[i] && slotSeq[i] == seq) return i
        }
        return -1
    }

    private fun queueContiguousFromExpected(): Boolean {
        var added = false
        while (readyTail < windowSize) {
            val slot = findSlotBySeq(expectedSeq)
            if (slot < 0) break
            readyQueue[readyTail++] = slot
            expectedSeq = (expectedSeq + 1) and 0xFFFF
            waitPackets = 0
            added = true
        }
        return added
    }

    private fun advancePastMissingAndPromote(): Boolean {
        var bestSlot = -1
        var bestDelta = Int.MAX_VALUE
        for (i in 0 until windowSize) {
            if (!slotUsed[i] || isQueued(i)) continue
            val d = signedDelta16(slotSeq[i], expectedSeq)
            if (d in 0 until bestDelta) {
                bestDelta = d
                bestSlot = i
            }
        }
        if (bestSlot < 0) {
            waitPackets = 0
            return false
        }
        expectedSeq = slotSeq[bestSlot]
        waitPackets = 0
        return queueContiguousFromExpected()
    }

    private fun flushAllPending(): Boolean {
        readyHead = 0
        readyTail = 0
        readyReadOffset = 0
        val count = slotUsed.count { it }
        if (count == 0) return false
        while (readyTail < windowSize) {
            var bestSlot = -1
            var bestDelta = Int.MAX_VALUE
            for (i in 0 until windowSize) {
                if (!slotUsed[i] || isQueued(i)) continue
                val d = signedDelta16(slotSeq[i], expectedSeq)
                val key = if (d < -DUPLICATE_LOOKBACK) d + 65536 else d
                if (key < bestDelta) {
                    bestDelta = key
                    bestSlot = i
                }
            }
            if (bestSlot < 0) break
            readyQueue[readyTail++] = bestSlot
        }
        waitPackets = 0
        return readyTail > 0
    }

    private fun isQueued(slotIndex: Int): Boolean {
        for (idx in readyHead until readyTail) {
            if (readyQueue[idx] == slotIndex) return true
        }
        return false
    }

    internal companion object {
        const val DEFAULT_WINDOW_SIZE = 8
        const val DEFAULT_MAX_WAIT_PACKETS = 4
        const val DEFAULT_SLOT_CAPACITY = 2048
        private const val DUPLICATE_LOOKBACK = 64
        private const val MAX_CONTIGUOUS_GAP = 2048
        private const val MIN_LOSS_SAMPLE_PACKETS = 50L

        fun signedDelta16(seq: Int, base: Int): Int {
            val diff = (seq - base) and 0xFFFF
            return if (diff >= 32768) diff - 65536 else diff
        }
    }
}

/**
 * Fixed-capacity single-allocation circular byte buffer decoupled between the high-priority UDP
 * receiver thread and ExoPlayer's loader thread.
 * - Producer [write] never blocks on ExoPlayer stalls; if full, oldest unread bytes are dropped.
 * - Consumer [read] returns `-1` on EOF, `0` on poll timeout, or `> 0` bytes read.
 */
class RtpCircularByteBuffer(
    val capacity: Int = DEFAULT_CAPACITY,
) {
    private val data = ByteArray(capacity)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    private var readPos = 0
    private var writePos = 0
    private var size = 0
    private var closed = false
    private var failure: Throwable? = null
    private var droppedOverflowBytes = 0L

    fun overflowBytes(): Long = lock.withLock { droppedOverflowBytes }

    fun errorOrNull(): Throwable? = lock.withLock { failure }

    fun write(src: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        lock.withLock {
            if (closed || failure != null) return
            var srcOff = offset
            var len = length
            if (len >= capacity) {
                val skip = len - capacity
                droppedOverflowBytes += size.toLong() + skip.toLong()
                srcOff += skip
                len = capacity
                readPos = 0
                writePos = 0
                size = 0
            } else if (size + len > capacity) {
                val drop = (size + len) - capacity
                readPos = (readPos + drop) % capacity
                size -= drop
                droppedOverflowBytes += drop.toLong()
            }
            val first = minOf(len, capacity - writePos)
            System.arraycopy(src, srcOff, data, writePos, first)
            val second = len - first
            if (second > 0) {
                System.arraycopy(src, srcOff + first, data, 0, second)
            }
            writePos = (writePos + len) % capacity
            size += len
            notEmpty.signalAll()
        }
    }

    fun read(dst: ByteArray, offset: Int, maxLen: Int, timeoutMs: Int): Int {
        if (maxLen <= 0) return 0
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(1).toLong())
        lock.withLock {
            while (size == 0) {
                failure?.let { return -1 }
                if (closed) return -1
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return 0
                try {
                    notEmpty.awaitNanos(remainingNs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
            val toCopy = minOf(maxLen, size)
            val first = minOf(toCopy, capacity - readPos)
            System.arraycopy(data, readPos, dst, offset, first)
            val second = toCopy - first
            if (second > 0) {
                System.arraycopy(data, 0, dst, offset + first, second)
            }
            readPos = (readPos + toCopy) % capacity
            size -= toCopy
            return toCopy
        }
    }

    fun fail(error: Throwable) {
        lock.withLock {
            if (failure == null) failure = error
            closed = true
            notEmpty.signalAll()
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 4 * 1024 * 1024
    }
}

/**
 * Latest RTP session packet/loss diagnostics (non-`@UnstableApi` so watchdog/logging callers
 * remain free of opt-in lint errors).
 */
object RtpDiagnostics {
    private const val TAG = "IQTV-RTP"

    data class Snapshot(
        val uri: String = "",
        val receivedPackets: Long = 0L,
        val lostPackets: Long = 0L,
        val lossPercent: Int = 0,
        val overflowBytes: Long = 0L,
        val updatedAtMs: Long = 0L,
    )

    @Volatile private var current: Snapshot = Snapshot()

    fun reset() {
        current = Snapshot()
    }

    fun record(
        uri: String,
        receivedPackets: Long,
        lostPackets: Long,
        lossPercent: Int,
        overflowBytes: Long,
    ) {
        current = Snapshot(
            uri = uri,
            receivedPackets = receivedPackets,
            lostPackets = lostPackets,
            lossPercent = lossPercent,
            overflowBytes = overflowBytes,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    fun snapshot(): Snapshot = current

    fun currentLossPercent(): Int = current.lossPercent

    fun logSevereLossOnce(uri: String, receivedPackets: Long, lostPackets: Long, lossPercent: Int, overflowBytes: Long) {
        runCatching {
            Log.w(
                TAG,
                "multicast loss=$lossPercent% received=$receivedPackets lost=$lostPackets ringDrop=$overflowBytes uri=$uri",
            )
        }
    }
}

/**
 * Pure networking helpers for native multicast sockets kept outside Media3's `@UnstableApi` classes
 * so callers such as [MulticastPrewarm] and [SourceLatencyProber] stay free of opt-in lint errors.
 */
object MulticastNetworkSupport {
    /**
     * Binds [ms] to `(group, port)` on Linux/Android so multiple IPTV multicast channels sharing one port
     * (e.g. `239.3.1.22:8001` and `239.3.1.175:8001`) do not leak datagrams across sockets, falling back
     * to wildcard port bind if the platform rejects binding a multicast address.
     */
    fun bindMulticastSocket(ms: MulticastSocket, group: InetAddress, port: Int) {
        val boundToGroup = runCatching {
            ms.bind(InetSocketAddress(group, port))
            true
        }.getOrDefault(false)
        if (!boundToGroup) {
            ms.bind(InetSocketAddress(port))
        }
    }

    fun pickMulticastInterfaces(target: InetAddress): List<NetworkInterface> {
        val ifaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrNull()
            ?: return emptyList()
        val wantClass = target::class.java
        return ifaces
            .filter { iface ->
                val name = iface.name.orEmpty().lowercase()
                val excluded = name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("dummy")
                !excluded && runCatching {
                    iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                        Collections.list(iface.inetAddresses).any { it::class.java == wantClass }
                }.getOrDefault(false)
            }
            .sortedBy { iface ->
                val name = iface.name.orEmpty().lowercase()
                when {
                    name.startsWith("eth") -> 0
                    name.startsWith("wlan") -> 1
                    else -> 2
                }
            }
    }

    fun joinMulticastOnActiveInterfaces(
        ms: MulticastSocket,
        group: InetAddress,
        port: Int,
    ): List<NetworkInterface> {
        val candidates = pickMulticastInterfaces(group)
        val joined = ArrayList<NetworkInterface>(candidates.size)
        for (iface in candidates) {
            if (runCatching { ms.joinGroup(InetSocketAddress(group, port), iface) }.isSuccess) {
                joined.add(iface)
            }
        }
        if (joined.isEmpty()) {
            @Suppress("DEPRECATION")
            ms.joinGroup(group)
        }
        return joined
    }

    fun leaveMulticastInterfaces(
        ms: MulticastSocket?,
        group: InetAddress?,
        port: Int,
        interfaces: List<NetworkInterface>,
    ) {
        if (ms == null || group == null) return
        if (interfaces.isNotEmpty()) {
            for (iface in interfaces) {
                runCatching { ms.leaveGroup(InetSocketAddress(group, port), iface) }
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { ms.leaveGroup(group) }
        }
    }
}

/**
 * Pure native `rtp://` & `udp://` multicast/unicast DataSource for Media3:
 * - Plays the exact URL from the playlist with zero URL rewriting or external proxy assumptions;
 * - Dedicated high-priority receiver thread + 4 MB circular buffer so 4K HDR bursts (35–50 Mbps)
 *   never overflow Android's 512 KB kernel `rmem_max` during codec/audio init or GC pauses;
 * - Group-IP socket binding + multi-NIC IGMP join + `WifiManager.MulticastLock`;
 * - Zero-allocation RTP sequence deduplication, bounded 8-packet reorder buffer, and RFC 3550 loss tracking;
 * - Bounded 500 ms poll slices so zapping away aborts stalled sockets immediately.
 */
@UnstableApi
class RtpMulticastDataSource(
    context: Context,
    private val socketTimeoutMs: Int = DEFAULT_SOCKET_TIMEOUT_MS,
    private val pollTimeoutMs: Int = DEFAULT_POLL_TIMEOUT_MS,
) : BaseDataSource(/* isNetwork = */ true) {

    private val appContext = context.applicationContext

    @Volatile private var uri: Uri? = null
    @Volatile private var endpoint: RtpPacketParser.Endpoint? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var multicastSocket: MulticastSocket? = null
    @Volatile private var groupAddress: InetAddress? = null
    @Volatile private var multicastInterfaces: List<NetworkInterface> = emptyList()
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var ringBuffer: RtpCircularByteBuffer? = null
    @Volatile private var rxThread: Thread? = null
    @Volatile private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val specUri = dataSpec.uri
        uri = specUri
        val rawUriStr = specUri.toString()
        val parsed = RtpPacketParser.parseEndpoint(rawUriStr)
            ?: throw DataSourceException(
                IllegalArgumentException("Invalid rtp/udp URI: $specUri"),
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            )
        endpoint = parsed
        RtpDiagnostics.reset()
        transferInitializing(dataSpec)

        val effectivePollMs = pollTimeoutMs.coerceIn(100, socketTimeoutMs.coerceAtLeast(100))
        val ring: RtpCircularByteBuffer
        val activeSock: DatagramSocket
        try {
            val resolvedGroup = NetUtil.orderAddresses(InetAddress.getAllByName(parsed.host).toList())
                .firstOrNull()
                ?: InetAddress.getByName(parsed.host)
            groupAddress = resolvedGroup

            if (parsed.isMulticast || resolvedGroup.isMulticastAddress) {
                acquireMulticastLock()
                val ms = MulticastSocket(null).apply {
                    reuseAddress = true
                    runCatching { receiveBufferSize = MULTICAST_RCVBUF_BYTES }
                    soTimeout = effectivePollMs
                    MulticastNetworkSupport.bindMulticastSocket(this, resolvedGroup, parsed.port)
                }
                multicastSocket = ms
                socket = ms
                activeSock = ms
                multicastInterfaces = MulticastNetworkSupport.joinMulticastOnActiveInterfaces(ms, resolvedGroup, parsed.port)
                ring = RtpCircularByteBuffer(MULTICAST_RING_BYTES)
            } else {
                val ds = DatagramSocket(null).apply {
                    reuseAddress = true
                    runCatching { receiveBufferSize = UNICAST_RCVBUF_BYTES }
                    soTimeout = effectivePollMs
                    bind(InetSocketAddress(parsed.port))
                }
                socket = ds
                activeSock = ds
                ring = RtpCircularByteBuffer(UNICAST_RING_BYTES)
            }
        } catch (e: IOException) {
            closeQuietly()
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        } catch (e: SecurityException) {
            closeQuietly()
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        }

        ringBuffer = ring
        opened = true
        val worker = Thread(
            { runReceiverPump(rawUriStr, activeSock, ring) },
            "iqtv-rtp-rx",
        ).apply { isDaemon = true }
        rxThread = worker
        worker.start()

        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    private fun runReceiverPump(
        rawUriStr: String,
        activeSocket: DatagramSocket,
        ring: RtpCircularByteBuffer,
    ) {
        runCatching {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        }
        val packetBuffer = ByteArray(MAX_DATAGRAM_BYTES)
        val packet = DatagramPacket(packetBuffer, packetBuffer.size)
        val parsedPacket = RtpPacketParser.ParsedPacket()
        val assembler = RtpStreamAssembler()
        var invalidPackets = 0
        var packetsSeen = 0L
        var severeLossLogged = false

        fun flushAssemblerAndCurrent(deliverCurrent: Boolean) {
            assembler.drainReady { buf, off, len -> ring.write(buf, off, len) }
            if (deliverCurrent) {
                ring.write(packetBuffer, parsedPacket.offset, parsedPacket.length)
            }
        }

        try {
            while (opened && !Thread.currentThread().isInterrupted) {
                packet.setData(packetBuffer, 0, packetBuffer.size)
                try {
                    activeSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    if (!opened || Thread.currentThread().isInterrupted) break
                    if (assembler.flushOnTimeout() && assembler.hasReady()) {
                        flushAssemblerAndCurrent(deliverCurrent = false)
                    }
                    continue
                } catch (e: IOException) {
                    if (!opened || Thread.currentThread().isInterrupted) break
                    ring.fail(DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
                    return
                }

                if (!RtpPacketParser.inspectPacket(packetBuffer, packet.length, parsedPacket)) {
                    if (++invalidPackets >= MAX_SKIPPED_INVALID_PACKETS) {
                        ring.fail(
                            DataSourceException(
                                IOException("Too many invalid RTP packets"),
                                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                            )
                        )
                        return
                    }
                    continue
                }
                invalidPackets = 0

                when (assembler.offer(packetBuffer, parsedPacket)) {
                    RtpStreamAssembler.OfferResult.DROP,
                    RtpStreamAssembler.OfferResult.BUFFERED -> Unit

                    RtpStreamAssembler.OfferResult.DELIVER_CURRENT -> {
                        ring.write(packetBuffer, parsedPacket.offset, parsedPacket.length)
                        assembler.onCurrentDelivered()
                        flushAssemblerAndCurrent(deliverCurrent = false)
                    }

                    RtpStreamAssembler.OfferResult.FLUSH_THEN_CURRENT -> {
                        flushAssemblerAndCurrent(deliverCurrent = true)
                    }

                    RtpStreamAssembler.OfferResult.DRAIN_READY -> {
                        flushAssemblerAndCurrent(deliverCurrent = false)
                    }
                }

                packetsSeen++
                if (packetsSeen == 1L || (packetsSeen and 0xFFL) == 0L) {
                    val rx = assembler.receivedPackets()
                    val lost = assembler.lostPackets()
                    val pct = assembler.lossPercent()
                    val overflow = ring.overflowBytes()
                    RtpDiagnostics.record(rawUriStr, rx, lost, pct, overflow)
                    if (!severeLossLogged && assembler.expectedPackets() >= 200L && pct >= 10) {
                        severeLossLogged = true
                        RtpDiagnostics.logSevereLossOnce(rawUriStr, rx, lost, pct, overflow)
                    }
                }
            }
        } finally {
            RtpDiagnostics.record(
                rawUriStr,
                assembler.receivedPackets(),
                assembler.lostPackets(),
                assembler.lossPercent(),
                ring.overflowBytes(),
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val ring = ringBuffer ?: return C.RESULT_END_OF_INPUT
        val effectivePollMs = pollTimeoutMs.coerceIn(100, socketTimeoutMs.coerceAtLeast(100))
        val maxIdlePolls = (socketTimeoutMs / effectivePollMs).coerceAtLeast(1)
        var idlePolls = 0

        while (true) {
            if (!opened || Thread.currentThread().isInterrupted) {
                throw DataSourceException(
                    InterruptedIOException("RTP read cancelled"),
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                )
            }
            val n = ring.read(buffer, offset, length, effectivePollMs)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (n < 0) {
                val err = ring.errorOrNull()
                if (err != null) {
                    throw (err as? DataSourceException)
                        ?: DataSourceException(err, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                }
                if (Thread.currentThread().isInterrupted && opened) {
                    throw DataSourceException(
                        InterruptedIOException("RTP read interrupted"),
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    )
                }
                return C.RESULT_END_OF_INPUT
            }
            if (++idlePolls >= maxIdlePolls) {
                throw DataSourceException(
                    SocketTimeoutException("RTP receive timed out after ${socketTimeoutMs}ms"),
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                )
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val wasOpened = opened
        opened = false
        uri = null
        val ep = endpoint
        endpoint = null
        ringBuffer?.close()
        rxThread?.interrupt()
        closeQuietly(ep?.port ?: 0)
        val t = rxThread
        rxThread = null
        if (t != null && t !== Thread.currentThread()) {
            runCatching { t.join(150) }
        }
        ringBuffer = null
        if (wasOpened) {
            transferEnded()
        }
    }

    private fun closeQuietly(port: Int = endpoint?.port ?: 0) {
        MulticastNetworkSupport.leaveMulticastInterfaces(
            multicastSocket,
            groupAddress,
            port,
            multicastInterfaces,
        )
        runCatching { socket?.close() }
        socket = null
        multicastSocket = null
        groupAddress = null
        multicastInterfaces = emptyList()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = runCatching {
            wifi.createMulticastLock("iqtvlite-rtp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching { if (lock.isHeld) lock.release() }
    }

    internal companion object {
        const val MAX_DATAGRAM_BYTES = 65_536
        const val MULTICAST_RCVBUF_BYTES = 4 * 1024 * 1024
        const val UNICAST_RCVBUF_BYTES = 512 * 1024
        const val MULTICAST_RING_BYTES = 4 * 1024 * 1024
        const val UNICAST_RING_BYTES = 1024 * 1024
        const val DEFAULT_SOCKET_TIMEOUT_MS = 5_000
        const val DEFAULT_POLL_TIMEOUT_MS = 500
        const val MAX_SKIPPED_INVALID_PACKETS = 256
    }
}

/**
 * Unified [DataSource.Factory] routing `rtp://` and `udp://` directly to [RtpMulticastDataSource]
 * with zero URL rewriting, and delegating all other URIs to Media3's [DefaultDataSource].
 */
@UnstableApi
class IptvStreamDataSourceFactory(
    context: Context,
    httpFactory: DataSource.Factory,
) : DataSource.Factory {
    private val appContext = context.applicationContext
    private val defaultFactory = DefaultDataSource.Factory(appContext, httpFactory)

    override fun createDataSource(): DataSource = RoutingDataSource(appContext, defaultFactory)

    private class RoutingDataSource(
        private val context: Context,
        private val defaultFactory: DefaultDataSource.Factory,
    ) : DataSource {
        private var delegate: DataSource? = null
        private val transferListeners = ArrayList<androidx.media3.datasource.TransferListener>(2)

        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            transferListeners.add(transferListener)
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme?.lowercase()
            val chosen = if (scheme == "rtp" || scheme == "udp") {
                RtpMulticastDataSource(context)
            } else {
                defaultFactory.createDataSource()
            }
            for (listener in transferListeners) {
                chosen.addTransferListener(listener)
            }
            delegate = chosen
            return try {
                chosen.open(dataSpec)
            } catch (t: Throwable) {
                runCatching { chosen.close() }
                if (delegate === chosen) delegate = null
                throw t
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): MutableMap<String, MutableList<String>> =
            delegate?.responseHeaders ?: mutableMapOf()

        override fun close() {
            try {
                delegate?.close()
            } finally {
                delegate = null
            }
        }
    }
}
