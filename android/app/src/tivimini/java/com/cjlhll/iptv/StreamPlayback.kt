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
    // t234: the rebuild ladder is per channel key and reset by every user zap.
    private val rebuildLadder = ZapRebuildPolicy.Ladder()
    // t234: the Activity installs this so the watchdog can rebuild the player instead of re-preparing it.
    @Volatile private var rebuildHandler: ((channelKey: String, softVideo: Boolean) -> Unit)? = null

    /** t234: install/clear the player-rebuild callback (PlayerActivity owns player lifecycle). */
    fun setRebuildHandler(handler: ((String, Boolean) -> Unit)?) {
        rebuildHandler = handler
    }

    /** t234: a user zap resets the ladder (rebuild/soft get their one chance again). */
    fun onUserZap() {
        rebuildLadder.clear()
    }
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

    /** t234: the 睛彩 rebuild path must replay without re-arming the zap stopwatch. */
    fun prepareForRebuild(
        player: ExoPlayer,
        context: Context,
        request: MediaPlaybackRequest,
        intent: PlaybackIntent,
        onFailure: (PlaybackFailure) -> Unit,
    ): PreparedPlayback = prepare(player, context, request, intent, zapTracking = false, onFailure = onFailure)

    fun prepare(player: ExoPlayer, context: Context, request: MediaPlaybackRequest, intent: PlaybackIntent,
                zapTracking: Boolean = true,
                onFailure: (PlaybackFailure) -> Unit = {}): PreparedPlayback {
        val channel = request.channel
        val streamUrl = request.streamUrl
        context.getSharedPreferences("iptv_stream", Context.MODE_PRIVATE).edit().putString("url", channel.url)
            .putLong("id", channel.id).putString("ua", channel.userAgent).putString("referrer", channel.referrer).apply()
        val metrics = sessions.getOrPut(player) { Metrics(player).also { player.addAnalyticsListener(it) } }
        val channelId = channel.id.takeIf { it != 0L } ?: RustBridge.stableId(RustBridge.stableId(0, Prefs.getLiveSource(context)), channel.tvgId ?: channel.url)
        val id = "c${channelId}-l${channel.url.hashCode().toUInt()}-s${sequence.incrementAndGet()}"
        val headers = request.httpHeaders()
        // t231: one at a time, all default OFF (see JingcaiExperiment).
        val experiment = JingcaiExperiment.parse(Prefs.getJingcaiExperiment(context))
        val http: androidx.media3.datasource.DataSource.Factory =
            if (JingcaiExperiment.usePlatformHttpDataSource(experiment)) {
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent(headers.getValue("User-Agent"))
                    .setDefaultRequestProperties(headers)
            } else {
                OkHttpDataSource.Factory(NetUtil.ipv4OkHttpClient)
                    .setUserAgent(headers.getValue("User-Agent"))
                    .setDefaultRequestProperties(headers)
            }
        val captions = if (JingcaiExperiment.injectCaptions(experiment)) {
            listOf(androidx.media3.common.Format.Builder()
                .setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).setLanguage("en").build())
        } else emptyList()
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsSubtitleFormats(captions)
            .setTsExtractorTimestampSearchBytes(JingcaiExperiment.tsSearchBytes(experiment))
        // t20/t38：实读字节计数（直播永不 complete，不能用 onLoadCompleted.bytesLoaded）。
        // t38：时间轴实验（A/B/AUTO）额外做包级改写；其余模式 pipeline = null（纯计数、零改写）。
        JingcaiDiag.reset(experiment)
        val pipeline = if (!JingcaiExperiment.touchesPacketBytes(experiment)) null
        else JingcaiTsPipeline(experiment) { line ->
            if (android.util.Log.isLoggable(JingcaiExperiment.TAG, android.util.Log.DEBUG)) {
                android.util.Log.d(JingcaiExperiment.TAG, line)
            }
        }
        JingcaiDiag.bindPipeline(pipeline)
        val upstreamFactory = DefaultDataSource.Factory(context, http)
        val countingFactory = androidx.media3.datasource.DataSource.Factory {
            JingcaiTsDataSource(upstreamFactory.createDataSource(), pipeline)
        }
        val factory = DefaultMediaSourceFactory(countingFactory, extractors,
            androidx.media3.extractor.text.DefaultSubtitleParserFactory())
            .experimentalParseSubtitlesDuringExtraction(JingcaiExperiment.injectCaptions(experiment))
        val item = MediaItem.Builder().setUri(streamUrl).setMediaId(id).build()
        val prepared = intent.prepared(request.copy(channel=channel.copy(id=channelId)), id)
        metrics.begin(prepared, onFailure)
        player.setMediaSource(factory.createMediaSource(item))
        // t204 (ZAP-TIMING-01): the stopwatch is armed at the USER entry (PlayerActivity.playChannel);
        // preparation only records its own timestamp, so the measurement is the real key-to-first-frame
        // latency rather than prepare-to-first-frame.
        if (zapTracking && android.util.Log.isLoggable("IQTV-ZAP", android.util.Log.DEBUG)) {
            // t216: the very first autoplay has no user zap; arm it here so cold start also produces a
            // ZAP line. A user entry has already armed the clock, so this is a no-op then.
            ZapTrace.armIfUnarmed(
                ZapTrace.zapKey(channelId, channel.url),
                android.os.SystemClock.elapsedRealtime()
            )
        }
        // t225/t228: arm BEFORE prepare — a fast first frame must never arrive un-armed (ARM-01).
        armWatchdog(player, context, channelId, experiment)
        if (zapTracking) ZapTrace.onPrepare(android.os.SystemClock.elapsedRealtime())
        player.prepare()
        // t27（冷启 rebuffer 实验③）：默认 0 = 立即 play（旧行为）；>0 时先攒缓冲再 play。
        // t27/t28：prebuffer 支持运行时实验开关（settings global，未设置时=生产默认 0）。
        val prebuffer = PerfTuning.prebufferMs(context).toLong()
        if (prebuffer > 0L) {
            player.playWhenReady = false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { player.play() }
            }, prebuffer)
        } else {
            player.play()
        }
        return prepared
    }

    /** t225/t228: arm a generation-stamped watchdog right before every prepare. */
    private fun armWatchdog(player: ExoPlayer, context: Context, channelId: Long, experiment: JingcaiExperiment.Mode) {
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
            // t234: one escalation per player instance. The ladder decides rebuild → soft → give up;
            // re-preparing the SAME player is useless here because the video decoder was never created.
            watchdog.cancel()
            val key = "$channelId"
            val stage = rebuildLadder.stageFor(key)
            when (ZapRebuildPolicy.actionFor(stage)) {
                ZapRebuildPolicy.Action.REBUILD, ZapRebuildPolicy.Action.SOFT -> {
                    val soft = ZapRebuildPolicy.actionFor(stage) == ZapRebuildPolicy.Action.SOFT
                    rebuildLadder.advance(key)
                    if (Log.isLoggable(JingcaiExperiment.TAG, Log.DEBUG)) {
                        Log.d(
                            JingcaiExperiment.TAG,
                            ZapRebuildPolicy.logLine(
                                if (soft) ZapRebuildPolicy.Action.SOFT else ZapRebuildPolicy.Action.REBUILD,
                                key
                            )
                        )
                    }
                    val handler = rebuildHandler
                    if (handler != null) {
                        // Activity releases this player and builds a fresh one (a new MediaCodec attempt).
                        handler(key, soft)
                    } else {
                        // No Activity hook (should not happen in the app): keep the old cheap retry.
                        try {
                            player.seekToDefaultPosition()
                            player.prepare()
                            player.play()
                        } catch (e: Exception) {
                            // never crash playback
                        }
                    }
                }
                ZapRebuildPolicy.Action.GIVE_UP -> {
                    // t231/t20: one diagnostic line per failed channel (DEBUG gated).
                    // giveup=1 + 时间轴/buffer 诊断（t20 加）：help acceptance grep 与归因。
                    if (Log.isLoggable(JingcaiExperiment.TAG, Log.DEBUG)) {
                        Log.d(JingcaiExperiment.TAG, "${ZapRebuildPolicy.logLine(ZapRebuildPolicy.Action.GIVE_UP, key)} giveup=1 stage=$stage")
                        sessions[player]?.let { m -> Log.d(JingcaiExperiment.TAG, "diag ${m.diagnosticLine(experiment)}") }
                        Log.d(JingcaiExperiment.TAG, "IQTV-DIAG ${JingcaiDiag.fields()}")
                        sessions[player]?.let { m -> Log.d(JingcaiExperiment.TAG, "buffer ${m.bufferDiagnosticLine()}") }
                    }
                    android.widget.Toast.makeText(
                        context,
                        FirstFrameWatchdogPolicy.GIVE_UP_MESSAGE,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
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
        private var firstFrameAt = 0L
        private var rebufferBytesAtStart = 0L
        private var bufferStart = 0L
        private var bufferingMs = 0L
        private var rebufferCount = 0
        private var droppedFrames = 0
        // t231 diagnosis state
        private var loaderBytes = 0L
        private var downstream = "none"

        fun diagnosticLine(mode: JingcaiExperiment.Mode): String {
            // t20：bytes 取「onLoadCompleted 累计」与「DataSource 实读」的较大者（直播恒无 complete）。
            val line = JingcaiExperiment.diagnosticLine(
                mode,
                maxOf(loaderBytes, JingcaiDiag.bytesRead()),
                downstream,
                firstFrame,
                player.playbackState,
            )
            // t38：追加时间轴一行（pcr/pts/offset/mode），无 pipeline 时省略。
            val tm = JingcaiDiag.timelineLine()
            return if (tm == null) line else "$line :: $tm"
        }

        /** t38（时间轴诊断，无首帧时打印一次）：播放位置/缓冲/窗口/状态 + pcr/pts/offset。 */
        fun timelineDiagnosticLine(): String {
            val windows = runCatching { player.currentTimeline.windowCount }.getOrDefault(-1)
            val pos = runCatching { player.currentPosition }.getOrDefault(-1L)
            val buffered = runCatching { player.bufferedPosition }.getOrDefault(-1L)
            val duration = runCatching { player.duration }.getOrDefault(-1L)
            return "format=$downstream currentPosition=$pos bufferedPosition=$buffered " +
                "duration=$duration windowCount=$windows state=${player.playbackState} " +
                (JingcaiDiag.timelineLine() ?: "timeline=none")
        }
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
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
            // t231: how much the loader actually pulled before giving up / stalling.
            loaderBytes += loadEventInfo.bytesLoaded
        }

        override fun onDownstreamFormatChanged(eventTime: AnalyticsListener.EventTime, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData) {
            // t231: the first format the extractor emitted (null when it never produced one).
            if (downstream == "none") {
                val f = mediaLoadData.trackFormat
                downstream = if (f == null) "none" else "${f.sampleMimeType}/${f.width}x${f.height}@${f.frameRate}"
            }
        }

        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
            // t228 (RETRY-01): notify the watchdog FIRST — a retry does not call begin(), so
            // `firstFrame` is still true and the old early-return swallowed the retry's success,
            // which made the second timer fire a bogus "cannot play" toast.
            onFirstFrameForWatchdog(player)
            if (!matches(eventTime) || firstFrame) return
            firstFrame = true
            firstFrameAt = SystemClock.elapsedRealtime()
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
            if (state == Player.STATE_BUFFERING && firstFrame && bufferStart == 0L) {
                bufferStart = now
                rebufferCount++
                rebufferBytesAtStart = JingcaiDiag.bytesRead()
                // t20（B · 冷启 rebuffer 真因）：rebuffer 前的 Loader 累计字节与 buffer 水位。
                // 首帧后 0.95s 就 rebuffer → 说明起播缓冲不足/补流慢，与 EPG 读取无关。
                emit(
                    "rebuffer_begin",
                    "bytesRead=${JingcaiDiag.bytesRead()} bufferMs=${safeBufferedMs()} " +
                        "since_first_frame_ms=${if (firstFrameAt > 0) now - firstFrameAt else -1}",
                )
            }
            if (state != Player.STATE_BUFFERING && bufferStart != 0L) {
                val lasted = now - bufferStart
                bufferingMs += lasted
                bufferStart = 0L
                val deltaBytes = (JingcaiDiag.bytesRead() - rebufferBytesAtStart).coerceAtLeast(0)
                val bps = if (lasted > 0) deltaBytes * 1000L / lasted else 0L
                // t20：rebuffer 期间的补流速率（字节/秒）与恢复后的 buffer 水位。
                emit(
                    "rebuffer_end",
                    "lasted_ms=$lasted bytes=$deltaBytes bytesPerSec=$bps bufferMs=${safeBufferedMs()}",
                )
            }
            emit("state", "state=$state rebuffers=$rebufferCount rebuffer_ms=$bufferingMs dropped=$droppedFrames")
        }

        /** 播放器“已缓冲时长”（ms）；异常/未知返回 -1，绝不因诊断崩播放。 */
        private fun safeBufferedMs(): Long = try { player.totalBufferedDuration } catch (e: Exception) { -1L }

        /** t20：无首帧/放弃时的 buffer 诊断（需要主线程调用）。 */
        fun bufferDiagnosticLine(): String {
            val win = try { player.currentTimeline.windowCount } catch (e: Exception) { -1 }
            val pos = try { player.currentPosition } catch (e: Exception) { -1L }
            return "bufferMs=${safeBufferedMs()} currentPosition=$pos windowCount=$win " +
                "state=${player.playbackState} rebuffers=$rebufferCount rebuffer_ms=$bufferingMs " +
                "bytesRead=${JingcaiDiag.bytesRead()} firstByteMs=${JingcaiDiag.firstByteMs()}"
        }
    }
}
