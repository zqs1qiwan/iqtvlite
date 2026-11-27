package com.cjlhll.iptv

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

internal data class VideoDecoderCandidate<T>(
    val value: T,
    val name: String,
    val softwareOnly: Boolean,
    val sizeSupported: Boolean,
    val unreliableSizeMetadata: Boolean = false,
)

/**
 * When MediaCodec selection puts a CPU software decoder first (e.g. a TV box firmware whose
 * `MediaCodecInfo` profile/level table omits HEVC High Tier Level 5.1 `hvc1.1.2.H153`), promote
 * hardware decoders ahead of it while keeping software decoders as fallback.
 *
 * Some Android 9 TV firmwares also misparse `media_codecs.xml` (e.g. `4096x2306` with `16x2`
 * alignment) into collapsed `supportedWidths/Heights` like `[128, 288]`; when either the
 * software-first decoder itself fails the size check (4K) or the hardware `VideoCapabilities`
 * ceiling is collapsed below `640x360`, we still prefer hardware decoding first and rely on
 * `enableDecoderFallback = true` if initialization fails.
 */
internal fun <T> promoteHardwareVideoDecoders(
    candidates: List<VideoDecoderCandidate<T>>,
): List<T> {
    if (candidates.size <= 1) return candidates.map { it.value }
    val first = candidates.first()
    if (!first.softwareOnly) return candidates.map { it.value }
    val shouldPromote: (VideoDecoderCandidate<T>) -> Boolean = {
        !it.softwareOnly && (it.sizeSupported || !first.sizeSupported || it.unreliableSizeMetadata)
    }
    val promotableHw = candidates.filter(shouldPromote)
    if (promotableHw.isEmpty()) return candidates.map { it.value }
    val remaining = candidates.filterNot(shouldPromote)
    return (promotableHw + remaining).map { it.value }
}

/**
 * Tier 3 fallback selector: filters MediaCodec candidates down to size-compatible software
 * decoders (`c2.android.*`, `OMX.google.*`) so a wedged hardware decoder never re-claims a 1080p
 * session. If no software decoder supports the requested geometry (e.g. `3840x2160` 4K UHD when
 * `OMX.google.hevc.decoder` is capped at `1920x1088`), returns hardware-preferred order instead
 * of locking the CPU onto an incapable 1080p software decoder.
 */
internal fun <T> selectSoftwareOnlyVideoDecoders(
    candidates: List<VideoDecoderCandidate<T>>,
): List<T> {
    if (candidates.isEmpty()) return emptyList()
    val capableSw = candidates.filter { it.softwareOnly && it.sizeSupported }
    if (capableSw.isNotEmpty()) {
        return capableSw.map { it.value }
    }
    return promoteHardwareVideoDecoders(candidates)
}

/**
 * t234: progressive HW -> SW video decoder ladder:
 *
 * `softVideo = false` (default for every channel and auto-reset on next zap) prefers hardware
 * MediaCodec video (promoting size-compatible hardware decoders ahead of software fallbacks when
 * firmware `profileLevel` metadata is incomplete), with `enableDecoderFallback = true` and FFmpeg
 * audio fallback. `softVideo = true` is the last-resort Tier 3 path (user-selected or triggered by
 * `ZapRebuildPolicy.Action.SOFT`) which offers `ExperimentalFfmpegVideoRenderer` first and routes
 * `MediaCodecVideoRenderer` exclusively to `softwareOnly` platform decoders (`c2.android.*` /
 * `OMX.google.*`).
 */
@androidx.annotation.OptIn(UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
class JingcaiRenderersFactory(
    context: Context,
    private val softVideo: Boolean,
    private val audioProcessor: AudioSyncBoostProcessor? = null,
) : DefaultRenderersFactory(context) {

    init {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            forceEnableMediaCodecAsynchronousQueueing()
        }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
        if (audioProcessor != null) {
            builder.setAudioProcessors(arrayOf(audioProcessor))
        }
        return builder.build()
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        if (softVideo) {
            if (FfmpegLibrary.isAvailable()) {
                // Offer FFmpeg video decoder first when compiled into lib-decoder-ffmpeg.aar.
                out.add(
                    ExperimentalFfmpegVideoRenderer(
                        allowedJoiningTimeMs,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_FRAMES_NOT_REPORTED,
                    )
                )
            }
            val builtSoft = ArrayList<Renderer>()
            super.buildVideoRenderers(
                context,
                extensionRendererMode,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                allowedJoiningTimeMs,
                builtSoft,
            )
            for (renderer in builtSoft) {
                if (renderer.javaClass == MediaCodecVideoRenderer::class.java) {
                    out.add(
                        SoftwarePreferredMediaCodecVideoRenderer(
                            MediaCodecVideoRenderer.Builder(context)
                                .setCodecAdapterFactory(codecAdapterFactory)
                                .setMediaCodecSelector(mediaCodecSelector)
                                .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                                .setEnableDecoderFallback(enableDecoderFallback)
                                .setEventHandler(eventHandler)
                                .setEventListener(eventListener)
                                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                        )
                    )
                } else {
                    out.add(renderer)
                }
            }
            return
        }

        val built = ArrayList<Renderer>()
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedJoiningTimeMs,
            built,
        )
        for (renderer in built) {
            if (renderer.javaClass == MediaCodecVideoRenderer::class.java) {
                out.add(
                    HardwarePreferredMediaCodecVideoRenderer(
                        MediaCodecVideoRenderer.Builder(context)
                            .setCodecAdapterFactory(codecAdapterFactory)
                            .setMediaCodecSelector(mediaCodecSelector)
                            .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
                            .setEnableDecoderFallback(enableDecoderFallback)
                            .setEventHandler(eventHandler)
                            .setEventListener(eventListener)
                            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                    )
                )
            } else {
                out.add(renderer)
            }
        }
    }

    private class HardwarePreferredMediaCodecVideoRenderer(
        builder: MediaCodecVideoRenderer.Builder,
    ) : MediaCodecVideoRenderer(builder) {
        override fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean,
        ): MutableList<MediaCodecInfo> {
            val base = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
            if (base.size <= 1 || !base.first().softwareOnly) {
                return base
            }
            val candidates = base.map { info ->
                VideoDecoderCandidate(
                    value = info,
                    name = info.name,
                    softwareOnly = info.softwareOnly,
                    sizeSupported = isHardwareSizeCompatible(info, format.width, format.height),
                    unreliableSizeMetadata = hasUnreliableSizeMetadata(info),
                )
            }
            val promoted = promoteHardwareVideoDecoders(candidates)
            val promotedFirst = promoted.firstOrNull()
            val baseFirst = base.firstOrNull()
            if (promotedFirst != null && baseFirst != null && promotedFirst.name != baseFirst.name) {
                Log.i(
                    CODEC_TAG,
                    "promote hw decoder ${promotedFirst.name} over ${baseFirst.name} for ${format.sampleMimeType} ${format.width}x${format.height} codecs=${format.codecs}",
                )
            }
            return promoted.toMutableList()
        }

        private fun isHardwareSizeCompatible(info: MediaCodecInfo, width: Int, height: Int): Boolean =
            isCodecSizeCompatible(info, width, height)

        private fun hasUnreliableSizeMetadata(info: MediaCodecInfo): Boolean =
            isCodecSizeMetadataCollapsed(info)
    }

    private class SoftwarePreferredMediaCodecVideoRenderer(
        builder: MediaCodecVideoRenderer.Builder,
    ) : MediaCodecVideoRenderer(builder) {
        override fun getDecoderInfos(
            mediaCodecSelector: MediaCodecSelector,
            format: Format,
            requiresSecureDecoder: Boolean,
        ): MutableList<MediaCodecInfo> {
            val base = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
            if (base.isEmpty()) return base
            val candidates = base.map { info ->
                VideoDecoderCandidate(
                    value = info,
                    name = info.name,
                    softwareOnly = info.softwareOnly,
                    sizeSupported = isCodecSizeCompatible(info, format.width, format.height),
                    unreliableSizeMetadata = isCodecSizeMetadataCollapsed(info),
                )
            }
            val selected = selectSoftwareOnlyVideoDecoders(candidates)
            selected.firstOrNull()?.let { first ->
                if (first.softwareOnly) {
                    if (Log.isLoggable(CODEC_TAG, Log.DEBUG)) {
                        Log.d(
                            CODEC_TAG,
                            "use sw decoder ${first.name} for ${format.sampleMimeType} ${format.width}x${format.height}",
                        )
                    }
                } else {
                    Log.w(
                        CODEC_TAG,
                        "skip 1080p-capped sw decoder for ${format.sampleMimeType} ${format.width}x${format.height}; keep ${first.name}",
                    )
                }
            }
            return selected.toMutableList()
        }
    }

    private companion object {
        const val MAX_DROPPED_FRAMES_NOT_REPORTED = 10
        const val CODEC_TAG = "IQTV-CODEC"

        fun isCodecSizeCompatible(info: MediaCodecInfo, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return true
            return info.capabilities?.videoCapabilities?.isSizeSupported(width, height) != false
        }

        fun isCodecSizeMetadataCollapsed(info: MediaCodecInfo): Boolean {
            val vc = info.capabilities?.videoCapabilities ?: return false
            return vc.supportedWidths.upper < 640 || vc.supportedHeights.upper < 360
        }
    }
}
