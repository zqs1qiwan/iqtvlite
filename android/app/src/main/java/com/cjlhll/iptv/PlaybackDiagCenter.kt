package com.cjlhll.iptv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tv.iqtvlite.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact, fixed-capacity (60 events) playback and network diagnostic ring buffer.
 * Zero per-frame allocation; updated only on tune, format/decoder transitions, errors, AFR changes, and audio changes.
 */
object PlaybackDiagCenter {
    const val MAX_EVENTS = 60

    data class DiagEvent(
        val timestampMs: Long,
        val tag: String,
        val message: String,
    )

    private val lock = Any()
    private val events = ArrayDeque<DiagEvent>(MAX_EVENTS)

    @Volatile var channelTitle: String = "-"
        private set
    @Volatile var streamUrl: String = "-"
        private set
    @Volatile var videoMime: String = "-"
        private set
    @Volatile var resolution: String = "-"
        private set
    @Volatile var videoFps: Float = 0f
        private set
    @Volatile var decoderName: String = "-"
        private set
    @Volatile var isHardwareDecoder: Boolean = true
        private set
    @Volatile var audioRoute: AudioOutputRoute = AudioOutputRoute.SPEAKER
        private set
    @Volatile var audioDelayMs: Int = 0
        private set
    @Volatile var audioBoostPct: Int = 100
        private set
    @Volatile var afrEnabled: Boolean = false
        private set
    @Volatile var afrActiveHz: Float = 0f
        private set
    @Volatile var lastError: String = "-"
        private set

    fun onTune(title: String, url: String) {
        channelTitle = title.ifBlank { "-" }
        streamUrl = sanitizeUrlForDisplay(url)
        videoMime = "-"
        resolution = "-"
        videoFps = 0f
        lastError = "-"
        recordEvent("TUNE", "$channelTitle -> $streamUrl")
    }

    fun updateVideoFormat(mime: String?, width: Int, height: Int, fps: Float) {
        videoMime = mime?.ifBlank { "-" } ?: "-"
        resolution = if (width > 0 && height > 0) "${width}x$height" else "-"
        if (fps.isFinite() && fps > 0f) videoFps = fps
    }

    fun updateDecoder(name: String?, hardware: Boolean) {
        decoderName = name?.ifBlank { "-" } ?: "-"
        isHardwareDecoder = hardware
        recordEvent("DECODER", "${if (hardware) "HW" else "SW"}: $decoderName")
    }

    fun updateAudio(route: AudioOutputRoute, delayMs: Int, boostPct: Int) {
        audioRoute = route
        audioDelayMs = delayMs
        audioBoostPct = boostPct
    }

    fun updateAfr(enabled: Boolean, activeHz: Float, matchedFps: Float) {
        afrEnabled = enabled
        afrActiveHz = activeHz
        if (matchedFps.isFinite() && matchedFps > 0f) videoFps = matchedFps
    }

    fun recordError(tag: String, error: String) {
        lastError = "$tag: $error"
        recordEvent(tag, error)
    }

    fun recordEvent(tag: String, message: String, nowMs: Long = System.currentTimeMillis()) {
        val cleanMsg = message.take(240)
        synchronized(lock) {
            if (events.size >= MAX_EVENTS) {
                events.removeFirst()
            }
            events.addLast(DiagEvent(nowMs, tag, cleanMsg))
        }
    }

    fun recentEvents(): List<DiagEvent> = synchronized(lock) { events.toList() }

    fun uiRows(context: Context, lang: AppLang): List<Pair<String, String>> {
        fun t(zh: String, en: String) = if (lang == AppLang.EN) en else zh
        val rtp = RtpDiagnostics.snapshot()
        val fpsText = if (videoFps > 0f) "${String.format(Locale.US, "%.2f", videoFps)} fps" else "-"
        val afrText = if (afrEnabled) {
            val hz = if (afrActiveHz > 0f) "${String.format(Locale.US, "%.2f", afrActiveHz)} Hz" else t("等待匹配", "Waiting")
            t("已开启 ($hz)", "ON ($hz)")
        } else {
            t("已关闭", "OFF")
        }
        val decType = if (isHardwareDecoder) t("硬解", "HW") else t("软解", "SW")
        val dnsMode = Prefs.getDnsMode(context)
        val ipStack = Prefs.getIpStackPreference(context)

        val rows = mutableListOf<Pair<String, String>>(
            t("当前频道", "Channel") to channelTitle,
            t("流地址", "Stream") to streamUrl,
            t("编码与分辨率", "Video") to "$resolution · $fpsText · $videoMime",
            t("视频解码器", "Decoder") to "$decType ($decoderName)",
            t("帧率匹配", "AFR") to afrText,
            t("音频路由与调校", "Audio") to "${audioRoute.localizedLabel(lang)} · ${audioDelayMs}ms · ${audioBoostPct}%",
            t("DNS / 协议栈", "DNS / Stack") to "${dnsMode.shortLabel(lang)} / ${ipStack.shortLabel(lang)} (hit=${DohResolver.dohHits}, fb=${DohResolver.dohFallbacks})",
        )
        if (rtp.uri.isNotBlank() || rtp.receivedPackets > 0L) {
            rows += t("RTP 组播丢包", "RTP Multicast") to "rx=${rtp.receivedPackets}, lost=${rtp.lostPackets} (${rtp.lossPercent}%), ovf=${rtp.overflowBytes}B"
        }
        if (lastError != "-") {
            rows += t("最近异常", "Last Error") to lastError
        }
        return rows
    }

    fun toExportJson(context: Context): String {
        val rtp = RtpDiagnostics.snapshot()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val dnsMode = Prefs.getDnsMode(context)
        val ipStack = Prefs.getIpStackPreference(context)
        val epgOffset = Prefs.getEpgOffsetMinutes(context)

        return JSONObject().apply {
            put("app", JSONObject().apply {
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("sdk", Build.VERSION.SDK_INT)
            })
            put("stream", JSONObject().apply {
                put("channel", channelTitle)
                put("url", streamUrl)
                put("mime", videoMime)
                put("resolution", resolution)
                put("fps", videoFps.toDouble())
                put("decoder", decoderName)
                put("hardware", isHardwareDecoder)
                put("lastError", lastError)
            })
            put("afr", JSONObject().apply {
                put("enabled", afrEnabled)
                put("activeRefreshHz", afrActiveHz.toDouble())
            })
            put("audio", JSONObject().apply {
                put("route", audioRoute.id)
                put("delayMs", audioDelayMs)
                put("boostPct", audioBoostPct)
            })
            put("network", JSONObject().apply {
                put("ipStack", ipStack.id)
                put("dnsMode", dnsMode.id)
                put("dohHits", DohResolver.dohHits)
                put("dohFallbacks", DohResolver.dohFallbacks)
                put("epgOffsetMinutes", epgOffset)
            })
            if (rtp.uri.isNotBlank() || rtp.receivedPackets > 0L) {
                put("rtp", JSONObject().apply {
                    put("uri", sanitizeUrlForDisplay(rtp.uri))
                    put("receivedPackets", rtp.receivedPackets)
                    put("lostPackets", rtp.lostPackets)
                    put("lossPercent", rtp.lossPercent)
                    put("overflowBytes", rtp.overflowBytes)
                })
            }
            put("jingcai", JSONObject().apply {
                put("fields", JingcaiDiag.fields())
                put("timeline", JingcaiDiag.timelineLine() ?: "")
            })
            put("events", JSONArray().apply {
                for (ev in recentEvents()) {
                    put(JSONObject().apply {
                        put("time", fmt.format(Date(ev.timestampMs)))
                        put("tag", ev.tag)
                        put("message", ev.message)
                    })
                }
            })
        }.toString(2)
    }

    private fun sanitizeUrlForDisplay(raw: String): String {
        val clean = raw.substringBefore('|').trim()
        val qIdx = clean.indexOf('?')
        return if (qIdx > 0 && clean.length > 96) {
            clean.substring(0, qIdx) + "?…"
        } else {
            clean.ifBlank { "-" }
        }
    }
}
