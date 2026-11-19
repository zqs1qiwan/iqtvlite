package com.cjlhll.iptv

import android.content.Context
import android.content.ContextWrapper
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device diagnostics. No codec/track overrides, global volume changes or public stream. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioDiagnosticTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
            super.getSharedPreferences("audio_diagnostic_$name", mode)
    }

    @Test fun api28AudioMimeCapabilities() {
        // All called platform APIs exist by API21. API29 isHardwareAccelerated/isSoftwareOnly are not used.
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
        assertTrue("Codec enumeration must work", codecs.isNotEmpty())
        var aacCandidates = 0
        for (mime in listOf("audio/mpeg-L2", "audio/mpeg", "audio/mp4a-latm")) {
            var count = 0
            for (codec in codecs) {
                val type = codec.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: continue
                count++
                try {
                    val caps = codec.getCapabilitiesForType(type)
                    val format = MediaFormat.createAudioFormat(type, 48_000, 2)
                    format.setInteger(MediaFormat.KEY_BIT_RATE, if (mime == "audio/mp4a-latm") 128_000 else 256_000)
                    if (mime == "audio/mp4a-latm") format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    Log.i("IQTV-AUDIO-PROBE", "api=${Build.VERSION.SDK_INT} mime=$mime name=${AudioDiagnosticFields.name(codec.name)} formatSupported=${caps.isFormatSupported(format)} rate48000=${caps.audioCapabilities.isSampleRateSupported(48_000)} maxChannels=${caps.audioCapabilities.maxInputChannelCount}")
                } catch (error: RuntimeException) {
                    Log.i("IQTV-AUDIO-PROBE", "api=${Build.VERSION.SDK_INT} mime=$mime name=${AudioDiagnosticFields.name(codec.name)} errorType=${AudioDiagnosticFields.name(error.javaClass.simpleName)}")
                }
            }
            Log.i("IQTV-AUDIO-PROBE", "api=${Build.VERSION.SDK_INT} mime=$mime candidates=$count")
            if (mime == "audio/mp4a-latm") aacCandidates = count
        }
        // MPEG-L2 zero candidates is diagnostic evidence, not a reason to suppress this test.
        assertTrue("AAC decoder is the enumeration positive control", aacCandidates > 0)
    }

    @Test fun pcmWaveReachesAudioTrackWithoutClaimingAudibility() {
        val advanced = CountDownLatch(1)
        val initialized = CountDownLatch(1)
        val input = AtomicReference<String>()
        val sinkError = AtomicReference<String>()
        lateinit var player: ExoPlayer
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(silentWave())))
            // Resolve the URL before entering the main looper: MockWebServer.url() does a reverse-DNS lookup
            // that StrictMode rejects as NetworkOnMainThreadException when called inside runOnMainSync.
            val silenceUrl = server.url("/silence.wav").toString()
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(context).build()
                player.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                        input.set(format.sampleMimeType)
                    }
                    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                        initialized.countDown()
                    }
                    override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                        advanced.countDown()
                    }
                    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                        sinkError.set(audioSinkError.javaClass.simpleName)
                    }
                })
                StreamPlayback.prepare(player, context, Channel("PCM diagnostic", silenceUrl, id = 703))
            }
            try {
                assertTrue("PCM must create AudioTrack", initialized.await(10, TimeUnit.SECONDS))
                assertTrue("Audio position must advance even when system gain is zero", advanced.await(10, TimeUnit.SECONDS))
                assertEquals(MimeTypes.AUDIO_RAW, input.get())
                assertEquals("No AudioSink error expected", null, sinkError.get())
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            } finally {
                instrumentation.runOnMainSync { StreamPlayback.release(player) }
            }
        }
    }

    /** Two seconds of digital silence; validates PCM plumbing without producing a test tone. */
    private fun silentWave(): ByteArray {
        val pcmBytes = 48_000 * 2 * 2 * 2
        return ByteBuffer.allocate(44 + pcmBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + pcmBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(2)
            putInt(48_000); putInt(48_000 * 2 * 2); putShort(4); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(pcmBytes)
        }.array()
    }
}
