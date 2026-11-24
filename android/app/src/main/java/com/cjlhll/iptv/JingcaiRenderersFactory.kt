package com.cjlhll.iptv

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.util.ArrayList

/**
 * t234: renderers factory with an **optional** FFmpeg video renderer in front of MediaCodec.
 *
 * `softVideo = false` (every normal channel) is byte-for-byte the previous behaviour: hardware
 * MediaCodec video, FFmpeg only for audio fallback. `softVideo = true` is used **only** by the 睛彩
 * rebuild ladder's last step, where the platform decoder was never even created — the FFmpeg renderer
 * is offered first so a stream that the hardware path refuses can still be decoded (at the cost of CPU;
 * on this v7a box 1080p may drop frames, which is why it is the last resort and only once per zap).
 */
@androidx.annotation.OptIn(UnstableApi::class, androidx.media3.common.util.ExperimentalApi::class)
class JingcaiRenderersFactory(
    context: Context,
    private val softVideo: Boolean,
) : DefaultRenderersFactory(context) {

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
        if (softVideo && FfmpegLibrary.isAvailable()) {
            // Offer the software decoder first; MediaCodec stays as the fallback behind it.
            out.add(
                ExperimentalFfmpegVideoRenderer(
                    allowedJoiningTimeMs,
                    eventHandler,
                    eventListener,
                    MAX_DROPPED_FRAMES_NOT_REPORTED,
                )
            )
        }
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedJoiningTimeMs,
            out,
        )
    }

    private companion object {
        const val MAX_DROPPED_FRAMES_NOT_REPORTED = 10
    }
}
