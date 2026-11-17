package tv.iqtvlite.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.util.Log
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * TiviMate 标志性功能：浏览频道列表时，焦点频道在小窗静音预览。
 *
 * 轻量设计：
 * - 单个 ExoPlayer 复用（不随焦点频繁创建/销毁）
 * - 静音、不请求音频焦点、低缓冲
 * - 焦点停留 350ms 后才加载（快速滑动时不狂拉流）
 * - 浮层关闭时 release，不占用解码器
 */
class PreviewPlayer(private val ctx: Context) {
    companion object {
        private const val TAG = "iqtv-preview"
        private const val FOCUS_DELAY_MS = 350L
    }

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private val currentUa = AtomicReference("VLC/3.0.20 LibVLC/3.0.20")
    private val focusToken = Any()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dns(NetUtil.ipv4OnlyDns)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", currentUa.get())
                .build()
            chain.proceed(req)
        }
        .build()

    private fun ensurePlayer(view: PlayerView): ExoPlayer {
        if (player != null) return player!!
        val okHttpFactory = OkHttpDataSource.Factory(http)
        player = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, okHttpFactory)))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1000, 3000, 300, 500)
                    .build()
            )
            .build().apply {
                volume = 0f
                setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false, // 不请求音频焦点
                )
            }
        view.player = player
        Log.i(TAG, "preview player created")
        return player!!
    }

    /** 焦点变化：延迟后切到该频道预览（防抖） */
    fun focus(ch: Channel?, view: PlayerView, handler: Handler) {
        handler.removeCallbacksAndMessages(focusToken)
        if (ch == null) { view.visibility = View.GONE; return }
        handler.postAtTime({ play(ch, view) }, focusToken, android.os.SystemClock.uptimeMillis() + FOCUS_DELAY_MS)
    }

    private fun play(ch: Channel, view: PlayerView) {
        val url = ch.url.substringBefore("|")
        if (url == currentUrl) { view.visibility = View.VISIBLE; return }
        val rawUa = ch.userAgent ?: ch.url.substringAfter("|", "").removePrefix("User-Agent=").ifEmpty { null }
        currentUa.set(resolveUa(rawUa))
        try {
            val p = ensurePlayer(view)
            currentUrl = url
            view.visibility = View.VISIBLE
            p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            p.prepare()
            p.playWhenReady = true
            Log.i(TAG, "preview ${ch.name} url=${url.take(60)}")
        } catch (e: Exception) {
            Log.w(TAG, "preview failed: ${e.message}")
            view.visibility = View.GONE
        }
    }

    private fun resolveUa(raw: String?): String = when {
        raw == null -> "VLC/3.0.20 LibVLC/3.0.20"
        raw.equals("VLC", ignoreCase = true) || raw.startsWith("VLC") -> "VLC/3.0.20 LibVLC/3.0.20"
        else -> raw
    }

    fun hide(view: PlayerView) {
        view.visibility = View.GONE
        player?.playWhenReady = false
    }

    fun release(view: PlayerView) {
        view.player = null
        player?.release()
        player = null
        currentUrl = null
        Log.i(TAG, "preview released")
    }
}
