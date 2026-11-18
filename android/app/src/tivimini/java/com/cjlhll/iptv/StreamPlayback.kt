package com.cjlhll.iptv

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.atomic.AtomicLong
import tv.iqtvlite.app.BuildConfig
import tv.iqtvlite.app.RustBridge

/** Immutable request headers per media source; old in-flight requests keep their own UA. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object StreamPlayback {
    private const val TAG = "IQTV-METRICS"
    private val sequence = AtomicLong()
    private val sessions = java.util.WeakHashMap<ExoPlayer, Metrics>()

    fun restore(context: Context, url: String, title: String): Channel {
        val prefs = context.getSharedPreferences("iptv_stream", Context.MODE_PRIVATE)
        return if (prefs.getString("url", null) == url) Channel(title=title, url=url,
            id=prefs.getLong("id", 0L), userAgent=prefs.getString("ua", null), referrer=prefs.getString("referrer", null))
        else Channel(title=title, url=url)
    }
    fun release(player: ExoPlayer) {
        sessions.remove(player)?.let { player.removeAnalyticsListener(it) }
        player.release()
    }
    fun prepare(player: ExoPlayer, context: Context, channel: Channel, streamUrl: String = channel.url): PreparedPlayback =
        prepare(player, context, MediaPlaybackRequest(channel, streamUrl), PlaybackIntent())

    fun prepare(player: ExoPlayer, context: Context, request: MediaPlaybackRequest, intent: PlaybackIntent,
                onFailure: (PlaybackFailure) -> Unit = {}): PreparedPlayback {
        val channel = request.channel
        val streamUrl = request.streamUrl
        context.getSharedPreferences("iptv_stream", Context.MODE_PRIVATE).edit().putString("url", channel.url)
            .putLong("id", channel.id).putString("ua", channel.userAgent).putString("referrer", channel.referrer).apply()
        val metrics = sessions.getOrPut(player) { Metrics(player).also { player.addAnalyticsListener(it) } }
        val channelId = channel.id.takeIf { it != 0L } ?: RustBridge.stableId(RustBridge.stableId(0, Prefs.getLiveSource(context)), channel.tvgId ?: channel.url)
        val id = "c${channelId}-l${channel.url.hashCode().toUInt()}-s${sequence.incrementAndGet()}"
        val headers = request.httpHeaders()
        val http = OkHttpDataSource.Factory(NetUtil.ipv4OkHttpClient).setUserAgent(headers.getValue("User-Agent")).setDefaultRequestProperties(headers)
        val captions = listOf(androidx.media3.common.Format.Builder()
            .setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).setLanguage("en").build())
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory().setTsSubtitleFormats(captions).setTsExtractorTimestampSearchBytes(1_000_000)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors,
            androidx.media3.extractor.text.DefaultSubtitleParserFactory()).experimentalParseSubtitlesDuringExtraction(true)
        val item = MediaItem.Builder().setUri(streamUrl).setMediaId(id).build()
        val prepared = intent.prepared(request.copy(channel=channel.copy(id=channelId)), id)
        metrics.begin(prepared, onFailure)
        player.setMediaSource(factory.createMediaSource(item))
        player.prepare()
        player.play()
        return prepared
    }

    private class Metrics(private val player: ExoPlayer) : AnalyticsListener {
        private var binding: PreparedPlayback? = null
        private var onFailure: (PlaybackFailure) -> Unit = {}
        private val session get() = binding?.mediaId ?: ""
        private var preparedAt = 0L
        private var firstFrame = false
        private var bufferStart = 0L
        private var bufferingMs = 0L
        private var rebufferCount = 0
        private var droppedFrames = 0
        private val enabled get() = BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG)
        fun begin(prepared: PreparedPlayback, callback: (PlaybackFailure) -> Unit) {
            binding = prepared
            onFailure = callback
            preparedAt = SystemClock.elapsedRealtime()
            firstFrame = false
            bufferStart = 0L; bufferingMs = 0L; rebufferCount = 0; droppedFrames = 0
            emit("prepare", "at=$preparedAt")
        }
        private fun matches(event: AnalyticsListener.EventTime): Boolean {
            if (player.currentMediaItem?.mediaId != session || event.timeline.isEmpty) return false
            val window = androidx.media3.common.Timeline.Window()
            return event.windowIndex in 0 until event.timeline.windowCount && event.timeline.getWindow(event.windowIndex, window).mediaItem.mediaId == session
        }
        private fun emit(event: String, fields: String) { if (enabled) Log.d(TAG, "$event session=$session $fields") }
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
            // The Analytics event timeline identifies the media that failed, not the later consumer's current row.
            if (!matches(eventTime)) return
            binding?.let { onFailure(PlaybackFailure(it)) }
        }
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            if (!matches(eventTime) || firstFrame) return
            firstFrame = true
            emit("first_frame", "ttff_ms=${SystemClock.elapsedRealtime()-preparedAt} rendered_at=$renderTimeMs")
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (matches(eventTime)) emit("decoder", "name=$decoderName init_ms=$initializationDurationMs")
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            if (matches(eventTime)) emit("format", "mime=${format.sampleMimeType} width=${format.width} height=${format.height} fps=${format.frameRate}")
        }
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            if (!matches(eventTime)) return
            this.droppedFrames += droppedFrames
            emit("dropped", "count=${this.droppedFrames} interval_ms=$elapsedMs")
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            if (!matches(eventTime)) return
            val now = SystemClock.elapsedRealtime()
            if (state == Player.STATE_BUFFERING && firstFrame && bufferStart == 0L) { bufferStart = now; rebufferCount++ }
            if (state != Player.STATE_BUFFERING && bufferStart != 0L) { bufferingMs += now-bufferStart; bufferStart = 0L }
            emit("state", "state=$state rebuffers=$rebufferCount rebuffer_ms=$bufferingMs dropped=$droppedFrames")
        }
    }
}
