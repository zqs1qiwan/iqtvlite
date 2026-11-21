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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.iqtvlite.app.BuildConfig
import tv.iqtvlite.app.RustBridge

/** Immutable request headers per media source; old in-flight requests keep their own UA. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object StreamPlayback {
    private const val TAG = "IQTV-METRICS"
    private const val WATCHDOG_TAG = "IQTV-WATCHDOG"
    private val sequence = AtomicLong()
    // t225: one first-frame watchdog per player (see FirstFrameWatchdogPolicy).
    private val watchdogs = java.util.WeakHashMap<ExoPlayer, FirstFrameWatchdogPolicy.Watchdog>()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // t228 (WATCHDOG-RELEASE-01): the timers must be cancellable, otherwise one can wake up after the
    // player was released and touch it / the old Activity's Context.
    private val watchdogJobs = java.util.WeakHashMap<ExoPlayer, MutableList<Job>>()
    private val sessions = java.util.WeakHashMap<ExoPlayer, Metrics>()

    fun restore(context: Context, url: String, title: String): Channel {
        val prefs = context.getSharedPreferences("iptv_stream", Context.MODE_PRIVATE)
        return if (prefs.getString("url", null) == url) Channel(title=title, url=url,
            id=prefs.getLong("id", 0L), userAgent=prefs.getString("ua", null), referrer=prefs.getString("referrer", null))
        else Channel(title=title, url=url)
    }
    fun release(player: ExoPlayer) {
        // t228 (RELEASE-01): kill the timers first, then the policy, then the player.
        watchdogJobs.remove(player)?.forEach { it.cancel() }
        watchdogs.remove(player)?.cancel()
        sessions.remove(player)?.let {
            it.cancelSummary()
            PlaybackSession.clear(it.mediaIdForSession())
            player.removeAnalyticsListener(it)
        }
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
        // t204 (ZAP-TIMING-01): the stopwatch is armed at the USER entry (PlayerActivity.playChannel);
        // preparation only records its own timestamp, so the measurement is the real key-to-first-frame
        // latency rather than prepare-to-first-frame.
        if (android.util.Log.isLoggable("IQTV-ZAP", android.util.Log.DEBUG)) {
            // t216: the very first autoplay has no user zap; arm it here so cold start also produces a
            // ZAP line. A user entry has already armed the clock, so this is a no-op then.
            ZapTrace.armIfUnarmed(
                ZapTrace.zapKey(channelId, channel.url),
                android.os.SystemClock.elapsedRealtime()
            )
        }
        // t225/t228: arm BEFORE prepare — a fast first frame must never arrive un-armed (ARM-01).
        armWatchdog(player, context, channelId)
        ZapTrace.onPrepare(android.os.SystemClock.elapsedRealtime())
        player.prepare()
        player.play()
        return prepared
    }

    /** t225/t228: arm a generation-stamped watchdog right before every prepare. */
    private fun armWatchdog(player: ExoPlayer, context: Context, channelId: Long) {
        // drop any timer of the previous channel before starting a new generation
        watchdogJobs.remove(player)?.forEach { it.cancel() }
        val watchdog = watchdogs.getOrPut(player) { FirstFrameWatchdogPolicy.Watchdog() }
        val gen = watchdog.arm("$channelId")
        val job = watchdogScope.launch(Dispatchers.Main) {
            delay(watchdog.timeoutMillis())
            // t228 (RELEASE-01/PAUSE-01): check ownership BEFORE touching the player, so a stale
            // generation can neither use a released player nor cancel the newer channel's watchdog.
            if (!watchdog.isCurrent(gen)) return@launch
            // Pausing sets playWhenReady = false; a paused zap must not be retried. (A rebuffer keeps
            // playWhenReady true, so buffering still counts as "should be playing".)
            if (!player.playWhenReady) {
                watchdog.cancel()
                return@launch
            }
            when (watchdog.onTimeout(gen)) {
                FirstFrameWatchdogPolicy.Action.RETRY -> {
                    if (Log.isLoggable(WATCHDOG_TAG, Log.DEBUG)) {
                        Log.d(WATCHDOG_TAG, FirstFrameWatchdogPolicy.retryLine("$channelId", watchdog.retriesUsed()))
                    }
                    try {
                        // Re-prepare the SAME media item: one retry, no new media source, no zap re-arm.
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.play()
                    } catch (e: Exception) {
                        // a failed retry must never crash playback
                    }
                    val retryJob = watchdogScope.launch(Dispatchers.Main) {
                        delay(watchdog.timeoutMillis())
                        if (!watchdog.isCurrent(gen)) return@launch
                        if (player.playWhenReady &&
                            watchdog.onTimeout(gen) == FirstFrameWatchdogPolicy.Action.GIVE_UP
                        ) {
                            android.widget.Toast.makeText(
                                context,
                                FirstFrameWatchdogPolicy.GIVE_UP_MESSAGE,
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    watchdogJobs.getOrPut(player) { ArrayList() }.add(retryJob)
                }
                FirstFrameWatchdogPolicy.Action.GIVE_UP -> android.widget.Toast.makeText(
                    context,
                    FirstFrameWatchdogPolicy.GIVE_UP_MESSAGE,
                    android.widget.Toast.LENGTH_LONG
                ).show()
                FirstFrameWatchdogPolicy.Action.IGNORE -> Unit
            }
        }
        watchdogJobs.getOrPut(player) { ArrayList() }.add(job)
    }

    /** t225: the first frame cancels the watchdog for this player. */
    internal fun onFirstFrameForWatchdog(player: ExoPlayer) {
        watchdogs[player]?.onFirstFrame()
    }

    private class Metrics(private val player: ExoPlayer) : AnalyticsListener {

        fun cancelSummary() { audioSummaryJob?.cancel(); audioSummaryJob = null }

        /** The session this listener is bound to (no Player access). */
        fun mediaIdForSession(): String? = binding?.mediaId
        // t165: one debug summary per session, 60 s after prepare.
        private var audioSummaryJob: Job? = null
        private val audioSummaryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
            // t165: debug-only audio cost counters (default off; see AudioCostCounters).
            AudioCostCounters.enabled = BuildConfig.DEBUG && Log.isLoggable("IQTV-AUDIO", Log.DEBUG)
            audioSummaryJob?.cancel()
            audioSummaryJob = null
            // t169: the session id is snapshotted here (main thread) — the 60 s job below reads the
            // snapshot only. Never call player.currentMediaItem from a background dispatcher:
            // ExoPlayer throws "Player is accessed on the wrong thread" and kills the process.
            PlaybackSession.begin(prepared.mediaId)
            if (AudioCostCounters.enabled) {
                AudioCostCounters.reset()
                val session = prepared.mediaId
                audioSummaryJob = audioSummaryScope.launch {
                    delay(60_000)
                    if (!PlaybackSession.isCurrent(session)) return@launch
                    Log.i("IQTV-AUDIO", AudioCostCounters.format(AudioCostCounters.sample()))
                }
            }
            firstFrame = false
            bufferStart = 0L; bufferingMs = 0L; rebufferCount = 0; droppedFrames = 0
            emit("prepare", "at=$preparedAt")
        }
        private fun matches(event: AnalyticsListener.EventTime): Boolean {
            // t169: use the main-thread snapshot instead of player.currentMediaItem, so this is safe
            // from any callback thread and cannot throw the wrong-thread IllegalStateException.
            if (!PlaybackSession.isCurrent(session) || event.timeline.isEmpty) return false
            val window = androidx.media3.common.Timeline.Window()
            return event.windowIndex in 0 until event.timeline.windowCount && event.timeline.getWindow(event.windowIndex, window).mediaItem.mediaId == session
        }
        private fun emit(event: String, fields: String) { if (enabled) Log.d(TAG, "$event session=$session $fields") }
        // Return before any group traversal/format rendering when diagnostics are disabled.
        private fun observe(eventTime: AnalyticsListener.EventTime): Boolean = enabled && matches(eventTime)
        private fun audioFormat(format: androidx.media3.common.Format): String =
            "mime=${AudioDiagnosticFields.mime(format.sampleMimeType)} codecs=${AudioDiagnosticFields.codecs(format.codecs)} " +
                "rate=${format.sampleRate} channels=${format.channelCount} language=${AudioDiagnosticFields.language(format.language)} bitrate=${format.bitrate}"
        private fun audioContext() = "volume=${player.volume} suppression=${player.playbackSuppressionReason} playWhenReady=${player.playWhenReady} audioSessionId=${player.audioSessionId}"
        private fun audioTrack(config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) =
            "encoding=${config.encoding} rate=${config.sampleRate} channelConfig=${config.channelConfig} bufferBytes=${config.bufferSize} tunneling=${config.tunneling} offload=${config.offload}"

        override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: androidx.media3.common.Tracks) {
            if (!observe(eventTime)) return
            var audioGroups = 0
            for ((groupIndex, group) in tracks.groups.withIndex()) {
                if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                audioGroups++
                for (trackIndex in 0 until group.length) {
                    emit("audio_track", "group=$groupIndex track=$trackIndex supported=${group.isTrackSupported(trackIndex)} selected=${group.isTrackSelected(trackIndex)} renderer_format_support=${group.getTrackSupport(trackIndex)} ${audioFormat(group.getTrackFormat(trackIndex))}")
                }
            }
            emit("audio_tracks", "groups=$audioGroups ${audioContext()}")
        }
        override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
            if (matches(eventTime)) AudioCostCounters.onActiveChanged(true)
            if (observe(eventTime)) emit("audio_enabled", audioContext())
        }
        override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: androidx.media3.exoplayer.DecoderCounters) {
            if (matches(eventTime)) {
                AudioCostCounters.onActiveChanged(false)
                AudioCostCounters.onCounters(
                    decoderCounters.renderedOutputBufferCount.toLong(),
                    decoderCounters.skippedOutputBufferCount.toLong(),
                )
            }
            if (observe(eventTime)) emit("audio_disabled", "rendered=${decoderCounters.renderedOutputBufferCount} skipped=${decoderCounters.skippedOutputBufferCount}")
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (matches(eventTime)) AudioCostCounters.onDecoder(AudioDiagnosticFields.name(decoderName))
            if (observe(eventTime)) emit("audio_decoder", "name=${AudioDiagnosticFields.name(decoderName)} init_ms=$initializationDurationMs")
        }
        override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
            if (observe(eventTime)) emit("audio_decoder_released", "name=${AudioDiagnosticFields.name(decoderName)}")
        }
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            if (observe(eventTime)) emit("audio_format", audioFormat(format))
        }
        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            if (observe(eventTime)) emit("audio_advancing", "playout_start_ms=$playoutStartSystemTimeMs since_prepare_ms=${SystemClock.elapsedRealtime()-preparedAt}")
        }
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            if (observe(eventTime)) emit("audio_session", "id=$audioSessionId")
        }
        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
            if (matches(eventTime)) AudioCostCounters.onRetry()
            if (observe(eventTime)) emit("audio_sink_error", "type=${AudioDiagnosticFields.name(audioSinkError.javaClass.simpleName)}")
        }
        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) {
            if (matches(eventTime)) AudioCostCounters.onRetry()
            if (observe(eventTime)) emit("audio_codec_error", "type=${AudioDiagnosticFields.name(audioCodecError.javaClass.simpleName)}")
        }
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
            if (matches(eventTime)) {
                AudioCostCounters.onTrack(
                    encoding = audioTrackConfig.encoding.toString(),
                    sampleRate = audioTrackConfig.sampleRate,
                    bufferBytes = audioTrackConfig.bufferSize,
                )
            }
            if (observe(eventTime)) emit("audio_track_initialized", audioTrack(audioTrackConfig))
        }

        // t165: buffer underrun = the sink starved (the CPU-relevant failure mode for MP2).
        override fun onAudioUnderrun(
            eventTime: AnalyticsListener.EventTime,
            bufferSize: Int,
            bufferSizeMs: Long,
            elapsedSinceLastFeedMs: Long,
        ) {
            if (matches(eventTime)) AudioCostCounters.onUnderrun()
            if (observe(eventTime)) emit("audio_underrun", "buffer=$bufferSize ms=$bufferSizeMs since_feed=$elapsedSinceLastFeedMs")
        }
        override fun onAudioTrackReleased(eventTime: AnalyticsListener.EventTime, audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
            if (observe(eventTime)) emit("audio_track_released", audioTrack(audioTrackConfig))
        }
        override fun onVolumeChanged(eventTime: AnalyticsListener.EventTime, volume: Float) {
            if (observe(eventTime)) emit("audio_volume", "value=$volume")
        }
        override fun onPlaybackSuppressionReasonChanged(eventTime: AnalyticsListener.EventTime, playbackSuppressionReason: Int) {
            if (observe(eventTime)) emit("audio_suppression", "reason=$playbackSuppressionReason ${audioContext()}")
        }
        override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
            if (observe(eventTime)) emit("audio_play_intent", "value=$playWhenReady reason=$reason ${audioContext()}")
        }
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: androidx.media3.common.PlaybackException) {
            // The Analytics event timeline identifies the media that failed, not the later consumer's current row.
            if (!matches(eventTime)) return
            binding?.let { onFailure(PlaybackFailure(it)) }
        }
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            // t228 (RETRY-01): notify the watchdog FIRST — a retry does not call begin(), so
            // `firstFrame` is still true and the old early-return swallowed the retry's success,
            // which made the second timer fire a bogus "cannot play" toast.
            onFirstFrameForWatchdog(player)
            if (!matches(eventTime) || firstFrame) return
            firstFrame = true
            emit("first_frame", "ttff_ms=${SystemClock.elapsedRealtime()-preparedAt} rendered_at=$renderTimeMs")
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            if (matches(eventTime)) emit("decoder", "name=$decoderName init_ms=$initializationDurationMs")
            // t213: stage mark for the first-frame breakdown (debug only).
            if (android.util.Log.isLoggable("IQTV-ZAP", android.util.Log.DEBUG)) {
                ZapTrace.onVideoDecoderInitialized(android.os.SystemClock.elapsedRealtime())
            }
        }
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            if (matches(eventTime)) emit("format", "mime=${format.sampleMimeType} width=${format.width} height=${format.height} fps=${format.frameRate}")
            if (android.util.Log.isLoggable("IQTV-ZAP", android.util.Log.DEBUG)) {
                ZapTrace.onInputFormatChanged(android.os.SystemClock.elapsedRealtime())
            }
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
