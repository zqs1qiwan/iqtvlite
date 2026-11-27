package com.cjlhll.iptv

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

enum class AudioOutputRoute(val id: String) {
    SPEAKER("speaker"),
    BLUETOOTH("bluetooth"),
    HDMI("hdmi");

    fun localizedLabel(lang: AppLang): String = when (this) {
        SPEAKER -> if (lang == AppLang.EN) "Speaker" else "本机扬声器"
        BLUETOOTH -> if (lang == AppLang.EN) "Bluetooth Audio" else "蓝牙音频"
        HDMI -> if (lang == AppLang.EN) "HDMI / ARC" else "HDMI / 功放"
    }

    companion object {
        fun parse(raw: String?): AudioOutputRoute =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) } ?: SPEAKER

        fun detect(context: Context): AudioOutputRoute {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return SPEAKER
            val devices = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull().orEmpty()
            var hasHdmi = false
            for (d in devices) {
                when (d.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    26 /* TYPE_BLE_HEADSET */,
                    27 /* TYPE_BLE_SPEAKER */ -> return BLUETOOTH
                    AudioDeviceInfo.TYPE_HDMI,
                    AudioDeviceInfo.TYPE_HDMI_ARC,
                    29 /* TYPE_HDMI_EARC */ -> hasHdmi = true
                }
            }
            return if (hasHdmi) HDMI else SPEAKER
        }
    }
}

/**
 * Pure Kotlin PCM gain + delay arithmetic helpers, directly testable on the JVM.
 */
object AudioPcmMath {
    const val MIN_DELAY_MS = -500
    const val MAX_DELAY_MS = 500
    const val STEP_DELAY_MS = 25
    const val DEFAULT_BOOST_PCT = 100
    const val MAX_BOOST_PCT = 150

    fun clampDelayMs(ms: Int): Int = ms.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    fun clampBoostPct(pct: Int): Int = pct.coerceIn(DEFAULT_BOOST_PCT, MAX_BOOST_PCT)

    fun delayFramesFor(delayMs: Int, sampleRateHz: Int): Int {
        if (delayMs == 0 || sampleRateHz <= 0) return 0
        return (abs(delayMs).toLong() * sampleRateHz.toLong() / 1000L).toInt()
    }

    /**
     * Copies 16-bit signed little-endian PCM from [src] to [dst] while applying integer Q12 gain
     * with saturating clamp so boost up to 150% never wraps/clicks.
     */
    fun applyGainPcm16Le(src: ByteBuffer, dst: ByteBuffer, boostPct: Int) {
        val clamped = clampBoostPct(boostPct)
        if (clamped == 100) {
            dst.put(src)
            return
        }
        val gainQ12 = (clamped * 4096L + 50L) / 100L
        while (src.remaining() >= 2) {
            val lo = src.get().toInt() and 0xFF
            val hi = src.get().toInt()
            val sample = (hi shl 8) or lo
            val prod = sample.toLong() * gainQ12
            val rounded = if (prod >= 0L) (prod + 2048L) shr 12 else -((-prod + 2048L) shr 12)
            val scaled = rounded
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toInt()
            dst.put((scaled and 0xFF).toByte())
            dst.put(((scaled ushr 8) and 0xFF).toByte())
        }
        while (src.hasRemaining()) {
            dst.put(src.get())
        }
    }

    fun applyGainPcmFloat(src: ByteBuffer, dst: ByteBuffer, boostPct: Int) {
        val clamped = clampBoostPct(boostPct)
        if (clamped == 100) {
            dst.put(src)
            return
        }
        val factor = clamped / 100f
        while (src.remaining() >= 4) {
            val sample = src.getFloat()
            val scaled = (sample * factor).coerceIn(-1.0f, 1.0f)
            dst.putFloat(scaled)
        }
        while (src.hasRemaining()) {
            dst.put(src.get())
        }
    }
}

/**
 * Lightweight Media3 AudioProcessor providing:
 * 1. Exact bypass (`isActive() == false`) when delay == 0 ms and boost == 100% (0 CPU, 0 allocation);
 * 2. Linear gain boost up to 150% with saturating clamp;
 * 3. Per-route A/V lip-sync offset in [-500ms, +500ms] by padding leading silence (+delay) or dropping initial PCM frames (-delay).
 */
@UnstableApi
class AudioSyncBoostProcessor : BaseAudioProcessor() {
    @Volatile
    private var delayMs: Int = 0

    @Volatile
    private var boostPct: Int = AudioPcmMath.DEFAULT_BOOST_PCT

    private val pendingPadBytes = AtomicInteger(0)
    private val pendingDropBytes = AtomicInteger(0)

    fun updateConfig(delayMs: Int, boostPct: Int) {
        val nextDelay = AudioPcmMath.clampDelayMs(delayMs)
        val nextBoost = AudioPcmMath.clampBoostPct(boostPct)
        val delayChanged = this.delayMs != nextDelay
        this.delayMs = nextDelay
        this.boostPct = nextBoost
        if (delayChanged && inputAudioFormat != AudioFormat.NOT_SET) {
            recomputeLeadBytes(nextDelay)
        }
    }

    fun currentDelayMs(): Int = delayMs
    fun currentBoostPct(): Int = boostPct

    override fun isActive(): Boolean {
        return super.isActive() && (delayMs != 0 || boostPct != AudioPcmMath.DEFAULT_BOOST_PCT)
    }

    @Throws(UnhandledAudioFormatException::class)
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onFlush() {
        recomputeLeadBytes(delayMs)
    }

    override fun onReset() {
        pendingPadBytes.set(0)
        pendingDropBytes.set(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // Negative delay: advance audio by trimming initial PCM bytes aligned to frame size.
        val curDrop = pendingDropBytes.get()
        if (curDrop > 0) {
            val frameBytes = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
            val drop = minOf(remaining, curDrop)
            val alignedDrop = (drop / frameBytes) * frameBytes
            if (alignedDrop > 0) {
                inputBuffer.position(inputBuffer.position() + alignedDrop)
                pendingDropBytes.addAndGet(-alignedDrop)
            }
        }

        val leftAfterDrop = inputBuffer.remaining()
        val pad = pendingPadBytes.getAndSet(0)
        if (leftAfterDrop == 0 && pad == 0) return

        val out = replaceOutputBuffer(pad + leftAfterDrop)
        if (pad > 0) {
            // ByteBuffer zero-fill from shared immutable silence chunk (0 heap allocations).
            var remPad = pad
            while (remPad > 0) {
                val chunk = minOf(remPad, SILENCE_CHUNK.size)
                out.put(SILENCE_CHUNK, 0, chunk)
                remPad -= chunk
            }
        }

        if (leftAfterDrop > 0) {
            when (inputAudioFormat.encoding) {
                C.ENCODING_PCM_FLOAT -> AudioPcmMath.applyGainPcmFloat(inputBuffer, out, boostPct)
                else -> AudioPcmMath.applyGainPcm16Le(inputBuffer, out, boostPct)
            }
        }
        out.flip()
    }

    private fun recomputeLeadBytes(ms: Int) {
        val fmt = inputAudioFormat
        if (fmt == AudioFormat.NOT_SET || fmt.bytesPerFrame <= 0) {
            pendingPadBytes.set(0)
            pendingDropBytes.set(0)
            return
        }
        val frames = AudioPcmMath.delayFramesFor(ms, fmt.sampleRate)
        val totalBytes = (frames.toLong() * fmt.bytesPerFrame.toLong())
            .coerceAtMost(2L * 1024L * 1024L)
            .toInt()
        if (ms > 0) {
            pendingPadBytes.set(totalBytes)
            pendingDropBytes.set(0)
        } else if (ms < 0) {
            pendingPadBytes.set(0)
            pendingDropBytes.set(totalBytes)
        } else {
            pendingPadBytes.set(0)
            pendingDropBytes.set(0)
        }
    }

    private companion object {
        val SILENCE_CHUNK = ByteArray(4096)
    }
}
